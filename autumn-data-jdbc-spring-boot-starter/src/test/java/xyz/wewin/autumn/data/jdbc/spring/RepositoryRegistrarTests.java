package xyz.wewin.autumn.data.jdbc.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册器自测：清单来自 {@code src/test/resources/META-INF/autumn-data/repositories}，
 * 其中一行有对应实现类（{@code WidgetRepositoryImpl}）、一行是仓储被删除后的残留（{@code GoneRepository}）。
 * 前者注册成构造器自动装配的 Bean 定义，后者跳过而不是让整个应用起不来。
 */
class RepositoryRegistrarTests {

	@Test
	void registersListedRepositoryWithConstructorAutowiring() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		new RepositoryRegistrar().postProcessBeanDefinitionRegistry(beanFactory);

		AbstractBeanDefinition definition = (AbstractBeanDefinition) beanFactory
			.getBeanDefinition("xyz.wewin.autumn.data.jdbc.spring.fixtures.WidgetRepositoryImpl");
		// 生成类只有一个 AutumnJdbc 构造器、也不标 @Service，全靠这个装配模式把它接上
		assertThat(definition.getBeanClassName())
			.isEqualTo("xyz.wewin.autumn.data.jdbc.spring.fixtures.WidgetRepositoryImpl");
		assertThat(definition.getAutowireMode()).isEqualTo(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
		assertThat(definition.getRole()).isEqualTo(BeanDefinition.ROLE_APPLICATION);
	}

	@Test
	void staleRegistryEntryIsSkippedInsteadOfBreakingStartup() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		new RepositoryRegistrar().postProcessBeanDefinitionRegistry(beanFactory);
		assertThat(beanFactory
			.containsBeanDefinition("xyz.wewin.autumn.data.jdbc.spring.fixtures.GoneRepositoryImpl")).isFalse();
	}
}
