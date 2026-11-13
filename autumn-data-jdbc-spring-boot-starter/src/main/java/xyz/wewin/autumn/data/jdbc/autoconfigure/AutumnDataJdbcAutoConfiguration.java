package xyz.wewin.autumn.data.jdbc.autoconfigure;

import javax.sql.DataSource;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

import xyz.wewin.autumn.data.common.dialect.DefaultDialect;
import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.dialect.DialectResolver;
import xyz.wewin.autumn.data.common.dialect.H2Dialect;
import xyz.wewin.autumn.data.common.dialect.MySqlDialect;
import xyz.wewin.autumn.data.common.dialect.PostgreSqlDialect;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.runtime.PerThreadConnectionSource;
import xyz.wewin.autumn.data.jdbc.spring.RepositoryRegistrar;
import xyz.wewin.autumn.data.jdbc.spring.SpringConnectionSource;

/**
 * autumn-data-jdbc 自动配置：注册 {@link Dialect}、{@link FetchPolicy}、生成实现类唯一依赖的
 * 运行期门面 {@link AutumnJdbc}，以及把编译期生成的 {@code <Repo>Impl} 注册成 Spring Bean 的
 * {@link RepositoryRegistrar}。
 *
 * <p>约定（无需任何配置即可使用）：引入本 starter 后，{@code DataSourceAutoConfiguration} 按
 * {@code spring.datasource.*} 建池，这里按 {@code DataSource} 元数据自动识别方言，用
 * {@link SpringConnectionSource} 把连接交给 Spring 的 {@link org.springframework.jdbc.datasource.DataSourceUtils}
 * （从而生成代码自动加入 {@code @Transactional}），并把编译期生成的 {@code <名称>Impl} 需要的
 * {@link AutumnJdbc} 装配好 —— 使用方只写接口，不写装配代码。</p>
 *
 * <p>可覆盖：{@code autumn.data.jdbc.dialect} 显式指定方言；
 * {@code autumn.data.jdbc.association-limit}（默认 1000）限制一对多单次加载行数；
 * {@code autumn.data.jdbc.batch-size}（默认 100）决定批量分块；
 * 或直接定义自己的 {@code Dialect}/{@code FetchPolicy}/{@code AutumnJdbc} Bean
 * （{@code @ConditionalOnMissingBean} 退让）；或 {@code autumn.data.jdbc.enabled=false} 整体关闭。</p>
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(Dialect.class)
@ConditionalOnProperty(prefix = "autumn.data.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AutumnDataJdbcProperties.class)
public class AutumnDataJdbcAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnSingleCandidate(DataSource.class)
	public Dialect autumnDialect(DataSource dataSource, AutumnDataJdbcProperties properties) {
		String configured = properties.getDialect();
		if (configured == null || configured.isBlank()) {
			return DialectResolver.resolve(dataSource);
		}
		String choice = configured.trim();
		if (choice.regionMatches(true, 0, "className:", 0, "className:".length())) {
			return instantiateDialect(choice.substring("className:".length()).trim());
		}
		return switch (choice.toLowerCase()) {
			case "mysql" -> new MySqlDialect();
			case "postgresql", "postgres" -> new PostgreSqlDialect();
			case "h2" -> new H2Dialect();
			case "default" -> new DefaultDialect();
			default -> throw new IllegalArgumentException(
					"Unknown autumn.data.jdbc.dialect value '" + configured
							+ "' (expected mysql|postgresql|h2|default|className:<fqcn>)");
		};
	}

	@Bean
	@ConditionalOnMissingBean
	public FetchPolicy autumnFetchPolicy(AutumnDataJdbcProperties properties) {
		return properties.fetchPolicy();
	}

	/**
	 * 运行期连接与事务的抽象：交给 Spring 的 {@code DataSourceUtils}，让生成代码自动加入 {@code @Transactional}。
	 *
	 * <p>外面再套一层 {@link PerThreadConnectionSource}：{@link SpringConnectionSource} 持有“当前连接”字段，
	 * 而容器里注入同一份连接源的 {@link AutumnJdbc} 是单例、被所有仓储实现共用，直接共享会串连接
	 * （甲线程取连接时把乙线程正在用的那条归还掉）。按线程各持一份之后，事务里的连接仍由
	 * {@code DataSourceUtils} 按事务绑定，语义不变；非事务时各自借还，互不干扰。</p>
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnSingleCandidate(DataSource.class)
	public ConnectionSource autumnConnectionSource(DataSource dataSource) {
		return new PerThreadConnectionSource(() -> new SpringConnectionSource(dataSource));
	}

	/** 生成实现类唯一依赖的运行期门面：SQL 执行 + 方言分页 + 取数上限。 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean({ ConnectionSource.class, Dialect.class, FetchPolicy.class })
	public AutumnJdbc autumnJdbc(ConnectionSource connectionSource, Dialect dialect, FetchPolicy fetchPolicy) {
		return new AutumnJdbc(connectionSource, dialect, fetchPolicy);
	}

	/** 把编译期生成的 {@code <Repo>Impl} 注册为 Spring Bean（生成类不标 {@code @Service}）。 */
	@Bean
	public static RepositoryRegistrar repositoryRegistrar() {
		return new RepositoryRegistrar();
	}

	private static Dialect instantiateDialect(String className) {
		try {
			Class<?> type = Class.forName(className);
			if (!Dialect.class.isAssignableFrom(type)) {
				throw new IllegalArgumentException(className + " does not implement " + Dialect.class.getName());
			}
			return (Dialect) type.getDeclaredConstructor().newInstance();
		}
		catch (ReflectiveOperationException ex) {
			throw new BeanCreationException("Failed to instantiate custom Dialect '" + className + "'", ex);
		}
	}
}
