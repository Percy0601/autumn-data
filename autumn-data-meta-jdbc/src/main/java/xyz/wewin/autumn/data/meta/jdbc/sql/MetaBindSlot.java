package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

import xyz.wewin.autumn.data.common.meta.MetaProperty;

/**
 * 执行计划里的一个绑定槽：第 {@code n} 个 {@code ?} 「该怎么绑」在编译阶段就定死。
 *
 * <p>编译期定下来的三件事，运行期一件不再做：</p>
 * <ul>
 *   <li>这个槽引用哪一列（{@link #column}，为 {@code null} 表示它不是列，比如业务开关或循环变量）；</li>
 *   <li>值按什么类型转换（{@link MetaTypeCoercion} 只吃列的 {@code jdbcType}，转换规则编译期就选好）；</li>
 *   <li>用哪个 setter（{@link #kind}）以及 NULL 上报哪个 JDBC 类型（{@link #jdbcType}）。</li>
 * </ul>
 *
 * <p>相比之下，{@code Object[] + setParams} 的写法每绑一个值都要重新判断运行时类型，NULL 只能报
 * {@link Types#NULL}（部分驱动会把它当未知类型拒绝，或因此放弃索引）。把判断挪到编译期，
 * 是这一层「再转换成预编译」的全部收益所在。</p>
 *
 * @param ref 槽位引用的名字：元数据路径是列名，模板路径是 XML 里写的参数名，只用于诊断
 * @param column 命中的列；{@code null} 表示不是列引用，值原样交给驱动
 * @param kind 编译期选定的 setter 策略
 * @param jdbcType NULL 时传给 {@link PreparedStatement#setNull(int, int)} 的类型
 */
public record MetaBindSlot(String ref, MetaProperty column, Kind kind, int jdbcType) {

	/** setter 策略，与 {@link MetaTypeCoercion} 按列类型转换出来的 Java 类型一一对应。 */
	public enum Kind {
		BOOL, INT, LONG, DOUBLE, DECIMAL, STRING, DATE, TIME, TIMESTAMP, BYTES, OBJECT
	}

	/** 列槽：类型转换、setter、NULL 类型全部跟着列的 {@code jdbcType}。 */
	public static MetaBindSlot ofColumn(MetaProperty column) {
		return new MetaBindSlot(column.getName(), column, kindOf(column.getJdbcType()), column.getJdbcType());
	}

	/** 非列槽：没有列类型可依据，值原样 {@code setObject}，NULL 报 {@link Types#NULL}。 */
	public static MetaBindSlot ofValue(String ref) {
		return new MetaBindSlot(ref, null, Kind.OBJECT, Types.NULL);
	}

	/** 列的 JDBC 类型 → setter 策略；未知类型落到 {@link Kind#OBJECT}，交给驱动判断。 */
	public static Kind kindOf(int jdbcType) {
		return switch (jdbcType) {
			case Types.BOOLEAN, Types.BIT -> Kind.BOOL;
			case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> Kind.INT;
			case Types.BIGINT -> Kind.LONG;
			case Types.REAL, Types.FLOAT, Types.DOUBLE -> Kind.DOUBLE;
			case Types.NUMERIC, Types.DECIMAL -> Kind.DECIMAL;
			case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
					Kind.STRING;
			case Types.DATE -> Kind.DATE;
			case Types.TIME, Types.TIME_WITH_TIMEZONE -> Kind.TIME;
			case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> Kind.TIMESTAMP;
			case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> Kind.BYTES;
			default -> Kind.OBJECT;
		};
	}

	/**
	 * 绑一个值。列槽先按列类型收口（表单字符串 {@code "12.50"} → {@code BigDecimal}），再走类型化 setter。
	 */
	void bind(PreparedStatement statement, int index, Object value) throws SQLException {
		Object jdbc = this.column == null ? value : MetaTypeCoercion.toJdbc(this.column, value);
		if (jdbc == null) {
			statement.setNull(index, this.jdbcType);
			return;
		}
		switch (this.kind) {
			case BOOL -> statement.setBoolean(index, (Boolean) jdbc);
			case INT -> statement.setInt(index, (Integer) jdbc);
			case LONG -> statement.setLong(index, (Long) jdbc);
			case DOUBLE -> statement.setDouble(index, (Double) jdbc);
			case DECIMAL -> statement.setBigDecimal(index, (BigDecimal) jdbc);
			case STRING -> statement.setString(index, (String) jdbc);
			case DATE -> statement.setDate(index, (java.sql.Date) jdbc);
			case TIME -> statement.setTime(index, (java.sql.Time) jdbc);
			case TIMESTAMP -> statement.setTimestamp(index, (java.sql.Timestamp) jdbc);
			case BYTES -> statement.setBytes(index, (byte[]) jdbc);
			case OBJECT -> statement.setObject(index, jdbc);
		}
	}

	/** 是否绑到具体列（诊断与测试断言用）。 */
	public boolean isColumn() {
		return this.column != null;
	}
}
