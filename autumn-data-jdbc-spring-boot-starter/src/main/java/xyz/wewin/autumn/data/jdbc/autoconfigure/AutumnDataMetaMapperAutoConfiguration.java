package xyz.wewin.autumn.data.jdbc.autoconfigure;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.jdbc.spring.SpringConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperEngine;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperOptions;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityStrategy;

/**
 * 动态表 CRUD 自动配置：把 {@link MetaMapperEngine} 装进 {@link MetaMapper} 静态门面。
 *
 * <p><b>连接池与事务都沿用 Spring Boot</b>：引擎直接吃容器里那个 {@link DataSource} Bean
 * （也就是 {@code spring.datasource.*} 配出来、{@code DataSourceAutoConfiguration} 建的池，
 * 默认 HikariCP），不再自建连接池；连接源用 {@link SpringConnectionSource}，因此</p>
 * <ul>
 *   <li>连接的创建/回收、池大小、超时等调优全部由该池负责，引擎不建池、连接用完即归还；</li>
 *   <li>已有 {@code @Transactional} 时取到的是事务绑定的同一条连接，动态层的语句自动加入事务，
 *       回滚由 spring-tx 决定；</li>
 *   <li>没有事务时按操作借还连接，与原生 JDBC 行为一致。</li>
 * </ul>
 *
 * <p>想换连接源（例如自己包一层连接来源）就定义自己的 {@code MetaMapperEngine} Bean，
 * {@code @ConditionalOnMissingBean} 直接退让。</p>
 *
 * <p><b>为什么是 {@code single candidate}</b>：引擎按<b>一个</b>默认池装配（连接、方言、元数据都来自它）。
 * 容器里有多个 {@link DataSource} 且没有 {@code @Primary} 时无法判断该用哪个，这里干脆退让，
 * 让使用方自己定义 {@code MetaMapperEngine}（用 {@code MetaMapperEngine.forDataSource(...)} 选池）。
 * 多数据源场景下的其它池仍可用 {@code MetaMapper.of(pool)} 访问。</p>
 *
 * <p>顺序排在 {@link AutumnDataMetaAutoConfiguration} 之后：接管模式下容器里的 {@link DataSource}
 * 是那个 {@code SpringMetaDataSource}（默认池参数来自 {@code spring.datasource.*}），
 * 本配置要等它注册完再判断，才能拿到同一个实例。</p>
 */
@AutoConfiguration(after = { AutumnDataJdbcAutoConfiguration.class, AutumnDataMetaAutoConfiguration.class })
@ConditionalOnClass({ MetaMapper.class, DataSource.class })
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "autumn.data.meta.mapper", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AutumnDataMetaMapperProperties.class)
public class AutumnDataMetaMapperAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public MetaMapperEngine metaMapperEngine(DataSource dataSource, FetchPolicy fetchPolicy,
			AutumnDataMetaMapperProperties properties) {
		MetaMapperOptions options = new MetaMapperOptions(properties.getDefaultListLimit(), fetchPolicy,
				properties.getLogicDeleteColumns(), properties.getMetaDirectory(), properties.isGenerateFiles(),
				properties.getTableCacheSize(),
				MetaEntityStrategy.resolve(properties.getEntityCodegen()));
		MetaMapperEngine engine = MetaMapperEngine.forDataSource(properties.getDefaultPoolName(), dataSource, options,
				SpringConnectionSource::new);
		MetaMapper.install(engine);
		return engine;
	}
}
