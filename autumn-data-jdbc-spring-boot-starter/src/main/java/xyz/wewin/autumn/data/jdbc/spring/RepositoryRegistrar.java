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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * {@link ClassLoader#getResources(String)} 读取全部，避免漏注册别的 jar 里的仓储。
 * 清单里指向不存在实现类的残留行（仓储改名/删除后增量编译留下的）会被跳过并告警；
 * 清单一条都没有时给一条 INFO，把「bean 找不到」和「编译期处理器没跑」联系起来。</p>
 */
public class RepositoryRegistrar implements BeanDefinitionRegistryPostProcessor {

	private static final String REGISTRY = "META-INF/autumn-data/repositories";

	private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryRegistrar.class);

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
		ClassLoader classLoader = classLoader();
		Set<String> repositories = readRegistry(classLoader);
		if (repositories.isEmpty()) {
			/*
			 * 清单一条都没有：要么这个应用确实没有 @AutumnRepository 仓储（正常），
			 * 要么编译期处理器根本没跑（IDE 增量编译 / 处理器 jar 是仓库里的旧版本）。
			 * 后者原本只会在业务 Bean 创建时报 "required a bean of type 'XRepository'
			 * that could not be found"，看不出和编译期有关系，所以这里把结论先说清楚。
			 */
			LOGGER.info("No {} resource on the classpath, so no compile-time generated repository will be "
					+ "registered. Fine if this app has no @AutumnRepository interface; otherwise annotation "
					+ "processing did not run - build the reactor once with 'mvnw install' so that "
					+ "autumn-data-jdbc-compiler resolves from the local repository, then rebuild.", REGISTRY);
			return;
		}
		for (String repositoryFqn : repositories) {
			String implName = repositoryFqn + "Impl";
			String beanName = implName;
			if (registry.containsBeanDefinition(beanName)) {
				continue;
			}
			if (!classPresent(classLoader, implName)) {
				/*
				 * 清单是编译期产物：仓储改名或被删掉后，增量编译留下的旧清单里可能还留着这一行。
				 * 实现类不在就跳过并告警，否则一个残留行会把整个应用按住起不来。
				 */
				LOGGER.warn("Skipping entry {} in {}: implementation class {} is not on the classpath",
						repositoryFqn, REGISTRY, implName);
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

	private static ClassLoader classLoader() {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		return (classLoader != null) ? classLoader : RepositoryRegistrar.class.getClassLoader();
	}

	private static boolean classPresent(ClassLoader classLoader, String className) {
		try {
			Class.forName(className, false, classLoader);
			return true;
		}
		catch (ClassNotFoundException ex) {
			return false;
		}
	}

	private Set<String> readRegistry(ClassLoader classLoader) {
		Set<String> repositories = new HashSet<>();
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
