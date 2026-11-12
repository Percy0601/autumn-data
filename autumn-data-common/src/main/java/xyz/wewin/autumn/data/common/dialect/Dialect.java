package xyz.wewin.autumn.data.common.dialect;

/**
 * 数据库方言抽象（公共 JDBC 底座）。
 *
 * <p>供 {@code autumn-data-jdbc} 与 {@code autumn-data-meta-jdbc} 等数据访问模块复用，
 * 封装不同数据库之间 SQL 语法层的差异，目前至少覆盖 MySQL 与 PostgreSQL，
 * 并可按需扩展（Oracle / SQL Server / H2 等）。</p>
 *
 * <p>设计原则：方言只负责“语法层差异”，不持有任何连接；连接由调用方通过
 * {@link DialectResolver} 根据 {@code DataSource} 元数据选择对应方言。</p>
 */
public interface Dialect {

	/** 产品名，例如 {@code MySQL} / {@code PostgreSQL} / {@code H2}。 */
	String productName();

	/**
	 * 对标识符（表名 / 列名）进行安全引用。
	 *
	 * @param identifier 原始标识符
	 * @return 带引用符号的标识符（MySQL 使用反引号，PostgreSQL 使用双引号）
	 */
	String quoteIdentifier(String identifier);

	/**
	 * 生成分页子句。
	 *
	 * <p>MySQL 习惯 {@code LIMIT offset, limit}，PostgreSQL / ANSI 习惯
	 * {@code LIMIT limit OFFSET offset}，这里统一封装。</p>
	 *
	 * @param limit  最大返回行数（&gt; 0）
	 * @param offset 跳过行数（&lt;= 0 时退化为仅 {@code LIMIT}）
	 * @return 可直接拼接在 SELECT 语句后的分页片段（含前导空格）
	 */
	String limitOffset(long limit, long offset);

	/**
	 * 生成参数化分页片段（两个 {@code ?}，值走绑定）。
	 *
	 * <p>预编译执行计划用这一条：SQL 文本不再随页码变化，同一种查询在第一页和第一百页拿到的是
	 * 同一个计划，编译一次跨页复用。默认给 ANSI 的 {@code LIMIT ? OFFSET ?}，
	 * MySQL 那类 {@code LIMIT offset, count} 语序的方言覆写并把 {@code offsetFirst} 置 true。</p>
	 *
	 * @return 分页片段，占位符顺序见 {@link LimitClause#offsetFirst()}
	 */
	default LimitClause limitClause() {
		return new LimitClause(" LIMIT ? OFFSET ?", false);
	}

	/** 是否支持通过 {@code Statement.RETURN_GENERATED_KEYS} 取回自增主键。 */
	boolean supportsReturnGeneratedKeys();
}
