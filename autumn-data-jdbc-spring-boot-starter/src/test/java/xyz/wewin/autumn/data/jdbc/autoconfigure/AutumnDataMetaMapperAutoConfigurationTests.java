package xyz.wewin.autumn.data.jdbc.autoconfigure;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.spring.SpringMetaDataSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperEngine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动态表 CRUD 自动装配冒烟测试：引擎是 Bean，静态门面已安装；关闭开关后整体退让；
 * 以及 Spring Boot 集成下的两个边界——接管多数据源模式、多个 DataSource 无法自动选池。
 */
class AutumnDataMetaMapperAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
				AutumnDataJdbcAutoConfiguration.class, AutumnDataMetaMapperAutoConfiguration.class))
		.withPropertyValues("spring.datasource.url=jdbc:h2:mem:meta-mapper-smoke;DB_CLOSE_DELAY=-1");

	@Test
	void installsEngineAndStaticFacade() {
		this.contextRunner.run(context -> {
			assertThat(context).hasSingleBean(MetaMapperEngine.class);
			assertThat(context.getBean(MetaMapperEngine.class).defaultPoolName()).isEqualTo("default");
			assertThat(MetaMapper.defaultClient().poolName()).isEqualTo("default");
		});
	}

	@Test
	void backsOffWhenDisabled() {
		this.contextRunner.withPropertyValues("autumn.data.meta.mapper.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(MetaMapperEngine.class));
	}

	/**
	 * 多个 {@link DataSource} 且没有 {@code @Primary}：默认池无从判断，自动配置退让而不是启动失败，
	 * 使用方自己定义 {@code MetaMapperEngine} 选池。
	 */
	@Test
	void backsOffWhenSeveralDataSourcesCannotBeDisambiguated() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(AutumnDataMetaMapperAutoConfiguration.class))
			.withBean(FetchPolicy.class, FetchPolicy::defaults)
			.withBean("firstDataSource", DataSource.class, AutumnDataMetaMapperAutoConfigurationTests::h2DataSource)
			.withBean("secondDataSource", DataSource.class, AutumnDataMetaMapperAutoConfigurationTests::h2DataSource)
			.withPropertyValues("autumn.data.meta.mapper.meta-directory=")
			.run(context -> assertThat(context).doesNotHaveBean(MetaMapperEngine.class));
	}

	/**
	 * 接管模式（{@code autumn.data.meta.enabled=true}）：容器里唯一的 DataSource 是
	 * {@link SpringMetaDataSource}，动态层仍能装配，并且写入照样加入它上面的事务。
	 */
	@Test
	void installsEngineOnTopOfSpringMetaDataSourceTakeOver() {
		this.contextRunner
			.withConfiguration(AutoConfigurations.of(AutumnDataMetaAutoConfiguration.class))
			.withPropertyValues("autumn.data.meta.enabled=true",
					"spring.datasource.url=jdbc:h2:mem:meta-takeover;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
					"autumn.data.meta.mapper.meta-directory=")
			.run(context -> {
				assertThat(context).hasSingleBean(DataSource.class);
				DataSource dataSource = context.getBean(DataSource.class);
				assertThat(dataSource).isInstanceOf(SpringMetaDataSource.class);
				assertThat(context).hasSingleBean(MetaMapperEngine.class);

				JdbcTemplate jdbc = new JdbcTemplate(dataSource);
				jdbc.execute("DROP TABLE IF EXISTS takeover_demo");
				jdbc.execute("CREATE TABLE takeover_demo (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64))");

				TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
				assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
					MetaClass row = MetaMapper.newEntity("takeover_demo");
					row.set("name", "rollback");
					MetaMapper.save(row);
					throw new IllegalStateException("boom");
				})).isInstanceOf(IllegalStateException.class);

				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM takeover_demo", Long.class)).isZero();
			});
	}

	private static DataSource h2DataSource() {
		JdbcDataSource dataSource = new JdbcDataSource();
		dataSource.setURL("jdbc:h2:mem:meta-mapper-extra;DB_CLOSE_DELAY=-1");
		dataSource.setUser("sa");
		return dataSource;
	}
}
