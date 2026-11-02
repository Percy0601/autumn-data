package xyz.wewin.autumn.common.jdbc.dialect;

/**
 * H2 内存库方言（主要用于测试，语法上与 PostgreSQL 基本一致）。
 */
public class H2Dialect extends DefaultDialect {

	@Override
	public String productName() {
		return "H2";
	}
}
