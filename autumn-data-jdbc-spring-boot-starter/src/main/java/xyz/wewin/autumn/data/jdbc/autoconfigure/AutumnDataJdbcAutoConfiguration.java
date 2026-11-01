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
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcOperations;

import xyz.wewin.autumn.data.jdbc.dialect.DefaultDialect;
import xyz.wewin.autumn.data.jdbc.dialect.Dialect;
import xyz.wewin.autumn.data.jdbc.dialect.DialectResolver;
import xyz.wewin.autumn.data.jdbc.dialect.H2Dialect;
import xyz.wewin.autumn.data.jdbc.dialect.MySqlDialect;
import xyz.wewin.autumn.data.jdbc.dialect.PostgreSqlDialect;
import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.spring.AutumnJdbc;

/**
 * autumn-data-jdbc 自动配置：注册 {@link Dialect}、{@link FetchPolicy} 与生成实现类唯一依赖的
 * {@link AutumnJdbc} 运行期门面。
 *
 * <p>约定（无需任何配置即可使用）：引入本 starter 后，
 * {@code DataSourceAutoConfiguration} 按 {@code spring.datasource.*} 建池，
 * {@code JdbcTemplateAutoConfiguration} 提供 JdbcTemplate，这里按 {@code DataSource} 元数据
 * 自动识别方言，并把编译期生成的 {@code <名称>Impl} 需要的 {@link AutumnJdbc} 装配好 ——
 * 使用方只写接口，不写装配代码。</p>
 *
 * <p>可覆盖：{@code autumn.data.jdbc.dialect} 显式指定方言；
 * {@code autumn.data.jdbc.association-limit}（默认 1000）限制一对多单次加载行数；
 * {@code autumn.data.jdbc.batch-size}（默认 100）决定批量分块；
 * 或直接定义自己的 {@code Dialect}/{@code FetchPolicy}/{@code AutumnJdbc} Bean
 * （{@code @ConditionalOnMissingBean} 退让）；或 {@code autumn.data.jdbc.enabled=false} 整体关闭。</p>
 */
@AutoConfiguration(after = { DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class })
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

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean({ JdbcOperations.class, Dialect.class, FetchPolicy.class })
	public AutumnJdbc autumnJdbc(JdbcOperations jdbcOperations, Dialect dialect, FetchPolicy fetchPolicy) {
		return new AutumnJdbc(jdbcOperations, dialect, fetchPolicy);
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
