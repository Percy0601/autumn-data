package xyz.wewin.autumn.data.jdbc.autoconfigure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.wewin.autumn.data.common.datasource.MetaDataSource;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.spring.SpringMetaDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多数据源接管（{@code autumn.data.meta.enabled=true}）的行为验证：
 * Boot 建池让位、默认池参数不变、额外池懒建且互相隔离、Spring 事务依旧绑定同一个连接、
 * 容器停止后注册表清空。
 *
 * <p>{@link MetaDataSource} 是进程级静态注册表，用例之间必须彻底清理，
 * 因此每个用例用各自独立的 H2 库名并在 {@code @AfterEach} 关掉全部池。</p>
 */
class AutumnDataMetaAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AutumnDataMetaAutoConfiguration.class,
				DataSourceAutoConfiguration.class, AutumnDataJdbcAutoConfiguration.class))
		.withPropertyValues("autumn.data.meta.enabled=true",
				"spring.datasource.url=jdbc:h2:mem:meta-takeover;DB_CLOSE_DELAY=-1",
				"spring.datasource.username=sa", "spring.datasource.password=");

	@AfterEach
	void releaseRegistry() {
		MetaDataSource.shutdown();
		MetaDataSource.setSweepInterval(180);
	}

	@Test
	void staysOutOfTheWayWhenDisabled() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AutumnDataMetaAutoConfiguration.class,
					DataSourceAutoConfiguration.class, AutumnDataJdbcAutoConfiguration.class))
			.withPropertyValues("spring.datasource.url=jdbc:h2:mem:meta-off;DB_CLOSE_DELAY=-1")
			.run(context -> {
				assertThat(context).hasSingleBean(DataSource.class);
				// 默认关闭：Boot 自己建池，MetaDataSource 一个池都没登记，行为与接管前完全一致
				assertThat(context.getBean(DataSource.class)).isNotInstanceOf(SpringMetaDataSource.class);
				assertThat(MetaDataSource.size()).isZero();
				assertThat(context.getBean(AutumnJdbc.class).count("SELECT 1")).isEqualTo(1L);
			});
	}

	@Test
	void takeoverMakesSpringMetaDataSourceTheOnlyDataSource() {
		this.contextRunner.run(context -> {
			assertThat(context).hasSingleBean(DataSource.class);
			// 本配置排在 DataSourceAutoConfiguration 之前，Boot 的 @ConditionalOnMissingBean 退让
			assertThat(context.getBean(DataSource.class)).isInstanceOf(SpringMetaDataSource.class);
			assertThat(MetaDataSource.contains("default")).isTrue();
			// 方言识别要读 JDBC 元数据，默认池在容器启动时就被打开（与 Boot 的 initMethod=initialize 行为一致）；
			// 真正等到首次访问才建的是额外池
			assertThat(MetaDataSource.size()).isEqualTo(1);
			assertThat(context.getBean(AutumnJdbc.class).count("SELECT 1")).isEqualTo(1L);
			assertThat(MetaDataSource.size()).isEqualTo(1);
		});
	}

	@Test
	void springDatasourceAndHikariKnobsStillDriveTheDefaultPool() {
		this.contextRunner
			.withPropertyValues("spring.datasource.url=jdbc:h2:mem:meta-knob;DB_CLOSE_DELAY=-1",
					"spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.pool-name=my-default")
			.run(context -> {
				update(context.getBean(DataSource.class), "SELECT 1");
				HikariDataSource defaultPool = (HikariDataSource) MetaDataSource.get("default");
				// 接管后 Boot 不再建池，但 spring.datasource.hikari.* 仍生效（显式给的池名不被覆盖）
				assertThat(defaultPool.getMaximumPoolSize()).isEqualTo(3);
				assertThat(defaultPool.getPoolName()).isEqualTo("my-default");
			});
	}

	@Test
	void namedPoolsStayLazyAndDoNotShareData() {
		this.contextRunner
			.withPropertyValues("spring.datasource.url=jdbc:h2:mem:meta-main;DB_CLOSE_DELAY=-1",
					"autumn.data.meta.pools.tenant-a.url=jdbc:h2:mem:meta-tenant-a;DB_CLOSE_DELAY=-1",
					"autumn.data.meta.pools.tenant-a.username=sa",
					"autumn.data.meta.pools.tenant-b.url=jdbc:h2:mem:meta-tenant-b;DB_CLOSE_DELAY=-1")
			.run(context -> {
				SpringMetaDataSource meta = (SpringMetaDataSource) context.getBean(DataSource.class);
				update(meta, "CREATE TABLE marker (id INT)");
				// 只碰了默认池：两个租户登记了工厂但都没建池
				assertThat(meta.poolNames()).containsExactly("default");
				assertThat(MetaDataSource.contains("tenant-a")).isTrue();

				DataSource tenantA = meta.pool("tenant-a");
				assertThat(meta.poolNames()).containsExactlyInAnyOrder("default", "tenant-a");
				assertThat(tenantA).isNotSameAs(meta.pool("default"));
				// 真隔离：默认池里建的表在 tenant-a 不存在，反之亦然
				assertThat(tableExists(tenantA, "MARKER")).isFalse();
				assertThat(tableExists(meta, "MARKER")).isTrue();
				update(tenantA, "CREATE TABLE marker (id INT)");
				assertThat(queryInt(tenantA, "SELECT COUNT(*) FROM marker")).isZero();
				assertThat(queryInt(meta, "SELECT COUNT(*) FROM marker")).isZero();
				assertThat(MetaDataSource.contains("tenant-b")).isTrue();
				assertThat(meta.poolNames()).doesNotContain("tenant-b");
			});
	}

	@Test
	void runtimeRegistrationAddsATenantPoolWithoutRestartingAnything() {
		this.contextRunner.withPropertyValues("spring.datasource.url=jdbc:h2:mem:meta-runtime;DB_CLOSE_DELAY=-1")
			.run(context -> {
				SpringMetaDataSource meta = (SpringMetaDataSource) context.getBean(DataSource.class);
				update(meta, "SELECT 1");
				HikariDataSource tenantPool = new HikariDataSource();
				tenantPool.setJdbcUrl("jdbc:h2:mem:meta-late;DB_CLOSE_DELAY=-1");
				tenantPool.setUsername("sa");
				tenantPool.setPoolName("late-tenant");

				meta.register("late-tenant", tenantPool);

				assertThat(meta.poolNames()).containsExactlyInAnyOrder("default", "late-tenant");
				assertThat(MetaDataSource.get("late-tenant")).isSameAs(tenantPool);
				assertThat(queryInt(meta, "SELECT 1")).isEqualTo(1);
				assertThat(queryInt(tenantPool, "SELECT 1")).isEqualTo(1);
				// 默认池仍是谁的事务目标
				assertThat(MetaDataSource.get("default")).isNotSameAs(tenantPool);
			});
	}

	@Test
	void springTransactionsBindOneConnectionForTheWholeCallStack() {
		this.contextRunner.withPropertyValues("spring.datasource.url=jdbc:h2:mem:meta-tx;DB_CLOSE_DELAY=-1")
			.run(context -> {
				DataSource dataSource = context.getBean(DataSource.class);
				AutumnJdbc autumn = context.getBean(AutumnJdbc.class);
				update(dataSource, "CREATE TABLE tx_row (id INT)");
				TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
				AtomicInteger inserted = new AtomicInteger();

				tx.execute(status -> {
					Connection bound = DataSourceUtils.getConnection(dataSource);
					// 同一个事务里再取还是那一条连接
					assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(bound);
					update(bound, "INSERT INTO tx_row VALUES (1)");
					inserted.incrementAndGet();
					// 生成代码用的门面走 DataSourceUtils，因此看得见同事务刚写的行
					assertThat(autumn.count("SELECT COUNT(*) FROM tx_row")).isEqualTo(1L);
					return null;
				});
				assertThat(autumn.count("SELECT COUNT(*) FROM tx_row")).isEqualTo(1L);

				assertThatThrownBy(() -> tx.execute(status -> {
					update(DataSourceUtils.getConnection(dataSource), "INSERT INTO tx_row VALUES (2)");
					throw new IllegalStateException("boom");
				})).isInstanceOf(IllegalStateException.class).hasMessage("boom");

				// 回滚只影响第二条，第一条已提交留下
				assertThat(autumn.count("SELECT COUNT(*) FROM tx_row")).isEqualTo(1L);
				assertThat(inserted.get()).isEqualTo(1);
			});
	}

	@Test
	void sweepIntervalComesFromConfiguration() {
		this.contextRunner.withPropertyValues("autumn.data.meta.sweep-interval=5s")
			.run(context -> assertThat(MetaDataSource.getSweepInterval()).isEqualTo(5));
	}

	@Test
	void contextCloseShutsTheWholeRegistryDown() {
		this.contextRunner
			.withPropertyValues("spring.datasource.url=jdbc:h2:mem:meta-close;DB_CLOSE_DELAY=-1",
					"autumn.data.meta.pools.tenant-a.url=jdbc:h2:mem:meta-close-tenant;DB_CLOSE_DELAY=-1")
			.run(context -> {
				SpringMetaDataSource meta = (SpringMetaDataSource) context.getBean(DataSource.class);
				update(meta, "SELECT 1");
				meta.pool("tenant-a");
				assertThat(MetaDataSource.size()).isEqualTo(2);
			});
		// runner 结束即关闭上下文，接管模式下登记的池（含额外池）随 dispose 一起收掉
		assertThat(MetaDataSource.size()).isZero();
		assertThat(MetaDataSource.names()).isEmpty();
	}

	// ------------------------------------------------------------------
	// 小工具：在指定池上执行语句
	// ------------------------------------------------------------------

	private static void update(DataSource dataSource, String sql) {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	/** 直接在不关闭的连接（事务内的那条绑定连接、或已建好的池实例）上执行。 */
	private static void update(Connection connection, String sql) {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static int queryInt(DataSource dataSource, String sql) {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(sql)) {
			return rs.next() ? rs.getInt(1) : -1;
		}
		catch (Exception ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	private static boolean tableExists(DataSource dataSource, String table) {
		try (Connection connection = dataSource.getConnection();
				ResultSet rs = connection.getMetaData().getTables(null, null, table, null)) {
			return rs.next();
		}
		catch (Exception ex) {
			throw new IllegalStateException(table, ex);
		}
	}
}
