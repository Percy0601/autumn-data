package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.SqlBuild;

/**
 * 按 {@link MetaTable} 描述生成 SQL：表名/列名全部来自元数据快照，值只走 {@code ?} 占位符。
 *
 * <p>这是动态查询的注入防线：调用方传进来的只是“列名引用”，最终拼进 SQL 的标识符一定来自
 * {@link MetaTable#requireColumn(String)} 的返回值；排序字段同样先校验再引用。</p>
 */
public final class MetaSqlBuilder {

	private final Dialect dialect;

	public MetaSqlBuilder(Dialect dialect) {
		this.dialect = dialect;
	}

	/** 全列查询 + 条件 + 排序。 */
	public SqlBuild select(MetaTable table, MetaQuery query) {
		return select(table, query, null);
	}

	/**
	 * 指定投影列查询。
	 *
	 * @param projection 需要查询的列（大小写不敏感）；{@code null}/空表示全列
	 */
	public SqlBuild select(MetaTable table, MetaQuery query, List<String> projection) {
		List<MetaColumn> selected = resolveProjection(table, projection);
		StringBuilder sql = new StringBuilder("SELECT ");
		for (int i = 0; i < selected.size(); i++) {
			if (i > 0) {
				sql.append(", ");
			}
			sql.append(quote(selected.get(i).name()));
		}
		sql.append(" FROM ").append(quote(table.name()));
		List<Object> args = appendWhere(sql, table, query);
		appendOrder(sql, table, query);
		return new SqlBuild(sql.toString(), args.toArray());
	}

	/** COUNT 查询（与 {@link #select} 共用同一组条件参数）。 */
	public SqlBuild count(MetaTable table, MetaQuery query) {
		StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(quote(table.name()));
		List<Object> args = appendWhere(sql, table, query);
		return new SqlBuild(sql.toString(), args.toArray());
	}

