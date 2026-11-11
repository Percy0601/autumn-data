package xyz.wewin.autumn.data.common.meta;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;

/**
 * 元属性值的「读方向」转换：把 JDBC 驱动 / JSON / 表单给回来的混合类型收口成目标 Java 类型。
 *
 * <p>与写方向的绑定转换分开放在各使用模块：这里只依赖 JDK，供 {@link MetaClass} 的便捷
 * getter 和运行期生成的实体类共用。生成类直接调用这些静态方法，不靠反射猜类型。</p>
 *
 * <p>所有方法对 {@code null} 透传，避免调用方到处写三目；无法转换时抛
 * {@link IllegalArgumentException}（带目标类型和实际值）。</p>
 */
public final class MetaValueReader {

	private MetaValueReader() {
	}

	/** 未识别的列类型：原值透传，字段声明成 {@code Object}，至少不会静默丢值。 */
	public static Object asObject(Object value) {
		return value;
	}

	public static String asString(Object value) {
		if (value == null || value instanceof String text) {
			return (String) value;
		}
		if (value instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		if (value instanceof char[] chars) {
			return new String(chars);
		}
		return String.valueOf(value);
	}

	public static Integer asInt(Object value) {
		if (value == null || value instanceof Integer number) {
			return (Integer) value;
		}
		try {
			if (value instanceof Number number) {
				return number.intValue();
			}
			if (value instanceof Boolean bool) {
				return bool ? 1 : 0;
			}
			return Integer.valueOf(asString(value).trim());
		}
		catch (NumberFormatException ex) {
			throw mismatch(value, "Integer");
		}
	}

	public static Long asLong(Object value) {
		if (value == null || value instanceof Long number) {
			return (Long) value;
		}
		try {
			if (value instanceof Number number) {
				return number.longValue();
			}
			if (value instanceof Boolean bool) {
				return bool ? 1L : 0L;
			}
			return Long.valueOf(asString(value).trim());
		}
		catch (NumberFormatException ex) {
			throw mismatch(value, "Long");
		}
	}

	public static Double asDouble(Object value) {
		if (value == null || value instanceof Double number) {
			return (Double) value;
		}
		try {
			if (value instanceof Number number) {
				return number.doubleValue();
			}
			return Double.valueOf(asString(value).trim());
		}
		catch (NumberFormatException ex) {
			throw mismatch(value, "Double");
		}
	}

	public static BigDecimal asBigDecimal(Object value) {
		if (value == null || value instanceof BigDecimal decimal) {
			return (BigDecimal) value;
		}
		try {
			return new BigDecimal(asString(value).trim());
		}
		catch (NumberFormatException ex) {
			throw mismatch(value, "BigDecimal");
		}
	}

	public static Boolean asBoolean(Object value) {
		if (value == null || value instanceof Boolean bool) {
			return (Boolean) value;
		}
		if (value instanceof Number number) {
			return number.intValue() != 0;
		}
		String text = asString(value).trim().toLowerCase(Locale.ROOT);
		return switch (text) {
			case "1", "true", "t", "yes", "y", "on" -> Boolean.TRUE;
			case "0", "false", "f", "no", "n", "off" -> Boolean.FALSE;
			default -> throw mismatch(value, "Boolean");
		};
	}

	public static LocalDate asLocalDate(Object value) {
		if (value == null || value instanceof LocalDate date) {
			return (LocalDate) value;
		}
		if (value instanceof java.sql.Date date) {
			return date.toLocalDate();
		}
		if (value instanceof LocalDateTime dateTime) {
			return dateTime.toLocalDate();
		}
		if (value instanceof OffsetDateTime offset) {
			return offset.toLocalDate();
		}
		if (value instanceof Date date) {
			return new java.sql.Date(date.getTime()).toLocalDate();
		}
		try {
			return LocalDate.parse(asString(value).trim());
		}
		catch (DateTimeParseException ex) {
			throw mismatch(value, "LocalDate");
		}
	}

	public static LocalTime asLocalTime(Object value) {
		if (value == null || value instanceof LocalTime time) {
			return (LocalTime) value;
		}
		if (value instanceof java.sql.Time time) {
			return time.toLocalTime();
		}
		if (value instanceof Date date) {
			return new java.sql.Time(date.getTime()).toLocalTime();
		}
		try {
			return LocalTime.parse(asString(value).trim());
		}
		catch (DateTimeParseException ex) {
			throw mismatch(value, "LocalTime");
		}
	}

	public static LocalDateTime asLocalDateTime(Object value) {
		if (value == null || value instanceof LocalDateTime dateTime) {
			return (LocalDateTime) value;
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
		String text = asString(value).trim();
		try {
			return LocalDateTime.parse(text);
		}
		catch (DateTimeParseException ex) {
			try {
				return LocalDateTime.parse(text.replace(' ', 'T'));
			}
			catch (DateTimeParseException ignored) {
				throw mismatch(value, "LocalDateTime");
			}
		}
	}

	private static IllegalArgumentException mismatch(Object value, String target) {
		return new IllegalArgumentException("cannot read value '" + asString(value) + "' ("
				+ value.getClass().getName() + ") as " + target);
	}
}
