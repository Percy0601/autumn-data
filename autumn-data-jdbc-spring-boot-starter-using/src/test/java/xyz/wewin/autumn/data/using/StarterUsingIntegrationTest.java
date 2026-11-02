package xyz.wewin.autumn.data.using;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import xyz.wewin.autumn.common.jdbc.dialect.Dialect;
import xyz.wewin.autumn.common.jdbc.dialect.H2Dialect;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.using.demo.Order;
import xyz.wewin.autumn.data.using.demo.User;
import xyz.wewin.autumn.data.using.demo.UserRepository;
import xyz.wewin.autumn.data.using.demo.UserRepositoryImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用方视角的端到端验证：本工程只依赖 autumn-data-jdbc-spring-boot-starter，
 * 除 spring.datasource 与脚本初始化外无任何自定义装配 —— 约定大于配置。
 *
 * <p>类上的 {@code @Transactional} 由 spring-boot-test 与 spring-tx 原生提供：
 * 生成的实现类走 JdbcOperations/DataSourceUtils，因此自动加入测试事务并在用例结束后回滚。</p>
 */
@SpringBootTest(classes = UsingApplication.class)
@Transactional
class StarterUsingIntegrationTest {

	@Autowired
	JdbcOperations jdbcOperations;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	Dialect dialect;

	@Autowired
	UserRepository userRepository;

	@Test
	void dialectResolvedByConvention() {
		// application.properties 显式写了 autumn.data.jdbc.dialect=h2；省略该项时按元数据也应得到 H2Dialect
		assertThat(dialect).isInstanceOf(H2Dialect.class);
		assertThat(dialect.productName()).isEqualTo("H2");
	}

	@Test
	void schemaAndDataInitialized() {
		Long count = jdbcClient.sql("SELECT COUNT(*) FROM basic_user").query(Long.class).single();
		assertThat(count).isEqualTo(3L);
		Long orderCount = jdbcClient.sql("SELECT COUNT(*) FROM demo_order").query(Long.class).single();
		assertThat(orderCount).isEqualTo(3L);
	}

	@Test
	void queryThroughJdbcClient() {
		var usernames = jdbcClient.sql("SELECT username FROM basic_user WHERE email IS NOT NULL ORDER BY username")
			.query(String.class)
			.list();
		assertThat(usernames).containsExactly("alice", "bob");
	}

	@Test
	void dialectPaginationFragmentAppends() {
		String sql = "SELECT username FROM basic_user ORDER BY id" + dialect.limitOffset(2, 0);
		var top2 = jdbcOperations.query(sql, (rs, rowNum) -> rs.getString(1));
		assertThat(top2).containsExactly("alice", "bob");
	}

	@Test
	void insertAndGeneratedKey() {
		int updated = jdbcClient.sql("INSERT INTO basic_user (username, email) VALUES ('dave', 'dave@example.com')")
			.update();
		assertThat(updated).isEqualTo(1);
		Long count = jdbcClient.sql("SELECT COUNT(*) FROM basic_user").query(Long.class).single();
		assertThat(count).isEqualTo(4L);
	}

	@Test
	void repositoryBeanComesFromCompiledGeneratedImplementation() {
		// 生成的 Impl 不标 @Service：由 starter 的 RepositoryRegistrar 依据
		// META-INF/autumn-data/repositories 注册为 Bean，构造注入 AutumnJdbc
		assertThat(this.userRepository).isInstanceOf(UserRepositoryImpl.class);
	}

	@Test
	void crudThroughGeneratedRepository() {
		assertThat(this.userRepository.findAll()).hasSize(3);
		User alice = this.userRepository.findById(1L).orElseThrow();
		assertThat(alice.getUsername()).isEqualTo("alice");

		User dave = new User();
		dave.setUsername("dave");
		dave.setEmail("dave@example.com");
		User saved = this.userRepository.save(dave);
		assertThat(saved.getId()).isNotNull();
		assertThat(this.userRepository.count()).isEqualTo(4L);

		saved.setUsername("dave-updated");
		this.userRepository.save(saved);
		assertThat(this.userRepository.findById(saved.getId()).orElseThrow().getUsername()).isEqualTo("dave-updated");

		this.userRepository.deleteById(2L);
		assertThat(this.userRepository.existsById(2L)).isFalse();
		assertThat(this.userRepository.findAllById(List.of(1L, 3L))).hasSize(2);
	}

	@Test
	void pagingThroughGeneratedRepository() {
		Page<User> firstPage = this.userRepository.findAll(Pageable.of(0, 2));
		assertThat(firstPage.content()).extracting(User::getUsername).containsExactly("alice", "bob");
		assertThat(firstPage.total()).isEqualTo(3L);
		assertThat(firstPage.totalPages()).isEqualTo(2);
		assertThat(firstPage.hasNext()).isTrue();

		Page<User> lastPage = this.userRepository.findAll(Pageable.of(1, 2));
		assertThat(lastPage.content()).extracting(User::getUsername).containsExactly("carol");
		assertThat(lastPage.hasNext()).isFalse();
	}

	@Test
	void oneToManyChildrenLoadWithOneExtraStatement() {
		List<User> users = this.userRepository.findAll();
		assertThat(users).extracting(user -> user.getOrders().size()).containsExactly(2, 1, 0);
		assertThat(users.get(0).getOrders()).extracting(Order::getOrderNo).containsExactly("SO-1001", "SO-1002");
	}
}