	/** INSERT；自增主键未提供时自动省略该列，非自增主键缺失则拒绝。 */
	public SqlBuild insert(MetaTable table, Map<String, ?> values) {
		Map<String, Object> resolved = resolveValues(table, values);
		for (MetaColumn key : table.primaryKeys()) {
			if (!key.autoIncrement()) {
				if (!resolved.containsKey(key.name())) {
					throw new MetaSqlException("table '" + table.name() + "' has a non-generated primary key '"
							+ key.name() + "', a value is required for insert");
				}
				if (resolved.get(key.name()) == null) {
					throw new MetaSqlException("primary key column '" + key.name() + "' must not be null");
				}
			}
		}
		List<String> columns = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		for (MetaColumn column : table.columns()) {
			Object value = resolved.get(column.name());
			if (value == null && !resolved.containsKey(column.name())) {
				continue;
			}
			if (column.autoIncrement() && !resolved.containsKey(column.name())) {
				continue;
			}
			columns.add(quote(column.name()));
			args.add(MetaTypeCoercion.toJdbc(column, value));
		}
		if (columns.isEmpty()) {
			throw new MetaSqlException("no writable columns provided for insert into '" + table.name() + "'");
		}
		StringBuilder sql = new StringBuilder("INSERT INTO ").append(quote(table.name())).append(" (")
				.append(String.join(", ", columns)).append(") VALUES (");
		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				sql.append(", ");
			}
			sql.append('?');
		}
		sql.append(')');
		return new SqlBuild(sql.toString(), args.toArray());
	}

	/**
	 * 按主键 UPDATE（部分更新）：只更新传入的列，主键列不允许出现在 SET 里。
	 *
	 * @param key 主键值，必须覆盖表的全部主键列
	 */
	public SqlBuild update(MetaTable table, Map<String, ?> key, Map<String, ?> values) {
		if (values == null || values.isEmpty()) {
			throw new MetaSqlException("no columns provided for update on '" + table.name() + "'");
		}
		Map<String, Object> resolvedKey = resolveKey(table, key);
		Map<String, Object> resolvedValues = resolveValues(table, values);
		if (resolvedValues.isEmpty()) {
			throw new MetaSqlException("no columns provided for update on '" + table.name() + "'");
		}
		List<String> setClauses = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		for (MetaColumn column : table.columns()) {
			Object value = resolvedValues.get(column.name());
			if (value == null && !resolvedValues.containsKey(column.name())) {
				continue;
			}
			if (column.primaryKey()) {
				throw new MetaSqlException("primary key column '" + column.name() + "' cannot be updated");
			}
			setClauses.add(quote(column.name()) + " = ?");
			args.add(MetaTypeCoercion.toJdbc(column, value));
		}
		if (setClauses.isEmpty()) {
			throw new MetaSqlException("no writable columns provided for update on '" + table.name() + "'");
		}
		StringBuilder sql = new StringBuilder("UPDATE ").append(quote(table.name())).append(" SET ")
				.append(String.join(", ", setClauses)).append(" WHERE ");
		List<String> keyClauses = new ArrayList<>();
		for (MetaColumn column : table.primaryKeys()) {
			keyClauses.add(quote(column.name()) + " = ?");
			args.add(MetaTypeCoercion.toJdbc(column, resolvedKey.get(column.name())));
		}
		sql.append(String.join(" AND ", keyClauses));
		return new SqlBuild(sql.toString(), args.toArray());
	}

	/**
	 * 读取列名时大小写不敏感，但返回给 SQL 的一定是元数据里的实际列名。
	 */
	private Map<String, Object> resolveValues(MetaTable table, Map<String, ?> values) {
		if (values == null || values.isEmpty()) {
			throw new MetaSqlException("values must not be empty for table '" + table.name() + "'");
		}
		Map<String, Object> resolved = new LinkedHashMap<>();
		for (Map.Entry<String, ?> entry : values.entrySet()) {
			MetaColumn column = table.requireColumn(entry.getKey());
			if (resolved.containsKey(column.name())) {
				throw new MetaSqlException("duplicate column '" + column.name() + "' in values");
			}
			resolved.put(column.name(), entry.getValue());
		}
		return resolved;
	}

	private Map<String, Object> resolveKey(MetaTable table, Map<String, ?> key) {
		if (table.primaryKeys().isEmpty()) {
			throw new MetaSqlException("table '" + table.name() + "' has no primary key");
		}
		if (key == null || key.isEmpty()) {
			throw new MetaSqlException("primary key values must not be empty for table '" + table.name() + "'");
		}
		Map<String, Object> resolved = new LinkedHashMap<>();
		for (Map.Entry<String, ?> entry : key.entrySet()) {
			MetaColumn column = table.requireColumn(entry.getKey());
			if (!column.primaryKey()) {
				throw new MetaSqlException("column '" + column.name() + "' is not a primary key of table '"
						+ table.name() + "'");
			}
			if (entry.getValue() == null) {
				throw new MetaSqlException("primary key column '" + column.name() + "' must not be null");
			}
			resolved.put(column.name(), entry.getValue());
		}
		for (MetaColumn column : table.primaryKeys()) {
			if (!resolved.containsKey(column.name())) {
				throw new MetaSqlException("missing primary key column '" + column.name() + "' for table '"
						+ table.name() + "'");
			}
		}
		return resolved;
	}

	private List<MetaColumn> resolveProjection(MetaTable table, List<String> projection) {
		if (projection == null || projection.isEmpty()) {
			return table.columns();
		}
		List<MetaColumn> selected = new ArrayList<>(projection.size());
		for (String column : projection) {
			MetaColumn found = table.requireColumn(column);
			if (!selected.contains(found)) {
				selected.add(found);
			}
		}
		return selected;
	}

	/**
	 * 拼 WHERE，返回绑定参数；逻辑删除列在查询未显式引用时自动追加 {@code = 0}。
	 */
	private List<Object> appendWhere(StringBuilder sql, MetaTable table, MetaQuery query) {
		List<Object> args = new ArrayList<>();
		StringBuilder where = new StringBuilder();
		for (MetaQuery.Condition condition : query.conditions()) {
			MetaColumn column = table.requireColumn(condition.column());
			appendCondition(where, column, condition, args);
		}
		String logicColumn = table.logicDeleteColumn();
		if (logicColumn != null && !query.references(logicColumn)) {
			MetaColumn column = table.requireColumn(logicColumn);
			if (!where.isEmpty()) {
				where.append(" AND ");
			}
			where.append(quote(column.name())).append(" = ?");
			args.add(MetaTypeCoercion.toJdbc(column, 0));
		}
		if (!where.isEmpty()) {
			sql.append(" WHERE ").append(where);
		}
		return args;
	}

	private void appendCondition(StringBuilder where, MetaColumn column, MetaQuery.Condition condition,
			List<Object> args) {
		if (!where.isEmpty()) {
			where.append(" AND ");
		}
		String quoted = quote(column.name());
		switch (condition.op()) {
			case EQ -> {
				where.append(quoted).append(" = ?");
				args.add(MetaTypeCoercion.toJdbc(column, condition.values().get(0)));
			}
			case NE -> {
				where.append(quoted).append(" <> ?");
				args.add(MetaTypeCoercion.toJdbc(column, condition.values().get(0)));
			}
			case GT -> {
				where.append(quoted).append(" > ?");
				args.add(MetaTypeCoercion.toJdbc(column, condition.values().get(0)));
			}
			case GE -> {
				where.append(quoted).append(" >= ?");
				args.add(MetaTypeCoercion.toJdbc(column, condition.values().get(0)));
			}
			case LT -> {
				where.append(quoted).append(" < ?");
				args.add(MetaTypeCoercion.toJdbc(column, condition.values().get(0)));
			}
			case LE -> {
				where.append(quoted).append(" <= ?");
				args.add(MetaTypeCoercion.toJdbc(column, condition.values().get(0)));
			}
			case LIKE -> {
				where.append(quoted).append(" LIKE ?");
				args.add(String.valueOf(condition.values().get(0)));
			}
			case IN -> {
				where.append(quoted).append(" IN (");
				for (int i = 0; i < condition.values().size(); i++) {
					if (i > 0) {
						where.append(", ");
					}
					where.append('?');
					args.add(MetaTypeCoercion.toJdbc(column, condition.values().get(i)));
				}
				where.append(')');
			}
			case BETWEEN -> {
				where.append(quoted).append(" BETWEEN ? AND ?");
				args.add(MetaTypeCoercion.toJdbc(column, condition.values().get(0)));
				args.add(MetaTypeCoercion.toJdbc(column, condition.values().get(1)));
			}
			case IS_NULL -> where.append(quoted).append(" IS NULL");
			case IS_NOT_NULL -> where.append(quoted).append(" IS NOT NULL");
		}
	}

	private void appendOrder(StringBuilder sql, MetaTable table, MetaQuery query) {
		if (query.orders().isEmpty()) {
			return;
		}
		sql.append(" ORDER BY ");
		for (int i = 0; i < query.orders().size(); i++) {
			if (i > 0) {
				sql.append(", ");
			}
			MetaQuery.Order order = query.orders().get(i);
			MetaColumn column = table.requireColumn(order.column());
			sql.append(quote(column.name())).append(order.direction() == MetaQuery.Direction.DESC ? " DESC" : " ASC");
		}
	}

	private String quote(String identifier) {
		return this.dialect.quoteIdentifier(identifier);
	}
}
