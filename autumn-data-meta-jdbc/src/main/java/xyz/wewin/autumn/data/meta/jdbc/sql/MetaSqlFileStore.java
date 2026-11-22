package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import xyz.wewin.autumn.data.common.dialect.DefaultDialect;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperXmlParser;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCache;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCaches;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories;

/**
 * <b>唯一的语句库</b>：一张表一个实体语句文件 {@code {根}/{数据源}/{命名空间}/{表名}.xml}，
 * 跨表 / 自定义结果形状（{@code resultType="map"}/{@code "metaclass"}）的 VO 语句另放
 * {@code {根}/{数据源}/{命名空间}/{表名}.vo.xml}。两份文件都可以直接编辑，框架只维护实体文件里的
 * {@code GENERATED START/END} 块；VO 文件是使用方资产，框架只在首次注册时追加，之后不会重写。
 *
 * <p>语法只有一套，就是 {@code autumn-data-common} 的 MyBatis 风格 XML：
 * {@code #{}} 绑定、{@code ${}} 标识符、{@code <if>/<where>/<trim>/<foreach>/<choose>}、
 * {@code <sql>/<include>}、{@code <resultMap>}，另加框架内置谓词 {@code changed('col')}
 * （见 {@link SqlTemplate#CHANGED_KEY}）。没有第二套文件目录、没有第二种语句来源。</p>
 *
 * <h2>每份文件分两块</h2>
 * <pre>{@code
 * <mapper namespace="...">
 *   <!-- ===== AUTUMN-DATA GENERATED START fingerprint=1-xxxx ===== -->
 *     ... findList / findPage / findOne / findById / save / update / delete ...
 *   <!-- ===== AUTUMN-DATA GENERATED END ===== -->
 *
 *   <!-- 自定义语句写在这里：框架更新文件时原样保留 -->
 *   <select id="hotOrders">...</select>
 * </mapper>
 * }</pre>
 *
 * <p>标记头里带结构指纹。表结构变化时框架<b>只替换标记之间的内容</b>，标记之前的注释、
 * 标记之后的自定义语句、缩进与空行逐字保留（{@link MetaSqlGenerator#merge}）。没有标记的文件
 * 第一次被访问时，框架把生成块<b>插进文件</b>（{@link MetaSqlGenerator#inject}），默认语句因此
 * 在 XML 里可见、可改；文件后半段的自定义语句照旧逐字保留。</p>
 *
 * <h2>生成 / 更新的时机</h2>
 * <ul>
 *   <li>磁盘根可写且 {@code generateFiles=true}：文件不存在时写一份完整文档；没有生成块时插入
 *       生成块；已有生成块但指纹过期时只重写生成块；</li>
 *   <li>只读 classpath 或纯内存模式（{@code generateFiles=false}）：不落盘，改写后的内容只在
 *       内存里使用，默认语句照样可用；classpath 里的手写文件保持只读，不会被复制到磁盘根。</li>
 * </ul>
 *
 * <h2>缓存</h2>
 * 解析结果按 {@code 数据源/命名空间/表} 进有界 LRU；磁盘文件按「修改时间 + 长度」自动重读，
 * classpath（jar 内）不可变，读一次即固定。额外按结构指纹判活：表结构一变就重新生成并重新解析，
 * 不需要显式刷新。构造时不扫目录、不读任何文件，全部懒加载。
 */
public final class MetaSqlFileStore {

	private static final Logger LOG = Logger.getLogger(MetaSqlFileStore.class.getName());

	/** 默认命名空间：语句落在 {@code {根}/{数据源}/default/{表名}.xml}。 */
	public static final String DEFAULT_NAMESPACE = "default";

	/** 文件系统根目录的系统属性名；{@code null}/空白表示只读 classpath。 */
	public static final String ROOT_PROPERTY = "autumn.data.meta.sql.root";

	private static final String CLASSPATH_ROOT = "meta/";

	private static final String XML_EXTENSION = ".xml";

