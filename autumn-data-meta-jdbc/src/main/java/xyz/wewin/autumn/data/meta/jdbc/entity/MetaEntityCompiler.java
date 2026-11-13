package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 把 {@link MetaEntitySource} 产出的 Java 源码在运行期编译并加载。
 *
 * <p>只依赖 JDK 自带的 {@code javax.tools}（需要完整 JDK，JRE 上会明确报错）。<b>全程内存编译</b>：
 * 源码是内存 {@link JavaFileObject}，javac 输出的字节码收进 {@code byte[]}，再由内存
 * {@link ClassLoader} 的 {@code defineClass} 加载——<b>不建临时目录、不落 .class、不留磁盘垃圾</b>。
 * 这一点对「表是运行期设计的」场景很关键：有界 LRU 淘汰掉 {@code MetaEntityModel} 后，
 * 对应的 Class 与 ClassLoader 没有其它活引用就可以被 GC，元空间随之回收；否则每改一次表结构
 * 都会永久多一份目录 + 一个 ClassLoader，缓存有界而物理资源无界。</p>
 *
 * <p>每个生成类一个独立的内存 ClassLoader，父加载器沿用当前上下文——生成的类因此能直接
 * 继承 {@link MetaClass}、实现本模块的 {@link MetaEntityReader}，不需要任何反射式适配；
 * 同名类在不同 loader 下互不冲突，旧实例不会被重定义。</p>
 *
 * <p>源码可选择落到 {@code entity-source-directory}，产出一份能读、能改、能固化成静态实体的
 * .java；这是「完全动态」与「编译期完全静态」之间的实物边界，<b>不配置就完全不落盘</b>。</p>
 */
public final class MetaEntityCompiler {

	private static final Logger LOG = Logger.getLogger(MetaEntityCompiler.class.getName());

	private MetaEntityCompiler() {
	}

