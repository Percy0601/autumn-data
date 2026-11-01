package xyz.wewin.autumn.data.jdbc.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import xyz.wewin.autumn.data.jdbc.runtime.FetchPolicy;

/**
 * autumn-data-jdbc 的 Spring Boot 配置项（前缀 {@code autumn.data.jdbc}）。
 */
@ConfigurationProperties(prefix = "autumn.data.jdbc")
public class AutumnDataJdbcProperties {

	/** 是否启用 autumn-data-jdbc 自动配置。 */
	private boolean enabled = true;

	/**
	 * 方言选择：{@code mysql} / {@code postgresql} / {@code h2} / {@code default}，
	 * 或以 {@code className:} 前缀指定自定义 {@code Dialect} 全限定类名
	 * （如 {@code className:com.acme.MyOracleDialect}）。
	 * 未指定时按 {@code DataSource} 元数据的产品名自动识别。
	 */
	private String dialect;

	/**
	 * 一次加载一对多集合时，子表查询最多取回多少行。
	 * 默认 1000：没有上限的一次 {@code @OneToMany} 加载可以把整张子表拖进内存。
	 */
	private int associationLimit = FetchPolicy.DEFAULT_ASSOCIATION_LIMIT;

	/** JDBC 批处理分块大小：一条批量语句打包多少行（{@code saveAll} / 批量删除用）。 */
	private int batchSize = FetchPolicy.DEFAULT_BATCH_SIZE;

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getDialect() {
		return this.dialect;
	}

	public void setDialect(String dialect) {
		this.dialect = dialect;
	}

	public int getAssociationLimit() {
		return this.associationLimit;
	}

	public void setAssociationLimit(int associationLimit) {
		this.associationLimit = associationLimit;
	}

	public int getBatchSize() {
		return this.batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	/** 生成代码运行期读取的取数策略。 */
	public FetchPolicy fetchPolicy() {
		return new FetchPolicy(this.associationLimit, this.batchSize);
	}
}