	/** 自定义结果形状语句的文件后缀：{@code meta_demo_form.vo.xml}。 */
	public static final String VO_FILE_SUFFIX = ".vo";

	/** 语句根目录；{@code null} 表示只读 classpath。 */
	private final Path rootDirectory;

	private final ClassLoader classLoader;

	private final MetaSqlGenerator generator;

	private final boolean generateFiles;

	/** 实体语句文件缓存（含七条默认语句）。 */
	private final BoundedCache<String, SqlFile> files;

	/** 结果形状 VO 语句文件缓存；与实体文件分开装载、分开失效。 */
	private final BoundedCache<String, SqlFile> voFiles;

	/** 结构指纹 → 七条默认语句（避免每次访问都重新解析同一份生成模板）。 */
	private final BoundedCache<String, Map<String, SqlTemplate>> defaults;

	// ------------------------------------------------------------------
	// 构造
	// ------------------------------------------------------------------

	/** 只读磁盘根 + classpath；不生成文件（诊断、纯读取场景）。 */
	public MetaSqlFileStore(Path rootDirectory) {
		this(rootDirectory, defaultClassLoader(), BoundedCache.DEFAULT_MAXIMUM_SIZE,
				new MetaSqlGenerator(new DefaultDialect()), false);
	}

	public MetaSqlFileStore(Path rootDirectory, ClassLoader classLoader, int maximumSize) {
		this(rootDirectory, classLoader, maximumSize, new MetaSqlGenerator(new DefaultDialect()), false);
	}

	/**
	 * @param rootDirectory 文件系统根（其下是 {@code 数据源/命名空间/表名.xml}）；{@code null} 表示只读 classpath
	 * @param classLoader   读 classpath 用；{@code null} 时取线程上下文加载器
	 * @param maximumSize   各级缓存上限
	 * @param generator     默认语句生成器（决定方言引号）
	 * @param generateFiles 是否把生成/合并结果写回磁盘
	 */
	public MetaSqlFileStore(Path rootDirectory, ClassLoader classLoader, int maximumSize, MetaSqlGenerator generator,
			boolean generateFiles) {
		if (generator == null) {
			throw new IllegalArgumentException("generator must not be null");
		}
		this.rootDirectory = rootDirectory;
		this.classLoader = classLoader == null ? defaultClassLoader() : classLoader;
		this.generator = generator;
		this.generateFiles = generateFiles;
		this.files = BoundedCaches.lru(maximumSize);
		this.voFiles = BoundedCaches.lru(maximumSize);
		this.defaults = BoundedCaches.lru(maximumSize);
	}

	/** 只读 classpath；文件系统根取系统属性 {@value #ROOT_PROPERTY}。 */
	public static MetaSqlFileStore classpath() {
		return classpath(defaultClassLoader(), new MetaSqlGenerator(new DefaultDialect()));
	}

	public static MetaSqlFileStore classpath(ClassLoader classLoader, MetaSqlGenerator generator) {
		String root = System.getProperty(ROOT_PROPERTY);
		Path directory = root == null || root.isBlank() ? null : Paths.get(root.trim());
		return new MetaSqlFileStore(directory, classLoader, BoundedCache.DEFAULT_MAXIMUM_SIZE, generator, false);
	}

	/** 默认语句生成器（客户端用它给 {@code ${table}} 填方言引号后的表名）。 */
	public MetaSqlGenerator generator() {
		return this.generator;
	}

	// ------------------------------------------------------------------
	// 主入口：带表结构，保证七条默认语句一定可用
	// ------------------------------------------------------------------

	/**
	 * 该 (数据源, 命名空间, 表) 下的全部语句：文件里的自定义语句 + 七条默认语句。
	 *
	 * <p>传入 {@link MetaTable} 才会触发生成/合并与默认语句兜底；返回的是解析后的
	 * {@link SqlTemplate}（AST + 解释器），不可修改。</p>
	 */
	public Map<String, SqlTemplate> statements(String datasource, String namespace, MetaTable table) {
		if (table == null) {
			throw new IllegalArgumentException("table must not be null");
		}
		return statements(datasource, namespace, table.name(), table);
	}

