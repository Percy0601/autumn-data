package xyz.wewin.autumn.data.using;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import xyz.wewin.autumn.data.jdbc.dialect.Dialect;
import xyz.wewin.autumn.data.jdbc.dialect.H2Dialect;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用方视角的端到端验证：本工程只依赖 autumn-data-jdbc-spring-boot-starter，
 * 除 spring.datasource 与脚本初始化外无任何自定义装配 —— 约定大于配置。
 */
@SpringBootTest(classes = UsingApplication.class)
@Transactional
class StarterUsingIntegrationTest {

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcOperations jdbcOperations;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	Dialect dialect;

	@Test
	void dialectResolvedByConvention() {
		// application.yaml 显式写了 autumn.data.jdbc.dialect=h2；省略该项时按元数据也应得到 H2Dialect
		assertThat(dialect).isInstanceOf(H2Dialect.class);
		assertThat(dialect.productName()).isEqualTo("H2");
	}

	@Test
	void schemaAndDataInitialized() {
		Long count = jdbcClient.sql("SELECT COUNT(*) FROM demo_user").query(Long.class).single();
		assertThat(count).isEqualTo(3L);
	}

	@Test
	void queryThroughJdbcClient() {
		var names = jdbcClient.sql("SELECT name FROM demo_user WHERE age > ? ORDER BY name")
			.param(28)
			.query(String.class)
			.list();
		assertThat(names).containsExactly("alice", "carol");
	}

	@Test
	void dialectPaginationFragmentAppends() {
		String sql = "SELECT name FROM demo_user ORDER BY age" + dialect.limitOffset(2, 0);
		var top2 = jdbcOperations.query(sql, (rs, rowNum) -> rs.getString(1));
		assertThat(top2).containsExactly("bob", "alice");
	}

	@Test
	void insertAndGeneratedKey() {
		int updated = jdbcClient.sql("INSERT INTO demo_user (name, age) VALUES ('dave', 28)").update();
		assertThat(updated).isEqualTo(1);
		Long count = jdbcClient.sql("SELECT COUNT(*) FROM demo_user").query(Long.class).single();
		assertThat(count).isEqualTo(4L);
	}
}
