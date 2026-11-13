package xyz.wewin.autumn.data.jdbc.autoconfigure;

import java.sql.Connection;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.PerThreadConnectionSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 静态仓储链路（{@link AutumnJdbc} 单例 + 编译期生成的 {@code <Repo>Impl}）在 Spring Boot 下的
 * 连接语义：连接来自 Boot 的池、每个线程一份连接源（不串连接）、语句加入 {@code @Transactional}。
 */
class AutumnDataJdbcSpringConnectionTests {

	private static final String DROP_TABLE = "DROP TABLE IF EXISTS spring_tx_demo";

	private static final String CREATE_TABLE = "CREATE TABLE spring_tx_demo (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
			+ "name VARCHAR(64))";

	private static final String INSERT_ROW = "INSERT INTO spring_tx_demo (name) VALUES (?)";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
				AutumnDataJdbcAutoConfiguration.class))
		.withPropertyValues("spring.datasource.url=jdbc:h2:mem:spring-conn;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
				"spring.datasource.hikari.maximum-pool-size=4");

	@Test
	void generatedRepositoriesJoinSpringTransaction() {
		this.contextRunner.run(context -> {
			DataSource dataSource = context.getBean(DataSource.class);
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			resetTable(jdbc);
			AutumnJdbc autumn = context.getBean(AutumnJdbc.class);
			TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

			assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
				autumn.update(INSERT_ROW, "rollback");
				throw new IllegalStateException("boom");
			})).isInstanceOf(IllegalStateException.class);
			assertThat(countRows(jdbc)).isZero();

			transaction.executeWithoutResult(status -> autumn.update(INSERT_ROW, "commit"));
			assertThat(countRows(jdbc)).isOne();
		});
	}

	@Test
	void connectionSourceIsPerThreadSoSingletonAutumnJdbcCannotCrossConnections() throws Exception {
		this.contextRunner.run(context -> {
			DataSource dataSource = context.getBean(DataSource.class);
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			resetTable(jdbc);

			ConnectionSource connectionSource = context.getBean(ConnectionSource.class);
			assertThat(connectionSource).isInstanceOf(PerThreadConnectionSource.class);

			CyclicBarrier barrier = new CyclicBarrier(2);
			AtomicReference<Connection> first = new AtomicReference<>();
			AtomicReference<Connection> second = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();

			Runnable borrower = () -> {
				try {
					Connection connection = connectionSource.getConnection();
					(Thread.currentThread().getName().equals("conn-a") ? first : second).set(connection);
					barrier.await();
					assertThat(connection.isValid(2)).isTrue();
				}
				catch (Throwable ex) {
					failure.compareAndSet(null, ex);
				}
				finally {
					try {
						connectionSource.close();
					}
					catch (Exception ignored) {
						// 归还连接失败不影响断言
					}
				}
			};

			Thread threadA = new Thread(borrower, "conn-a");
			Thread threadB = new Thread(borrower, "conn-b");
			threadA.start();
			threadB.start();
			threadA.join();
			threadB.join();

			assertThat(failure.get()).isNull();
			// 两个线程各自持有不同连接：共享一个连接源时这里会拿到同一条，且有线程的连接被对方提前归还
			assertThat(first.get()).isNotNull();
			assertThat(second.get()).isNotNull();
			assertThat(first.get()).isNotSameAs(second.get());
		});
	}

	private static void resetTable(JdbcTemplate jdbc) {
		jdbc.execute(DROP_TABLE);
		jdbc.execute(CREATE_TABLE);
	}

	private static long countRows(JdbcTemplate jdbc) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM spring_tx_demo", Long.class);
	}
}
