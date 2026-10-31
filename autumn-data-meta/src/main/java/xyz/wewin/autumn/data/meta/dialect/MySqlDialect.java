package xyz.wewin.autumn.data.meta.dialect;

/**
 * MySQL 方言：反引号引用、{@code LIMIT offset, rowCount} 分页。
 */
public class MySqlDialect implements Dialect {

	@Override
	public String productName() {
		return "MySQL";
	}

	@Override
	public String quoteIdentifier(String identifier) {
		return "`" + identifier + "`";
	}

	@Override
	public String limitOffset(long limit, long offset) {
		if (offset <= 0) {
			return " LIMIT " + limit;
		}
		return " LIMIT " + offset + ", " + limit;
	}

	@Override
	public boolean supportsReturnGeneratedKeys() {
		return true;
	}
}
