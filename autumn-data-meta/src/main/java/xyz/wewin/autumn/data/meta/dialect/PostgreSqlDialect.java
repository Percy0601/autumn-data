package xyz.wewin.autumn.data.meta.dialect;

/**
 * PostgreSQL 方言：双引号引用、{@code LIMIT rowCount OFFSET offset} 分页。
 */
public class PostgreSqlDialect implements Dialect {

	@Override
	public String productName() {
		return "PostgreSQL";
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
