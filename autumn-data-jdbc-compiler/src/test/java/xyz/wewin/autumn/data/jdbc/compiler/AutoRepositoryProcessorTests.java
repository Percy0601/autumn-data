package xyz.wewin.autumn.data.jdbc.compiler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 处理器进程内自测：用系统 {@link JavaCompiler} 编译内存源码，
 * 验证约定校验与 {@code <名称>Impl} 骨架生成。
 */
class AutoRepositoryProcessorTests {

	private static final String VALID_REPO = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface UserRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<String, java.lang.Long> {}
			""";

	private static final String INTERFACE_ON_CLASS = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public class BadRepository {}
			""";

	private static final String NOT_A_REPOSITORY = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface NotRepo {}
			""";

	private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

	@Test
	void generatesImplSkeleton(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, "demo/UserRepository.java", VALID_REPO);
		assertThat(result.diagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList())
			.isEmpty();
		Path generated = dir.resolve("gen/demo/UserRepositoryImpl.java");
		assertThat(generated).exists();
		String source = Files.readString(generated);
		assertThat(source).contains("class UserRepositoryImpl implements UserRepository");
		assertThat(source).contains("java.util.Optional<java.lang.String> findById(java.lang.Long id)");
		assertThat(source).contains("public <S extends java.lang.String> S save(S entity)");
	}

	@Test
	void rejectsClassAnnotation(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, "demo/BadRepository.java", INTERFACE_ON_CLASS);
		assertThat(result.diagnosticMessages()).anySatisfy(message -> assertThat(message)
			.contains("can only be applied to interfaces"));
	}

	@Test
	void rejectsNonRepositoryInterface(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, "demo/NotRepo.java", NOT_A_REPOSITORY);
		assertThat(result.diagnosticMessages()).anySatisfy(message -> assertThat(message)
			.contains("must extend Repository<T, ID>"));
	}

	private Compilation compile(Path dir, String fileName, String source) throws Exception {
		Path sourceFile = dir.resolve("src").resolve(fileName);
		Files.createDirectories(sourceFile.getParent());
		Files.writeString(sourceFile, source);
		Path classes = dir.resolve("classes");
		Path gen = dir.resolve("gen");
		Files.createDirectories(classes);
		Files.createDirectories(gen);
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fileManager = this.compiler.getStandardFileManager(diagnostics, null, null)) {
			fileManager.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
			fileManager.setLocation(javax.tools.StandardLocation.SOURCE_OUTPUT, List.of(gen.toFile()));
			Iterable<? extends JavaFileObject> units = fileManager
				.getJavaFileObjectsFromFiles(List.of(sourceFile.toFile()));
			List<String> options = List.of("-proc:full");
			boolean success = this.compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
			return new Compilation(success, diagnostics.getDiagnostics(), gen);
		}
	}

	private record Compilation(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path gen) {

		List<String> diagnosticMessages() {
			return this.diagnostics.stream().map(d -> d.getMessage(null)).toList();
		}
	}
}
