package xyz.wewin.autumn.data.meta.dialect;

/**
 * 数据库方言抽象（meta 模块）。
 *
 * <p>与 {@code autumn-data-jdbc} 中的方言职责一致：封装不同数据库之间的 SQL 语法差异，
 * 至少覆盖 MySQL 与 PostgreSQL，可扩展。当前 meta 引擎主要用到标识符引用与分页片段。</p>
 */
public interface Dialect {

	/** 产品名，例如 {@code MySQL} / {@code PostgreSQL} / {@code H2}。 */
	String productName();

	/** 对标识符（表名 / 列名）进行安全引用。 */
	String quoteIdentifier(String identifier);

	/**
	 * 生成分页子句。
	 *
	 * @param limit  最大返回行数（&gt; 0）
	 * @param offset 跳过行数（&lt;= 0 时退化为仅 {@code LIMIT}）
	 * @return 可直接拼接在 SELECT 语句后的分页片段（含前导空格）
	 */
	String limitOffset(long limit, long offset);

	/** 是否支持通过 {@code Statement.RETURN_GENERATED_KEYS} 取回自增主键。 */
	boolean supportsReturnGeneratedKeys();
}
