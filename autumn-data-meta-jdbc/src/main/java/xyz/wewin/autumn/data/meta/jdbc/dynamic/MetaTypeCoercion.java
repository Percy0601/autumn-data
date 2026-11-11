package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;

import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 动态值到 JDBC 类型的转换。
 *
 * <p>表单/JSON 送进来的值大多是字符串或混合数值，直接交给 {@code setObject} 会让驱动猜类型，
 * 报错信息也难以定位。这里按列的真实 JDBC 类型做一次显式转换，失败时把「表.列 + 期望类型 + 实际值」
 * 一并放进异常消息。</p>
 */
public final class MetaTypeCoercion {

	private MetaTypeCoercion() {
	}

	/** 按列类型转换写入值；{@link MetaValue#NULL} 与 {@code null} 都返回 {@code null}。 */
	public static Object toJdbc(MetaColumn column, Object value) {
		if (value == null || value == MetaValue.NULL) {
			return null;
		}
		return switch (column.jdbcType()) {
			case Types.BOOLEAN, Types.BIT -> toBoolean(column, value);
			case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> toInt(column, value);
			case Types.BIGINT -> toLong(column, value);
			case Types.REAL, Types.FLOAT, Types.DOUBLE -> toDouble(column, value);
			case Types.NUMERIC, Types.DECIMAL -> toBigDecimal(column, value);
			case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
					String.valueOf(value);
			case Types.DATE -> toSqlDate(column, value);
			case Types.TIME, Types.TIME_WITH_TIMEZONE -> toSqlTime(column, value);
			case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> toSqlTimestamp(column, value);
			case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
					value instanceof byte[] bytes ? bytes : String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
			default -> value;
		};
	}

	/** 转 {@code Long}（读取结果与主键参数共用）。 */
	public static Long toLong(MetaColumn column, Object value) {
		if (value == null) {
			return null;
		}
		try {
			if (value instanceof Number number) {
				return number.longValue();
			}
			if (value instanceof Boolean bool) {
				return bool ? 1L : 0L;
			}
			return Long.valueOf(String.valueOf(value).trim());
		}
		catch (NumberFormatException ex) {
			throw mismatch(column, value, "BIGINT");
		}
	}

	public static Integer toInt(MetaColumn column, Object value) {
		if (value == null) {
			return null;
		}
		try {
			if (value instanceof Number number) {
				return number.intValue();
			}
			if (value instanceof Boolean bool) {
				return bool ? 1 : 0;
			}
			return Integer.valueOf(String.valueOf(value).trim());
		}
		catch (NumberFormatException ex) {
			throw mismatch(column, value, "INTEGER");
		}
	}

	public static BigDecimal toBigDecimal(MetaColumn column, Object value) {
		if (value == null) {
			return null;
		}
		try {
			if (value instanceof BigDecimal decimal) {
				return decimal;
			}
			return new BigDecimal(String.valueOf(value).trim());
		}
		catch (NumberFormatException ex) {
			throw mismatch(column, value, "DECIMAL");
		}
	}

	public static Boolean toBoolean(MetaColumn column, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.intValue() != 0;
		}
		String text = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
		return switch (text) {
			case "1", "true", "t", "yes", "y", "on" -> Boolean.TRUE;
			case "0", "false", "f", "no", "n", "off" -> Boolean.FALSE;
			default -> throw mismatch(column, value, "BOOLEAN");
		};
	}

	public static Double toDouble(MetaColumn column, Object value) {
		if (value == null) {
			return null;
		}
		try {
			if (value instanceof Number number) {
				return number.doubleValue();
			}
			return Double.valueOf(String.valueOf(value).trim());
		}
		catch (NumberFormatException ex) {
			throw mismatch(column, value, "DOUBLE");
		}
	}

	public static LocalDate toLocalDate(MetaColumn column, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof LocalDate date) {
			return date;
		}
		if (value instanceof java.sql.Date date) {
			return date.toLocalDate();
		}
		if (value instanceof Date date) {
			return new java.sql.Date(date.getTime()).toLocalDate();
		}
		try {
			return LocalDate.parse(String.valueOf(value).trim());
		}
		catch (DateTimeParseException ex) {
			throw mismatch(column, value, "DATE");
		}
	}

	public static LocalDateTime toLocalDateTime(MetaColumn column, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof LocalDateTime dateTime) {
			return dateTime;
		}
		if (value instanceof Timestamp timestamp) {
			return timestamp.toLocalDateTime();
		}
		if (value instanceof OffsetDateTime offset) {
			return offset.toLocalDateTime();
		}
		if (value instanceof Date date) {
			return new Timestamp(date.getTime()).toLocalDateTime();
		}
		String text = String.valueOf(value).trim();
		try {
			return LocalDateTime.parse(text);
		}
		catch (DateTimeParseException ex) {
			try {
				return LocalDateTime.parse(text.replace(' ', 'T'));
			}
			catch (DateTimeParseException ignored) {
				throw mismatch(column, value, "TIMESTAMP");
			}
		}
	}

	private static java.sql.Date toSqlDate(MetaColumn column, Object value) {
		if (value instanceof java.sql.Date date) {
			return date;
		}
		return java.sql.Date.valueOf(toLocalDate(column, value));
	}

	private static java.sql.Time toSqlTime(MetaColumn column, Object value) {
		if (value instanceof java.sql.Time time) {
			return time;
		}
		if (value instanceof LocalTime time) {
			return java.sql.Time.valueOf(time);
		}
		if (value instanceof Date date) {
			return new java.sql.Time(date.getTime());
		}
		try {
			return java.sql.Time.valueOf(LocalTime.parse(String.valueOf(value).trim()));
		}
		catch (DateTimeParseException ex) {
			throw mismatch(column, value, "TIME");
		}
	}

	private static Timestamp toSqlTimestamp(MetaColumn column, Object value) {
		if (value instanceof Timestamp timestamp) {
			return timestamp;
		}
		return Timestamp.valueOf(toLocalDateTime(column, value));
	}

	private static MetaSqlException mismatch(MetaColumn column, Object value, String expected) {
		return new MetaSqlException("cannot convert value '" + value + "' (" + value.getClass().getSimpleName()
				+ ") to " + expected + " for column '" + column.name() + "' (jdbcType=" + column.jdbcType()
				+ ", " + column.typeName() + ")");
	}
}
