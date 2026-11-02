package xyz.wewin.autumn.data.jdbc.spring;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.util.Assert;

import xyz.wewin.autumn.data.common.datasource.MetaDataSource;

/**
 * Spring 侧的多数据源入口：继承 Spring 的 {@link AbstractDataSource}，把 {@code javax.sql.DataSource}
 * 的生命周期与事务语义交给 Spring，底下真正的注册表、懒加载与空闲回收全是
 * {@link MetaDataSource}。
 *
 * <p><b>接管方式</b>：本类是应用里唯一的 {@code DataSource} Bean（由
 * {@code AutumnDataMetaAutoConfiguration} 在 {@code autumn.data.meta.enabled=true} 时注册，
 * Spring Boot 自己的建池随之退让）。因此 {@code Dialect} 识别、{@code SpringConnectionSource}、
 * {@code DataSourceTransactionManager}、{@code spring.sql.init} 拿到的都是本实例，
 * {@code DataSourceUtils} 按实例键绑定事务连接，生成代码依旧自动加入 {@code @Transactional}。</p>
 *
 * <p><b>两条取连接路径</b>：</p>
 * <ol>
 *   <li>{@link #getConnection()}（含 Spring 与事务管理器走的那条）→ 默认池
 *       {@code autumn.data.meta.default-pool-name}，参数来自 {@code spring.datasource.*}；</li>
 *   <li>{@link #connection(String)} → 按池名取，池名由配置 {@code autumn.data.meta.pools.<名称>.*}
 *       或运行期 {@link #register(String, Supplier)} 登记，<b>首次取连接才真正建池</b>。</li>
 * </ol>
 *
 * <p><b>事务边界</b>：Spring 事务只绑定默认池那一个实例。额外池不参与外层 {@code @Transactional}
 * —— 需要它就 {@link #pool(String)} 取到真实池，自建一个 {@code DataSourceTransactionManager}；
 * 跨池分布式事务不在本类职责内。</p>
 *
 * <p><b>生命周期</b>：{@link #destroy()} 收掉注册表里的全部池并撤销轮询任务；
 * 全局线程池（{@code GlobalScheduler}）留给应用自己收尾，别的组件可能还在用。</p>
 */
public class SpringMetaDataSource extends AbstractDataSource implements InitializingBean, DisposableBean {

	private final String defaultPoolName;

	private final Supplier<DataSource> defaultPoolFactory;

	private final Map<String, Supplier<DataSource>> poolFactories;

	private final Duration sweepInterval;

	/** 经本类登记过的池名（含默认池），用于日志与诊断；关闭时注册表整体收掉。 */
	private final Set<String> registeredNames = new LinkedHashSet<>();

	/**
	 * @param defaultPoolName   默认池名，无参 {@link #getConnection()} 走它
	 * @param defaultPoolFactory 默认池创建工厂（懒建：第一次取连接时才建 Hikari 池）
	 * @param poolFactories      额外池名 -&gt; 创建工厂，可为空
	 * @param sweepInterval      空闲池轮询间隔；{@code null} 表示沿用 {@link MetaDataSource} 的内置默认
	 */
	public SpringMetaDataSource(String defaultPoolName, Supplier<DataSource> defaultPoolFactory,
			Map<String, Supplier<DataSource>> poolFactories, Duration sweepInterval) {
		Assert.hasText(defaultPoolName, "defaultPoolName must not be blank");
		Assert.notNull(defaultPoolFactory, "defaultPoolFactory must not be null");
		this.defaultPoolName = defaultPoolName;
		this.defaultPoolFactory = defaultPoolFactory;
		this.poolFactories = poolFactories;
		this.sweepInterval = sweepInterval;
	}

	@Override
	public void afterPropertiesSet() {
		register(this.defaultPoolName, this.defaultPoolFactory);
		this.poolFactories.forEach((name, factory) -> register(name, factory));
		if (this.sweepInterval != null) {
			MetaDataSource.setSweepInterval(this.sweepInterval.toSeconds());
		}
		this.logger.info("MetaDataSource took over " + this.registeredNames + " (default pool '"
				+ this.defaultPoolName + "')");
	}

	/** 默认池的连接：Spring、事务管理器与生成的仓储实现走的就是这一条。 */
	@Override
	public Connection getConnection() throws SQLException {
		return MetaDataSource.getConnection(this.defaultPoolName);
	}

	/** 用户名/密码登录同样落到默认池（Spring 的数据源探测与某些驱动包装会调它）。 */
	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return MetaDataSource.get(this.defaultPoolName).getConnection(username, password);
	}

	// ------------------------------------------------------------------
	// 多数据源 API
	// ------------------------------------------------------------------

	/**
	 * 按池名取连接。名字从未登记时抛 {@link IllegalArgumentException}；
	 * 以工厂方式登记过的池在这里才真正创建。
	 */
	public Connection connection(String poolName) throws SQLException {
		return MetaDataSource.getConnection(poolName);
	}

	/**
	 * 取某个池的原始 {@link DataSource}（已建则直接给，未建则触发懒加载）。
	 * 想给单个池配事务管理器、或让它跑自己的迁移脚本时用这里，别自己去 new 池。
	 */
	public DataSource pool(String poolName) {
		return MetaDataSource.get(poolName);
	}

	public String defaultPoolName() {
		return this.defaultPoolName;
	}

	/** 当前已实例化的池名集合（懒加载未命中的名字不在其中，判断登记与否用 {@link MetaDataSource#contains}）。 */
	public Set<String> poolNames() {
		return MetaDataSource.names();
	}

	/**
	 * 运行期登记一个已建好的池（「未来的多数据源」入口）。同名已存在时抛 {@link IllegalStateException}。
	 */
	public void register(String poolName, DataSource dataSource) {
		assertUsableName(poolName);
		MetaDataSource.register(poolName, dataSource);
		this.registeredNames.add(poolName);
	}

	/**
	 * 运行期登记一个懒加载池：只登记工厂，第一次 {@link #connection(String)} / {@link #pool(String)}
	 * 才真正建池。适合按租户动态开库的场景——没访问过的租户不占连接。
	 */
	public void register(String poolName, Supplier<DataSource> poolFactory) {
		assertUsableName(poolName);
		MetaDataSource.register(poolName, poolFactory);
		this.registeredNames.add(poolName);
	}

	/** 移除并关闭指定池（同时清掉它的懒加载工厂）；返回被移除的池，不存在返回 {@code null}。 */
	public DataSource unregister(String poolName) {
		this.registeredNames.remove(poolName);
		return MetaDataSource.unregister(poolName);
	}

	@Override
	public void destroy() {
		// 接管模式下注册表里的池都由本类登记，容器停止时整体收掉（含撤销轮询任务）
		MetaDataSource.shutdown();
	}

	private void assertUsableName(String poolName) {
		Assert.hasText(poolName, "poolName must not be blank");
	}
}
