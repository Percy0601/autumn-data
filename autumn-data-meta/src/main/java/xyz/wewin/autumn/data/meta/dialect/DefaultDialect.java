package xyz.wewin.autumn.data.meta.dialect;

/**
 * ANSI 通用方言（兜底实现），分页采用标准形式 {@code LIMIT ... OFFSET ...}。
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
