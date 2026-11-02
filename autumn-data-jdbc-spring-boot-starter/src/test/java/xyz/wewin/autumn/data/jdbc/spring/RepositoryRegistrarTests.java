package xyz.wewin.autumn.data.jdbc.spring;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

	/**
	 * 清单一条都没有（编译期处理器没跑，或处理器是本地仓库里的旧版本）：不注册任何 Bean，
	 * 同时必须把原因写进日志 —— 这是把启动期的 "required a bean of type 'XRepository'"
	 * 和编译期联系起来的唯一线索，否则使用者只会去加 {@code @Service}。
	 */
	@Test
	void absentRegistryRegistersNothingAndExplainsItself() {
		Logger registrarLogger = (Logger) LoggerFactory.getLogger(RepositoryRegistrar.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		registrarLogger.addAppender(appender);
		Thread current = Thread.currentThread();
		ClassLoader original = current.getContextClassLoader();
		current.setContextClassLoader(new URLClassLoader(new URL[0], null));
		try {
			DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
			new RepositoryRegistrar().postProcessBeanDefinitionRegistry(beanFactory);

			assertThat(beanFactory.getBeanDefinitionCount()).isZero();
			assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
				.anyMatch((message) -> message.contains("META-INF/autumn-data/repositories")
						&& message.contains("annotation processing")
						&& message.contains("mvnw install"));
		}
		finally {
			current.setContextClassLoader(original);
			registrarLogger.detachAppender(appender);
		}
	}
}
