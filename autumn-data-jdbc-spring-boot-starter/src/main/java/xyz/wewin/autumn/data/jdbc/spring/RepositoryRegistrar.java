package xyz.wewin.autumn.data.jdbc.spring;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;

import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;

/**
 * 把编译期生成的 {@code <Repo>Impl} 注册为 Spring Bean —— 生成类不标 {@code @Service}，
 * 因此由本注册器在容器启动早期依据 {@code META-INF/autumn-data/repositories} 装配。
 *
 * <p>每个 {@code <Repo>Impl} 只有一个接收核心 {@link AutumnJdbc} 的构造器，注册时开启
 * 构造器自动装配（{@link AbstractBeanDefinition#AUTOWIRE_CONSTRUCTOR}），由 Spring 按类型把
 * 自动配置提供的 {@code AutumnJdbc} 注入进去 —— 生成的代码于是自动加入 {@code @Transactional}。</p>
 *
 * <p>注册表资源可能出现在多个模块（每个用了编译期处理器的模块各写一份），所以用
 * {@link ClassLoader#getResources(String)} 读取全部，避免漏注册别的 jar 里的仓储。</p>
 */
public class RepositoryRegistrar implements BeanDefinitionRegistryPostProcessor {

	private static final String REGISTRY = "META-INF/autumn-data/repositories";

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
		for (String repositoryFqn : readRegistry()) {
			String implName = repositoryFqn + "Impl";
			String beanName = implName;
			if (registry.containsBeanDefinition(beanName)) {
				continue;
			}
			RootBeanDefinition definition = new RootBeanDefinition(implName);
			// 构造注入核心 AutumnJdbc（自动配置提供），生成类不感知 Spring
			definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
			definition.setRole(BeanDefinition.ROLE_APPLICATION);
			registry.registerBeanDefinition(beanName, definition);
		}
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		// 注册定义阶段已处理完，无需再动 BeanFactory
	}

	private Set<String> readRegistry() {
		Set<String> repositories = new HashSet<>();
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		if (classLoader == null) {
			classLoader = RepositoryRegistrar.class.getClassLoader();
		}
		try {
			Enumeration<URL> resources = classLoader.getResources(REGISTRY);
			while (resources.hasMoreElements()) {
				URL url = resources.nextElement();
				try (InputStream in = url.openStream();
						BufferedReader reader = new BufferedReader(
								new InputStreamReader(in, StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						String trimmed = line.trim();
						if (!trimmed.isEmpty()) {
							repositories.add(trimmed);
						}
					}
				}
			}
		}
		catch (IOException ignored) {
			// 注册表缺失：没有编译期生成的仓储，跳过注册即可
		}
		return repositories;
	}
}
