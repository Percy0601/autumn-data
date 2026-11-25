package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import xyz.wewin.autumn.data.common.dialect.DefaultDialect;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperXmlParser;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaCache;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaCaches;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories;

/**
 * <b>唯一的语句库</b>：每张表两份 XML，分别由框架和使用方所有。
 *
 * <pre>{@code
 * {根}/{数据源}-default/{命名空间}-default/auto/xml/{表名}.xml   框架生成，表结构变化整份重写
 * {根}/{数据源}-default/{命名空间}-default/spec/xml/{表名}.xml   使用方手写，框架只读不写
 * }</pre>
 *
 * <p>两份文件用同一套 MyBatis 语法（{@code autumn-data-common} 的 XML AST）：
 * {@code #{}} 绑定、{@code ${}} 标识符、{@code <if>/<where>/<trim>/<foreach>/<choose>}、
 * {@code <sql>/<include>}、{@code <resultMap>}，另有内置谓词 {@code changed('col')}。</p>
 *
 * <h2>职责边界</h2>
 * <ul>
 *   <li><b>auto 文件</b>：七条默认语句（findList / findPage / findOne / findById / save / update / delete）。
 *       文件头的 {@code AUTUMN-DATA GENERATED fingerprint=...} 注释记着结构指纹；指纹变了整份重写，
 *       没有生成块、不做局部合并——使用方不该改这个文件。</li>
 *   <li><b>spec 文件</b>：使用方的自定义语句。框架<b>只读、绝不创建也不覆盖</b>；磁盘或 classpath
 *       （{@code classpath:meta-data/{数据源}-default/{命名空间}-default/spec/xml/{表名}.xml}）有一份就解析一份。
 *       同 id 时 spec 覆盖 auto，因此可以在 spec 里覆盖任意一条默认语句来换语义（含 delete 的逻辑/物理删除）。</li>
 * </ul>
 *
 * <h2>生成 / 落盘时机</h2>
 * <ul>
 *   <li>磁盘根可写且 {@code generateFiles=true}：auto 文件不存在或指纹过期时整份写回；spec 文件只读。</li>
 *   <li>只读 classpath 或纯内存模式（{@code generateFiles=false}）：auto 文件不落盘，改写后的内容只在内存
 *       使用，默认语句照样可用；classpath 里的 spec 文件保持只读。</li>
 * </ul>
 *
 * <h2>缓存</h2>
 * auto 与 spec 各一级有界 LRU，按「修改时间 + 长度」自动重读（classpath 不可变，读一次即固定），
 * auto 额外按结构指纹判活。构造时不扫目录、不读文件，全部懒加载。
 */
public final class MetaSqlFileStore {

	private static final Logger LOG = Logger.getLogger(MetaSqlFileStore.class.getName());

	/** 默认命名空间。 */
	public static final String DEFAULT_NAMESPACE = "default";

	/** 文件系统根目录的系统属性名；{@code null}/空白表示只读 classpath。 */
	public static final String ROOT_PROPERTY = "autumn.data.meta.sql.root";

	private final MetaDirectories directories;

	private final ClassLoader classLoader;

	private final MetaSqlGenerator generator;

	private final boolean generateFiles;

	/** 框架生成的 auto 文件缓存（含七条默认语句）。 */
	private final MetaCache<String, SqlFile> autoFiles;

	/** 使用方手写的 spec 文件缓存。 */
	private final MetaCache<String, SqlFile> specFiles;

	/** 结构指纹 → 七条默认语句（避免每次访问都重新解析生成模板）。 */
	private final MetaCache<String, Map<String, SqlTemplate>> defaults;

	// ------------------------------------------------------------------
	// 构造
	// ------------------------------------------------------------------

	/** 只读磁盘根 + classpath；不生成文件（诊断、纯读取场景）。 */
	public MetaSqlFileStore(Path rootDirectory) {
		this(rootDirectory == null ? null : MetaDirectories.of(rootDirectory.toString()),
				defaultClassLoader(), MetaCache.DEFAULT_MAXIMUM_SIZE, new MetaSqlGenerator(new DefaultDialect()),
				false);
	}

	public MetaSqlFileStore(Path rootDirectory, ClassLoader classLoader, int maximumSize) {
		this(rootDirectory == null ? null : MetaDirectories.of(rootDirectory.toString()), classLoader, maximumSize,
				new MetaSqlGenerator(new DefaultDialect()), false);
	}

