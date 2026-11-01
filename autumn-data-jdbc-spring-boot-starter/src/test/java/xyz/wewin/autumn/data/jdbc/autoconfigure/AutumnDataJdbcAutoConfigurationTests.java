package xyz.wewin.autumn.data.jdbc.autoconfigure;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.wewin.autumn.data.jdbc.dialect.Dialect;
import xyz.wewin.autumn.data.jdbc.dialect.H2Dialect;
import xyz.wewin.autumn.data.jdbc.dialect.MySqlDialect;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.spring.AutumnJdbc;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * starter 冒烟测试：验证「约定大于配置」—— 只引 starter、只配 spring.datasource，
 * DataSource / JdbcClient / JdbcTemplate / Dialect 全部开箱可用；以及各项覆盖开关。
 */
class AutumnDataJdbcAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
				JdbcTemplateAutoConfiguration.class, JdbcClientAutoConfiguration.class,
				AutumnDataJdbcAutoConfiguration.class))
		.withPropertyValues("spring.datasource.url=jdbc:h2:mem:starter-smoke;DB_CLOSE_DELAY=-1");

	@Test
	void conventionOverConfigurationProvidesEverything() {
		this.contextRunner.run(context -> {
			assertThat(context).hasSingleBean(DataSource.class);
			assertThat(context).hasSingleBean(JdbcOperations.class);
			assertThat(context).hasSingleBean(JdbcClient.class);
			assertThat(context).hasSingleBean(Dialect.class);
			assertThat(context.getBean(Dialect.class)).isInstanceOf(H2Dialect.class);
			// 开箱即可执行 SQL（约定：无需自定义代码）
			assertThat(context.getBean(JdbcClient.class).sql("SELECT 1").query(Integer.class).single())
				.isEqualTo(1);
		});
	}

	@Test
	void providesRuntimeFacadeWithDefaultFetchPolicy() {
		this.contextRunner.run(context -> {
			assertThat(context).hasSingleBean(FetchPolicy.class);
			assertThat(context).hasSingleBean(AutumnJdbc.class);
			FetchPolicy fetchPolicy = context.getBean(FetchPolicy.class);
			assertThat(fetchPolicy.associationLimit()).isEqualTo(FetchPolicy.DEFAULT_ASSOCIATION_LIMIT);
			assertThat(fetchPolicy.batchSize()).isEqualTo(FetchPolicy.DEFAULT_BATCH_SIZE);
			assertThat(context.getBean(AutumnJdbc.class).count("SELECT 1")).isEqualTo(1L);
		});
	}

	@Test
	void fetchPolicyIsConfigurable() {
		this.contextRunner.withPropertyValues("autumn.data.jdbc.association-limit=250", "autumn.data.jdbc.batch-size=20")
			.run(context -> {
				FetchPolicy fetchPolicy = context.getBean(FetchPolicy.class);
				assertThat(fetchPolicy.associationLimit()).isEqualTo(250);
				assertThat(fetchPolicy.batchSize()).isEqualTo(20);
			});
	}

	@Test
	void resolvesH2DialectFromDataSourceMetadata() {
		this.contextRunner.run(context -> assertThat(context).hasSingleBean(Dialect.class));
	}

	@Test
	void explicitDialectPropertyWins() {
		this.contextRunner.withPropertyValues("autumn.data.jdbc.dialect=mysql")
			.run(context -> assertThat(context.getBean(Dialect.class)).isInstanceOf(MySqlDialect.class));
	}

	@Test
	void backsOffWhenDisabled() {
		this.contextRunner.withPropertyValues("autumn.data.jdbc.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(Dialect.class)
				.doesNotHaveBean(FetchPolicy.class)
				.doesNotHaveBean(AutumnJdbc.class));
	}

	@Test
	void userDefinedDialectBeanWins() {
		this.contextRunner.withBean(Dialect.class, H2Dialect::new)
			.run(context -> assertThat(context).hasSingleBean(Dialect.class));
	}
}
