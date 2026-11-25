package xyz.wewin.autumn.data.meta.jdbc.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 动态层的目录约定：数据源 / 命名空间分层，spec 归使用方、auto 归框架。
 *
 * <pre>
 * &lt;root&gt;/
 *   {数据源}-default/{命名空间}-default/
 *     spec/
 *       vo/       用户手写 VO 的约定位置（框架不读写）
 *       xml/      用户手写语句：框架只读、绝不覆盖
 *     auto/
 *       entity/   保留目录（统一 MetaClass 后不再生成实体源码）
 *       xml/      框架生成的七条默认语句（指纹变了整份重写）
 * </pre>
 *
 * <p>{@code root} 同时是 classpath 资源根名：只读部署时手写语句从
 * {@code classpath:meta-data/{数据源}-default/{命名空间}-default/spec/xml/{表名}.xml} 读取。
 * 空串表示纯内存模式：产物只在内存里生成，不留盘。</p>
 *
 * @param rootDirectory 根目录；{@code null} 表示纯内存模式
 */
public record MetaDirectories(Path rootDirectory) {

	/** 默认根目录名；也是 classpath 资源根名。 */
	public static final String DEFAULT_ROOT = "meta-data";

	/** classpath 资源根（与磁盘根同名）。 */
	public static final String CLASSPATH_ROOT = DEFAULT_ROOT;

	/** 数据源 / 命名空间目录的固定后缀。 */
	public static final String DEFAULT_SUFFIX = "-default";

	/** 未显式指定时用于 share 目录的默认段。 */
	public static final String DEFAULT_SEGMENT = "default";

	public static final String SPEC = "spec";
	public static final String AUTO = "auto";
	public static final String VO = "vo";
	public static final String XML = "xml";
	public static final String ENTITY = "entity";

	private static final Logger LOG = Logger.getLogger(MetaDirectories.class.getName());

	/** 相对工作目录的默认根。 */
	public static MetaDirectories defaults() {
		return of(DEFAULT_ROOT);
	}

	/** 空白字符串按纯内存模式处理。 */
	public static MetaDirectories of(String rootDirectory) {
		String trimmed = rootDirectory == null ? "" : rootDirectory.trim();
		return new MetaDirectories(trimmed.isEmpty() ? null : Paths.get(trimmed));
	}

	/**
	 * 数据源目录段：名字后面固定加 {@value #DEFAULT_SUFFIX}；已经带后缀的不再重复加。
	 */
	public static String datasourceSegment(String datasource) {
		return segment(datasource, "datasource");
	}

	/** 命名空间目录段，规则同 {@link #datasourceSegment(String)}。 */
	public static String namespaceSegment(String namespace) {
		return segment(namespace, "namespace");
	}

	private static String segment(String name, String label) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException(label + " must not be blank");
		}
		String safe = safeFileName(name.trim());
		return safe.endsWith(DEFAULT_SUFFIX) ? safe : safe + DEFAULT_SUFFIX;
	}

	/** 数据源 + 命名空间这一层目录；纯内存模式返回 {@code null}。 */
	public Path scopeDirectory(String datasource, String namespace) {
		return this.rootDirectory == null ? null
				: this.rootDirectory.resolve(datasourceSegment(datasource)).resolve(namespaceSegment(namespace));
	}

	/** 用户手写 VO 的约定目录（框架不读写，仅供使用方 / 文档引用）。 */
	public Path specVoDirectory(String datasource, String namespace) {
		return subDirectory(scopeDirectory(datasource, namespace), SPEC, VO);
	}

	/** 用户手写语句目录：框架只读、绝不覆盖。 */
	public Path specXmlDirectory(String datasource, String namespace) {
		return subDirectory(scopeDirectory(datasource, namespace), SPEC, XML);
	}

	/**
	 * 实体定义目录（{@code spec/} 根下）：{@code {表名}.json} 定义优先；纯内存模式返回 {@code null}。
	 */
	public Path specDirectory(String datasource, String namespace) {
		return scopeDirectory(datasource, namespace) == null ? null
				: scopeDirectory(datasource, namespace).resolve(SPEC);
	}

	/** 实体源码的保留目录；统一 MetaClass 后框架不再写这里。 */
	public Path autoEntityDirectory(String datasource, String namespace) {
		return subDirectory(scopeDirectory(datasource, namespace), AUTO, ENTITY);
	}

	/** 框架生成的默认语句目录。 */
	public Path autoXmlDirectory(String datasource, String namespace) {
		return subDirectory(scopeDirectory(datasource, namespace), AUTO, XML);
	}

	/**
	 * 共享实体注册表用的目录：实体只由表结构决定、全引擎共享，落在默认段下。
	 */
	public Path autoEntityDirectory() {
		return autoEntityDirectory(DEFAULT_SEGMENT, DEFAULT_SEGMENT);
	}

	private static Path subDirectory(Path base, String first, String second) {
		return base == null ? null : base.resolve(first).resolve(second);
	}

	/** classpath 上手写语句的资源路径（不带前导斜杠）。 */
	public static String specXmlResource(String datasource, String namespace, String table) {
		return classpathResource(datasource, namespace, SPEC, XML, table);
	}

	/** classpath 上框架生成语句的资源路径（不带前导斜杠）。 */
	public static String autoXmlResource(String datasource, String namespace, String table) {
		return classpathResource(datasource, namespace, AUTO, XML, table);
	}

	private static String classpathResource(String datasource, String namespace, String kind, String sub,
			String table) {
		return CLASSPATH_ROOT + "/" + datasourceSegment(datasource) + "/" + namespaceSegment(namespace) + "/" + kind
				+ "/" + sub + "/" + safeFileName(table) + ".xml";
	}

	/** 写文件前确保目录存在；失败只说明落不了盘，内存路径照样可用，因此不抛。 */
	public static boolean ensure(Path directory) {
		if (directory == null) {
			return false;
		}
		try {
			Files.createDirectories(directory);
			return true;
		}
		catch (IOException ex) {
			LOG.log(Level.WARNING, "failed to create directory ''{0}''; falling back to in-memory",
					new Object[] { directory, ex });
			return false;
		}
	}

	/** 把表名 / 元类名收口成安全文件名：路径分隔符与 {@code ..} 不得越出子目录。 */
	public static String safeFileName(String name) {
		String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_").replace("..", "_");
		return (safe.isEmpty() || safe.startsWith(".")) ? "_" + safe : safe;
	}
}