	/**
	 * @param directories   目录约定；{@code null} 表示只读 classpath
	 * @param classLoader   读 classpath 用；{@code null} 时取线程上下文加载器
	 * @param maximumSize   各级缓存上限
	 * @param generator     默认语句生成器（决定方言引号）
	 * @param generateFiles 是否把生成的 auto 文件写回磁盘
	 */
	public MetaSqlFileStore(MetaDirectories directories, ClassLoader classLoader, int maximumSize,
			MetaSqlGenerator generator, boolean generateFiles) {
		if (generator == null) {
			throw new IllegalArgumentException("generator must not be null");
		}
		this.directories = directories;
		this.classLoader = classLoader == null ? defaultClassLoader() : classLoader;
		this.generator = generator;
		this.generateFiles = generateFiles;
		this.autoFiles = MetaCaches.lru(maximumSize);
		this.specFiles = MetaCaches.lru(maximumSize);
		this.defaults = MetaCaches.lru(maximumSize);
	}

	/** 只读 classpath；文件系统根取系统属性 {@value #ROOT_PROPERTY}。 */
	public static MetaSqlFileStore classpath() {
		return classpath(defaultClassLoader(), new MetaSqlGenerator(new DefaultDialect()));
	}

	public static MetaSqlFileStore classpath(ClassLoader classLoader, MetaSqlGenerator generator) {
		String root = System.getProperty(ROOT_PROPERTY);
		MetaDirectories dirs = root == null || root.isBlank() ? null : MetaDirectories.of(root.trim());
		return new MetaSqlFileStore(dirs, classLoader, MetaCache.DEFAULT_MAXIMUM_SIZE, generator, false);
	}

	/** 默认语句生成器（客户端用它给 {@code ${table}} 填方言引号后的表名）。 */
	public MetaSqlGenerator generator() {
		return this.generator;
	}

	// ------------------------------------------------------------------
	// 主入口：带表结构，保证七条默认语句一定可用
	// ------------------------------------------------------------------

	/**
	 * 该 (数据源, 命名空间, 表) 下的全部语句：spec 自定义语句 + auto 默认语句。
	 *
	 * <p>传入 {@link MetaTable} 才会触发生成/落盘与默认语句兜底；返回的是解析后的
	 * {@link SqlTemplate}（AST + 解释器），不可修改。spec 同 id 覆盖 auto。</p>
	 */
	public Map<String, SqlTemplate> statements(String datasource, String namespace, MetaTable table) {
		if (table == null) {
			throw new IllegalArgumentException("table must not be null");
		}
		return statements(datasource, namespace, table.name(), table);
	}

	/** 取一条语句；两边都没有返回 {@code null}。 */
	public SqlTemplate statement(String datasource, String namespace, MetaTable table, String statementId) {
		return statements(datasource, namespace, table).get(requireId(statementId));
	}

	/** 该表可用的语句 id（诊断用）。 */
	public List<String> statementIds(String datasource, String namespace, MetaTable table) {
		return List.copyOf(statements(datasource, namespace, table).keySet());
	}

	/** 缺失时给出「两份文件在哪、有哪些 id」的清晰错误。 */
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

	/** 幂等地确保该表的 auto 文件已按当前结构生成（首次访问也会自动做）。 */
	public String ensure(String datasource, String namespace, MetaTable table) {
		statements(datasource, namespace, table);
		SqlFile cached = this.autoFiles.getIfPresent(key(datasource, namespace, table.name()));
		return cached == null ? describeAuto(datasource, namespace, table.name()) : cached.location();
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
		Map<String, SqlTemplate> auto = loadAuto(datasource, namespace, table, meta).statements();
		Map<String, SqlTemplate> spec = loadSpec(datasource, namespace, table).statements();
		if (spec.isEmpty()) {
			return auto;
		}
		// spec 优先：同 id 覆盖 auto 的默认语句，便于换掉删除语义 / 条件 / 列清单。
		Map<String, SqlTemplate> merged = new LinkedHashMap<>(auto);
		merged.putAll(spec);
		return Collections.unmodifiableMap(merged);
	}

	// ------------------------------------------------------------------
	// 缓存装载
	// ------------------------------------------------------------------

	/** auto 文件：指纹过期或磁盘变化时整份重写。 */
	private SqlFile loadAuto(String datasource, String namespace, String table, MetaTable meta) {
		String key = key(datasource, namespace, table);
		String fingerprint = meta == null ? null : this.generator.fingerprint(meta);
		SqlFile cached = this.autoFiles.getIfPresent(key);
		if (cached != null && !cached.stale() && sameFingerprint(cached.fingerprint(), fingerprint)) {
			return cached;
		}
		this.autoFiles.invalidate(key);
		SqlFile loaded = this.autoFiles.get(key,
				ignored -> loadAutoFile(datasource, namespace, table, meta, fingerprint));
		return loaded == null ? emptyFile(describeAuto(datasource, namespace, table)) : loaded;
	}

