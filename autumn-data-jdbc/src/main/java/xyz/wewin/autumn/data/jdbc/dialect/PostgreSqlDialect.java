package xyz.wewin.autumn.data.jdbc.dialect;

/**
 * PostgreSQL 方言。
 *
 * <ul>
 *   <li>标识符引用：双引号 {@code "id"}</li>
 *   <li>自增列：{@code SERIAL} / {@code GENERATED ... AS IDENTITY}</li>
 *   <li>分页：{@code LIMIT rowCount OFFSET offset}</li>
 * </ul>
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
