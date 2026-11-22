package xyz.wewin.autumn.data.using.demo.vo;

import java.math.BigDecimal;
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
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.using.demo.UserRepository;
import xyz.wewin.autumn.data.using.demo.UserRepositoryImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自定义返回类型（VO）的生成实现直连 H2 验证：派生查询只取 VO 要的列、record 与 setter 两种形态都能映射、
 * {@code Optional} / {@code Page} / {@code @Query} 聚合结果都能落到 VO 上。
 */
class VoProjectionTest {

	private final DataSource dataSource = new DriverManagerDataSource(
			"jdbc:h2:mem:autumn-vo-projection;DB_CLOSE_DELAY=-1", "sa", "");

	private OrderSummaryRepository summaryRepository;

	private UserRepository userRepository;

	@BeforeEach
	void setUp() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(this.dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS demo_order");
		jdbcTemplate.execute("DROP TABLE IF EXISTS basic_user");
		jdbcTemplate.execute("CREATE TABLE basic_user ("
				+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(64) NOT NULL, email VARCHAR(128))");
		jdbcTemplate.execute("CREATE TABLE demo_order ("
				+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, order_no VARCHAR(64) NOT NULL,"
				+ " amount DECIMAL(12, 2))");
		jdbcTemplate.update("INSERT INTO basic_user (id, username, email) VALUES (?, ?, ?)", 1L, "alice",
				"alice@example.com");
		jdbcTemplate.update("INSERT INTO basic_user (id, username, email) VALUES (?, ?, ?)", 2L, "bob",
				"bob@example.com");
		jdbcTemplate.update("INSERT INTO demo_order (user_id, order_no, amount) VALUES (?, ?, ?)", 1L, "A-1",
				new BigDecimal("10.00"));
		jdbcTemplate.update("INSERT INTO demo_order (user_id, order_no, amount) VALUES (?, ?, ?)", 1L, "A-2",
				new BigDecimal("30.00"));
		jdbcTemplate.update("INSERT INTO demo_order (user_id, order_no, amount) VALUES (?, ?, ?)", 2L, "B-1",
				new BigDecimal("20.00"));

		AutumnJdbc autumn = new AutumnJdbc(new JdbcConnectionSource(this.dataSource), new H2Dialect(),
				FetchPolicy.defaults());
		this.summaryRepository = new OrderSummaryRepositoryImpl(autumn);
		this.userRepository = new UserRepositoryImpl(autumn);
		AutumnDataContextHolder.clear();
	}

	@Test
	void derivedQuerySelectsOnlyVoColumnsAndMapsRecord() throws Exception {
		// 编译期已按 VO 成员裁剪列：不再拖 user_id / id 这些 VO 不要的列
		assertThat(constant("SQL_FIND_BY_USER_ID_ORDER_BY_AMOUNT_DESC")).isEqualTo(
				"SELECT order_no, amount FROM demo_order WHERE user_id = ? ORDER BY amount DESC");

		List<OrderSummaryVo> rows = this.summaryRepository.findByUserIdOrderByAmountDesc(1L);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).orderNo()).isEqualTo("A-2");
		assertThat(rows.get(0).amount()).isEqualByComparingTo("30.00");
		assertThat(rows.get(1).orderNo()).isEqualTo("A-1");
		assertThat(rows.get(1).amount()).isEqualByComparingTo("10.00");
	}

	@Test
	void firstReturnsEmptyOptionalWhenNoRowMatches() {
		assertThat(this.summaryRepository.findFirstByUserIdOrderByAmountDesc(99L)).isEmpty();
		assertThat(this.summaryRepository.findFirstByUserIdOrderByAmountDesc(1L)).get()
			.extracting(OrderSummaryVo::orderNo)
			.isEqualTo("A-2");
	}

	@Test
	void pagedQueryMapsVoAndCountsMatchingRows() {
		Page<OrderSummaryVo> page = this.summaryRepository.findByUserIdOrderByAmountDesc(1L, Pageable.of(0, 1));
		assertThat(page.total()).isEqualTo(2);
		assertThat(page.content()).singleElement().extracting(OrderSummaryVo::orderNo).isEqualTo("A-2");

		Page<OrderSummaryVo> second = this.summaryRepository.findByUserIdOrderByAmountDesc(1L, Pageable.of(1, 1));
		assertThat(second.content()).singleElement().extracting(OrderSummaryVo::orderNo).isEqualTo("A-1");
	}

	@Test
	void queryAnnotationMapsAggregateResultByColumnLabels() {
		List<UserOrderStatsVo> stats = this.summaryRepository.statsByUser(1L);
		assertThat(stats).singleElement().satisfies(vo -> {
			assertThat(vo.userId()).isEqualTo(1L);
			assertThat(vo.orderCount()).isEqualTo(2L);
			assertThat(vo.totalAmount()).isEqualByComparingTo("40.00");
		});
	}

	@Test
	void setterBasedVoIsWrittenThroughSetters() {
		List<UserBriefVo> users = this.userRepository.findByUsernameContaining("li");
		assertThat(users).singleElement().satisfies(vo -> {
			assertThat(vo.getId()).isEqualTo(1L);
			assertThat(vo.getUsername()).isEqualTo("alice");
		});
	}

	private static String constant(String name) throws Exception {
		java.lang.reflect.Field field = OrderSummaryRepositoryImpl.class.getDeclaredField(name);
		field.setAccessible(true);
		return (String) field.get(null);
	}
}