	private SqlFile loadAutoFile(String datasource, String namespace, String table, MetaTable meta,
			String fingerprint) {
		Path disk = autoTarget(datasource, namespace, table);
		String content = disk == null ? null : readIfExists(disk);
		Path stampPath = disk == null ? null : disk;
		long stamp = disk == null ? -1L : stamp(disk);

		if (content == null) {
			// 磁盘没有：classpath 有就用它，否则内存生成 / 落盘
			Source classpath = readClasspath(MetaDirectories.autoXmlResource(datasource, namespace, table));
			if (classpath.content() != null) {
				content = classpath.content();
				stampPath = classpath.stampPath();
				stamp = classpath.stamp();
			}
		}
		if (meta != null) {
			if (content == null) {
				String generated = this.generator.fullDocument(meta, namespace);
				if (this.generateFiles && disk != null && write(disk, generated)) {
					content = generated;
					stampPath = disk;
					stamp = stamp(disk);
				}
				else {
					content = this.generator.statementsDocument(meta);
					stampPath = null;
					stamp = -1L;
				}
			}
			else if (!fingerprint.equals(MetaSqlGenerator.fingerprintOf(content))) {
				// 指纹过期：整份重写。classpath 资源只读，改写只在内存里生效。
				String generated = this.generator.fullDocument(meta, namespace);
				if (this.generateFiles && disk != null && write(disk, generated)) {
					content = generated;
					stampPath = disk;
					stamp = stamp(disk);
				}
				else {
					content = generated;
					stampPath = null;
					stamp = -1L;
				}
			}
		}
		String location = disk == null ? describeAuto(datasource, namespace, table) : disk.toString();
		// 纯文件视图下没有 auto 文件也没有表结构：空语句集，不能把 null 交给 XML 解析器。
		Map<String, SqlTemplate> parsed = content == null ? Map.of() : parse(withMapperRoot(content), location);
		Map<String, SqlTemplate> result = new LinkedHashMap<>(parsed);
		if (meta != null) {
			// 内存兜底：auto 文件里缺的默认语句补上，文件里已有的同名 id 优先。
			for (Map.Entry<String, SqlTemplate> entry : defaults(meta).entrySet()) {
				result.putIfAbsent(entry.getKey(), entry.getValue());
			}
		}
		return new SqlFile(location, Collections.unmodifiableMap(result), stampPath, stamp, fingerprint);
	}

	/** spec 文件：只读磁盘 / classpath，框架绝不写。 */
	private SqlFile loadSpec(String datasource, String namespace, String table) {
		String key = key(datasource, namespace, table);
		SqlFile cached = this.specFiles.getIfPresent(key);
		if (cached != null && !cached.stale()) {
			return cached;
		}
		this.specFiles.invalidate(key);
		SqlFile loaded = this.specFiles.get(key,
				ignored -> loadSpecFile(datasource, namespace, table));
		return loaded == null ? emptyFile(describeSpec(datasource, namespace, table)) : loaded;
	}

	private SqlFile loadSpecFile(String datasource, String namespace, String table) {
		Path disk = specTarget(datasource, namespace, table);
		String content = disk == null ? null : readIfExists(disk);
		String location = describeSpec(datasource, namespace, table);
		Path stampPath = disk;
		long stamp = disk == null ? -1L : stamp(disk);
		if (content == null) {
			Source classpath = readClasspath(MetaDirectories.specXmlResource(datasource, namespace, table));
			if (classpath.content() != null) {
				content = classpath.content();
				location = classpath.location();
				stampPath = classpath.stampPath();
				stamp = classpath.stamp();
			}
		}
		Map<String, SqlTemplate> parsed = content == null ? Map.of() : parse(withMapperRoot(content), location);
		return new SqlFile(location, parsed, stampPath, stamp, null);
	}

	private Map<String, SqlTemplate> defaults(MetaTable table) {
		String fingerprint = this.generator.fingerprint(table);
		return this.defaults.get(fingerprint,
				ignored -> parse(this.generator.statementsDocument(table), "generated defaults for " + table.name()));
	}

	/** 解析一份 XML：允许 spec 覆盖同名默认 id。 */
	private static Map<String, SqlTemplate> parse(String content, String location) {
		Map<String, SqlTemplate> statements = new LinkedHashMap<>();
		for (StatementDefinition statement : MapperXmlParser.parse(content, location).statements()) {
			statements.put(statement.id(), new SqlTemplate(statement));
		}
		return statements.isEmpty() ? Map.of() : Collections.unmodifiableMap(statements);
	}

	// ------------------------------------------------------------------
	// 缓存控制 / 诊断
	// ------------------------------------------------------------------

