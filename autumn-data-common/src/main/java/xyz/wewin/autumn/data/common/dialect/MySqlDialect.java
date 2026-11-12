package xyz.wewin.autumn.data.common.dialect;

/**
 * MySQL 方言。
 *
 * <ul>
 *   <li>标识符引用：反引号 {@code `id`}</li>
 *   <li>自增列：{@code AUTO_INCREMENT}</li>
 *   <li>分页：{@code LIMIT offset, rowCount}</li>
 * </ul>
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

	/** MySQL 的 {@code LIMIT offset, count}：两个占位符是偏移在前。 */
	@Override
	public LimitClause limitClause() {
		return new LimitClause(" LIMIT ?, ?", true);
	}

	@Override
	public boolean supportsReturnGeneratedKeys() {
		return true;
	}
}
