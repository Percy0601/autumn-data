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

import xyz.wewin.autumn.data.common.mapper.MapperDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementDefinition;
import xyz.wewin.autumn.data.common.mapper.MapperXmlParser;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCache;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCaches;
import xyz.wewin.autumn.data.meta.jdbc.support.MetaDirectories;

/**
 * 按「数据源 / 命名空间 / 表」三级目录组织的语句库：
 * {@code meta/{datasource}/{namespace}/{table}.xml}。
 *
 * <p>和 {@link MetaStatementStore}（{@code <root>/mapper/<表名>.xml}，一张表一个文件）的区别只是
 * <b>寻址方式</b>：这里把「连哪个库」「业务分组」也编进路径，因此同一张表可以在不同数据源、
 * 不同命名空间下各有一份语句，互不覆盖。语句内容本身还是同一套 XML 方言
 * （{@code autumn-data-common} 的 {@link MapperXmlParser}），{@code #{}} / {@code ${}} /
 * {@code <if>} / {@code <where>} / {@code <foreach>} / {@code <sql>} / {@code <resultMap>} 全部通用。</p>
 *
 * <h2>两份来源</h2>
 * <ol>
 *   <li><b>文件系统</b>：系统属性 {@value #ROOT_PROPERTY} 指到目录时优先读它（开发期改完即时生效）；</li>
 *   <li><b>classpath</b>：{@code meta/{datasource}/{namespace}/{table}.xml}，
 *       即打包进 jar 的默认语句。</li>
 * </ol>
 *
 * <h2>懒加载</h2>
 * 构造时不扫目录、不读任何文件；某个 (数据源, 命名空间, 表) 第一次被访问时才解析那一份文件。
 * 已解析的结果进有界 LRU（{@link BoundedCache}），低代码场景下表的数量再涨也不会把语句常驻内存。</p>
 *
 * <h2>动态更新</h2>
 * 每次访问都会比对来源的「最后修改时间 + 长度」：文件被改过就重新读盘、重新解析，
 * 不需要重启也不需要显式刷新（classpath 里以 jar 形式存在的文件不可变，读一次即固定）。
 * 想强制重读用 {@link #refresh(String, String, String)} / {@link #refreshAll()}。</p>
 *
 * <h2>宽松解析</h2>
 * 完整的 MyBatis 写法（{@code <?xml?>} + {@code <mapper namespace="...">}）照常解析；
 * 一个文件里只写几条语句、没有 {@code <mapper>} 根（或根上漏了 {@code namespace}）也能用——
 * 缺的部分在这里补上，省得每张表都写一遍壳。文件不存在不算错误：{@link #statements} 返回空表，
 * 由调用方决定「回退到按元数据现拼」还是「直接报错」。</p>
 */
public final class MetaSqlFileStore {

	/** 默认命名空间：{@code meta/{datasource}/default/{table}.xml}。 */
	public static final String DEFAULT_NAMESPACE = "default";

	/** 文件系统根目录的系统属性名；{@code null}/空白表示只读 classpath。 */
	public static final String ROOT_PROPERTY = "autumn.data.meta.sql.root";

	private static final String CLASSPATH_ROOT = "meta/";

	private static final String XML_EXTENSION = ".xml";

	private final Path rootDirectory;

	private final ClassLoader classLoader;

	private final BoundedCache<String, SqlFile> files;

	/** 只读 classpath；文件系统根目录取系统属性 {@value #ROOT_PROPERTY}。 */
	public static MetaSqlFileStore classpath() {
		return classpath(defaultClassLoader());
	}

	public static MetaSqlFileStore classpath(ClassLoader classLoader) {
		String root = System.getProperty(ROOT_PROPERTY);
		Path directory = root == null || root.isBlank() ? null : Paths.get(root.trim());
		return new MetaSqlFileStore(directory, classLoader, BoundedCache.DEFAULT_MAXIMUM_SIZE);
	}

	public MetaSqlFileStore(Path rootDirectory) {
		this(rootDirectory, defaultClassLoader(), BoundedCache.DEFAULT_MAXIMUM_SIZE);
	}

	/**
	 * @param rootDirectory 文件系统根（其下是 {@code 数据源/命名空间/表.xml}）；{@code null} 表示只读 classpath
	 * @param classLoader   读 classpath 用；{@code null} 时取线程上下文加载器
	 * @param maximumSize   同时缓存的「表级语句文件」数量上限
	 */
	public MetaSqlFileStore(Path rootDirectory, ClassLoader classLoader, int maximumSize) {
		this.rootDirectory = rootDirectory;
		this.classLoader = classLoader == null ? defaultClassLoader() : classLoader;
		this.files = BoundedCaches.lru(maximumSize);
	}

	/**
	 * 该 (数据源, 命名空间, 表) 下的全部语句；文件不存在返回空 Map（不抛）。
	 *
	 * <p>返回的 Map 不可修改，是解析后的 {@link SqlTemplate}（AST + 解释器），键是语句 id。</p>
	 */
	public Map<String, SqlTemplate> statements(String datasource, String namespace, String table) {
		String key = key(datasource, namespace, table);
		SqlFile cached = this.files.getIfPresent(key);
		if (cached != null && !cached.stale()) {
			return cached.statements();
		}
		// 过期（或从未加载）：先失效再走 get，让 get 的单飞语义管住并发重载
		this.files.invalidate(key);
		SqlFile loaded = this.files.get(key, ignored -> load(datasource, namespace, table));
		return loaded == null ? Map.of() : loaded.statements();
	}

