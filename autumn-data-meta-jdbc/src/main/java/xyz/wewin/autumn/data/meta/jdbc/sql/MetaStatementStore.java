package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperXmlParser;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCache;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCaches;

/**
 * 动态表的 Mapper 文件仓库：表名 → 语句 AST。
 *
 * <p>解析顺序（和「文件存在就不再调整」这条规则一样，只有前两级是读取）：</p>
 * <ol>
 *   <li>{@code <mapper 目录>/<表名>.xml}；</li>
 *   <li>classpath {@code meta-mappers/<表名>.xml}（打进 jar 的默认语句）；</li>
 *   <li>都没有且 {@code generateFiles=true}：按 {@link MetaTable} 的列结构生成一份 MyBatis 风格的
 *       Mapper XML，写到该目录下后使用；目录为空则只在内存里用、不落盘。</li>
 * </ol>
 *
 * <p>前两级按「表名原样 / 小写 / 大写」三个文件名依次找：元数据里的表名在 H2、Oracle 下是大写，
 * 而手写文件多按 MySQL 习惯写成小写，两边得能对上才算「文件存在」。</p>
 *
 * <p>生成的文件是<b>一次性产物</b>：之后无论表结构怎么变，都不会回头覆盖它——改表加列要同步进
 * 语句，请手工编辑该文件（它归使用方所有）。{@link #refresh(String)} 只丢掉进程内缓存、
 * 重新读盘，不会重写文件，这样「刷新元数据」和「重写用户文件」两件事就不互相打架了。</p>
 *
 * <p>文件内容是 {@code autumn-data-common} 的那套 XML 方言（{@code #{}} 绑定、{@code ${}} 标识符、
 * {@code <if>/<choose>/<foreach>/<where>/<trim>}、{@code <sql>/<include>}、{@code <resultMap>}），
 * 和静态实体的编译期 Mapper 完全同源；这一层只是把同一棵 AST 解释执行，不引入模板引擎。</p>
 *
 * <p>每张表一份 AST，同样走有界 LRU：触顶淘汰冷表，下次访问重新读已有的 XML 文件（文件归使用方所有，
 * 不会被重写），内存占用不随表数量线性增长。</p>
 */
public final class MetaStatementStore {

	private static final Logger LOG = Logger.getLogger(MetaStatementStore.class.getName());

	private static final String GENERATED_MARKER = "generated-by: autumn-data-meta-jdbc";

	private static final String CLASSPATH_DIRECTORY = "meta-mappers/";

	private final Path directory;

	private final boolean generateFiles;

	private final ClassLoader classLoader;

	private final Dialect dialect;

	private final BoundedCache<String, Map<String, SqlTemplate>> cache;

	public MetaStatementStore(Path directory, boolean generateFiles, Dialect dialect) {
		this(directory, generateFiles, dialect, defaultClassLoader(), BoundedCache.DEFAULT_MAXIMUM_SIZE);
	}

	public MetaStatementStore(Path directory, boolean generateFiles, Dialect dialect, int maximumSize) {
		this(directory, generateFiles, dialect, defaultClassLoader(), maximumSize);
	}

	public MetaStatementStore(Path directory, boolean generateFiles, Dialect dialect, ClassLoader classLoader) {
		this(directory, generateFiles, dialect, classLoader, BoundedCache.DEFAULT_MAXIMUM_SIZE);
	}

	/**
	 * @param directory   Mapper 文件目录（通常是 {@code <root>/mapper}）；{@code null} 表示只在内存里生成、不落盘
	 * @param generateFiles 目录里没有该表的文件时是否按表结构生成一份；false 时缺失直接报错
	 * @param maximumSize 表级语句缓存上限（默认 {@value BoundedCache#DEFAULT_MAXIMUM_SIZE}）
	 */
	public MetaStatementStore(Path directory, boolean generateFiles, Dialect dialect, ClassLoader classLoader,
			int maximumSize) {
		if (dialect == null) {
			throw new IllegalArgumentException("dialect must not be null");
		}
		this.directory = directory;
		this.generateFiles = generateFiles;
		this.dialect = dialect;
		this.classLoader = classLoader == null ? defaultClassLoader() : classLoader;
		this.cache = BoundedCaches.lru(maximumSize);
	}

	/** 按语句 id 取模板；不存在时给出该文件里可用的 id。 */
	public SqlTemplate statement(MetaTable table, String statementId) {
		Map<String, SqlTemplate> statements = statements(table);
		SqlTemplate statement = statements.get(statementId);
		if (statement == null) {
			throw new MetaSqlException("mapper file '" + fileName(table) + "' for table '" + table.name()
					+ "' has no statement '" + statementId + "'; available: " + statements.keySet());
		}
		return statement;
	}

	/** 该表可见的全部语句（首次访问时读盘或生成）。 */
	public Map<String, SqlTemplate> statements(MetaTable table) {
		return this.cache.get(normalize(table.name()), ignored -> load(table));
	}

