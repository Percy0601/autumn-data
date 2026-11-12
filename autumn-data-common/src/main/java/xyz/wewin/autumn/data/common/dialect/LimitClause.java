package xyz.wewin.autumn.data.common.dialect;

/**
 * 参数化分页片段：SQL 片段里的行数上限与偏移用 {@code ?} 占位，值走 JDBC 绑定。
 *
 * <p>和 {@link Dialect#limitOffset(long, long)} 的拼接版相比，参数化片段让<b>同一种查询在所有页码上
 * 得到完全相同的 SQL 文本</b>——预编译执行计划只需编译一次即可跨页复用，数据库侧的游标/执行计划缓存
 * 也才可能命中。拼接版每条 {@code LIMIT 20 OFFSET 40} 都是新文本，计划缓存会被页码打散。</p>
 *
 * @param fragment     可直接拼在 SELECT 之后的片段（含前导空格），内含两个 {@code ?}
 * @param offsetFirst  两个占位符的实参顺序：{@code true} 为 {@code (offset, limit)}（MySQL
 *                     {@code LIMIT offset, count}），{@code false} 为 {@code (limit, offset)}（ANSI）
 */
public record LimitClause(String fragment, boolean offsetFirst) {

	public LimitClause {
		if (fragment == null || fragment.isBlank()) {
			throw new IllegalArgumentException("fragment must not be blank");
		}
	}

	/** 按本片段的顺序，把上限与偏移排成绑定实参。 */
	public long[] bind(long limit, long offset) {
		return this.offsetFirst ? new long[] { offset, limit } : new long[] { limit, offset };
	}
}
