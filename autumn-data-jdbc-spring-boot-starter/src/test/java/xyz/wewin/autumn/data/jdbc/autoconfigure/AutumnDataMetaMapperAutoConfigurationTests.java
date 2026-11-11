package xyz.wewin.autumn.data.jdbc.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaMapperEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态表 CRUD 自动装配冒烟测试：引擎是 Bean，静态门面已安装；关闭开关后整体退让。
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
}
