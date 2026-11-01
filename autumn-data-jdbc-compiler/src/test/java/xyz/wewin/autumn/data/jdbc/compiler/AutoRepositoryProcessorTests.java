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
 * {@code <名称>Impl} 生成，以及标准 CRUD SQL 在编译期内联为生成类里的
 * {@code private static final String} 常量（运行期零资源 I/O）。
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

	private static final String PAGING_REPO = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface PagedUserRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<User, java.lang.Long>,
					xyz.wewin.autumn.data.jdbc.repository.PagingRepository<User, java.lang.Long> {}
			""";

	private static final String ORDER_ENTITY = """
			package demo;
			@jakarta.persistence.Entity
			@jakarta.persistence.Table(name = "demo_order")
			public class Order {
				@jakarta.persistence.Id
				@jakarta.persistence.GeneratedValue
				private java.lang.Long id;
				@jakarta.persistence.Column(name = "user_id")
				private java.lang.Long userId;
				private java.lang.String memo;
				@jakarta.persistence.ManyToOne
				@jakarta.persistence.JoinColumn(name = "owner_id")
				private Customer owner;
				public java.lang.Long getId() { return this.id; }
				public void setId(java.lang.Long id) { this.id = id; }
				public java.lang.Long getUserId() { return this.userId; }
				public void setUserId(java.lang.Long userId) { this.userId = userId; }
				public java.lang.String getMemo() { return this.memo; }
				public void setMemo(java.lang.String memo) { this.memo = memo; }
				public Customer getOwner() { return this.owner; }
				public void setOwner(Customer owner) { this.owner = owner; }
			}
			""";

	private static final String ASSOCIATION_ENTITY = """
			package demo;
			@jakarta.persistence.Entity
			@jakarta.persistence.Table(name = "demo_user")
			public class Customer {
				@jakarta.persistence.Id
				@jakarta.persistence.GeneratedValue
				private java.lang.Long id;
				private java.lang.String name;
				@jakarta.persistence.OneToMany
				@jakarta.persistence.JoinColumn(name = "user_id")
				private java.util.List<Order> orders = new java.util.ArrayList<>();
				@jakarta.persistence.OneToMany(fetch = jakarta.persistence.FetchType.LAZY)
				@jakarta.persistence.JoinColumn(name = "audit_id")
				private java.util.List<Order> lazyOrders = new java.util.ArrayList<>();
				@jakarta.persistence.OneToMany(mappedBy = "owner")
				private java.util.Set<Order> byOwner = new java.util.LinkedHashSet<>();
				public java.lang.Long getId() { return this.id; }
				public void setId(java.lang.Long id) { this.id = id; }
				public java.lang.String getName() { return this.name; }
				public void setName(java.lang.String name) { this.name = name; }
				public java.util.List<Order> getOrders() { return this.orders; }
				public void setOrders(java.util.List<Order> orders) { this.orders = orders; }
				public java.util.List<Order> getLazyOrders() { return this.lazyOrders; }
				public void setLazyOrders(java.util.List<Order> lazyOrders) { this.lazyOrders = lazyOrders; }
				public java.util.Set<Order> getByOwner() { return this.byOwner; }
				public void setByOwner(java.util.Set<Order> byOwner) { this.byOwner = byOwner; }
			}
			""";

	private static final String ASSOCIATION_REPO = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface CustomerRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<Customer, java.lang.Long> {}
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
	void generatesServiceImplementationWithInlinedSql(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/User.java", USER_ENTITY),
				new Source("demo/UserRepository.java", VALID_REPO)));
		assertThat(result.errorMessages()).isEmpty();

		Path generated = dir.resolve("gen/demo/UserRepositoryImpl.java");
		assertThat(generated).exists();
		String source = Files.readString(generated);
		assertThat(source).contains("class UserRepositoryImpl implements UserRepository");
		// 生成类不标 @Service：starter 用 RepositoryRegistrar 在读 META-INF/autumn-data/repositories 后注册为 Bean
		assertThat(source).doesNotContain("@org.springframework.stereotype.Service");
		assertThat(source).contains(
				"public UserRepositoryImpl(xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc autumn)");
		assertThat(source).contains("row.setAge(rs.getInt(\"age\"))");
		assertThat(source).contains("row.setCreatedAt(rs.getObject(\"created_at\", java.time.LocalDateTime.class))");
		// 批量写：新行一条带生成键的批量 INSERT，已有行一条批量 UPDATE（引用编译期内联的 SQL 常量）
		assertThat(source).contains("this.autumn.batchInsertReturningKeys(SQL_INSERT, \"id\", rows)");
		assertThat(source).contains("this.autumn.batchUpdate(SQL_UPDATE, rows)");
		// 批量删：一条 IN，不再逐行 DELETE（IN 占位符在运行期由 Sql.inClause 展开）
		assertThat(source).contains(
				"this.autumn.update(xyz.wewin.autumn.data.jdbc.sql.Sql.inClause(SQL_DELETE_BY_IDS, ids.size()), ids.toArray())");
		// SQL 在编译期内联为生成类常量，不再落盘 .properties
		assertThat(source).contains(
				"private static final String SQL_INSERT = \"INSERT INTO demo_user (name, age, created_at) VALUES (?, ?, ?)\"");
		assertThat(source).contains(
				"private static final String SQL_SELECT_ALL = \"SELECT id, name, age, created_at FROM demo_user\"");
		assertThat(source).contains("private static final String SQL_PAGE_ALL"
				+ " = \"SELECT id, name, age, created_at FROM demo_user ORDER BY id\"");
		assertThat(source).contains("private static final String SQL_UPDATE"
				+ " = \"UPDATE demo_user SET name = ?, age = ?, created_at = ? WHERE id = ?\"");
		assertThat(source).contains("private static final String SQL_SELECT_BY_IDS"
				+ " = \"SELECT id, name, age, created_at FROM demo_user WHERE id IN (:ids)\"");
	}

	@Test
	void generatesPagingImplementation(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/User.java", USER_ENTITY),
				new Source("demo/PagedUserRepository.java", PAGING_REPO)));
		assertThat(result.errorMessages()).isEmpty();
		String source = Files.readString(dir.resolve("gen/demo/PagedUserRepositoryImpl.java"));
		assertThat(source).contains(
				"public xyz.wewin.autumn.data.jdbc.page.Page<demo.User> findAll(xyz.wewin.autumn.data.jdbc.page.Pageable pageable)");
		// 分页片段不在编译期写死，交给运行期方言拼；两条语句都引用编译期内联常量
		assertThat(source).contains("this.autumn.page(SQL_PAGE_ALL, SQL_COUNT, ROW_MAPPER, pageable)");
	}

	@Test
	void generatesTwoStatementAssociationLoadingWithCap(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/Order.java", ORDER_ENTITY),
				new Source("demo/Customer.java", ASSOCIATION_ENTITY),
				new Source("demo/CustomerRepository.java", ASSOCIATION_REPO)));
		assertThat(result.errorMessages()).isEmpty();

		String source = Files.readString(dir.resolve("gen/demo/CustomerRepositoryImpl.java"));
		// 子表自己把外键映射成列时不重复选；mappedBy 从子侧 @JoinColumn 解析出 owner_id
		// SQL 已内联为生成类常量（注意常量名是 SQL_<KEY 大写>）
		assertThat(source).contains(
				"private static final String SQL_ORDERS_BY_PARENT_IDS = \"SELECT id, user_id, memo FROM demo_order WHERE user_id IN (:ids) ORDER BY id\"");
		assertThat(source).contains("private static final String SQL_BY_OWNER_BY_PARENT_IDS"
				+ " = \"SELECT id, user_id, memo, owner_id FROM demo_order WHERE owner_id IN (:ids) ORDER BY id\"");
		// fetch = LAZY 不生成装配语句
		assertThat(source).doesNotContain("SQL_LAZY_ORDERS_BY_PARENT_IDS");

		// 第二条语句按父主键 IN 批量取子行，且走 queryLimited（受 associationLimit 截断）
		assertThat(source).contains("private void attachOrders(java.util.List<demo.Customer> parents)");
		assertThat(source).contains(
				"this.autumn.queryLimited(xyz.wewin.autumn.data.jdbc.sql.Sql.inClause(SQL_ORDERS_BY_PARENT_IDS, parentIds.size())");
		assertThat(source).contains("grouped.computeIfAbsent(entry.getKey(), key -> new java.util.ArrayList<>())");
		assertThat(source).contains("attachOrders(rows);");
		// Set 形态的关联从分组结果转成 LinkedHashSet
		assertThat(source).contains("new java.util.LinkedHashSet<demo.Order>(loaded)");
		assertThat(source).doesNotContain("attachLazyOrders");
	}

	@Test
	void fallsBackToNamingConventionAndAssignedId(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/Account.java", PLAIN_ENTITY),
				new Source("demo/AccountRepository.java", ASSIGNED_ID_REPO)));
		assertThat(result.errorMessages()).isEmpty();
		// 无 @Table/@Column：表名取类名、列名取驼峰转下划线；无 @GeneratedValue：主键参与 INSERT
		// SQL 内联为生成类常量
		String source = Files.readString(dir.resolve("gen/demo/AccountRepositoryImpl.java"));
		assertThat(source).contains(
				"private static final String SQL_SELECT_ALL = \"SELECT account_id, balance FROM account\"");
		assertThat(source).contains(
				"private static final String SQL_INSERT = \"INSERT INTO account (account_id, balance) VALUES (?, ?)\"");
		// 主键非自增：拿不到生成键，批量写退化为逐条 upsert
		assertThat(source).doesNotContain("batchInsertReturningKeys");
		assertThat(source).contains("saved.add(save(entity))");
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
