package xyz.wewin.autumn.data.jdbc.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
}
