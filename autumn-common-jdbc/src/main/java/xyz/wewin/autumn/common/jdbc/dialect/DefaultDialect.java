package xyz.wewin.autumn.common.jdbc.dialect;

/**
 * ANSI 通用方言（兜底实现）。
 *
 * <p>适用于未识别的数据库或单元测试；分页采用 {@code LIMIT ... OFFSET ...} 标准形式。</p>
 */
public class DefaultDialect implements Dialect {

	@Override
	public String productName() {
		return "ANSI";
	}

	@Override
	public String quoteIdentifier(String identifier) {
		return "\"" + identifier + "\"";
	}

	@Override
	public String limitOffset(long limit, long offset) {
		if (offset <= 0) {
			return " LIMIT " + limit;
		}
		return " LIMIT " + limit + " OFFSET " + offset;
	}

	@Override
	public boolean supportsReturnGeneratedKeys() {
		return true;
	}
}
