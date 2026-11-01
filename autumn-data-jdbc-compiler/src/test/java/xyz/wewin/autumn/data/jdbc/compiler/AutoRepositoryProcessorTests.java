package xyz.wewin.autumn.data.jdbc.compiler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * 处理器进程内自测：用系统 {@link JavaCompiler} 编译内存源码，验证约定校验、
 * {@code <名称>Impl} 生成，以及 SQL 以 {@code .properties} 落到编译输出目录。
 */
class AutoRepositoryProcessorTests {

	private static final String USER_ENTITY = """
			package demo;
			@jakarta.persistence.Entity
			@jakarta.persistence.Table(name = "demo_user")
			public class User {
				@jakarta.persistence.Id
				@jakarta.persistence.GeneratedValue
				private java.lang.Long id;
				private java.lang.String name;
				private int age;
				@jakarta.persistence.Column(name = "created_at")
				private java.time.LocalDateTime createdAt;
				public java.lang.Long getId() { return this.id; }
				public void setId(java.lang.Long id) { this.id = id; }
				public java.lang.String getName() { return this.name; }
				public void setName(java.lang.String name) { this.name = name; }
				public int getAge() { return this.age; }
				public void setAge(int age) { this.age = age; }
				public java.time.LocalDateTime getCreatedAt() { return this.createdAt; }
				public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
			}
			""";

	private static final String PLAIN_ENTITY = """
			package demo;
			@jakarta.persistence.Entity
			public class Account {
				@jakarta.persistence.Id
				private java.lang.String accountId;
				private java.math.BigDecimal balance;
				public java.lang.String getAccountId() { return this.accountId; }
				public void setAccountId(java.lang.String accountId) { this.accountId = accountId; }
				public java.math.BigDecimal getBalance() { return this.balance; }
				public void setBalance(java.math.BigDecimal balance) { this.balance = balance; }
			}
			""";

	private static final String VALID_REPO = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface UserRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<User, java.lang.Long> {}
			""";

	private static final String ASSIGNED_ID_REPO = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface AccountRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<Account, java.lang.String> {}
			""";

	private static final String NO_ID_ENTITY_REPO = """
			package demo;
			@jakarta.persistence.Entity
			class NoIdEntity {
				private java.lang.String name;
				public java.lang.String getName() { return this.name; }
				public void setName(java.lang.String name) { this.name = name; }
			}
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			interface NoIdRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<NoIdEntity, java.lang.String> {}
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
	void generatesServiceImplementationAndSqlResource(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/User.java", USER_ENTITY),
				new Source("demo/UserRepository.java", VALID_REPO)));
		assertThat(result.errorMessages()).isEmpty();

		Path generated = dir.resolve("gen/demo/UserRepositoryImpl.java");
		assertThat(generated).exists();
		String source = Files.readString(generated);
		assertThat(source).contains("class UserRepositoryImpl implements UserRepository");
		assertThat(source).contains("@org.springframework.stereotype.Service");
		assertThat(source).contains("public UserRepositoryImpl(org.springframework.jdbc.core.simple.JdbcClient jdbcClient)");
		assertThat(source).contains("row.setAge(rs.getInt(\"age\"))");
		assertThat(source).contains("row.setCreatedAt(rs.getObject(\"created_at\", java.time.LocalDateTime.class))");

		Path properties = dir.resolve("classes/META-INF/autumn-data/sql/demo/UserRepository.properties");
		assertThat(properties).exists();
		assertThat(Files.readString(properties)).contains("insert=INSERT INTO demo_user (name, age, created_at) VALUES (?, ?, ?)")
			.contains("selectAll=SELECT id, name, age, created_at FROM demo_user")
			.contains("update=UPDATE demo_user SET name = ?, age = ?, created_at = ? WHERE id = ?")
			.contains("selectByIds=SELECT id, name, age, created_at FROM demo_user WHERE id IN (:ids)");
	}

	@Test
	void fallsBackToNamingConventionAndAssignedId(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/Account.java", PLAIN_ENTITY),
				new Source("demo/AccountRepository.java", ASSIGNED_ID_REPO)));
		assertThat(result.errorMessages()).isEmpty();
		String properties = Files
			.readString(dir.resolve("classes/META-INF/autumn-data/sql/demo/AccountRepository.properties"));
		// 无 @Table/@Column：表名取类名、列名取驼峰转下划线；无 @GeneratedValue：主键参与 INSERT
		assertThat(properties).contains("selectAll=SELECT account_id, balance FROM account")
			.contains("insert=INSERT INTO account (account_id, balance) VALUES (?, ?)");
	}

	@Test
	void rejectsClassAnnotation(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/BadRepository.java", INTERFACE_ON_CLASS)));
		assertThat(result.errorMessages()).anySatisfy(message -> assertThat(message)
			.contains("can only be applied to interfaces"));
	}

	@Test
	void rejectsNonRepositoryInterface(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/NotRepo.java", NOT_A_REPOSITORY)));
		assertThat(result.errorMessages()).anySatisfy(message -> assertThat(message)
			.contains("must extend Repository<T, ID>"));
	}

	@Test
	void rejectsEntityWithoutId(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/NoId.java", NO_ID_ENTITY_REPO)));
		assertThat(result.errorMessages()).anySatisfy(message -> assertThat(message).contains("has no @Id field"));
	}

	private Compilation compile(Path dir, List<Source> sources) throws Exception {
		Path classes = dir.resolve("classes");
		Path gen = dir.resolve("gen");
		Files.createDirectories(classes);
		Files.createDirectories(gen);
		List<File> sourceFiles = new ArrayList<>();
		for (Source source : sources) {
			Path sourceFile = dir.resolve("src").resolve(source.path());
			Files.createDirectories(sourceFile.getParent());
			Files.writeString(sourceFile, source.text());
			sourceFiles.add(sourceFile.toFile());
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fileManager = this.compiler.getStandardFileManager(diagnostics, null, null)) {
			fileManager.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
			fileManager.setLocation(javax.tools.StandardLocation.SOURCE_OUTPUT, List.of(gen.toFile()));
			Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
			this.compiler.getTask(null, fileManager, diagnostics, List.of("-proc:full"), null, units).call();
			return new Compilation(diagnostics.getDiagnostics(), gen);
		}
	}

	private record Source(String path, String text) {
	}

	private record Compilation(List<Diagnostic<? extends JavaFileObject>> diagnostics, Path gen) {

		List<String> errorMessages() {
			return this.diagnostics.stream()
				.filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
				.map(diagnostic -> diagnostic.getMessage(null))
				.toList();
		}
	}
}
