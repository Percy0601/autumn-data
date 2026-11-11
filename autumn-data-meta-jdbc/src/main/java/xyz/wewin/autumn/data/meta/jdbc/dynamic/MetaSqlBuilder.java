package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.SqlBuild;

/**
 * 按 {@link MetaTable} 描述生成 SQL：表名/列名全部来自元数据快照，值只走 {@code ?} 占位符。
 *
 * <p>这是动态查询的注入防线：调用方传进来的只是“列名引用”，最终拼进 SQL 的标识符一定来自
 * {@link MetaTable#requireColumn(String)} 的返回值；排序字段同样先校验再引用。</p>
 *
 * <p>只服务 {@link MetaQuery} 驱动的 CRUD。命名语句不在这里拼——那部分 SQL 文本来自
 * Mapper 文件（{@link MetaStatementStore}），两条路各自完整，互不掺混。</p>
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
		StringBuilder sql = new StringBuilder("SELECT ")
				.append(quoteNames(resolveProjection(table, projection))).append(" FROM ")
				.append(quote(table.name()));
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
		for (MetaProperty key : table.primaryKeys()) {
			if (!key.isAutoIncrement()) {
				if (!resolved.containsKey(key.getName())) {
					throw new MetaSqlException("table '" + table.name() + "' has a non-generated primary key '"
							+ key.getName() + "', a value is required for insert");
				}
				if (resolved.get(key.getName()) == null) {
					throw new MetaSqlException("primary key column '" + key.getName() + "' must not be null");
				}
			}
		}
		List<Object> args = new ArrayList<>();
		StringBuilder columns = new StringBuilder();
		StringBuilder placeholders = new StringBuilder();
		for (MetaProperty column : table.columns()) {
			Object value = resolved.get(column.getName());
			if (value == null && !resolved.containsKey(column.getName())) {
				continue;
			}
			if (column.isAutoIncrement() && !resolved.containsKey(column.getName())) {
				continue;
			}
			if (!columns.isEmpty()) {
				columns.append(", ");
				placeholders.append(", ");
			}
			columns.append(quote(column.getName()));
			placeholders.append('?');
			args.add(MetaTypeCoercion.toJdbc(column, value));
		}
		if (columns.isEmpty()) {
			throw new MetaSqlException("no writable columns provided for insert into '" + table.name() + "'");
		}
		return new SqlBuild(
				"INSERT INTO " + quote(table.name()) + " (" + columns + ") VALUES (" + placeholders + ")",
				args.toArray());
	}

	/**
	 * 按主键 UPDATE（部分更新）：只更新传入的列，主键列不允许出现在 SET 里。
	 *
	 * @param key 主键值，必须覆盖表的全部主键列
	 */
	public SqlBuild update(MetaTable table, Map<String, ?> key, Map<String, ?> values) {
		Map<String, Object> resolvedKey = resolveKey(table, key);
		Map<String, Object> resolvedValues = resolveValues(table, values);
		List<String> setClauses = new ArrayList<>();
		List<Object> args = new ArrayList<>();
		for (MetaProperty column : table.columns()) {
			Object value = resolvedValues.get(column.getName());
			if (value == null && !resolvedValues.containsKey(column.getName())) {
				continue;
			}
			if (column.isPrimaryKey()) {
				throw new MetaSqlException("primary key column '" + column.getName() + "' cannot be updated");
			}
			setClauses.add(quote(column.getName()) + " = ?");
			args.add(MetaTypeCoercion.toJdbc(column, value));
		}
		if (setClauses.isEmpty()) {
			throw new MetaSqlException("no writable columns provided for update on '" + table.name() + "'");
		}
		List<String> keyClauses = new ArrayList<>();
		for (MetaProperty column : table.primaryKeys()) {
			keyClauses.add(quote(column.getName()) + " = ?");
			args.add(MetaTypeCoercion.toJdbc(column, resolvedKey.get(column.getName())));
		}
		return new SqlBuild("UPDATE " + quote(table.name()) + " SET " + String.join(", ", setClauses) + " WHERE "
				+ String.join(" AND ", keyClauses), args.toArray());
	}

	/** 表名（已加引号），供 Mapper 语句的 {@code ${table}}。 */
	public String quoteTable(MetaTable table) {
		return quote(table.name());
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
			MetaProperty column = table.requireColumn(entry.getKey());
			if (resolved.containsKey(column.getName())) {
				throw new MetaSqlException("duplicate column '" + column.getName() + "' in values");
			}
			resolved.put(column.getName(), entry.getValue());
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
			MetaProperty column = table.requireColumn(entry.getKey());
			if (!column.isPrimaryKey()) {
				throw new MetaSqlException("column '" + column.getName() + "' is not a primary key of table '"
						+ table.name() + "'");
			}
			if (entry.getValue() == null) {
				throw new MetaSqlException("primary key column '" + column.getName() + "' must not be null");
			}
			resolved.put(column.getName(), entry.getValue());
		}
		for (MetaProperty column : table.primaryKeys()) {
			if (!resolved.containsKey(column.getName())) {
				throw new MetaSqlException("missing primary key column '" + column.getName() + "' for table '"
						+ table.name() + "'");
			}
		}
		return resolved;
	}

	private List<MetaProperty> resolveProjection(MetaTable table, List<String> projection) {
		if (projection == null || projection.isEmpty()) {
			return table.columns();
		}
		List<MetaProperty> selected = new ArrayList<>(projection.size());
		for (String column : projection) {
			MetaProperty found = table.requireColumn(column);
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
			MetaProperty column = table.requireColumn(condition.column());
			appendCondition(where, column, condition, args);
		}
		String logicColumn = table.logicDeleteColumn();
		if (logicColumn != null && !query.references(logicColumn)) {
			MetaProperty column = table.requireColumn(logicColumn);
			if (!where.isEmpty()) {
				where.append(" AND ");
			}
			where.append(quote(column.getName())).append(" = ?");
			args.add(MetaTypeCoercion.toJdbc(column, 0));
		}
		if (!where.isEmpty()) {
			sql.append(" WHERE ").append(where);
		}
		return args;
	}

	private void appendCondition(StringBuilder where, MetaProperty column, MetaQuery.Condition condition,
			List<Object> args) {
		if (!where.isEmpty()) {
			where.append(" AND ");
		}
		String quoted = quote(column.getName());
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
			MetaProperty column = table.requireColumn(order.column());
			sql.append(quote(column.getName())).append(order.direction() == MetaQuery.Direction.DESC ? " DESC" : " ASC");
		}
	}

	private String quote(String identifier) {
		return this.dialect.quoteIdentifier(identifier);
	}

	private String quoteNames(List<MetaProperty> columns) {
		StringBuilder names = new StringBuilder();
		for (MetaProperty column : columns) {
			if (!names.isEmpty()) {
				names.append(", ");
			}
			names.append(quote(column.getName()));
		}
		return names.toString();
	}
}
