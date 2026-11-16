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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementType;
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
 *
 * <p><b>代码注册的手写语句</b>（{@link #register(String, String, StatementType, String)}）放在文件之上：
 * 同一个 id 优先用注册的版本，文件里没有这张表也能跑。它们由代码持有，{@link #refresh(String)} /
 * {@link #refreshAll()} 只丢文件解析缓存、不会顺手删掉；要撤销就显式 {@link #unregister}。
 * 注册层自身也走有界 LRU，与「表是运行期产物」的定位一致。</p>
 */
public final class MetaStatementStore {

	private static final Logger LOG = Logger.getLogger(MetaStatementStore.class.getName());

	private static final String GENERATED_MARKER = "generated-by: autumn-data-meta-jdbc";

	private static final String CLASSPATH_DIRECTORY = "meta-mappers/";

	/** 语句 id 的允许写法：字母开头，字母/数字/下划线/点/横线。 */
	private static final java.util.regex.Pattern STATEMENT_ID = java.util.regex.Pattern
			.compile("[A-Za-z_][A-Za-z0-9_.-]*");

	private final Path directory;

	private final boolean generateFiles;

	private final ClassLoader classLoader;

	private final Dialect dialect;

	private final BoundedCache<String, Map<String, SqlTemplate>> cache;

	/** 代码里注册的手写语句（表名 → id → 模板）；和文件缓存分开，refresh 不动它。 */
	private final BoundedCache<String, Map<String, SqlTemplate>> declared;

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
		this.declared = BoundedCaches.lru(maximumSize);
	}

	/** 按语句 id 取模板：代码注册的优先，其次才是文件里的；不存在时给出该表可用的 id。 */
	public SqlTemplate statement(MetaTable table, String statementId) {
		SqlTemplate custom = declaredOrEmpty(table.name()).get(statementId);
		if (custom != null) {
			return custom;
		}
		Map<String, SqlTemplate> statements;
		try {
			statements = statements(table);
		}
		catch (MetaSqlException ex) {
			// 文件缺失且不生成：只要代码里注册过语句，就按注册的 id 列表报错，而不是抛「文件找不到」
			Set<String> available = new LinkedHashSet<>(declaredIds(table.name()));
			if (available.isEmpty()) {
				throw ex;
			}
			throw new MetaSqlException("table '" + table.name() + "' has no statement '" + statementId
					+ "' (no mapper file, only code-registered statements); available from register: " + available,
					ex);
		}
		SqlTemplate statement = statements.get(statementId);
		if (statement == null) {
			Set<String> available = new LinkedHashSet<>(declaredIds(table.name()));
			available.addAll(statements.keySet());
			throw new MetaSqlException("mapper file '" + fileName(table) + "' for table '" + table.name()
					+ "' has no statement '" + statementId + "'; available: " + available);
		}
		return statement;
	}

	/** 该表文件里的全部语句（首次访问时读盘或生成）；代码注册的不在这里。 */
	public Map<String, SqlTemplate> statements(MetaTable table) {
		return this.cache.get(normalize(table.name()), ignored -> load(table));
	}

	// ------------------------------------------------------------------
	// 代码注册的手写语句
	// ------------------------------------------------------------------

	/**
	 * 注册一条手写 SQL 作为该表的命名语句：之后 {@code sqlFor/selectList/selectOne/selectCount/execute}
	 * 用同一个 id 即可，等价于在 Mapper 文件里写一条 {@code <select id="...">}，但不需要建文件。
	 *
	 * <p>同一 id 重复注册就是覆盖（幂等），因此配置变更或热更新代码后可以直接再注册一次。
	 * {@code sql} 里的 {@code #{}} / {@code ${}} 与文件里的语义完全一致。</p>
	 *
	 * @param tableName  表名（只做缓存键，不要求此刻已存在：低代码平台的表可能稍后才建）
	 * @param statementId 语句 id
	 * @param type        语句类型；{@code DELETE} 会被拒绝（动态层不执行物理删除）
	 * @param sql         手写 SQL 文本
	 */
	public SqlTemplate register(String tableName, String statementId, StatementType type, String sql) {
		if (tableName == null || tableName.isBlank()) {
			throw new MetaSqlException("table name must not be blank");
		}
		if (statementId == null || !STATEMENT_ID.matcher(statementId).matches()) {
			throw new MetaSqlException("invalid statement id '" + statementId
					+ "'; use letters/digits/underscore/dot/dash and start with a letter or underscore");
		}
		if (type == null) {
			throw new MetaSqlException("statement type must not be null");
		}
		if (type == StatementType.DELETE) {
			throw new MetaSqlException("the dynamic layer does not run physical deletes; "
					+ "register a <select>/<insert>/<update> instead");
		}
		SqlTemplate template = SqlTemplate.ofText(statementId, type, sql);
		declared(tableName).put(statementId, template);
		return template;
	}

	/** 撤销一条代码注册的语句；返回 false 表示本来就没有。 */
	public boolean unregister(String tableName, String statementId) {
		Map<String, SqlTemplate> statements = this.declared.getIfPresent(normalize(tableName));
		return statements != null && statements.remove(statementId) != null;
	}

	/** 该表代码注册的语句 id（只读快照，诊断用；不会因为查询而给该表建一个空条目）。 */
	public List<String> declaredIds(String tableName) {
		return List.copyOf(declaredOrEmpty(tableName).keySet());
	}

	/** 该表全部可见的语句 id：代码注册的在前，文件里的在后（文件缺失时只有注册的）。 */
	public List<String> statementIds(MetaTable table) {
		Set<String> ids = new LinkedHashSet<>(declaredIds(table.name()));
		try {
			ids.addAll(statements(table).keySet());
		}
		catch (MetaSqlException ex) {
			// 文件缺失且不生成：这只是诊断接口，不应因为「还没建文件」而失败
			LOG.log(Level.FINE, "no mapper file for table ''{0}'': {1}", new Object[] { table.name(),
					ex.getMessage() });
		}
		return List.copyOf(ids);
	}

	/** 已注册语句的条数（跨表求和，诊断用）。 */
	public long declaredCount() {
		long count = 0;
		for (Map<String, SqlTemplate> statements : this.declared.asMap().values()) {
			count += statements.size();
		}
		return count;
	}

	/** 丢掉某张表的 AST 缓存，下次访问重新读文件；不重写文件，也不动代码注册的语句。 */
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

	/** 代码注册层的可写视图：并发注册/撤销与查询共用同一张表级映射。 */
	private Map<String, SqlTemplate> declared(String tableName) {
		return this.declared.get(normalize(tableName), ignored -> new ConcurrentHashMap<>());
	}

	/** 只读视图：没注册过的表不建空条目，避免诊断时把「查过语句」当成「注册过语句」。 */
	private Map<String, SqlTemplate> declaredOrEmpty(String tableName) {
		Map<String, SqlTemplate> statements = this.declared.getIfPresent(normalize(tableName));
		return statements == null ? Map.of() : statements;
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
