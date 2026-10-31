package xyz.wewin.autumn.data.jdbc.autoconfigure;

import javax.sql.DataSource;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

import xyz.wewin.autumn.data.jdbc.dialect.DefaultDialect;
import xyz.wewin.autumn.data.jdbc.dialect.Dialect;
import xyz.wewin.autumn.data.jdbc.dialect.DialectResolver;
import xyz.wewin.autumn.data.jdbc.dialect.H2Dialect;
import xyz.wewin.autumn.data.jdbc.dialect.MySqlDialect;
import xyz.wewin.autumn.data.jdbc.dialect.PostgreSqlDialect;

/**
 * autumn-data-jdbc 自动配置：注册 {@link Dialect} Bean。
 *
 * <p>约定（无需任何配置即可使用）：引入本 starter 后，
 * {@code DataSourceAutoConfiguration} 按 {@code spring.datasource.*} 建池，
 * {@code JdbcTemplateAutoConfiguration} / {@code JdbcClientAutoConfiguration} 提供 JdbcClient，
 * 这里再按 {@code DataSource} 元数据自动识别并注册 {@link Dialect}。</p>
 *
 * <p>可覆盖：{@code autumn.data.jdbc.dialect} 显式指定；
 * 或直接定义自己的 {@code Dialect} Bean（{@code @ConditionalOnMissingBean} 退让）；
 * 或 {@code autumn.data.jdbc.enabled=false} 整体关闭。</p>
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
