package xyz.wewin.autumn.data.using.demo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnDataContextHolder;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.FieldProjection;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.jdbc.sql.Sql;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编译期生成的 {@code UserRepositoryImpl} 直连 H2 验证（不起 Spring 容器）：
 * SQL 在编译期内联为生成类的 {@code private static final String} 常量 → 实现类真实读写
 * → 批量写 / 分页 / 一对多装配与行数上限。
 */
class GeneratedUserRepositoryImplTest {

	private final DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:autumn-generated-impl;DB_CLOSE_DELAY=-1",
			"sa", "");

	private JdbcTemplate jdbcTemplate;

	private UserRepository repository;

	private OrderRepository orderRepository;

	@BeforeEach
	void setUp() {
		this.jdbcTemplate = new JdbcTemplate(this.dataSource);
		this.jdbcTemplate.execute("DROP TABLE IF EXISTS demo_order");
		this.jdbcTemplate.execute("DROP TABLE IF EXISTS basic_user");
		this.jdbcTemplate.execute("CREATE TABLE basic_user ("
				+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) NOT NULL, email VARCHAR(128))");
		this.jdbcTemplate.execute("CREATE TABLE demo_order ("
				+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, order_no VARCHAR(64) NOT NULL,"
				+ " amount DECIMAL(12, 2))");
		AutumnJdbc autumn = new AutumnJdbc(new JdbcConnectionSource(this.dataSource), new H2Dialect(),
				FetchPolicy.defaults());
		this.repository = new UserRepositoryImpl(autumn);
		this.orderRepository = new OrderRepositoryImpl(autumn);
		// 上一个用例若在断言前抛错，一次性上下文就不会被消费；用例开始前清一次，避免串台
		AutumnDataContextHolder.clear();
	}

	@Test
	void inlinedSqlConstantsAndRuntimeInClause() throws Exception {
		// SQL 已编译期内联为生成类的 private static final String 常量（不再有 properties 资源）
		assertThat(constant("SQL_INSERT")).isEqualTo("INSERT INTO basic_user (username, email) VALUES (?, ?)");
		assertThat(constant("SQL_UPDATE")).isEqualTo("UPDATE basic_user SET username = ?, email = ? WHERE id = ?");
		assertThat(constant("SQL_SELECT_ALL")).isEqualTo("SELECT id, username, email FROM basic_user");
		assertThat(constant("SQL_PAGE_ALL")).isEqualTo("SELECT id, username, email FROM basic_user ORDER BY id");
		assertThat(constant("SQL_DELETE_BY_IDS")).isEqualTo("DELETE FROM basic_user WHERE id IN (:ids)");
		assertThat(constant("SQL_ORDERS_BY_PARENT_IDS"))
			.isEqualTo("SELECT id, user_id, order_no, amount FROM demo_order WHERE user_id IN (:ids) ORDER BY id");
		// 运行期仅剩的拼接：把 IN 占位符按参数个数展开成 ?
		assertThat(Sql.inClause(constant("SQL_DELETE_BY_IDS"), 3))
			.isEqualTo("DELETE FROM basic_user WHERE id IN (?, ?, ?)");
		assertThat(Sql.inClause(constant("SQL_ORDERS_BY_PARENT_IDS"), 3))
			.isEqualTo("SELECT id, user_id, order_no, amount FROM demo_order WHERE user_id IN (?, ?, ?) ORDER BY id");
	}

	private static String constant(String name) throws Exception {
		java.lang.reflect.Field field = UserRepositoryImpl.class.getDeclaredField(name);
		field.setAccessible(true);
		return (String) field.get(null);
	}

	@Test
	void implementationHasSingleAutumnJdbcConstructor() throws Exception {
		// 生成类不标 @Service：它由 starter 的 RepositoryRegistrar 在容器启动时注册为 Bean
		assertThat(UserRepositoryImpl.class.getConstructor(AutumnJdbc.class)).isNotNull();
		assertThat(UserRepositoryImpl.class.getDeclaredConstructors()).hasSize(1);
	}

	@Test
	void saveInsertsAndBackfillsGeneratedId() {
		User saved = this.repository.save(newUser("alice"));
		assertThat(saved.getId()).isNotNull();
		assertThat(this.repository.findById(saved.getId())).get().hasFieldOrPropertyWithValue("username", "alice");
	}

	@Test
	void saveAllBatchesInsertsAndBackfillsEveryId() {
		List<User> users = List.of(newUser("u1"), newUser("u2"), newUser("u3"), newUser("u4"), newUser("u5"));
		this.repository.saveAll(users);
		assertThat(users).extracting(User::getId).doesNotContainNull().doesNotHaveDuplicates();
		assertThat(this.repository.count()).isEqualTo(5L);
	}

	@Test
	void saveAllBatchesExistingRowsWithUpdate() {
		List<User> users = new ArrayList<>(List.of(newUser("u1"), newUser("u2")));
		this.repository.saveAll(users);
		users.forEach(user -> user.setUsername(user.getUsername() + "-renamed"));
		this.repository.saveAll(users);
		assertThat(this.repository.count()).isEqualTo(2L);
		assertThat(this.repository.findAll()).extracting(User::getUsername)
			.containsExactlyInAnyOrder("u1-renamed", "u2-renamed");
	}

	@Test
	void findAllPagesWithDialectLimitAndTotalCount() {
		this.repository
			.saveAll(List.of(newUser("u1"), newUser("u2"), newUser("u3"), newUser("u4"), newUser("u5")));

		Page<User> first = this.repository.findAll(Pageable.of(0, 2));
		assertThat(first.content()).extracting(User::getUsername).containsExactly("u1", "u2");
		assertThat(first.total()).isEqualTo(5L);
		assertThat(first.totalPages()).isEqualTo(3);
		assertThat(first.hasNext()).isTrue();

		Page<User> last = this.repository.findAll(Pageable.of(2, 2));
		assertThat(last.content()).extracting(User::getUsername).containsExactly("u5");
		assertThat(last.hasNext()).isFalse();
	}

	@Test
	void readsLoadChildrenWithOneExtraStatementGroupedPerParent() {
		User alice = this.repository.save(newUser("alice"));
		User bob = this.repository.save(newUser("bob"));
		this.repository.save(newUser("carol"));
		this.orderRepository.saveAll(List.of(newOrder(alice.getId(), "SO-1"), newOrder(alice.getId(), "SO-2"),
				newOrder(bob.getId(), "SO-3")));

		assertThat(this.repository.findById(alice.getId()).orElseThrow().getOrders()).extracting(Order::getOrderNo)
			.containsExactly("SO-1", "SO-2");
		assertThat(this.repository.findAll()).extracting(user -> user.getOrders().size())
			.containsExactly(2, 1, 0);
		// 无单的用户拿到空集合而不是 null
		assertThat(this.repository.findAll()).filteredOn(user -> user.getUsername().equals("carol")).singleElement()
			.satisfies(user -> assertThat(user.getOrders()).isEmpty());
	}

	@Test
	void associationLimitCapsHowManyChildrenComeBack() {
		User alice = this.repository.save(newUser("alice"));
		List<Order> orders = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			orders.add(newOrder(alice.getId(), "SO-" + i));
		}
		this.orderRepository.saveAll(orders);

		AutumnJdbc capped = new AutumnJdbc(new JdbcConnectionSource(this.dataSource), new H2Dialect(),
				new FetchPolicy(2, 100));
		User limited = new UserRepositoryImpl(capped).findById(alice.getId()).orElseThrow();
		assertThat(limited.getOrders()).hasSize(2);
	}

	@Test
	void defaultModeSkipsAssociationForLargePage() {
		User alice = this.repository.save(newUser("alice"));
		User bob = this.repository.save(newUser("bob"));
		this.orderRepository.saveAll(List.of(newOrder(alice.getId(), "SO-1"), newOrder(bob.getId(), "SO-2")));

		// DEFAULT 模式：页大小 25 > 阈值 20 → 不自动级联（交给调用方），关联为空
		Page<User> large = this.repository.findAll(Pageable.of(0, 25));
		assertThat(large.content()).allSatisfy(user -> assertThat(user.getOrders()).isEmpty());
	}

	@Test
	void defaultModeLoadsAssociationForSmallPage() {
		User alice = this.repository.save(newUser("alice"));
		this.orderRepository.saveAll(List.of(newOrder(alice.getId(), "SO-1")));

		// DEFAULT 模式：页大小 2 <= 阈值 20 → 默认级联，关联正常加载
		Page<User> small = this.repository.findAll(Pageable.of(0, 2));
		assertThat(small.content()).singleElement()
			.satisfies(user -> assertThat(user.getOrders()).extracting(Order::getOrderNo)
				.containsExactly("SO-1"));
	}

	@Test
	void cascadeModeForcesAssociationEvenOnLargePage() {
		User alice = this.repository.save(newUser("alice"));
		User bob = this.repository.save(newUser("bob"));
		this.orderRepository.saveAll(List.of(newOrder(alice.getId(), "SO-1"), newOrder(bob.getId(), "SO-2")));

		// 一次性强制级联（CASCADE）：页大小 25 > 阈值，仍加载关联；查完自动清除
		AutumnDataContextHolder.forceCascade();
		Page<User> large = this.repository.findAll(Pageable.of(0, 25));
		assertThat(large.content()).extracting(user -> user.getOrders().size())
			.containsExactlyInAnyOrder(1, 1);

		// 一次性上下文已自动消费 → 再次大页查询回到 DEFAULT，不再级联
		Page<User> backToDefault = this.repository.findAll(Pageable.of(0, 25));
		assertThat(backToDefault.content()).allSatisfy(user -> assertThat(user.getOrders()).isEmpty());
	}

	@Test
	void disabledModeSkipsAssociationEvenOnSmallPage() {
		User alice = this.repository.save(newUser("alice"));
		this.orderRepository.saveAll(List.of(newOrder(alice.getId(), "SO-1")));

		// 一次性强制关闭（DISABLED）：即便页大小 2 <= 阈值，也不查询关联
		AutumnDataContextHolder.disableCascade();
		Page<User> small = this.repository.findAll(Pageable.of(0, 2));
		assertThat(small.content()).singleElement()
			.satisfies(user -> assertThat(user.getOrders()).isEmpty());
	}

	@Test
	void oneShotDisableCascadeAutoClearsAndDoesNotLeak() {
		User alice = this.repository.save(newUser("alice"));
		this.orderRepository.saveAll(List.of(newOrder(alice.getId(), "SO-1")));

		// 一次性关闭：本次小页查询不加载关联
		AutumnDataContextHolder.disableCascade();
		Page<User> first = this.repository.findAll(Pageable.of(0, 2));
		assertThat(first.content()).singleElement()
			.satisfies(user -> assertThat(user.getOrders()).isEmpty());

		// 一次性上下文已自动清除 → 下一次查询回到 DEFAULT，小页正常级联加载
		Page<User> second = this.repository.findAll(Pageable.of(0, 2));
		assertThat(second.content()).singleElement()
			.satisfies(user -> assertThat(user.getOrders()).extracting(Order::getOrderNo)
				.containsExactly("SO-1"));
	}

	@Test
	void deleteAllUsesOneInStatement() {
		this.repository.saveAll(List.of(newUser("u1"), newUser("u2"), newUser("u3")));
		this.repository.deleteAll(this.repository.findAll());
		assertThat(this.repository.count()).isZero();
	}

	@Test
	void derivedQueryFiltersOnMappedColumnAndStillAssemblesChildren() {
		User alice = this.repository.save(newUser("alice"));
		this.repository.save(newUser("bob"));
		this.orderRepository.saveAll(List.of(newOrder(alice.getId(), "SO-1"), newOrder(alice.getId(), "SO-2")));

		// 方法名派生的条件走列名，结果仍复用一对多装配（不会退化成 N+1）
		assertThat(this.repository.findByUsername("alice")).singleElement()
			.satisfies(user -> assertThat(user.getOrders()).extracting(Order::getOrderNo)
				.containsExactly("SO-1", "SO-2"));
		assertThat(this.repository.findByUsername("nobody")).isEmpty();
	}

	@Test
	void associationConditionFiltersParentsThroughCorrelatedExists() throws Exception {
		User alice = this.repository.save(newUser("alice"));
		User bob = this.repository.save(newUser("bob"));
		this.repository.save(newUser("carol"));
		this.orderRepository.saveAll(List.of(newOrder(alice.getId(), "SO-1", "9.90"),
				newOrder(alice.getId(), "SO-2", "250.00"), newOrder(bob.getId(), "SO-3", "1200.00")));

		// 一层关联路径在编译期展开成 EXISTS 相关子查询：条件写在子表，返回的仍是父实体
		assertThat(constant("SQL_FIND_BY_ORDERS_ORDER_NO")).isEqualTo(
				"SELECT id, username, email FROM basic_user WHERE EXISTS (SELECT 1 FROM demo_order"
						+ " WHERE demo_order.user_id = basic_user.id AND demo_order.order_no = ?)");
		assertThat(this.repository.findByOrdersOrderNo("SO-3")).extracting(User::getUsername).containsExactly("bob");
		// 无单用户不在结果里；命中多单的用户只回一行（EXISTS 是半连接，不产生重复）
		assertThat(this.repository.findByOrdersOrderNo("SO-999")).isEmpty();

		// 关联条件 + 本表条件 + 0 参数关键词组合
		assertThat(this.repository.findByOrdersAmountGreaterThanAndUsernameIsNotNull(new BigDecimal("200")))
			.extracting(User::getUsername)
			.containsExactlyInAnyOrder("alice", "bob");
		assertThat(this.repository.countByOrdersOrderNoIsNotNull()).isEqualTo(2L);

		// 派生的关联条件不影响装配：命中的父实体仍带着全部子行
		assertThat(this.repository.findByOrdersOrderNo("SO-1")).singleElement()
			.satisfies(user -> assertThat(user.getOrders()).extracting(Order::getOrderNo)
				.containsExactly("SO-1", "SO-2"));
	}

	@Test
	void projectionTemplateIsCompiledInAndTrimmedAtRuntime() throws Exception {
		// 编译期默认仍是全列；每条取实体行的语句额外内联一份 {cols} 模板
		assertThat(constant("SQL_SELECT_ALL")).isEqualTo("SELECT id, username, email FROM basic_user");
		assertThat(constant("SQL_SELECT_ALL_PROJECTION")).isEqualTo("SELECT {cols} FROM basic_user");
		assertThat(constant("SQL_PAGE_ALL_PROJECTION")).isEqualTo("SELECT {cols} FROM basic_user ORDER BY id");
		assertThat(constant("SQL_FIND_BY_USERNAME_PROJECTION")).isEqualTo("SELECT {cols} FROM basic_user"
				+ " WHERE username = ?");
		// 运行期挑列：主键编译期判定为不可省略，恒进清单（否则回填、级联装配都断了）
		assertThat(projectionColumns(FieldProjection.include("username"))).isEqualTo("id, username");
		assertThat(projectionColumns(FieldProjection.exclude("email"))).isEqualTo("id, username");
		// 列清单按实体声明顺序拼装，与调用方写投影的先后无关
		assertThat(projectionColumns(FieldProjection.include("email", "username")))
			.isEqualTo("id, username, email");
		assertThat(Sql.withColumns(constant("SQL_SELECT_ALL_PROJECTION"),
				projectionColumns(FieldProjection.include("username")))).isEqualTo("SELECT id, username FROM basic_user");
	}

	@Test
	void selectFieldsTrimsColumnsAndLeavesUnselectedAtEntityDefault() {
		this.repository.saveAll(List.of(newUser("alice"), newUser("bob")));

		AutumnDataContextHolder.selectFields("username");
		List<User> users = this.repository.findAll();
		// 真裁剪 SELECT：未取的列不再占网络与内存，属性留在实体默认值上
		assertThat(users).hasSize(2).allSatisfy(user -> {
			assertThat(user.getId()).isNotNull();
			assertThat(user.getUsername()).isNotNull();
			assertThat(user.getEmail()).isNull();
		});

		// 一次性：下一次查询回到编译期的全列形态
		assertThat(this.repository.findAll()).extracting(User::getEmail).doesNotContainNull();
	}

	@Test
	void excludeFieldsKeepsEverythingElse() {
		this.repository.saveAll(List.of(newUser("alice"), newUser("bob")));

		AutumnDataContextHolder.excludeFields("email");
		assertThat(this.repository.findAll()).allSatisfy(user -> {
			assertThat(user.getUsername()).isNotNull();
			assertThat(user.getEmail()).isNull();
		});
	}

	@Test
	void projectionAppliesToSingleRowBatchAndPagedReads() {
		User alice = this.repository.save(newUser("alice"));
		User bob = this.repository.save(newUser("bob"));

		AutumnDataContextHolder.selectFields("username");
		assertThat(this.repository.findById(alice.getId()).orElseThrow().getEmail()).isNull();

		AutumnDataContextHolder.selectFields("username");
		assertThat(this.repository.findAllById(List.of(alice.getId(), bob.getId()))).allSatisfy(user ->
				assertThat(user.getEmail()).isNull());

		AutumnDataContextHolder.selectFields("username");
		Page<User> page = this.repository.findAll(Pageable.of(0, 2));
		assertThat(page.content()).hasSize(2).allSatisfy(user -> assertThat(user.getEmail()).isNull());
		// 计数语句不带列清单：总数不受投影影响
		assertThat(page.total()).isEqualTo(2L);

		AutumnDataContextHolder.selectFields("username");
		assertThat(this.repository.findByUsername("alice").getFirst().getEmail()).isNull();
	}

	@Test
	void projectionKeepsAssociationAssemblyAndComposesWithCascadeMode() {
		User alice = this.repository.save(newUser("alice"));
		this.orderRepository.saveAll(List.of(newOrder(alice.getId(), "SO-1"), newOrder(alice.getId(), "SO-2")));

		// 投影 + 默认级联：只取 username 与主键，一对多仍走第二条语句装配（主键不可省略才办得到）
		AutumnDataContextHolder.selectFields("username");
		User projected = this.repository.findById(alice.getId()).orElseThrow();
		assertThat(projected.getEmail()).isNull();
		assertThat(projected.getOrders()).extracting(Order::getOrderNo).containsExactly("SO-1", "SO-2");

		// 投影 + 一次性强制级联：大页仍级联，且列被裁剪；两个设置一起被消费
		this.repository.save(newUser("bob"));
		AutumnDataContextHolder.forceCascade();
		AutumnDataContextHolder.selectFields("username");
		Page<User> large = this.repository.findAll(Pageable.of(0, 25));
		assertThat(large.content()).allSatisfy(user -> {
			assertThat(user.getEmail()).isNull();
			assertThat(user.getOrders()).isNotNull();
		});
		assertThat(large.content()).filteredOn(user -> user.getUsername().equals("alice")).singleElement()
			.satisfies(user -> assertThat(user.getOrders()).hasSize(2));

		// 一次性上下文已被消费：回到全列 + DEFAULT 模式
		Page<User> next = this.repository.findAll(Pageable.of(0, 2));
		assertThat(next.content()).allSatisfy(user -> assertThat(user.getEmail()).isNotNull());
	}

	@Test
	void countAndExistsDoNotConsumeThePendingProjection() {
		this.repository.saveAll(List.of(newUser("alice"), newUser("bob")));

		// 不取行的语句（count / existsBy）与写操作都不消费：投影顺延到下一次取实体行的查询
		AutumnDataContextHolder.selectFields("username");
		assertThat(this.repository.count()).isEqualTo(2L);
		assertThat(this.repository.findByUsername("alice").getFirst().getEmail()).isNull();

		// 顺延的那一次查询把它消费掉
		assertThat(this.repository.findByUsername("bob").getFirst().getEmail()).isNotNull();
	}

	private static String projectionColumns(FieldProjection projection) throws Exception {
		java.lang.reflect.Method method = UserRepositoryImpl.class.getDeclaredMethod("__projectionColumns",
				FieldProjection.class);
		method.setAccessible(true);
		return (String) method.invoke(null, projection);
	}

	private static User newUser(String username) {
		User user = new User();
		user.setUsername(username);
		user.setEmail(username + "@example.com");
		return user;
	}

	private static Order newOrder(Long userId, String orderNo) {
		return newOrder(userId, orderNo, "9.90");
	}

	private static Order newOrder(Long userId, String orderNo, String amount) {
		Order order = new Order();
		order.setUserId(userId);
		order.setOrderNo(orderNo);
		order.setAmount(new BigDecimal(amount));
		return order;
	}

}