	/** 取一条语句；文件或语句不存在返回 {@code null}。 */
	public SqlTemplate statement(String datasource, String namespace, String table, String statementId) {
		if (statementId == null || statementId.isBlank()) {
			throw new MetaSqlException("statement id must not be blank");
		}
		return statements(datasource, namespace, table).get(statementId);
	}

	/** 该表可用的语句 id（错误信息 / 诊断用）。 */
	public List<String> statementIds(String datasource, String namespace, String table) {
		return List.copyOf(statements(datasource, namespace, table).keySet());
	}

	/** 该表解析到的来源（磁盘路径或 classpath 资源，含「没找到」时的说明），诊断用。 */
	public String location(String datasource, String namespace, String table) {
		SqlFile cached = this.files.getIfPresent(key(datasource, namespace, table));
		return cached == null ? describe(datasource, namespace, table) : cached.location();
	}

	/** 丢掉一张表的解析结果，下次访问重新读盘（文件删除/回滚后也能用）。 */
	public void refresh(String datasource, String namespace, String table) {
		this.files.invalidate(key(datasource, namespace, table));
	}

	public void refreshAll() {
		this.files.invalidateAll();
	}

	/** 已缓存的表级条目数（诊断用）。 */
	public long size() {
		return this.files.estimatedSize();
	}

	/** 缓存上限（诊断用）。 */
	public int maximumSize() {
		return this.files.maximumSize();
	}

	// ------------------------------------------------------------------
	// 加载
	// ------------------------------------------------------------------

	private SqlFile load(String datasource, String namespace, String table) {
		List<String> names = fileNames(table);
		String directory = segment(datasource) + "/" + segment(namespace);
		if (this.rootDirectory != null) {
			Path base = this.rootDirectory.resolve(segment(datasource)).resolve(segment(namespace));
			for (String name : names) {
				Path file = base.resolve(name);
				if (!Files.isRegularFile(file)) {
					continue;
				}
				try {
					String content = Files.readString(file, StandardCharsets.UTF_8);
					return parse(content, file.toString(), file, stamp(file));
				}
				catch (IOException ex) {
					throw new MetaSqlException("failed to read meta sql file '" + file + "'", ex);
				}
			}
		}
		for (String name : names) {
			String resource = CLASSPATH_ROOT + directory + "/" + name;
			URL url = this.classLoader.getResource(resource);
			if (url == null) {
				continue;
			}
			try (InputStream input = url.openStream()) {
				String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
				Path file = asFile(url);
				return parse(content, "classpath:" + resource, file, file == null ? -1L : stamp(file));
			}
			catch (IOException ex) {
				throw new MetaSqlException("failed to read meta sql resource 'classpath:" + resource + "'", ex);
			}
		}
		return new SqlFile(describe(datasource, namespace, table), Map.of(), null, -1L);
	}

	private static SqlFile parse(String content, String location, Path file, long lastModified) {
		Map<String, SqlTemplate> statements = new LinkedHashMap<>();
		for (StatementDefinition statement : MapperXmlParser.parse(withMapperRoot(content), location).statements()) {
			if (statements.put(statement.id(), new SqlTemplate(statement)) != null) {
				throw new MetaSqlException("duplicate statement id '" + statement.id() + "' in " + location);
			}
		}
		return new SqlFile(location, statements.isEmpty() ? Map.of() : Collections.unmodifiableMap(statements),
				file, lastModified);
	}

	/**
	 * 补壳：没有 {@code <mapper>} 根就把内容整体包一层；有根但漏了 {@code namespace} 就补上。
	 * 根上的 {@code namespace} 在动态层只用于错误信息，缺了不该让整份文件不可用。
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

	// ------------------------------------------------------------------
	// 文件寻址
	// ------------------------------------------------------------------

	/**
	 * 候选文件名：表名原样、小写、大写。元数据里的表名在 H2/Oracle 下是大写，手写文件多按
	 * MySQL 习惯写小写，两边得能对上才算「文件存在」。
	 */
	private static List<String> fileNames(String table) {
		String base = MetaDirectories.safeFileName(table) + XML_EXTENSION;
		Set<String> names = new LinkedHashSet<>();
		names.add(base);
		names.add(base.toLowerCase(Locale.ROOT));
		names.add(base.toUpperCase(Locale.ROOT));
		return List.copyOf(names);
	}

	private String describe(String datasource, String namespace, String table) {
		String directory = CLASSPATH_ROOT + segment(datasource) + "/" + segment(namespace) + "/";
		String name = fileNames(table).get(0);
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

	private static ClassLoader defaultClassLoader() {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return loader == null ? MetaSqlFileStore.class.getClassLoader() : loader;
	}

	/** 一份文件解析后的结果；{@link #stale()} 决定下次访问要不要重读。 */
	private record SqlFile(String location, Map<String, SqlTemplate> statements, Path file, long lastModified) {

		/** 磁盘上的文件改过/被删，或长度变了；classpath 里的 jar 条目不可变，永远不算过期。 */
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
