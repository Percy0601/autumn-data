package xyz.wewin.autumn.data.jdbc.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多数据源接管配置（前缀 {@code autumn.data.meta}）。
 *
 * <p>默认<b>关闭</b>：不配置时 {@code DataSourceAutoConfiguration} 照常建单个 {@code DataSource}，
 * 一切行为与之前一致。打开 {@code autumn.data.meta.enabled=true} 后，autumn 侧的
 * {@code SpringMetaDataSource}（继承 Spring 的 {@code AbstractDataSource}）成为应用唯一的
 * {@code DataSource}，Spring Boot 的建池随之让位；默认池仍从 {@code spring.datasource.*} 取参数，
 * 额外池由 {@code autumn.data.meta.pools.<名称>.*} 声明，运行期也可以直接调
 * {@code SpringMetaDataSource#register} 追加。</p>
 */
@ConfigurationProperties(prefix = "autumn.data.meta")
public class AutumnDataMetaProperties {

	/** 是否让 {@code SpringMetaDataSource} 接管应用的数据源（默认关闭）。 */
	private boolean enabled = false;

	/**
	 * 默认池在 {@code MetaDataSource} 注册表里的名字：{@code getConnection()} 无参调用即取它，
	 * 也是 {@code @Transactional} 绑定的那一个池。
	 */
	private String defaultPoolName = "default";

	/**
	 * 空闲池轮询回收间隔；留空则沿用 {@code MetaDataSource} 的内置默认（3 分钟）。
	 * 只在多池（≥ 2 个已实例化的池）时才有实际轮询发生。
	 */
	private Duration sweepInterval;

	/** 额外数据源：键是池名（{@code getConnection(名称)} 用的名字），值是该池的连接参数。 */
	private Map<String, Pool> pools = new LinkedHashMap<>();

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getDefaultPoolName() {
		return this.defaultPoolName;
	}

	public void setDefaultPoolName(String defaultPoolName) {
		this.defaultPoolName = defaultPoolName;
	}

	public Duration getSweepInterval() {
		return this.sweepInterval;
	}

	public void setSweepInterval(Duration sweepInterval) {
		this.sweepInterval = sweepInterval;
	}

	public Map<String, Pool> getPools() {
		return this.pools;
	}

	public void setPools(Map<String, Pool> pools) {
		this.pools = pools;
	}

	/** 单个额外数据源的连接参数（HikariCP 建池；更多调优项用 {@code register(name, supplier)} 自己建）。 */
	public static class Pool {

		/** JDBC URL（必填，否则启动即失败，不留到首次取连接时才报模糊错误）。 */
		private String url;

		/** 数据库用户名。 */
		private String username;

		/** 数据库密码。 */
		private String password;

		/** 驱动类名；留空由 JDBC URL 自动推断。 */
		private String driverClassName;

		public String getUrl() {
			return this.url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public String getUsername() {
			return this.username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return this.password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public String getDriverClassName() {
			return this.driverClassName;
		}

		public void setDriverClassName(String driverClassName) {
			this.driverClassName = driverClassName;
		}
	}
}