	/** 该表 auto 文件解析到的来源（磁盘路径或 classpath 资源），诊断用。 */
	public String location(String datasource, String namespace, String table) {
		SqlFile cached = this.autoFiles.getIfPresent(key(datasource, namespace, table));
		return cached == null ? describeAuto(datasource, namespace, table) : cached.location();
	}

	/** spec 文件实际路径（未加载时给出预期磁盘路径）。 */
	public String specLocation(String datasource, String namespace, String table) {
		SqlFile cached = this.specFiles.getIfPresent(key(datasource, namespace, table));
		return cached == null ? describeSpec(datasource, namespace, table) : cached.location();
	}

	/** spec 文件落盘路径；纯内存 / 只读模式返回 {@code null}。 */
	public String specFile(String datasource, String namespace, String table) {
		Path target = specTarget(datasource, namespace, table);
		return target == null ? null : target.toString();
	}

	/** 丢掉一张表的两份解析结果，下次访问重新读盘。 */
	public void refresh(String datasource, String namespace, String table) {
		String key = key(datasource, namespace, table);
		this.autoFiles.invalidate(key);
		this.specFiles.invalidate(key);
	}

	public void refreshAll() {
		this.autoFiles.invalidateAll();
		this.specFiles.invalidateAll();
		this.defaults.invalidateAll();
	}

	public long size() {
		return this.autoFiles.estimatedSize();
	}

	/** 已缓存的 spec 文件数（诊断用）。 */
	public long specSize() {
		return this.specFiles.estimatedSize();
	}

	public int maximumSize() {
		return this.autoFiles.maximumSize();
	}

	// ------------------------------------------------------------------
	// 文件寻址 / IO
	// ------------------------------------------------------------------

	private Path autoTarget(String datasource, String namespace, String table) {
		Path directory = this.directories == null ? null : this.directories.autoXmlDirectory(datasource, namespace);
		return directory == null ? null : directory.resolve(safe(table) + ".xml");
	}

	private Path specTarget(String datasource, String namespace, String table) {
		Path directory = this.directories == null ? null : this.directories.specXmlDirectory(datasource, namespace);
		return directory == null ? null : directory.resolve(safe(table) + ".xml");
	}

	private String describeAuto(String datasource, String namespace, String table) {
		String cp = "classpath:" + MetaDirectories.autoXmlResource(datasource, namespace, table);
		return this.directories == null ? cp : autoTarget(datasource, namespace, table) + " or " + cp;
	}

	private String describeSpec(String datasource, String namespace, String table) {
		String cp = "classpath:" + MetaDirectories.specXmlResource(datasource, namespace, table);
		return this.directories == null ? cp : specTarget(datasource, namespace, table) + " or " + cp;
	}

	private Source readClasspath(String resource) {
		URL url = this.classLoader.getResource(resource);
		if (url == null) {
			return new Source(null, "classpath:" + resource, null, -1L);
		}
		Path asFile = asFile(url);
		return new Source(read(url, resource), "classpath:" + resource, asFile,
				asFile == null ? -1L : stamp(asFile));
	}

	private static String safe(String table) {
		if (table == null || table.isBlank()) {
			throw new MetaSqlException("table name must not be blank");
		}
		return MetaDirectories.safeFileName(table.trim());
	}

	/**
	 * 补壳：没有 {@code <mapper>} 根就把内容整体包一层；有根但漏了 {@code namespace} 就补上。
	 */
	static String withMapperRoot(String content) {
		if (content == null || content.isBlank()) {
			return null;
		}
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

	private static String readIfExists(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
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

	private static String key(String datasource, String namespace, String table) {
		if (table == null || table.isBlank()) {
			throw new MetaSqlException("table name must not be blank");
		}
		return segment(datasource) + "/" + segment(namespace) + "/" + table.trim().toLowerCase(Locale.ROOT);
	}

	private static String segment(String name) {
		if (name == null || name.isBlank()) {
			throw new MetaSqlException("datasource and namespace must not be blank");
		}
		return MetaDirectories.safeFileName(name.trim());
	}

	private static boolean sameFingerprint(String cached, String requested) {
		return requested == null ? cached == null : requested.equals(cached);
	}

	private static long stamp(Path file) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
			return attributes.lastModifiedTime().toMillis() * 31 + attributes.size();
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
			return java.nio.file.Paths.get(url.toURI());
		}
		catch (Exception ex) {
			return null;
		}
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

	/** 一次文件读取的结果：内容、来源描述、判活用的磁盘文件与时间戳。 */
	private record Source(String content, String location, Path stampPath, long stamp) {
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

	private static SqlFile emptyFile(String location) {
		return new SqlFile(location, Map.of(), null, -1L, null);
	}
}
