package xyz.wewin.autumn.data.using.demo.multi;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import xyz.wewin.autumn.data.common.datasource.MetaDataSource;
import xyz.wewin.autumn.data.jdbc.spring.SpringMetaDataSource;

/**
 * 多数据源使用示例（只在 {@code autumn.data.meta.enabled=true} 时装载）。
 *
 * <p>演示三件事：</p>
 * <ol>
 *   <li><b>默认数据源</b>：完全不用写代码。{@code spring.datasource.*} 照旧配，打开接管开关后
 *       {@link SpringMetaDataSource}（Spring 的 {@code AbstractDataSource} 子类）成为应用唯一的
 *       {@code DataSource}，默认池被登记进 {@link MetaDataSource}，生成出来的仓储与
 *       {@code @Transactional} 都跑在它上面；</li>
 *   <li><b>配置声明的额外池</b>：{@code autumn.data.meta.pools.<名称>.*}，首次访问才建池；</li>
 *   <li><b>运行期追加的池</b>：{@link #registerDynamicTenant(String)} —— 按租户/按库动态开池，
 *       工厂方式登记，没访问过的租户一条连接都不占，空闲后由 {@link MetaDataSource} 的轮询回收。</li>
 * </ol>
 *
 * <p>非默认的池不参与外层 {@code @Transactional}（Spring 事务只绑定默认池那一个实例），
 * 需要事务时用 {@link #templateFor(String)} 给该池单独建 {@link JdbcTemplate}，
 * 或自己套一个 {@code DataSourceTransactionManager}。</p>
 */
@Component
@ConditionalOnProperty(prefix = "autumn.data.meta", name = "enabled", havingValue = "true")
public class MultiDataSourceDemo {

	private final SpringMetaDataSource metaDataSource;

	public MultiDataSourceDemo(SpringMetaDataSource metaDataSource) {
		this.metaDataSource = metaDataSource;
	}

	/** 启动即演示「运行期注册」：tenant-live 只在工厂里登记，真正建池发生在第一次访问。 */
	@PostConstruct
	void registerDynamicTenantOnStartup() {
		registerDynamicTenant("tenant-live");
	}

	/** 运行期给某个租户开一个库（同名已存在则跳过，方便反复调用）。 */
	public void registerDynamicTenant(String tenant) {
		if (!MetaDataSource.contains(tenant)) {
			this.metaDataSource.register(tenant, () -> tenantPool(tenant));
		}
	}

	/** 指定池的 {@link JdbcTemplate}：拿的是注册表里的真实池，与默认池互不干扰。 */
	public JdbcTemplate templateFor(String poolName) {
		return new JdbcTemplate(this.metaDataSource.pool(poolName));
	}

	/** 各池的连接概况（池名 → JDBC URL），用来验证「多个池确实各自独立」。 */
	public Map<String, String> poolUrls() {
		Map<String, String> urls = new LinkedHashMap<>();
		for (String name : this.metaDataSource.poolNames()) {
			urls.put(name, urlOf(this.metaDataSource.pool(name)));
		}
		return urls;
	}

	public String defaultPoolName() {
		return this.metaDataSource.defaultPoolName();
	}

	/** 演示用的租户库：内存 H2，库名跟租户名一致，便于肉眼区分与断言。 */
	private static DataSource tenantPool(String tenant) {
		HikariDataSource pool = new HikariDataSource();
		pool.setPoolName(tenant);
		pool.setJdbcUrl("jdbc:h2:mem:demo-" + tenant + ";DB_CLOSE_DELAY=-1");
		pool.setUsername("sa");
		pool.setMaximumPoolSize(2);
		return pool;
	}

	private static String urlOf(DataSource dataSource) {
		if (dataSource instanceof HikariDataSource hikari) {
			return hikari.getJdbcUrl();
		}
		return dataSource.getClass().getSimpleName();
	}
}