	/** 取一条语句；文件或语句都不存在返回 {@code null}。 */
	public SqlTemplate statement(String datasource, String namespace, MetaTable table, String statementId) {
		return statements(datasource, namespace, table).get(requireId(statementId));
	}

	/** 该表可用的语句 id（诊断用）。 */
	public List<String> statementIds(String datasource, String namespace, MetaTable table) {
		return List.copyOf(statements(datasource, namespace, table).keySet());
	}

	/** 缺失时给出「文件在哪、有哪些 id」的清晰错误，而不是返回 null 让调用方猜。 */
	public SqlTemplate requireStatement(String datasource, String namespace, MetaTable table, String statementId) {
		Map<String, SqlTemplate> all = statements(datasource, namespace, table);
		SqlTemplate found = all.get(requireId(statementId));
		if (found == null) {
			throw new MetaSqlException("no statement '" + statementId + "' for table '" + table.name() + "' under "
					+ datasource + "/" + namespace + " (" + location(datasource, namespace, table.name())
					+ "); available: " + all.keySet());
		}
		return found;
	}

	/** 幂等地确保该表的文件已按当前结构生成/更新（首次访问也会自动做，这里供显式调用）。 */
	public String ensure(String datasource, String namespace, MetaTable table) {
		statements(datasource, namespace, table);
		SqlFile cached = this.files.getIfPresent(key(datasource, namespace, table.name()));
		return cached == null ? describe(datasource, namespace, table.name()) : cached.location();
	}

	/**
	 * 首次创建 VO 语句文件：写到 {@code {表名}.vo.xml}，与实体语句文件分开。
	 *
	 * <p><b>文件已存在时原样返回，不再生成、不追加、不覆盖</b>——首次落盘后 SQL 归使用方所有，
	 * 想调整直接编辑 XML；下次访问按修改时间重新读盘。语句必须是
	 * {@code <select resultType="map">} 或 {@code resultType="metaclass"}。</p>
	 */
	public String appendShapeStatement(String datasource, String namespace, MetaTable table, String statementId,
			String xml) {
		Map<String, String> snippets = new LinkedHashMap<>();
		snippets.put(requireId(statementId), xml);
		return appendShapeStatements(datasource, namespace, table, snippets);
	}

	/** 首次创建 VO 语句文件，一次写入多条；已有文件绝不改写。 */
	public String appendShapeStatements(String datasource, String namespace, MetaTable table,
			Map<String, String> snippets) {
		if (snippets == null || snippets.isEmpty()) {
			throw new MetaSqlException("at least one VO statement is required");
		}
		if (!this.generateFiles) {
			throw new MetaSqlException("VO statement registration needs autumn.data.meta.mapper.generate-files=true");
		}
		Path target = shapeTarget(datasource, namespace, table);
		if (target == null) {
			throw new MetaSqlException("VO statement registration needs autumn.data.meta.mapper.meta-directory "
					+ "(current mode is in-memory)");
		}
		// 关键的人类使用场景：文件已经存在就是使用方资产，框架绝不重新生成。
		if (Files.isRegularFile(target)) {
			return target.toString();
		}

		String content = shapeDocument(datasource, namespace, table);
		for (Map.Entry<String, String> entry : snippets.entrySet()) {
			String id = requireId(entry.getKey());
			String snippet = entry.getValue() == null ? "" : stripDeclaration(entry.getValue()).trim();
			if (snippet.isEmpty()) {
				throw new MetaSqlException("VO statement '" + id + "' is empty");
			}
			if (snippet.contains("<mapper")) {
				throw new MetaSqlException("pass only the <select> fragment for VO statement '" + id
						+ "', not a whole <mapper> document");
			}
			content = insertBeforeMapperEnd(content, snippet);
		}

		Map<String, SqlTemplate> parsed = parse(content, target.toString());
		for (String id : snippets.keySet()) {
			SqlTemplate added = parsed.get(id);
			if (added == null) {
				throw new MetaSqlException("VO statement fragment does not declare <select id=\"" + id + "\">");
			}
			if (added.type() != MapperDefinition.StatementType.SELECT || !added.returnsCustomShape()) {
				throw new MetaSqlException("VO statement '" + id + "' must be a <select resultType=\"map\"> or "
						+ "<select resultType=\"metaclass\">");
			}
		}
		if (!write(target, content)) {
			throw new MetaSqlException("cannot write VO statement file '" + target + "'");
		}
		this.voFiles.invalidate(key(datasource, namespace, table.name()));
		return target.toString();
	}

