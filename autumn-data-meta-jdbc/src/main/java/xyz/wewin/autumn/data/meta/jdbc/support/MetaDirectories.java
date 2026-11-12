package xyz.wewin.autumn.data.meta.jdbc.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 动态层的工作目录：一个根目录 + 四个固定子目录，使用方只需要给根。
 *
 * <pre>
 * &lt;root&gt;/
 *   meta-class/   元类定义（JSON）：定义优先，库里多出来的列由运行期补齐
 *   entity/       生成的实体 .java
 *   template/     生成实体用的 .ftl（内置默认在首次使用时落一份，之后归使用方编辑）
 *   mapper/       动态表的 MyBatis 风格 Mapper XML
 * </pre>
 *
 * <p>四类产物遵循同一条规则：<b>文件已经存在就绝不重写</b>——它们一旦落到磁盘就归使用方所有，
 * 运行期只读；进程内的有界 LRU 负责「同一份文件不重复解析 / 同一份结构不重复编译」。
 * 目录为空串表示纯内存模式：模板退回 classpath 内置那份，产物只在内存里生成，不留盘。</p>
 *
 * @param rootDirectory 根目录；{@code null} 表示纯内存模式
 */
public record MetaDirectories(Path rootDirectory) {

	/** 默认根目录名（相对工作目录）。 */
	public static final String DEFAULT_ROOT = "autumn-meta";

	/** 元类定义（JSON）子目录。 */
	public static final String META_CLASS = "meta-class";

	/** 生成的实体源码子目录。 */
	public static final String ENTITY = "entity";

	/** 实体模板（.ftl）子目录。 */
	public static final String TEMPLATE = "template";

	/** Mapper XML 子目录。 */
	public static final String MAPPER = "mapper";

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

	/** 是否落盘。 */
	public boolean onDisk() {
		return this.rootDirectory != null;
	}

	/** 子目录绝对化前的相对路径；纯内存模式返回 {@code null}。 */
	public Path directory(String subDirectory) {
		return this.rootDirectory == null ? null : this.rootDirectory.resolve(subDirectory);
	}

	public Path metaClassDirectory() {
		return directory(META_CLASS);
	}

	public Path entityDirectory() {
		return directory(ENTITY);
	}

	public Path templateDirectory() {
		return directory(TEMPLATE);
	}

	public Path mapperDirectory() {
		return directory(MAPPER);
	}

	/**
	 * 写文件前确保目录存在；失败只说明落不了盘，内存路径照样可用，因此不抛。
	 *
	 * @return 目录可用（已存在或刚建好）
	 */
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
