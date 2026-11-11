package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 把 {@link MetaEntityTemplate} 产出的 Java 源码在运行期编译并加载。
 *
 * <p>只依赖 JDK 自带的 {@code javax.tools}（需要 JDK，JRE 上会明确报错）。源码落进临时目录，
 * class 由独立的 {@link URLClassLoader} 加载，父加载器沿用当前上下文——生成的类因此能直接
 * 继承 {@link MetaClass}、实现本模块的 {@link MetaEntityReader}，不需要任何反射式适配。</p>
 *
 * <p>源码可选择落到 {@code entity-source-directory}，产出的是一份能读、能改、能固化成
 * 静态实体的 .java；这是「完全动态」与「编译期完全静态」之间的实物边界。</p>
 */
public final class MetaEntityCompiler {

	private static final Logger LOG = Logger.getLogger(MetaEntityCompiler.class.getName());

	private MetaEntityCompiler() {
	}

	/** 编译并加载生成类；失败时异常里带完整源码与 javac 诊断。 */
	public static Class<? extends MetaClass> compile(MetaEntityTemplate.Source source, Path sourceDirectory) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new MetaSqlException("cannot generate entity class '" + source.qualifiedName()
					+ "': no system Java compiler. autumn-data-meta-jdbc needs a full JDK (not a JRE) at runtime");
		}
		try {
			Path work = Files.createTempDirectory("autumn-meta-entity-");
			Path classes = Files.createDirectories(work.resolve("classes"));
			Path sourceFile = work.resolve(source.packageName().replace('.', '/'))
					.resolve(source.className() + ".java");
			Files.createDirectories(sourceFile.getParent());
			Files.writeString(sourceFile, source.java(), StandardCharsets.UTF_8);
			writeSourceForHumans(source, sourceDirectory, sourceFile);

			DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
			boolean success;
			try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics,
					Locale.ROOT, StandardCharsets.UTF_8)) {
				fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath());
				fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
				Iterable<? extends JavaFileObject> units = fileManager
						.getJavaFileObjectsFromPaths(List.of(sourceFile));
				List<String> options = List.of("-encoding", "UTF-8", "-proc:none");
				success = Boolean.TRUE.equals(compiler.getTask(null, fileManager, diagnostics, options, null,
						units).call());
			}
			if (!success) {
				throw new MetaSqlException("failed to compile generated entity '" + source.qualifiedName()
						+ "':\n" + format(diagnostics) + "\n--- generated source ---\n" + source.java());
			}

			// loader 不关闭：它与生成类同生命周期，关闭后常量池里延迟解析的类会找不到
			URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() },
					MetaEntityCompiler.class.getClassLoader());
			Class<?> loaded = Class.forName(source.qualifiedName(), true, loader);
			if (!MetaClass.class.isAssignableFrom(loaded)) {
				throw new MetaSqlException("generated class '" + source.qualifiedName()
						+ "' is not a MetaClass subclass");
			}
			@SuppressWarnings("unchecked")
			Class<? extends MetaClass> entityType = (Class<? extends MetaClass>) loaded;
			return entityType;
		}
		catch (IOException ex) {
			throw new MetaSqlException("failed to write/compile generated entity '" + source.qualifiedName()
					+ "'", ex);
		}
		catch (ClassNotFoundException ex) {
			throw new MetaSqlException("generated entity '" + source.qualifiedName()
					+ "' was compiled but could not be loaded", ex);
		}
	}

	/** 把生成源码额外落一份到使用方指定目录，方便查看/固化；失败不影响内存编译。 */
	private static void writeSourceForHumans(MetaEntityTemplate.Source source, Path directory, Path fallback) {
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
			LOG.log(Level.WARNING, "failed to persist generated entity source to ''{0}''; in-memory source is at ''{1}''",
					new Object[] { directory, fallback, ex });
		}
	}

	// ------------------------------------------------------------------
	// classpath：Maven Surefire 的 manifest-only jar 也要能展开
	// ------------------------------------------------------------------

	/**
	 * javac 编译生成源码时需要的 classpath。
	 *
	 * <p>不能只信 {@code java.class.path}：Surefire 默认给的是一个 manifest-only booter jar，
	 * 真正的依赖写在它的 {@code Class-Path} 属性里。这里把 jar manifest 显式展开一层，
	 * 再补上本模块几个关键类的 CodeSource，保证 IDE / 单模块测试 / fat jar 都能编过。</p>
	 */
	private static List<Path> classpath() {
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
				try {
					addNormalized(paths, Paths.get(codeSource.getLocation().toURI()));
				}
				catch (URISyntaxException | IllegalArgumentException ex) {
					LOG.log(Level.FINE, "cannot resolve code source for " + type.getName(), ex);
				}
			}
		}
		return paths.stream().filter(path -> Files.exists(path)).toList();
	}

	private static void addNormalized(Set<Path> paths, Path path) {
		try {
			paths.add(path.toAbsolutePath().normalize());
		}
		catch (RuntimeException ex) {
			LOG.log(Level.FINE, "cannot normalize classpath entry " + path, ex);
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
					Path resolved = uri.isAbsolute() ? Paths.get(uri)
							: jar.getParent().resolve(uri.getPath()).normalize();
					addNormalized(paths, resolved);
				}
				catch (URISyntaxException | IllegalArgumentException ex) {
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
