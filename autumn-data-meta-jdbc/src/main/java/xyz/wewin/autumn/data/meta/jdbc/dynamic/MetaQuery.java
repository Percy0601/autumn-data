package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 动态查询条件：AND 链 + 排序 + 操作符白名单。
 *
 * <p>列名只作为“待校验的引用”保存，真正拼进 SQL 前由 {@link MetaSqlBuilder} 到
 * {@link MetaTable} 里解析实际列名；用户传入的字符串永远不会直接进 SQL 文本。</p>
 *
 * <pre>{@code
 * MetaQuery.by("status", 1)
 *          .and("create_time", GE, from)
 *          .orderBy("create_time", DESC)
 * }</pre>
 */
public final class MetaQuery {

	/** 支持的操作符。 */
	public enum Op {
		EQ, NE, GT, GE, LT, LE, LIKE, IN, BETWEEN, IS_NULL, IS_NOT_NULL
	}

	/** 排序方向。 */
	public enum Direction {
		ASC, DESC
	}

	/** 单个条件。 */
	public record Condition(String column, Op op, List<Object> values) {
	}

	/** 单个排序项。 */
	public record Order(String column, Direction direction) {
	}

	private final List<Condition> conditions;

	private final List<Order> orders;

	private MetaQuery(List<Condition> conditions, List<Order> orders) {
		this.conditions = List.copyOf(conditions);
		this.orders = List.copyOf(orders);
	}

	/** 空条件（等价于全表，仍会叠加逻辑删除过滤）。 */
	public static MetaQuery all() {
		return new MetaQuery(List.of(), List.of());
	}

	/** {@code column = value} 的快捷写法。 */
	public static MetaQuery by(String column, Object value) {
		return all().and(column, Op.EQ, value);
	}

	/** 指定操作符的起点。 */
	public static MetaQuery by(String column, Op op, Object... values) {
		return all().and(column, op, values);
	}

	/** 追加一个 AND 条件（{@code column = value}）。 */
	public MetaQuery and(String column, Object value) {
		return and(column, Op.EQ, value);
	}

	/** 追加一个 AND 条件。 */
	public MetaQuery and(String column, Op op, Object... values) {
		requireColumn(column);
		Objects.requireNonNull(op, "op");
		List<Object> normalized = normalize(op, values);
		List<Condition> next = new ArrayList<>(this.conditions);
		next.add(new Condition(column, op, normalized));
		return new MetaQuery(next, this.orders);
	}

	/** 追加一个 AND 条件（集合形态的 IN / BETWEEN 值）。 */
	public MetaQuery and(String column, Op op, Collection<?> values) {
		requireColumn(column);
		Objects.requireNonNull(op, "op");
		List<Object> normalized = normalize(op, values.toArray());
		List<Condition> next = new ArrayList<>(this.conditions);
		next.add(new Condition(column, op, normalized));
		return new MetaQuery(next, this.orders);
	}

	/** 包含匹配：自动补 {@code %}，值是普通文本。 */
	public MetaQuery contains(String column, String value) {
		if (value == null) {
			throw new IllegalArgumentException("LIKE value must not be null for column '" + column + "'");
		}
		return and(column, Op.LIKE, "%" + value + "%");
	}

	/** 追加排序（默认升序）。 */
	public MetaQuery orderBy(String column) {
		return orderBy(column, Direction.ASC);
	}

	/** 追加排序。 */
	public MetaQuery orderBy(String column, Direction direction) {
		requireColumn(column);
		Objects.requireNonNull(direction, "direction");
		List<Order> next = new ArrayList<>(this.orders);
		next.add(new Order(column, direction));
		return new MetaQuery(this.conditions, next);
	}

	public List<Condition> conditions() {
		return this.conditions;
	}

	public List<Order> orders() {
		return this.orders;
	}

	/** 条件/排序是否已经显式引用某列（用于避免重复追加逻辑删除过滤）。 */
	public boolean references(String column) {
		for (Condition condition : this.conditions) {
			if (condition.column().equalsIgnoreCase(column)) {
				return true;
			}
		}
		return false;
	}

	private static void requireColumn(String column) {
		if (column == null || column.isBlank()) {
			throw new IllegalArgumentException("query column must not be blank");
		}
	}

	private static List<Object> normalize(Op op, Object[] values) {
		Object[] safe = values == null ? new Object[0] : values;
		return switch (op) {
			case IS_NULL, IS_NOT_NULL -> {
				if (safe.length != 0) {
					throw new IllegalArgumentException(op + " does not accept a value");
				}
				yield List.of();
			}
			case BETWEEN -> {
				if (safe.length != 2) {
					throw new IllegalArgumentException("BETWEEN needs exactly 2 values but got " + safe.length);
				}
				yield List.of(safe[0], safe[1]);
			}
			case IN -> {
				if (safe.length == 0) {
					throw new IllegalArgumentException("IN needs at least 1 value");
				}
				List<Object> list = new ArrayList<>(safe.length);
				for (Object value : safe) {
					if (value == null) {
						throw new IllegalArgumentException("IN does not accept null (use IS_NULL)");
					}
					list.add(value);
				}
				yield List.copyOf(list);
			}
			default -> {
				if (safe.length != 1) {
					throw new IllegalArgumentException(op + " needs exactly 1 value but got " + safe.length);
				}
				yield List.of(Objects.requireNonNull(safe[0], "query value"));
			}
		};
	}
}