	/** 丢掉某张表的 AST 缓存，下次访问重新读文件；不重写文件。 */
	public void refresh(String tableName) {
		this.cache.invalidate(normalize(tableName));
	}

	public void refreshAll() {
		this.cache.invalidateAll();
	}

	/** 已缓存的表数（诊断用）。 */
	public long size() {
		return this.cache.estimatedSize();
	}

	/** 缓存上限（诊断用）。 */
	public int maximumSize() {
		return this.cache.maximumSize();
	}

	private Map<String, SqlTemplate> load(MetaTable table) {
		List<String> candidates = fileNames(table);
		for (String fileName : candidates) {
			Path file = this.directory == null ? null : this.directory.resolve(fileName);
			String content = readDisk(file);
			if (content != null) {
				return parse(content, file + " for " + table.name());
			}
			content = readClasspath(fileName);
			if (content != null) {
				return parse(content, "classpath:" + CLASSPATH_DIRECTORY + fileName + " for " + table.name());
			}
		}
		if (!this.generateFiles) {
			throw new MetaSqlException("mapper file not found for table '" + table.name() + "'; looked for "
					+ candidates + " in " + (this.directory == null ? "(no mapper directory)" : this.directory)
					+ " and on classpath " + CLASSPATH_DIRECTORY + ", and file generation is off");
		}
		String content = generate(table);
		Path file = this.directory == null ? null : this.directory.resolve(candidates.get(0));
		String source = (file == null ? "generated in memory" : file + " (generated)") + " for " + table.name();
		write(file, content, table);
		return parse(content, source);
	}

	private static Map<String, SqlTemplate> parse(String content, String source) {
		MapperDefinition mapper = MapperXmlParser.parse(content, source);
		Map<String, SqlTemplate> statements = new LinkedHashMap<>();
		for (StatementDefinition statement : mapper.statements()) {
			if (statements.put(statement.id(), new SqlTemplate(statement)) != null) {
				throw new MetaSqlException("duplicate statement id '" + statement.id() + "' in " + source);
			}
		}
		if (statements.isEmpty()) {
			throw new MetaSqlException("mapper file " + source + " declares no statement");
		}
		return Collections.unmodifiableMap(statements);
	}

	// ------------------------------------------------------------------
	// 按表结构生成
	// ------------------------------------------------------------------

	private String generate(MetaTable table) {
		StringBuilder xml = new StringBuilder(1024);
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		xml.append("<!-- ").append(GENERATED_MARKER).append(" -->\n");
		xml.append("<!-- 按表结构生成一次：本文件之后归使用方所有，改表不会回头覆盖它。 -->\n");
		xml.append("<!-- #{} 走 JDBC 绑定，${} 只允许元数据标识符；test/命名与元数据列名一致。 -->\n");
		xml.append("<mapper namespace=\"").append(namespace(table)).append("\">\n");
		xml.append("    <sql id=\"allColumns\">").append(quotedNames(table.columns())).append("</sql>\n\n");
		appendSelect(xml, table, "selectList", "<include refid=\"allColumns\"/>", true);
		appendSelect(xml, table, "selectCount", "COUNT(*)", false);
		appendInsert(xml, table);
		appendUpdate(xml, table);
		xml.append("</mapper>\n");
		return xml.toString();
	}

	/** {@code <select>}：投影 + {@code <where>} 里的逐列条件，可选按主键排序。 */
	private void appendSelect(StringBuilder xml, MetaTable table, String id, String projection, boolean orderByKey) {
		xml.append("    <select id=\"").append(id).append("\">\n");
		xml.append("        SELECT ").append(projection).append(" FROM ").append(quote(table.name())).append('\n');
		appendWhere(xml, table);
		if (orderByKey && !table.primaryKeys().isEmpty()) {
			xml.append("        ORDER BY ").append(quotedNames(table.primaryKeys())).append('\n');
		}
		xml.append("    </select>\n");
	}

	private void appendWhere(StringBuilder xml, MetaTable table) {
		xml.append("        <where>\n");
		MetaProperty logic = logicColumn(table);
		if (logic != null) {
			xml.append("            AND ").append(quote(logic.getName())).append(" = 0\n");
		}
		for (MetaProperty column : table.columns()) {
			if (column == logic) {
				continue;
			}
			xml.append("            <if test=\"").append(column.getName()).append(" != null\">AND ")
					.append(quote(column.getName())).append(" = #{").append(column.getName()).append("}</if>\n");
		}
		xml.append("        </where>\n");
	}

