package xyz.wewin.autumn.data.using.demo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import xyz.wewin.autumn.data.jdbc.dialect.H2Dialect;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
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
	void deleteAllUsesOneInStatement() {
		this.repository.saveAll(List.of(newUser("u1"), newUser("u2"), newUser("u3")));
		this.repository.deleteAll(this.repository.findAll());
		assertThat(this.repository.count()).isZero();
	}

	private static User newUser(String username) {
		User user = new User();
		user.setUsername(username);
		user.setEmail(username + "@example.com");
		return user;
	}

	private static Order newOrder(Long userId, String orderNo) {
		Order order = new Order();
		order.setUserId(userId);
		order.setOrderNo(orderNo);
		order.setAmount(new BigDecimal("9.90"));
		return order;
	}

}
