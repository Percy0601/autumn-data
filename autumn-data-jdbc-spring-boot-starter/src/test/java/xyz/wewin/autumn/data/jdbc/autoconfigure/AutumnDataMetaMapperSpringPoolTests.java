package xyz.wewin.autumn.data.jdbc.autoconfigure;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动态层与 Spring Boot 连接池的兼容验证：引擎吃容器里的 {@link DataSource}（
 * {@code spring.datasource.*} 建出来的 Hikari 池），借还都走这个池，并且语句自动加入
 * {@code @Transactional}（回滚会连动态层的写入一起撤回）。
 */
class AutumnDataMetaMapperSpringPoolTests {

	private static final String DROP_TABLE = "DROP TABLE IF EXISTS spring_demo";

	private static final String CREATE_TABLE = "CREATE TABLE spring_demo (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
			+ "name VARCHAR(64), amount DECIMAL(12,2), status INT)";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
				AutumnDataJdbcAutoConfiguration.class, AutumnDataMetaMapperAutoConfiguration.class))
		.withPropertyValues(
				"spring.datasource.url=jdbc:h2:mem:meta-spring-pool;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
				"spring.datasource.hikari.pool-name=meta-spring-pool",
				"spring.datasource.hikari.maximum-pool-size=4",
				// 纯内存：元类 / 实体 / Mapper 都不落盘，测试不污染工作目录
				"autumn.data.meta.mapper.meta-directory=");

	@Test
	void usesSpringBootPoolAndReturnsConnectionsAfterEachOperation() {
		this.contextRunner.run(context -> {
			DataSource dataSource = context.getBean(DataSource.class);
			assertThat(dataSource).isInstanceOf(HikariDataSource.class);
			HikariDataSource hikari = (HikariDataSource) dataSource;
			assertThat(hikari.getPoolName()).isEqualTo("meta-spring-pool");

			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			resetTable(jdbc);

			MetaMapper.findList("spring_demo", MetaQuery.all());

			// 连接的创建与回收都归 Hikari：池里确实有连接，但操作结束后一条都不再被动态层占着
			assertThat(hikari.getHikariPoolMXBean().getTotalConnections()).isGreaterThanOrEqualTo(1);
			assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();
		});
	}

	@Test
	void joinsSpringTransactionAndRollsBack() {
		this.contextRunner.run(context -> {
			DataSource dataSource = context.getBean(DataSource.class);
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			resetTable(jdbc);
			TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

			assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
				MetaClass row = MetaMapper.newEntity("spring_demo");
				row.set("name", "tx-rollback");
				MetaMapper.save(row);
				throw new IllegalStateException("boom");
			})).isInstanceOf(IllegalStateException.class);

			assertThat(selectCount(jdbc)).isZero();
		});
	}

	@Test
	void commitsWithSpringTransaction() {
		this.contextRunner.run(context -> {
			DataSource dataSource = context.getBean(DataSource.class);
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			resetTable(jdbc);
			TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

			transaction.executeWithoutResult(status -> {
				MetaClass row = MetaMapper.newEntity("spring_demo");
				row.set("name", "tx-commit");
				MetaMapper.save(row);
			});

			assertThat(selectCount(jdbc)).isOne();
		});
	}

	private static void resetTable(JdbcTemplate jdbc) {
		jdbc.execute(DROP_TABLE);
		jdbc.execute(CREATE_TABLE);
	}

	private static long selectCount(JdbcTemplate jdbc) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM spring_demo", Long.class);
	}
}