	/**
	 * {@code <insert>}：列清单和 VALUES 各用一个 {@code <trim>}，
	 * 只写调用方实际给了值的列——自增主键整体不出现，缺省列交给数据库的 DEFAULT。
	 */
	private void appendInsert(StringBuilder xml, MetaTable table) {
		List<MetaProperty> writable = new ArrayList<>();
		for (MetaProperty column : table.columns()) {
			if (!column.isAutoIncrement()) {
				writable.add(column);
			}
		}
		xml.append("    <insert id=\"insert\">\n");
		xml.append("        INSERT INTO ").append(quote(table.name())).append('\n');
		appendTrimOpen(xml, "(", ")");
		for (MetaProperty column : writable) {
			xml.append("            <if test=\"").append(column.getName()).append(" != null\">")
					.append(quote(column.getName())).append(",</if>\n");
		}
		xml.append("        </trim>\n");
		appendTrimOpen(xml, "VALUES (", ")");
		for (MetaProperty column : writable) {
			xml.append("            <if test=\"").append(column.getName()).append(" != null\">#{")
					.append(column.getName()).append("},</if>\n");
		}
		xml.append("        </trim>\n");
		xml.append("    </insert>\n");
	}

	/**
	 * {@code <update>}：{@code <trim prefix="SET ">} 只更新传了值的非主键列，条件固定在主键上。
	 * 无主键的表不生成这条语句——没有主键条件的部分更新会一次改全表。
	 */
	private void appendUpdate(StringBuilder xml, MetaTable table) {
		if (table.primaryKeys().isEmpty()) {
			return;
		}
		xml.append("    <update id=\"updateByKey\">\n");
		xml.append("        UPDATE ").append(quote(table.name())).append('\n');
		appendTrimOpen(xml, "SET ", "");
		for (MetaProperty column : table.columns()) {
			if (column.isPrimaryKey()) {
				continue;
			}
			xml.append("            <if test=\"").append(column.getName()).append(" != null\">")
					.append(quote(column.getName())).append(" = #{").append(column.getName()).append("},</if>\n");
		}
		xml.append("        </trim>\n");
		xml.append("        WHERE ").append(keyCondition(table)).append('\n');
		xml.append("    </update>\n");
	}

	private static void appendTrimOpen(StringBuilder xml, String prefix, String suffix) {
		xml.append("        <trim prefix=\"").append(prefix).append("\" suffix=\"").append(suffix)
				.append("\" suffixOverrides=\",\">\n");
	}

	private String quotedNames(List<MetaProperty> columns) {
		StringBuilder names = new StringBuilder();
		for (MetaProperty column : columns) {
			if (!names.isEmpty()) {
				names.append(", ");
			}
			names.append(quote(column.getName()));
		}
		return names.toString();
	}

	private String keyCondition(MetaTable table) {
		StringBuilder condition = new StringBuilder();
		for (MetaProperty key : table.primaryKeys()) {
			if (!condition.isEmpty()) {
				condition.append(" AND ");
			}
			condition.append(quote(key.getName())).append(" = #{").append(key.getName()).append('}');
		}
		return condition.toString();
	}

	private String quote(String identifier) {
		return this.dialect.quoteIdentifier(identifier);
	}

	// ------------------------------------------------------------------
	// 文件与 classpath 读取
	// ------------------------------------------------------------------

	private static String readDisk(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to read mapper file '" + file + "'", ex);
		}
	}

	private String readClasspath(String fileName) {
		String resource = CLASSPATH_DIRECTORY + fileName;
		try (InputStream input = this.classLoader.getResourceAsStream(resource)) {
			if (input == null) {
				return null;
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to read classpath mapper file '" + resource + "'", ex);
		}
	}

	/** 落盘失败不算失败：内存里的语句照样能用，只是下次访问还要再生成一次。 */
	private static void write(Path file, String content, MetaTable table) {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			LOG.log(Level.WARNING, "failed to write mapper file ''{0}'' for table ''{1}'';"
					+ " using the generated content in memory", new Object[] { file, table.name(), ex });
		}
	}

	private static String fileName(MetaTable table) {
		String safe = table.name().replaceAll("[^A-Za-z0-9_.-]", "_").replace("..", "_");
		return (safe.startsWith(".") ? "_" + safe : safe) + ".xml";
	}

	/**
	 * 候选文件名：表名原样、小写、大写。文件系统的大小写规则不统一，元数据里的表名在
	 * H2/Oracle 下是大写、MySQL 习惯写小写，手写文件 {@code demo_order.xml} 配
	 * {@code DEMO_ORDER} 这张表必须能对上，否则会被当成「文件不存在」又生成一份。
	 */
	private static List<String> fileNames(MetaTable table) {
		String base = fileName(table);
		Set<String> names = new LinkedHashSet<>();
		names.add(base);
		names.add(base.toLowerCase(Locale.ROOT));
		names.add(base.toUpperCase(Locale.ROOT));
		return List.copyOf(names);
	}

	private static String namespace(MetaTable table) {
		return "dynamic." + table.name().replaceAll("[^A-Za-z0-9_.-]", "_");
	}

	private MetaProperty logicColumn(MetaTable table) {
		String column = table.logicDeleteColumn();
		return column == null ? null : table.findColumn(column);
	}

	private static String normalize(String tableName) {
		return tableName.toLowerCase(Locale.ROOT);
	}

	private static ClassLoader defaultClassLoader() {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return loader == null ? MetaStatementStore.class.getClassLoader() : loader;
	}
}
