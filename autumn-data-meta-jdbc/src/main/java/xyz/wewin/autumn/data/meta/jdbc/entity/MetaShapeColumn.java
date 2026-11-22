package xyz.wewin.autumn.data.meta.jdbc.entity;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * 自定义结果形状的一列：只描述「结果集给了什么」，不依赖任何表结构。
 *
 * <p>{@code index} 是这一列在 {@link ResultSet} 里的原始序号（1 起）。同名标签被收口掉时，
 * 后续列的序号不会左移——生成的 {@code read} 按这个序号取列，不能重新编号。
 * {@code javaType} 由 JDBC 类型码映射而来（见 {@code MetaJdbcTypes}），
 * {@code sqlTypeName} 保留驱动原名用于诊断；{@code nullable} 来自 {@link ResultSetMetaData}。
 * 这组描述既用于生成具体形状类，也用于 GENERIC 模式下逐行建 {@code MetaProperty}。</p>
 */
public record MetaShapeColumn(int index, String name, int jdbcType, String sqlTypeName, boolean nullable,
		String javaType) {

	/** 从结果集元数据按投影顺序读出列描述；列标签统一小写，同名标签只留第一列。 */
	public static List<MetaShapeColumn> read(ResultSetMetaData metaData, java.util.function.IntFunction<String> javaType)
			throws SQLException {
		java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
		java.util.List<MetaShapeColumn> columns = new java.util.ArrayList<>(metaData.getColumnCount());
		for (int i = 1; i <= metaData.getColumnCount(); i++) {
			String label = metaData.getColumnLabel(i);
			if (label == null || !seen.add(label.toLowerCase(Locale.ROOT))) {
				continue;
			}
			int jdbcType = metaData.getColumnType(i);
			columns.add(new MetaShapeColumn(i, label.toLowerCase(Locale.ROOT), jdbcType,
					metaData.getColumnTypeName(i), metaData.isNullable(i) != ResultSetMetaData.columnNoNulls,
					javaType.apply(jdbcType)));
		}
		return java.util.List.copyOf(columns);
	}
}