	private Path shapeTarget(String datasource, String namespace, MetaTable table) {
		return table == null ? null : shapeTarget(datasource, namespace, table.name());
	}

	private Path shapeTarget(String datasource, String namespace, String table) {
		if (this.rootDirectory == null) {
			return null;
		}
		return this.rootDirectory.resolve(segment(datasource)).resolve(segment(namespace))
				.resolve(fileNames(table, VO_FILE_SUFFIX).get(0));
	}

	private String shapeDocument(String datasource, String namespace, MetaTable table) {
		String mapperNamespace = segment(datasource) + "/" + segment(namespace) + "/"
				+ MetaDirectories.safeFileName(table.name()) + VO_FILE_SUFFIX;
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<!-- VO 语句文件：跟实体语句分开，SQL 归使用方所有；框架首次注册时写入，之后不覆盖。 -->\n"
				+ "<mapper namespace=\"" + mapperNamespace + "\">\n</mapper>\n";
	}

	private static String insertBeforeMapperEnd(String content, String snippet) {
		int end = content.lastIndexOf("</mapper>");
		if (end < 0) {
			throw new MetaSqlException("VO statement file has no </mapper> closing tag");
		}
		StringBuilder indented = new StringBuilder(snippet.length() + 8);
		for (String line : snippet.split("\\R", -1)) {
			indented.append("    ").append(line).append('\n');
		}
		return content.substring(0, end) + indented + content.substring(end);
	}

	// ------------------------------------------------------------------
	// 纯文件视图：不带表结构，不生成、不补默认（诊断 / 兼容）
	// ------------------------------------------------------------------

	public Map<String, SqlTemplate> statements(String datasource, String namespace, String table) {
		return statements(datasource, namespace, table, null);
	}

	public SqlTemplate statement(String datasource, String namespace, String table, String statementId) {
		return statements(datasource, namespace, table).get(requireId(statementId));
	}

	public List<String> statementIds(String datasource, String namespace, String table) {
		return List.copyOf(statements(datasource, namespace, table).keySet());
	}

	private Map<String, SqlTemplate> statements(String datasource, String namespace, String table, MetaTable meta) {
		String fingerprint = meta == null ? null : this.generator.fingerprint(meta);
		SqlFile base = loadFile(datasource, namespace, table, meta, fingerprint, false);
		SqlFile vo = loadFile(datasource, namespace, table, null, null, true);
		if (vo.statements().isEmpty()) {
			return base.statements();
		}
		// VO 文件优先：同 id 时 resultType 形状语句覆盖实体文件里的旧定义，便于平滑迁移。
		Map<String, SqlTemplate> merged = new LinkedHashMap<>(base.statements());
		merged.putAll(vo.statements());
		return Collections.unmodifiableMap(merged);
	}

