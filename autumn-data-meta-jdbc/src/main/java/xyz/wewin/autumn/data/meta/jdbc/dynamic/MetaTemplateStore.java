package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 实体模板（{@code .ftl}）的仓库：模板文件在使用方目录里，不在 jar 里。
 *
 * <p>规则和其他三类产物一致：</p>
 * <ol>
 *   <li>{@code <root>/template/<名字>} 存在 —— 直接用它，内容一个字都不动；</li>
 *   <li>不存在 —— 把 classpath 内置的默认模板<b>一次性</b>落到那里（{@code CREATE_NEW}，
 *       并发下先到先得，后到的用先到那份），然后同样从磁盘读；</li>
 *   <li>纯内存模式（没有目录）—— 直接用 classpath 内置那份。</li>
 * </ol>
 *
 * <p>于是「可视化产品里想改实体长什么样」变成改一个文本文件：下一次访问生效，不重启、不重新打包。
 * 缓存键带 {@code lastModified + size}，文件没动就复用同一个 {@link Template}
 * （FreeMarker 的 Template 不可变、线程安全），动过则换键重解析；旧键条目交给有界 LRU 淘汰。</p>
 */
public final class MetaTemplateStore {

	/** jar 内置默认模板的位置，也是外置模板缺失时的补齐来源。 */
	private static final String BUILTIN_BASE = "xyz/wewin/autumn/data/meta/jdbc/dynamic";

	/** 生成实体类的默认模板名。 */
	public static final String ENTITY_TEMPLATE = "meta-class.ftl";

	private static final Logger LOG = Logger.getLogger(MetaTemplateStore.class.getName());

	private static final Configuration FREEMARKER = configuration();

	private final Path directory;

	private final boolean materialize;

	private final BoundedCache<String, Template> templates;

	/**
	 * @param templateDirectory 外置模板目录；{@code null} 表示只用 classpath 内置模板
	 * @param materialize       目录里的模板缺失时是否落一份内置默认
	 * @param maximumSize       模板条目上限（按「文件 + 修改时间」计数，正常只有个位数）
	 */
	public MetaTemplateStore(Path templateDirectory, boolean materialize, int maximumSize) {
		this.directory = templateDirectory;
		this.materialize = materialize;
		this.templates = BoundedCaches.lru(maximumSize);
	}

	/** classpath-only：测试与内置默认行为。 */
	public static MetaTemplateStore builtin() {
		return new MetaTemplateStore(null, false, 16);
	}

	/** 取模板；解析失败与读盘失败都归入 {@link MetaSqlException}。 */
	public Template template(String name) {
		Path file = file(name);
		if (file == null) {
			return builtin(name);
		}
		if (!Files.isRegularFile(file)) {
			materialize(file, name);
		}
		if (!Files.isRegularFile(file)) {
			// 目录只读或关掉了补齐：内置模板继续可用，只是使用方改不到它
			return builtin(name);
		}
		String stamp = stamp(file);
		return this.templates.get(stamp, ignored -> parse(readFile(file, name), name));
	}

	private Template builtin(String name) {
		return this.templates.get("classpath:" + name, ignored -> parse(readBuiltin(name), name));
	}

	/** 丢掉解析结果：下次访问重新按磁盘现状解析（文件本身不会被重写）。 */
	public void refresh() {
		this.templates.invalidateAll();
	}

	/** 已解析的模板条目数（诊断用）。 */
	public long size() {
		return this.templates.estimatedSize();
	}

	/** 模板当前来源，用于诊断与「模板确实外置了」的断言。 */
	public Path file(String name) {
		return this.directory == null ? null : this.directory.resolve(MetaDirectories.safeFileName(name));
	}

	private static Configuration configuration() {
		Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
		cfg.setClassLoaderForTemplateLoading(MetaTemplateStore.class.getClassLoader(), BUILTIN_BASE);
		cfg.setDefaultEncoding(StandardCharsets.UTF_8.name());
		cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
		cfg.setLogTemplateExceptions(false);
		cfg.setWrapUncheckedExceptions(true);
		return cfg;
	}

	private static Template parse(String content, String name) {
		try {
			return new Template(name, new StringReader(content), FREEMARKER);
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to parse entity template '" + name + "'", ex);
		}
	}

	/** 文件不存在时补一份内置默认；已存在（并发或只读介质）就用现状。 */
	private void materialize(Path file, String name) {
		if (!this.materialize) {
			return;
		}
		String content = readBuiltin(name);
		if (!MetaDirectories.ensure(file.getParent())) {
			return;
		}
		try {
			Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE);
		}
		catch (FileAlreadyExistsException won) {
			LOG.log(Level.FINE, "template ''{0}'' was materialized by a concurrent caller", file);
		}
		catch (IOException ex) {
			LOG.log(Level.WARNING, "failed to materialize template ''{0}''; using built-in content",
					new Object[] { file, ex });
		}
	}

	private static String stamp(Path file) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
			return file.getFileName() + ":" + attrs.lastModifiedTime().toMillis() + ":" + attrs.size();
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to stat entity template '" + file + "'", ex);
		}
	}

	private static String readFile(Path file, String name) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to read entity template '" + file + "'", ex);
		}
	}

	private static String readBuiltin(String name) {
		String resource = BUILTIN_BASE + '/' + MetaDirectories.safeFileName(name);
		try (InputStream input = MetaTemplateStore.class.getClassLoader().getResourceAsStream(resource)) {
			if (input == null) {
				throw new MetaSqlException("built-in entity template not found on classpath: " + resource);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to read built-in entity template '" + resource + "'", ex);
		}
	}
}
