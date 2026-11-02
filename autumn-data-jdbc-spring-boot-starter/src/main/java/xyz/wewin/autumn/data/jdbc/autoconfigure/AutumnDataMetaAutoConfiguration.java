package xyz.wewin.autumn.data.jdbc.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;

import xyz.wewin.autumn.data.common.datasource.MetaDataSource;
import xyz.wewin.autumn.data.jdbc.spring.SpringMetaDataSource;

/**
 * 多数据源接管自动配置（{@code autumn.data.meta.enabled=true} 才生效，默认关闭）。
 *
 * <p>开起来后注册唯一的 {@link DataSource} Bean —— {@link SpringMetaDataSource}，并把它排在
 * {@code DataSourceAutoConfiguration} <b>之前</b>：Boot 的建池方法带
 * {@code @ConditionalOnMissingBean(DataSource.class)}，于是自动退让，不会再冒出第二个池。</p>
 *
 * <p>默认池参数完全沿用 {@code spring.datasource.*}（含 {@code spring.datasource.hikari.*} 的调优项），
 * 使用方不需要为此改配置；额外池由 {@code autumn.data.meta.pools.<名称>.*} 声明，或者运行期调
 * {@code SpringMetaDataSource.register(...)} 追加。两类池都只登记工厂：默认池一般会被方言识别
 * 立刻打开（它要读一次 JDBC 元数据，与 Boot 建池时的 {@code initialize} 等价），额外池则等到
 * 第一次访问才真正建池，空闲后由 {@link MetaDataSource} 的轮询任务回收。</p>
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass({ MetaDataSource.class, SpringMetaDataSource.class })
@ConditionalOnProperty(prefix = "autumn.data.meta", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({ AutumnDataMetaProperties.class, DataSourceProperties.class })
public class AutumnDataMetaAutoConfiguration {

	/** Boot 建池让位后，若使用方自己又声明了 DataSource，则以使用方的为准（本配置退让，接管不生效）。 */
	@Bean
	@ConditionalOnMissingBean(DataSource.class)
	public SpringMetaDataSource springMetaDataSource(DataSourceProperties dataSourceProperties,
			AutumnDataMetaProperties metaProperties, Environment environment) {

		String defaultName = metaProperties.getDefaultPoolName();
		Map<String, Supplier<DataSource>> factories = new LinkedHashMap<>();
		metaProperties.getPools().forEach((name, pool) -> factories.put(name,
				() -> buildExtraPool(name, pool, environment)));
		return new SpringMetaDataSource(defaultName,
				() -> buildDefaultPool(dataSourceProperties, defaultName, environment), factories,
				metaProperties.getSweepInterval());
	}

	/** 默认池：走 Spring 自己的 {@link DataSourceProperties}，参数与 {@code spring.datasource.*} 一致。 */
	private static DataSource buildDefaultPool(DataSourceProperties properties, String poolName,
			Environment environment) {
		DataSource dataSource = properties.initializeDataSourceBuilder().build();
		if (dataSource instanceof HikariDataSource hikari && !hikariPoolNameBound(environment)) {
			hikari.setPoolName(poolName);
		}
		bindHikariKnobs(environment, dataSource);
		return dataSource;
	}

	/** 额外池：只接最常用的四个参数，更多调优请改用 {@code register(name, supplier)} 自己建池。 */
	private static DataSource buildExtraPool(String poolName, AutumnDataMetaProperties.Pool pool,
			Environment environment) {
		Assert.hasText(pool.getUrl(),
				"autumn.data.meta.pools." + poolName + ".url must not be blank");
		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setPoolName(poolName);
		dataSource.setJdbcUrl(pool.getUrl());
		if (pool.getDriverClassName() != null && !pool.getDriverClassName().isBlank()) {
			dataSource.setDriverClassName(pool.getDriverClassName());
		}
		if (pool.getUsername() != null) {
			dataSource.setUsername(pool.getUsername());
		}
		if (pool.getPassword() != null) {
			dataSource.setPassword(pool.getPassword());
		}
		// 先吃全局调优，再允许单池覆盖：autumn.data.meta.pools.<名称>.hikari.maximum-pool-size=5
		bindHikariKnobs(environment, dataSource);
		Binder.get(environment).bind("autumn.data.meta.pools." + poolName + ".hikari",
				Bindable.ofInstance(dataSource));
		return dataSource;
	}

	/**
	 * 把 {@code spring.datasource.hikari.*} 绑到 Hikari 池上：接管模式下 Boot 不再建池，
	 * 它那段 {@code @ConfigurationProperties("spring.datasource.hikari")} 也一并没了，这里补回来，
	 * 免得使用方的既有调优项静默失效（额外池同样吃这份全局调优）。
	 */
	private static void bindHikariKnobs(Environment environment, DataSource dataSource) {
		if (dataSource instanceof HikariDataSource) {
			Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(dataSource));
		}
	}

	private static boolean hikariPoolNameBound(Environment environment) {
		return Binder.get(environment).bind("spring.datasource.hikari.pool-name", String.class).isBound();
	}
}
