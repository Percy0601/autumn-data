package xyz.wewin.autumn.data.jdbc.autoconfigure;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperEngine;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperOptions;

/**
 * 动态表 CRUD 自动配置：把 {@link MetaMapperEngine} 装进 {@link MetaMapper} 静态门面。
 *
 * <p><b>当前版本不接入 Spring 事务</b>：引擎用 {@code JdbcConnectionSource} 按线程取连接、
 * 每次操作结束归还，因此单个方法内部的语句是自提交的；跨方法的 {@code @Transactional} 暂不生效。
 * 后续接入事务时只需替换这里的 client 工厂（连接源换成 {@code SpringConnectionSource}），
 * 门面与 SQL 层不动。</p>
 */
@AutoConfiguration(after = AutumnDataJdbcAutoConfiguration.class)
@ConditionalOnClass({ MetaMapper.class, DataSource.class })
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "autumn.data.meta.mapper", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AutumnDataMetaMapperProperties.class)
public class AutumnDataMetaMapperAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public MetaMapperEngine metaMapperEngine(DataSource dataSource, FetchPolicy fetchPolicy,
			AutumnDataMetaMapperProperties properties) {
		MetaMapperOptions options = new MetaMapperOptions(properties.getDefaultListLimit(), fetchPolicy,
				properties.getLogicDeleteColumns(), properties.getMetaDirectory(), properties.isGenerateFiles(),
				properties.getTableCacheSize());
		MetaMapperEngine engine = MetaMapperEngine.forDataSource(properties.getDefaultPoolName(), dataSource, options);
		MetaMapper.install(engine);
		return engine;
	}
}