	/** 单份文件（实体 / VO）的 LRU 装载；两份文件各自按修改时间 + 长度判活。 */
	private SqlFile loadFile(String datasource, String namespace, String table, MetaTable meta, String fingerprint,
			boolean vo) {
		String key = key(datasource, namespace, table);
		BoundedCache<String, SqlFile> cache = vo ? this.voFiles : this.files;
		SqlFile cached = cache.getIfPresent(key);
		if (cached != null && !cached.stale() && sameFingerprint(cached.fingerprint(), fingerprint)) {
			return cached;
		}
		// 过期 / 结构变化：先失效再走 get，让单飞语义管住并发重载
		cache.invalidate(key);
		SqlFile loaded = cache.get(key, ignored -> load(datasource, namespace, table, meta, fingerprint, vo));
		return loaded == null ? emptyFile(datasource, namespace, table, vo) : loaded;
	}

	private static SqlFile emptyFile(String datasource, String namespace, String table, boolean vo) {
		return new SqlFile(vo ? datasource + "/" + namespace + "/" + table + VO_FILE_SUFFIX + XML_EXTENSION
				: datasource + "/" + namespace + "/" + table + XML_EXTENSION, Map.of(), null, -1L, null);
	}

	private static boolean sameFingerprint(String cached, String requested) {
		return requested == null ? cached == null : requested.equals(cached);
	}

	// ------------------------------------------------------------------
	// 缓存控制 / 诊断
	// ------------------------------------------------------------------

	/** 该表解析到的来源（磁盘路径或 classpath 资源），诊断用。 */
	public String location(String datasource, String namespace, String table) {
		SqlFile cached = this.files.getIfPresent(key(datasource, namespace, table));
		return cached == null ? describe(datasource, namespace, table) : cached.location();
	}

	/** VO 语句文件的实际路径（未加载时给出预期磁盘路径）。 */
	public String shapeLocation(String datasource, String namespace, String table) {
		SqlFile cached = this.voFiles.getIfPresent(key(datasource, namespace, table));
		return cached == null ? describe(datasource, namespace, table, true) : cached.location();
	}

	/** VO 语句文件的落盘路径；纯内存 / 只读模式返回 {@code null}。 */
	public String shapeFile(String datasource, String namespace, String table) {
		Path target = shapeTarget(datasource, namespace, table);
		return target == null ? null : target.toString();
	}

	/** 丢掉一张表的解析结果，下次访问重新读盘（想立刻生效时用；平时自动按修改时间 + 指纹重读）。 */
	public void refresh(String datasource, String namespace, String table) {
		String key = key(datasource, namespace, table);
		this.files.invalidate(key);
		this.voFiles.invalidate(key);
	}

	public void refreshAll() {
		this.files.invalidateAll();
		this.voFiles.invalidateAll();
		this.defaults.invalidateAll();
	}

	public long size() {
		return this.files.estimatedSize();
	}

	/** 已缓存的 VO 语句文件数（诊断用）。 */
	public long voSize() {
		return this.voFiles.estimatedSize();
	}

	public int maximumSize() {
		return this.files.maximumSize();
	}

	// ------------------------------------------------------------------
	// 加载：磁盘 → classpath → 内存生成
	// ------------------------------------------------------------------

