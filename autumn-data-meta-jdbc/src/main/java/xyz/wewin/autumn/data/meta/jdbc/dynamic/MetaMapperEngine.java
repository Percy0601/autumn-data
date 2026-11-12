package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.util.Map;
import java.util.function.Function;

import javax.sql.DataSource;

import xyz.wewin.autumn.data.common.datasource.MetaDataSource;
import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.dialect.DialectResolver;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.JdbcConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 动态查询引擎：按池名懒建并缓存 {@link MetaMapperClient}。
 *
 * <p>引擎本身是实例对象（静态门面只持有一个 {@code volatile} 引用），因此多数据源、测试时的
 * 多实例、Spring 容器关闭后的替换都不会互相串。默认池由构造参数固定，其余池名按需创建。</p>
 *
 * <p>所有池<b>共享一个 {@link MetaEntityRegistry}</b>：生成的实体类只由表结构决定，与连的是哪个库
 * 无关，同构的表只编译一次。池名 → client 本身也是有界 LRU：低代码/多租户场景下池名可能动态增长，
 * 触顶淘汰冷池，下次访问按需重建（旧 client 上正在执行的操作持有引用，不受影响）。</p>
 *
 * <p>核心模块不依赖 Spring：{@link #standalone(String)} 用 {@link MetaDataSource} 注册表 +
 * JDBC 连接源；Spring 集成层可以传入自己的 client 工厂（例如把连接交给
 * {@code SpringConnectionSource} 以加入事务）。</p>
 */
public final class MetaMapperEngine {

	private final String defaultPoolName;

	private final Function<String, MetaMapperClient> clientFactory;

	private final MetaEntityRegistry entityRegistry;

	private final BoundedCache<String, MetaMapperClient> clients;

	public MetaMapperEngine(String defaultPoolName, Function<String, MetaMapperClient> clientFactory) {
		this(defaultPoolName, clientFactory, new MetaEntityRegistry());
	}

	public MetaMapperEngine(String defaultPoolName, Function<String, MetaMapperClient> clientFactory,
			MetaEntityRegistry entityRegistry) {
		this(defaultPoolName, clientFactory, entityRegistry, BoundedCache.DEFAULT_MAXIMUM_SIZE);
	}

	/** @param poolCacheSize 池名 → client 的缓存上限（默认 {@value BoundedCache#DEFAULT_MAXIMUM_SIZE}） */
	public MetaMapperEngine(String defaultPoolName, Function<String, MetaMapperClient> clientFactory,
			MetaEntityRegistry entityRegistry, int poolCacheSize) {
		if (defaultPoolName == null || defaultPoolName.isBlank()) {
			throw new IllegalArgumentException("defaultPoolName must not be blank");
		}
		if (clientFactory == null) {
			throw new IllegalArgumentException("clientFactory must not be null");
		}
		this.defaultPoolName = defaultPoolName;
		this.clientFactory = clientFactory;
		this.entityRegistry = entityRegistry == null ? new MetaEntityRegistry() : entityRegistry;
		this.clients = BoundedCaches.lru(poolCacheSize);
	}

	/**
	 * 无 Spring 引导：池来自 {@link MetaDataSource} 注册表，连接源按线程持有、操作结束归还。
	 */
	public static MetaMapperEngine standalone(String defaultPoolName) {
		return standalone(defaultPoolName, MetaMapperOptions.defaults());
	}

	public static MetaMapperEngine standalone(String defaultPoolName, MetaMapperOptions options) {
		MetaEntityRegistry entities = entityRegistry(options);
		return new MetaMapperEngine(defaultPoolName, poolName -> {
			DataSource dataSource;
			try {
				dataSource = MetaDataSource.get(poolName);
			}
			catch (IllegalArgumentException ex) {
				throw new MetaSqlException("no data source registered under pool name '" + poolName + "'", ex);
			}
			return createClient(poolName, dataSource, options, entities);
		}, entities, options.tableCacheSize());
	}

	/**
	 * 单数据源引导：默认池直接用传入的 {@link DataSource}，不要求先注册进
	 * {@link MetaDataSource}；其他池名仍然从注册表取。
	 *
	 * <p>这一版不处理跨方法事务：每次操作取连接、结束归还。后续接入 Spring 事务时，
	 * 只需替换 client 工厂里的连接源（{@code SpringConnectionSource}），门面与 SQL 层不用动。</p>
	 */
	public static MetaMapperEngine forDataSource(String defaultPoolName, DataSource dataSource) {
		return forDataSource(defaultPoolName, dataSource, MetaMapperOptions.defaults());
	}

	public static MetaMapperEngine forDataSource(String defaultPoolName, DataSource dataSource,
			MetaMapperOptions options) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource must not be null");
		}
		MetaEntityRegistry entities = entityRegistry(options);
		return new MetaMapperEngine(defaultPoolName, poolName -> {
			DataSource target = poolName.equals(defaultPoolName) ? dataSource : MetaDataSource.get(poolName);
			return createClient(poolName, target, options, entities);
		}, entities, options.tableCacheSize());
	}

	/** 实体类注册表：模板与生成的 .java 都来自同一个工作目录，全引擎共享一份。 */
	private static MetaEntityRegistry entityRegistry(MetaMapperOptions options) {
		return new MetaEntityRegistry(options.directories(), options.generateFiles(), options.tableCacheSize());
	}

	private static MetaMapperClient createClient(String poolName, DataSource dataSource, MetaMapperOptions options,
			MetaEntityRegistry entities) {
		Dialect dialect = DialectResolver.resolve(dataSource);
		ConnectionSource connectionSource = new PerThreadConnectionSource(
				() -> new JdbcConnectionSource(dataSource));
		AutumnJdbc jdbc = new AutumnJdbc(connectionSource, dialect, options.fetchPolicy());
		MetaDirectories directories = options.directories();
		MetaModelStore models = new MetaModelStore(directories.metaClassDirectory(), options.generateFiles(),
				options.tableCacheSize());
		MetaTableRegistry registry = new MetaTableRegistry(dataSource, dialect, options.logicDeleteColumns(),
				models, options.tableCacheSize());
		MetaStatementStore statementStore = new MetaStatementStore(directories.mapperDirectory(),
				options.generateFiles(), dialect, options.tableCacheSize());
		return new MetaMapperClient(poolName, jdbc, connectionSource, registry, new MetaSqlBuilder(dialect),
				statementStore, entities, options.defaultListLimit());
	}

	/** 默认池名。 */
	public String defaultPoolName() {
		return this.defaultPoolName;
	}

	/** 动态实体类注册表（诊断 / 整体刷新用）。 */
	public MetaEntityRegistry entityRegistry() {
		return this.entityRegistry;
	}

	/** 默认池客户端。 */
	public MetaMapperClient defaultClient() {
		return client(this.defaultPoolName);
	}

	/** 指定池客户端；首次访问才创建。 */
	public MetaMapperClient client(String poolName) {
		if (poolName == null || poolName.isBlank()) {
			throw new IllegalArgumentException("poolName must not be blank");
		}
		return this.clients.get(poolName, name -> {
			MetaMapperClient client = this.clientFactory.apply(name);
			if (client == null) {
				throw new MetaSqlException("client factory returned null for pool '" + name + "'");
			}
			return client;
		});
	}

	/** 已实例化的池名 → client（只读快照，诊断用）。 */
	public Map<String, MetaMapperClient> clients() {
		return this.clients.asMap();
	}
}
