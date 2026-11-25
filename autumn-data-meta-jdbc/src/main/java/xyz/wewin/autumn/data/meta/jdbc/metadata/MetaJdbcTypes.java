package xyz.wewin.autumn.data.meta.jdbc.metadata;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * JDBC 类型与 Java 类型的一张小映射表。
 *
 * <p>表结构元数据和自定义结果形状都按 JDBC 类型码描述列，两处必须给出同一种 Java 类型，
 * 否则 {@code row.property("total_amount").getType()} 在不同入口下会得到不同答案。
 * 值归一也放在这里：{@code java.sql.Timestamp/Date/Time} → {@code java.time}，与通用实体读同一列一致。</p>
 */
public final class MetaJdbcTypes {

	private MetaJdbcTypes() {
	}

	/** JDBC 类型码 → Java 类型全限定名；未覆盖的按 {@link String} 处理。 */
	public static String javaTypeName(int jdbcType) {
		return switch (jdbcType) {
			case Types.BIGINT -> Long.class.getName();
			case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> Integer.class.getName();
			case Types.NUMERIC, Types.DECIMAL -> BigDecimal.class.getName();
			case Types.BOOLEAN, Types.BIT -> Boolean.class.getName();
			case Types.REAL, Types.FLOAT, Types.DOUBLE -> Double.class.getName();
			case Types.DATE -> LocalDate.class.getName();
			case Types.TIME, Types.TIME_WITH_TIMEZONE -> LocalTime.class.getName();
			case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> LocalDateTime.class.getName();
			default -> String.class.getName();
		};
	}

	/** 驱动返回值做一次时序归一；其它类型原样返回。 */
	public static Object value(Object raw) {
		if (raw instanceof java.sql.Timestamp timestamp) {
			return timestamp.toLocalDateTime();
		}
		if (raw instanceof java.sql.Date date) {
			return date.toLocalDate();
		}
		if (raw instanceof java.sql.Time time) {
			return time.toLocalTime();
		}
		return raw;
	}
}