	private SqlFile load(String datasource, String namespace, String table, MetaTable meta, String fingerprint,
			boolean vo) {
		Source source = readSource(datasource, namespace, table, vo);
		String content = source.content();
		String location = source.location();
		Path stampPath = source.stampPath();
		long stamp = source.stamp();

		if (meta != null && !vo) {
			if (content == null) {
				// 文件不存在：落一份完整文档，或在内存里用默认语句
				String generated = this.generator.fullDocument(meta, namespace);
				if (this.generateFiles && source.writeTarget() != null && write(source.writeTarget(), generated)) {
					content = generated;
					location = source.writeTarget().toString();
					stampPath = source.writeTarget();
					stamp = stamp(source.writeTarget());
				}
				else {
					content = this.generator.statementsDocument(meta);
					location = "generated in memory for " + table + " (" + location + ")";
				}
			}
			else {
				// 有生成块：指纹变了才重写，且只替换标记之间那一段；
				// 没有生成块：把默认语句插进文件（findOne 等因此在 XML 里可见、可改），自定义语句逐字保留
				String rewritten = this.generator.hasGeneratedBlock(content)
						? this.generator.merge(content, meta)
						: this.generator.inject(content, meta, namespace);
				if (rewritten != null) {
					content = rewritten;
					// 只回写磁盘上真实存在的文件；classpath 资源保持只读，改写结果只在内存里生效
					if (this.generateFiles && source.writeTarget() != null
							&& write(source.writeTarget(), rewritten)) {
						location = source.writeTarget().toString();
						stampPath = source.writeTarget();
						stamp = stamp(source.writeTarget());
					}
				}
			}
		}

		Map<String, SqlTemplate> parsed = content == null ? Map.of() : parse(withMapperRoot(content), location);
		Map<String, SqlTemplate> result = new LinkedHashMap<>(parsed);
		if (meta != null) {
			// 只读 classpath / 落盘失败时文件里可能仍缺默认语句：内存补齐，文件里已有的同名 id 优先
			for (Map.Entry<String, SqlTemplate> entry : defaults(meta).entrySet()) {
				result.putIfAbsent(entry.getKey(), entry.getValue());
			}
		}
		return new SqlFile(location, Collections.unmodifiableMap(result), stampPath, stamp, fingerprint);
	}

	private Map<String, SqlTemplate> defaults(MetaTable table) {
		String fingerprint = this.generator.fingerprint(table);
		return this.defaults.get(fingerprint,
				ignored -> parse(this.generator.statementsDocument(table), "generated defaults for " + table.name()));
	}

	/** 解析一份 XML：允许自定义区覆盖同名默认 id（后者优先），不因同名而让整份文件不可用。 */
	private static Map<String, SqlTemplate> parse(String content, String location) {
		Map<String, SqlTemplate> statements = new LinkedHashMap<>();
		for (StatementDefinition statement : MapperXmlParser.parse(content, location).statements()) {
			statements.put(statement.id(), new SqlTemplate(statement));
		}
		return statements.isEmpty() ? Map.of() : Collections.unmodifiableMap(statements);
	}

	private Source readSource(String datasource, String namespace, String table, boolean vo) {
		List<String> names = fileNames(table, vo ? VO_FILE_SUFFIX : "");
		String directory = segment(datasource) + "/" + segment(namespace);
		Path base = this.rootDirectory == null ? null
				: this.rootDirectory.resolve(segment(datasource)).resolve(segment(namespace));
		if (base != null) {
			for (String name : names) {
				Path file = base.resolve(name);
				if (Files.isRegularFile(file)) {
					return new Source(read(file), file.toString(), file, stamp(file), file);
				}
			}
		}
		for (String name : names) {
			String resource = CLASSPATH_ROOT + directory + "/" + name;
			URL url = this.classLoader.getResource(resource);
			if (url == null) {
				continue;
			}
			Path asFile = asFile(url);
			// classpath 资源只读：不复制、不回写，生成块只在内存里补
			return new Source(read(url, resource), "classpath:" + resource, asFile,
					asFile == null ? -1L : stamp(asFile), null);
		}
		Path target = base == null ? null : base.resolve(names.get(0));
		return new Source(null, describe(datasource, namespace, table, vo), null, -1L, target);
	}

	/**
	 * 补壳：没有 {@code <mapper>} 根就把内容整体包一层；有根但漏了 {@code namespace} 就补上。
	 * 根上的 namespace 在动态层只用于错误信息，缺了不该让整份文件不可用。
	 */
	static String withMapperRoot(String content) {
		int at = content.indexOf("<mapper");
		if (at < 0) {
			return "<mapper namespace=\"meta\">\n" + stripDeclaration(content) + "\n</mapper>";
		}
		int end = content.indexOf('>', at);
		if (end < 0) {
			return content;
		}
		String openTag = content.substring(at, end);
		if (openTag.contains("namespace")) {
			return content;
		}
		return content.substring(0, at) + openTag + " namespace=\"meta\"" + content.substring(end);
	}