	/** 编译并加载生成类；失败时异常里带完整源码与 javac 诊断。 */
	public static Class<? extends MetaClass> compile(MetaEntitySource.Source source, Path sourceDirectory) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new MetaSqlException("cannot generate entity class '" + source.qualifiedName()
					+ "': no system Java compiler. autumn-data-meta-jdbc needs a full JDK (not a JRE) at runtime");
		}
		writeSourceForHumans(source, sourceDirectory);

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		Map<String, ClassFile> generated = new LinkedHashMap<>();
		try {
			StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics,
					Locale.ROOT, StandardCharsets.UTF_8);
			// MemoryFileManager.close() 会一并关闭被包装的 standard
			try (MemoryFileManager fileManager = new MemoryFileManager(standard, generated)) {
				standard.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath());
				List<String> options = List.of("-encoding", "UTF-8", "-proc:none");
				Iterable<? extends JavaFileObject> units = List.of(
						new SourceFile(source.qualifiedName(), source.java()));
				boolean success = Boolean.TRUE.equals(
						compiler.getTask(null, fileManager, diagnostics, options, null, units).call());
				if (!success) {
					throw new MetaSqlException("failed to compile generated entity '" + source.qualifiedName()
							+ "':\n" + format(diagnostics) + "\n--- generated source ---\n" + source.java());
				}
			}
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to compile generated entity '" + source.qualifiedName()
					+ "'", ex);
		}
		if (generated.isEmpty()) {
			throw new MetaSqlException("generated entity '" + source.qualifiedName()
					+ "' produced no bytecode");
		}

		Map<String, byte[]> bytecode = new LinkedHashMap<>();
		generated.forEach((name, file) -> bytecode.put(name, file.bytes()));
		MemoryClassLoader loader = new MemoryClassLoader(MetaEntityCompiler.class.getClassLoader(), bytecode);
		Class<?> loaded;
		try {
			loaded = Class.forName(source.qualifiedName(), true, loader);
		}
		catch (ClassNotFoundException ex) {
			throw new MetaSqlException("generated entity '" + source.qualifiedName()
					+ "' was compiled but could not be loaded", ex);
		}
		if (!MetaClass.class.isAssignableFrom(loaded)) {
			throw new MetaSqlException("generated class '" + source.qualifiedName()
					+ "' is not a MetaClass subclass");
		}
		@SuppressWarnings("unchecked")
		Class<? extends MetaClass> entityType = (Class<? extends MetaClass>) loaded;
		return entityType;
	}

	// ------------------------------------------------------------------
	// 内存 JavaFileObject / ClassLoader
	// ------------------------------------------------------------------

	/** 源码不落盘：javac 直接读 {@link #getCharContent(boolean)}。 */
	private static final class SourceFile extends SimpleJavaFileObject {

		private final String code;

		SourceFile(String qualifiedName, String code) {
			super(URI.create("string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension),
					Kind.SOURCE);
			this.code = code;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) {
			return this.code;
		}
	}

	/** 字节码不落盘：javac 写进 {@link ByteArrayOutputStream}。 */
	private static final class ClassFile extends SimpleJavaFileObject {

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		ClassFile(String className, Kind kind) {
			super(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind);
		}

		@Override
		public OutputStream openOutputStream() {
			return this.buffer;
		}

		byte[] bytes() {
			return this.buffer.toByteArray();
		}
	}

	/** 拦截 CLASS_OUTPUT：把每个输出类（含可能的内部类）都收进内存表。 */
	private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

		private final Map<String, ClassFile> generated;

		MemoryFileManager(StandardJavaFileManager delegate, Map<String, ClassFile> generated) {
			super(delegate);
			this.generated = generated;
		}

		@Override
		public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
				JavaFileObject.Kind kind, FileObject sibling) {
			ClassFile file = new ClassFile(className, kind);
			this.generated.put(className, file);
			return file;
		}
	}

	/** parent-first 找依赖（MetaClass 等），生成类本身按内存字节码 defineClass。 */
	private static final class MemoryClassLoader extends ClassLoader {

		private final Map<String, byte[]> bytecode;

		MemoryClassLoader(ClassLoader parent, Map<String, byte[]> bytecode) {
			super(parent);
			this.bytecode = Map.copyOf(bytecode);
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = this.bytecode.get(name);
			if (bytes == null) {
				return super.findClass(name);
			}
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	/** 把生成源码额外落一份到使用方指定目录，方便查看/固化；失败不影响内存编译。 */
	private static void writeSourceForHumans(MetaEntitySource.Source source, Path directory) {
		if (directory == null) {
			return;
		}
		try {
			Path target = directory.resolve(source.packageName().replace('.', '/'))
					.resolve(source.className() + ".java");
			Files.createDirectories(target.getParent());
			Files.writeString(target, source.java(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			LOG.log(Level.WARNING, "failed to persist generated entity source to ''{0}''",
					new Object[] { directory, ex });
		}
	}

	// ------------------------------------------------------------------
	// classpath：Maven Surefire 的 manifest-only jar 也要能展开
	// ------------------------------------------------------------------

	/**
	 * javac 编译生成源码时需要的 classpath。JVM 生命周期内不变，首次编译才懒加载计算一次，
	 * 避免每次生成实体都重扫 {@code java.class.path} 与所有 manifest jar。
	 */
	private static List<Path> classpath() {
		return ClasspathHolder.PATHS;
	}

	private static final class ClasspathHolder {

		static final List<Path> PATHS = compute();

		private static List<Path> compute() {
			Set<Path> paths = new LinkedHashSet<>();
			String property = System.getProperty("java.class.path", "");
			for (String part : property.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
				if (!part.isBlank()) {
					addNormalized(paths, Paths.get(part));
				}
			}
			List<Path> initial = new ArrayList<>(paths);
			for (Path path : initial) {
				if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
					expandManifest(path, paths);
				}
			}
			for (Class<?> type : List.of(MetaClass.class, MetaEntityReader.class, MetaEntityCompiler.class)) {
				CodeSource codeSource = type.getProtectionDomain().getCodeSource();
				if (codeSource != null && codeSource.getLocation() != null) {
					Path location = pathFromUri(codeSource.getLocation());
					if (location != null) {
						addNormalized(paths, location);
					}
					else {
						LOG.log(Level.FINE, "skipping non-file code source for {0}: {1}",
								new Object[] { type.getName(), codeSource.getLocation() });
					}
				}
			}
			return paths.stream().filter(path -> Files.exists(path)).toList();
		}
	}

	private static void addNormalized(Set<Path> paths, Path path) {
		try {
			paths.add(path.toAbsolutePath().normalize());
		}
		catch (RuntimeException ex) {
			LOG.log(Level.FINE, "cannot normalize classpath entry " + path, ex);
		}
	}

	/**
	 * 把 {@link CodeSource} 的 URL 转成 javac 可用的目录/文件路径。
	 *
	 * <p>Spring Boot 可执行 jar 里这个 URL 是 {@code jar:file:/app.jar!/BOOT-INF/classes!/}：
	 * {@code Paths.get(URI)} 会抛 {@code FileSystemNotFoundException}（不是
	 * {@code IllegalArgumentException}），若漏掉就会让 classpath 的静态初始化整体失败，
	 * 报出与真实原因无关的 {@code ExceptionInInitializerError}。这里只对 {@code file:} 做转换，
	 * 其它协议一律跳过，让 javac 自己给出「找不到依赖类」的可读诊断。</p>
	 */
	static Path pathFromUri(URL location) {
		if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
			return null;
		}
		try {
			return Paths.get(location.toURI()).toAbsolutePath().normalize();
		}
		catch (URISyntaxException | RuntimeException ex) {
			LOG.log(Level.FINE, "cannot resolve code source " + location, ex);
			return null;
		}
	}

	private static void expandManifest(Path jar, Set<Path> paths) {
		try (JarFile jarFile = new JarFile(jar.toFile())) {
			Manifest manifest = jarFile.getManifest();
			if (manifest == null) {
				return;
			}
			String declared = manifest.getMainAttributes().getValue("Class-Path");
			if (declared == null || declared.isBlank()) {
				return;
			}
			for (String entry : declared.trim().split("\\s+")) {
				try {
					URI uri = new URI(entry);
					if (uri.isAbsolute() && !"file".equalsIgnoreCase(uri.getScheme())) {
						continue;
					}
					Path resolved = uri.isAbsolute() ? Paths.get(uri)
							: jar.getParent().resolve(uri.getPath()).normalize();
					addNormalized(paths, resolved);
				}
				catch (URISyntaxException | RuntimeException ex) {
					LOG.log(Level.FINE, "bad Class-Path entry in " + jar + ": " + entry, ex);
				}
			}
		}
		catch (IOException ex) {
			LOG.log(Level.FINE, "cannot read manifest of " + jar, ex);
		}
	}

	private static String format(DiagnosticCollector<JavaFileObject> diagnostics) {
		StringBuilder text = new StringBuilder();
		for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
			if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
				continue;
			}
			text.append(diagnostic.getKind()).append(": line ").append(diagnostic.getLineNumber())
					.append(": ").append(diagnostic.getMessage(Locale.ROOT)).append('\n');
		}
		return text.isEmpty() ? "(javac reported no diagnostics)" : text.toString();
	}
}
