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

	/** 编译期方言（标识符引号）用的最小仓储：一条 CRUD + 一条派生查询即可观察引号效果。 */
	private static final String QUOTED_REPO = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface QuotedUserRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<User, java.lang.Long> {
				java.util.List<demo.User> findByName(java.lang.String name);
			}
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

	private static final String ADDRESS_ENTITY = """
			package demo;
			@jakarta.persistence.Entity
			@jakarta.persistence.Table(name = "address")
			public class Address {
				@jakarta.persistence.Id
				@jakarta.persistence.GeneratedValue
				private java.lang.Long id;
				private java.lang.String city;
				public java.lang.Long getId() { return this.id; }
				public void setId(java.lang.Long id) { this.id = id; }
				public java.lang.String getCity() { return this.city; }
				public void setCity(java.lang.String city) { this.city = city; }
			}
			""";

	/**
	 * 多对一：父表用 {@code address_id} 指向子表主键，该列同时以标量字段 {@code addressId} 暴露，
	 * 第二条语句靠它读外键（与一对多「子表暴露外键列」对称）。
	 */
	private static final String MANY_TO_ONE_ENTITY = """
			package demo;
			@jakarta.persistence.Entity
			@jakarta.persistence.Table(name = "basic_user")
			public class User {
				@jakarta.persistence.Id
				@jakarta.persistence.GeneratedValue
				private java.lang.Long id;
				private java.lang.String username;
				@jakarta.persistence.Column(name = "address_id")
				private java.lang.Long addressId;
				@jakarta.persistence.ManyToOne
				@jakarta.persistence.JoinColumn(name = "address_id")
				private Address address;
				public java.lang.Long getId() { return this.id; }
				public void setId(java.lang.Long id) { this.id = id; }
				public java.lang.String getUsername() { return this.username; }
				public void setUsername(java.lang.String username) { this.username = username; }
				public java.lang.Long getAddressId() { return this.addressId; }
				public void setAddressId(java.lang.Long addressId) { this.addressId = addressId; }
				public Address getAddress() { return this.address; }
				public void setAddress(Address address) { this.address = address; }
			}
			""";

	private static final String MANY_TO_ONE_REPO = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface UserRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<User, java.lang.Long> {
				java.util.List<demo.User> findByAddressCity(java.lang.String city);
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

	private static final String DERIVED_REPO = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface DerivedUserRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<User, java.lang.Long> {
				java.util.List<User> findByName(java.lang.String name);
				java.util.List<User> findByName(java.lang.Integer name);
				java.util.Optional<User> getByName(java.lang.String name);
				long countByName(java.lang.String name);
				boolean existsByAgeGreaterThan(int age);
				java.util.List<User> findByAgeIn(java.util.List<java.lang.Long> ages);
				java.util.List<User> findByNameAndAgeLessThanOrderByNameAsc(java.lang.String name, java.lang.Integer age);
			java.util.List<User> findByNameContaining(java.lang.String name);
			java.util.List<User> findByAgeIsNot(int age);
			java.util.List<User> findByNameIgnoreCase(java.lang.String name);
			java.util.List<User> findDistinctByName(java.lang.String name);
			java.util.List<User> findTop3ByName(java.lang.String name);
			java.util.List<User> findFirstByName(java.lang.String name);
			long deleteByName(java.lang.String name);
			java.util.List<User> deleteByNameAndAge(java.lang.String name, int age);
			@xyz.wewin.autumn.data.jdbc.annotation.Query("SELECT id, name, age, created_at FROM demo_user WHERE age > ?")
			java.util.List<User> findAdults(int age);
			@xyz.wewin.autumn.data.jdbc.annotation.Query("DELETE FROM demo_user WHERE name = ?")
			void purgeByName(java.lang.String name);
		}
		""";

	private static final String ASSOCIATION_QUERY_REPO = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface AssociationQueryRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<Customer, java.lang.Long> {
				java.util.List<Customer> findByOrdersMemo(java.lang.String memo);
				java.util.List<Customer> findByByOwnerMemoLike(java.lang.String pattern);
			}
			""";

	private static final String UNSUPPORTED_QUERY_REPO = """
			package demo;
			@xyz.wewin.autumn.data.jdbc.annotation.AutumnRepository
			public interface BadQueryRepository extends xyz.wewin.autumn.data.jdbc.repository.CrudRepository<User, java.lang.Long> {

				java.util.List<User> findByCity(java.lang.String city);
			}
			""";

	/** 生成类里的 SQL 助手全名（运行期展开 {@code IN} 占位符、替换投影列清单）。 */
	private static final String SQL = "xyz.wewin.autumn.data.jdbc.sql.Sql";

	/** 每个取实体行的方法开头都会读一次性字段投影。 */
	private static final String READ_PROJECTION = "\t\t" + "xyz.wewin.autumn.data.jdbc.runtime.FieldProjection"
			+ " __projection = xyz.wewin.autumn.data.jdbc.runtime.AutumnDataContextHolder.projection();\n";

	/** 取完就消费上下文：级联模式与字段投影只作用于最近一次查询。 */
	private static final String CONSUME_CONTEXT = "\t\txyz.wewin.autumn.data.jdbc.runtime.AutumnDataContextHolder"
			+ ".consumePending();\n";

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
	void generatesManyToOneLoadingWithSecondStatement(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/Address.java", ADDRESS_ENTITY),
				new Source("demo/User.java", MANY_TO_ONE_ENTITY), new Source("demo/UserRepository.java",
						MANY_TO_ONE_REPO)));
		assertThat(result.errorMessages()).isEmpty();

		String source = Files.readString(dir.resolve("gen/demo/UserRepositoryImpl.java"));
		// 多对一的第二条语句：用父表外键 IN 子表主键，一次取回全部相关子实体（不是 JOIN）
		assertThat(source).contains("private static final String SQL_ADDRESS_BY_IDS"
				+ " = \"SELECT id, city FROM address WHERE id IN (:ids) ORDER BY id\"");
		// 按父表外键挂回单值字段
		assertThat(source).contains("private void attachAddress(java.util.List<demo.User> parents)");
		assertThat(source).contains(
				"this.autumn.query(xyz.wewin.autumn.data.jdbc.sql.Sql.inClause(SQL_ADDRESS_BY_IDS, keys.size())");
		assertThat(source).contains("byKey.put(child.getId(), child);");
		assertThat(source).contains("parent.setAddress(byKey.get(parent.getAddressId()));");
		// findById 与列表查询之后都装配多对一
		assertThat(source).contains("attachAddress(java.util.List.of(row));");
		assertThat(source).contains("attachAddress(rows);");
		// 多对一关联条件：连接方向是 child.id = parent.fk（外键在父表）
		assertThat(source).contains("private static final String SQL_FIND_BY_ADDRESS_CITY"
				+ " = \"SELECT id, username, address_id FROM basic_user WHERE EXISTS (SELECT 1 FROM address"
				+ " WHERE address.id = basic_user.address_id AND address.city = ?)\"");
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
	void generatesDerivedQueryMethods(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/User.java", USER_ENTITY),
				new Source("demo/DerivedUserRepository.java", DERIVED_REPO)));
		assertThat(result.errorMessages()).isEmpty();

		String source = Files.readString(dir.resolve("gen/demo/DerivedUserRepositoryImpl.java"));
		// SQL 与 CRUD 语句一样在编译期内联；同名重载另起一个常量名
		assertThat(source).contains("private static final String SQL_FIND_BY_NAME = \"SELECT id, name, age,"
				+ " created_at FROM demo_user WHERE name = ?\"");
		assertThat(source).contains("private static final String SQL_FIND_BY_NAME_2");
		assertThat(source).contains(
				"private static final String SQL_FIND_BY_NAME_AND_AGE_LESS_THAN_ORDER_BY_NAME_ASC"
						+ " = \"SELECT id, name, age, created_at FROM demo_user WHERE name = ? AND age < ?"
						+ " ORDER BY name ASC\"");
		// 取实体行的语句额外内联一条投影模板：列清单换成 {cols}，运行期设了投影才替换
		assertThat(source).contains("private static final String SQL_FIND_BY_NAME_PROJECTION"
				+ " = \"SELECT {cols} FROM demo_user WHERE name = ?\"");
		assertThat(source).contains("private static final String SQL_SELECT_ALL_PROJECTION"
				+ " = \"SELECT {cols} FROM demo_user\"");
		assertThat(source).contains("private static final String SQL_FIND_DISTINCT_BY_NAME_PROJECTION"
				+ " = \"SELECT DISTINCT {cols} FROM demo_user WHERE name = ?\"");
		// 投影三元组：属性名 / 进 SELECT 的列名 / 该列是否不可省略（主键恒取，否则回填与级联都断了）
		assertThat(source).contains("private static final String[] __PROJ_FIELDS = { \"id\", \"name\", \"age\","
				+ " \"createdAt\" };");
		assertThat(source).contains("private static final String[] __PROJ_COLUMNS = { \"id\", \"name\", \"age\","
				+ " \"created_at\" };");
		assertThat(source).contains("private static final boolean[] __PROJ_REQUIRED = { true, false, false, false };");
		// 结果形态决定调用：List 走 query、Optional 走 queryOptional、count/exists 走 count（没有 mapper）
		// 取实体行的方法先读一次性投影，默认分支仍是全列常量 + ROW_MAPPER
		assertThat(source).contains("public java.util.List<demo.User> findByName(java.lang.Integer name) {\n"
				+ READ_PROJECTION + "\t\tjava.util.List<demo.User> rows = (__projection == null) ? this.autumn.query(");
		assertThat(source).contains("public java.util.Optional<demo.User> getByName(java.lang.String name) {\n"
				+ READ_PROJECTION
				+ "\t\tjava.util.Optional<demo.User> found = (__projection == null)"
				+ " ? this.autumn.queryOptional(SQL_GET_BY_NAME, ROW_MAPPER, name) : this.autumn.queryOptional("
				+ SQL + ".withColumns(SQL_GET_BY_NAME_PROJECTION, __projectionColumns(__projection)),"
				+ " __projectedMapper(__projection), name);\n" + CONSUME_CONTEXT + "\t\treturn found;");
		assertThat(source).contains("public long countByName(java.lang.String name) {\n"
				+ "\t\treturn this.autumn.count(SQL_COUNT_BY_NAME, name);");
		assertThat(source).contains("public boolean existsByAgeGreaterThan(int age) {\n"
				+ "\t\treturn this.autumn.count(SQL_EXISTS_BY_AGE_GREATER_THAN, age) > 0;");
		// 集合条件：运行期按集合大小展开占位符，所以标量和集合混排时实参先收进一个数组；投影模板同样要展开 IN
		assertThat(source).contains("private static final String SQL_FIND_BY_AGE_IN"
				+ " = \"SELECT id, name, age, created_at FROM demo_user WHERE age IN (:ids)\"");
		assertThat(source).contains("public java.util.List<demo.User> findByAgeIn(java.util.List<java.lang.Long> ages) {\n"
				+ "\t\tjava.util.List<Object> values = new java.util.ArrayList<>();\n" + "\t\tvalues.addAll(ages);\n"
				+ READ_PROJECTION + "\t\tjava.util.List<demo.User> rows = (__projection == null) ? this.autumn.query("
				+ SQL + ".inClause(SQL_FIND_BY_AGE_IN, ages.size()), ROW_MAPPER, values.toArray())");
		assertThat(source).contains(SQL + ".inClause(" + SQL + ".withColumns(SQL_FIND_BY_AGE_IN_PROJECTION,"
				+ " __projectionColumns(__projection)), ages.size())");
		// Like 家族：SQL 仍是占位符，取数时由生成代码把实参包成 %v% / v% / %v
		assertThat(source).contains("private static final String SQL_FIND_BY_NAME_CONTAINING"
				+ " = \"SELECT id, name, age, created_at FROM demo_user WHERE name LIKE ?\"");
		assertThat(source).contains("public java.util.List<demo.User> findByNameContaining(java.lang.String name) {\n"
				+ READ_PROJECTION + "\t\tjava.util.List<demo.User> rows = (__projection == null) ? this.autumn.query("
				+ "SQL_FIND_BY_NAME_CONTAINING, ROW_MAPPER, \"%\" + name + \"%\")");
		// 否定关键词与 IgnoreCase
		assertThat(source).contains("private static final String SQL_FIND_BY_AGE_IS_NOT"
				+ " = \"SELECT id, name, age, created_at FROM demo_user WHERE age <> ?\"");
		assertThat(source).contains("private static final String SQL_FIND_BY_NAME_IGNORE_CASE"
				+ " = \"SELECT id, name, age, created_at FROM demo_user WHERE LOWER(name) = LOWER(?)\"");
		assertThat(source).contains("public java.util.List<demo.User> findByNameIgnoreCase(java.lang.String name) {\n"
				+ READ_PROJECTION + "\t\tjava.util.List<demo.User> rows = (__projection == null) ? this.autumn.query("
				+ "SQL_FIND_BY_NAME_IGNORE_CASE, ROW_MAPPER, name)");
		// Distinct：DISTINCT 内联进 SELECT 投影，SQL 常量本身不含 LIMIT/OFFSET
		assertThat(source).contains("private static final String SQL_FIND_DISTINCT_BY_NAME"
				+ " = \"SELECT DISTINCT id, name, age, created_at FROM demo_user WHERE name = ?\"");
		assertThat(source).contains("public java.util.List<demo.User> findDistinctByName(java.lang.String name) {\n"
				+ READ_PROJECTION + "\t\tjava.util.List<demo.User> rows = (__projection == null) ? this.autumn.query("
				+ "SQL_FIND_DISTINCT_BY_NAME, ROW_MAPPER, name)");
		// First/Top：行数上限作为字面量传给带 limit 的 query 重载（放在首位，避免与无 limit 重载歧义），方言在运行期拼 LIMIT
		assertThat(source).contains("private static final String SQL_FIND_TOP3_BY_NAME"
				+ " = \"SELECT id, name, age, created_at FROM demo_user WHERE name = ?\"");
		assertThat(source).contains("public java.util.List<demo.User> findTop3ByName(java.lang.String name) {\n"
				+ READ_PROJECTION + "\t\tjava.util.List<demo.User> rows = (__projection == null) ? this.autumn.query("
				+ "3L, SQL_FIND_TOP3_BY_NAME, ROW_MAPPER, name) : this.autumn.query(3L,"
				+ " " + SQL + ".withColumns(SQL_FIND_TOP3_BY_NAME_PROJECTION, __projectionColumns(__projection)),"
				+ " __projectedMapper(__projection), name);");
		assertThat(source).contains("public java.util.List<demo.User> findFirstByName(java.lang.String name) {\n"
				+ READ_PROJECTION + "\t\tjava.util.List<demo.User> rows = (__projection == null) ? this.autumn.query("
				+ "1L, SQL_FIND_FIRST_BY_NAME, ROW_MAPPER, name)");
		// deleteBy 返回 long：DELETE 走 update 拿受影响行数
		assertThat(source).contains("private static final String SQL_DELETE_BY_NAME"
				+ " = \"DELETE FROM demo_user WHERE name = ?\"");
		assertThat(source).contains("public long deleteByName(java.lang.String name) {\n"
				+ "\t\treturn this.autumn.update(SQL_DELETE_BY_NAME, name);");
		// deleteBy 返回 List<Entity>：先 SELECT 取回再 DELETE，两条语句各成一常量；取回的那条可投影，DELETE 不受影响
		assertThat(source).contains("private static final String SQL_DELETE_BY_NAME_AND_AGE"
				+ " = \"DELETE FROM demo_user WHERE name = ? AND age = ?\"");
		assertThat(source).contains("private static final String SQL_DELETE_BY_NAME_AND_AGE_SELECT"
				+ " = \"SELECT id, name, age, created_at FROM demo_user WHERE name = ? AND age = ?\"");
		assertThat(source).contains("private static final String SQL_DELETE_BY_NAME_AND_AGE_SELECT_PROJECTION"
				+ " = \"SELECT {cols} FROM demo_user WHERE name = ? AND age = ?\"");
		assertThat(source).contains(
				"public java.util.List<demo.User> deleteByNameAndAge(java.lang.String name, int age) {\n"
						+ READ_PROJECTION + "\t\tjava.util.List<demo.User> rows = (__projection == null)"
						+ " ? this.autumn.query(SQL_DELETE_BY_NAME_AND_AGE_SELECT, ROW_MAPPER, name, age)");
		// @Query：SQL 直接采用注解文本，不再解析方法名；自定义 SQL 不触发一对多装配，也不参与投影（读不到 {cols} 模板）
		assertThat(source).contains("private static final String SQL_FIND_ADULTS"
				+ " = \"SELECT id, name, age, created_at FROM demo_user WHERE age > ?\"");
		assertThat(source).doesNotContain("SQL_FIND_ADULTS_PROJECTION");
		assertThat(source).contains("public java.util.List<demo.User> findAdults(int age) {\n"
				+ "\t\treturn this.autumn.query(SQL_FIND_ADULTS, ROW_MAPPER, age);");
		// @Query 返回 void 的写操作走 update，无 return
		assertThat(source).contains("private static final String SQL_PURGE_BY_NAME = \"DELETE FROM demo_user WHERE name = ?\"");
		assertThat(source).contains("public void purgeByName(java.lang.String name) {\n"
				+ "\t\tthis.autumn.update(SQL_PURGE_BY_NAME, name);");
	}

	@Test
	void generatesAssociationConditionAsExistsSubquery(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/Order.java", ORDER_ENTITY),
				new Source("demo/Customer.java", ASSOCIATION_ENTITY),
				new Source("demo/AssociationQueryRepository.java", ASSOCIATION_QUERY_REPO)));
		assertThat(result.errorMessages()).isEmpty();

		String source = Files.readString(dir.resolve("gen/demo/AssociationQueryRepositoryImpl.java"));
		// 一对多条件写成相关子查询：不 JOIN，父行不重复，父列清单与 ROW_MAPPER 原样复用
		assertThat(source).contains("private static final String SQL_FIND_BY_ORDERS_MEMO"
				+ " = \"SELECT id, name FROM demo_user WHERE EXISTS (SELECT 1 FROM demo_order"
				+ " WHERE demo_order.user_id = demo_user.id AND demo_order.memo = ?)\"");
		// mappedBy = "owner" 的关联用子侧的外键列
		assertThat(source).contains("private static final String SQL_FIND_BY_BY_OWNER_MEMO_LIKE"
				+ " = \"SELECT id, name FROM demo_user WHERE EXISTS (SELECT 1 FROM demo_order"
				+ " WHERE demo_order.owner_id = demo_user.id AND demo_order.memo LIKE ?)\"");
		// 派生查询照样走关联装配（两条语句取子行），不会退回 N+1
		assertThat(source).contains("public java.util.List<demo.Customer> findByOrdersMemo(java.lang.String memo) {\n"
				+ READ_PROJECTION + "\t\tjava.util.List<demo.Customer> rows = (__projection == null)"
				+ " ? this.autumn.query(SQL_FIND_BY_ORDERS_MEMO, ROW_MAPPER, memo)"
				+ " : this.autumn.query(" + SQL + ".withColumns(SQL_FIND_BY_ORDERS_MEMO_PROJECTION,"
				+ " __projectionColumns(__projection)), __projectedMapper(__projection), memo);\n"
				+ "\t\tattachOrders(rows);\n" + "\t\tattachByOwner(rows);\n" + CONSUME_CONTEXT + "\t\treturn rows;");
	}

	@Test
	void reportsUnsupportedDerivedQueryOnTheMethodDeclaration(@TempDir Path dir) throws Exception {
		Compilation result = compile(dir, List.of(new Source("demo/User.java", USER_ENTITY),
				new Source("demo/BadQueryRepository.java", UNSUPPORTED_QUERY_REPO)));
		// 过去这里静默跳过，错误由 javac 在生成类里报「不是抽象的，并且未覆盖…」，把框架的边界伪装成用户的错
		Diagnostic<? extends JavaFileObject> failure = result.diagnostics().stream()
			.filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
			.filter(diagnostic -> diagnostic.getMessage(null).contains("derived query"))
			.findFirst()
			.orElseThrow();
		assertThat(failure.getMessage(null)).contains("unknown property 'City' on demo.User")
			.contains("fields: id, name, age, createdAt");
		// 报错位置是接口上的方法声明，不是生成类
		assertThat(failure.getSource().getName()).endsWith("BadQueryRepository.java");
		assertThat(failure.getLineNumber()).isEqualTo(5);
		assertThat(dir.resolve("gen/demo/BadQueryRepositoryImpl.java")).doesNotExist();
	}

	@Test
	void incrementalCompileKeepsRepositoriesFromEarlierRounds(@TempDir Path dir) throws Exception {
		Path registry = dir.resolve("classes/META-INF/autumn-data/repositories");
		compile(dir, List.of(new Source("demo/User.java", USER_ENTITY),
				new Source("demo/UserRepository.java", VALID_REPO), new Source("demo/Account.java", PLAIN_ENTITY),
				new Source("demo/AccountRepository.java", ASSIGNED_ID_REPO)));
		assertThat(registry).exists();
		assertThat(Files.readString(registry).trim().split("\n"))
			.containsExactlyInAnyOrder("demo.UserRepository", "demo.AccountRepository");

		// 只重编改动的文件（IDE/增量构建的常态）：清单必须合并，不能把上一轮的仓储抹掉，
		// 否则 OrderRepositoryImpl 明明在 classpath 上，启动却报 required a bean that could not be found
		compile(dir, List.of(new Source("demo/Account.java", PLAIN_ENTITY),
				new Source("demo/AccountRepository.java", ASSIGNED_ID_REPO)));
		assertThat(Files.readString(registry)).contains("demo.UserRepository").contains("demo.AccountRepository");
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

	@Test
	void quotesIdentifiersWhenCompileTimeDialectIsSet(@TempDir Path dir) throws Exception {
		// MySQL：反引号
		Compilation mysql = compile(dir.resolve("mysql"),
				List.of(new Source("demo/User.java", USER_ENTITY),
						new Source("demo/QuotedUserRepository.java", QUOTED_REPO)),
				List.of("-Aautumn.data.jdbc.dialect=mysql"));
		assertThat(mysql.errorMessages()).isEmpty();
		String source = Files.readString(dir.resolve("mysql/gen/demo/QuotedUserRepositoryImpl.java"));
		assertThat(source).contains("private static final String SQL_SELECT_ALL"
				+ " = \"SELECT `id`, `name`, `age`, `created_at` FROM `demo_user`\"");
		assertThat(source).contains("private static final String SQL_SELECT_BY_ID"
				+ " = \"SELECT `id`, `name`, `age`, `created_at` FROM `demo_user` WHERE `id` = ?\"");
		// 自增主键不写进 INSERT 列清单；派生查询同样加引号
		assertThat(source).contains("private static final String SQL_INSERT"
				+ " = \"INSERT INTO `demo_user` (`name`, `age`, `created_at`) VALUES (?, ?, ?)\"");
		assertThat(source).contains("private static final String SQL_FIND_BY_NAME"
				+ " = \"SELECT `id`, `name`, `age`, `created_at` FROM `demo_user` WHERE `name` = ?\"");
		// 结果集标签不受引号影响：行映射仍用裸列名
		assertThat(source).contains("rs.getObject(\"name\", java.lang.String.class)");

		// PostgreSQL：双引号（在 Java 字符串字面量里转义）
		Compilation postgres = compile(dir.resolve("pg"),
				List.of(new Source("demo/User.java", USER_ENTITY),
						new Source("demo/QuotedUserRepository.java", QUOTED_REPO)),
				List.of("-Aautumn.data.jdbc.dialect=postgresql"));
		assertThat(postgres.errorMessages()).isEmpty();
		String pgSource = Files.readString(dir.resolve("pg/gen/demo/QuotedUserRepositoryImpl.java"));
		assertThat(pgSource).contains("private static final String SQL_SELECT_ALL"
				+ " = \"SELECT \\\"id\\\", \\\"name\\\", \\\"age\\\", \\\"created_at\\\" FROM \\\"demo_user\\\"\"");
	}

	@Test
	void leavesIdentifiersBareByDefaultAndRejectsUnknownDialect(@TempDir Path dir) throws Exception {
		// 默认不加引号：既有 schema 与断言都基于裸标识符
		Compilation bare = compile(dir, List.of(new Source("demo/User.java", USER_ENTITY),
				new Source("demo/QuotedUserRepository.java", QUOTED_REPO)));
		assertThat(bare.errorMessages()).isEmpty();
		assertThat(Files.readString(dir.resolve("gen/demo/QuotedUserRepositoryImpl.java")))
			.contains("= \"SELECT id, name, age, created_at FROM demo_user\"");

		Compilation unknown = compile(dir.resolve("bad"),
				List.of(new Source("demo/User.java", USER_ENTITY),
						new Source("demo/QuotedUserRepository.java", QUOTED_REPO)),
				List.of("-Aautumn.data.jdbc.dialect=oracle"));
		assertThat(unknown.errorMessages()).anySatisfy(message -> assertThat(message)
			.contains("unsupported value 'oracle'")
			.contains("use mysql, postgresql, h2 or default"));
	}

	private Compilation compile(Path dir, List<Source> sources) throws Exception {
		return compile(dir, sources, List.of());
	}

	private Compilation compile(Path dir, List<Source> sources, List<String> options) throws Exception {
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
			List<String> allOptions = new ArrayList<>(options);
			allOptions.add("-proc:full");
			this.compiler.getTask(null, fileManager, diagnostics, allOptions, null, units).call();
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