	private static String stripDeclaration(String content) {
		int at = content.indexOf("<?xml");
		if (at < 0) {
			return content;
		}
		int end = content.indexOf("?>", at);
		return end < 0 ? content : content.substring(0, at) + content.substring(end + 2);
	}

	private static boolean write(Path file, String content) {
		if (file == null) {
			return false;
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
			return true;
		}
		catch (IOException ex) {
			LOG.log(Level.WARNING, "failed to write statement file ''{0}''; using the content in memory",
					new Object[] { file, ex });
			return false;
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to read statement file '" + file + "'", ex);
		}
	}

	private static String read(URL url, String resource) {
		try (InputStream input = url.openStream()) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to read statement resource 'classpath:" + resource + "'", ex);
		}
	}

	// ------------------------------------------------------------------
	// 文件寻址
	// ------------------------------------------------------------------

	/** 候选文件名：表名原样、小写、大写。元数据表名在 H2/Oracle 下常是大写，手写文件多按小写。 */
	private static List<String> fileNames(String table) {
		return fileNames(table, "");
	}

	private static List<String> fileNames(String table, String suffix) {
		String base = MetaDirectories.safeFileName(table) + suffix + XML_EXTENSION;
		Set<String> names = new LinkedHashSet<>();
		names.add(base);
		names.add(base.toLowerCase(Locale.ROOT));
		names.add(base.toUpperCase(Locale.ROOT));
		return List.copyOf(names);
	}

	private String describe(String datasource, String namespace, String table) {
		return describe(datasource, namespace, table, false);
	}

	private String describe(String datasource, String namespace, String table, boolean vo) {
		String directory = CLASSPATH_ROOT + segment(datasource) + "/" + segment(namespace) + "/";
		String name = fileNames(table, vo ? VO_FILE_SUFFIX : "").get(0);
		return (this.rootDirectory == null ? "" : this.rootDirectory.resolve(segment(datasource))
				.resolve(segment(namespace)).resolve(name) + " or ") + "classpath:" + directory + name;
	}

	/** 目录名只保留安全字符：数据源/命名空间来自调用方，不得借 {@code ..} 越出根目录。 */
	private static String segment(String name) {
		if (name == null || name.isBlank()) {
			throw new MetaSqlException("datasource and namespace must not be blank");
		}
		return MetaDirectories.safeFileName(name.trim());
	}

	private static long stamp(Path file) {
		try {
			return Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file);
		}
		catch (IOException ex) {
			return -1L;
		}
	}

	private static Path asFile(URL url) {
		if (!"file".equals(url.getProtocol())) {
			return null;
		}
		try {
			return Paths.get(url.toURI());
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static String key(String datasource, String namespace, String table) {
		if (table == null || table.isBlank()) {
			throw new MetaSqlException("table name must not be blank");
		}
		return segment(datasource) + "/" + segment(namespace) + "/" + table.trim().toLowerCase(Locale.ROOT);
	}

	private static String requireId(String statementId) {
		if (statementId == null || statementId.isBlank()) {
			throw new MetaSqlException("statement id must not be blank");
		}
		return statementId;
	}

	private static ClassLoader defaultClassLoader() {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return loader == null ? MetaSqlFileStore.class.getClassLoader() : loader;
	}

	/**
	 * 一次文件读取的结果：内容、来源描述、判活用的磁盘文件与时间戳、可回写的目标。
	 * {@code writeTarget} 为 {@code null} 表示来源只读（classpath 资源），框架不落盘。
	 */
	private record Source(String content, String location, Path stampPath, long stamp, Path writeTarget) {
	}

	/** 一份文件解析后的结果；{@link #stale()} 决定下次访问要不要重读。 */
	private record SqlFile(String location, Map<String, SqlTemplate> statements, Path file, long lastModified,
			String fingerprint) {

		boolean stale() {
			if (this.file == null) {
				return false;
			}
			if (!Files.isRegularFile(this.file)) {
				return true;
			}
			return stamp(this.file) != this.lastModified;
		}
	}

}
