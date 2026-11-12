package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.util.ArrayList;
import java.util.List;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;

/**
 * 按 {@link MetaTable} 描述生成 SQL：表名/列名全部来自元数据快照，值只走 {@code ?} 占位符。
 *
 * <p>这是动态查询的注入防线：调用方传进来的只是“列名引用”，最终拼进 SQL 的标识符一定来自
 * {@link MetaTable#requireColumn(String)} 的返回值；排序字段同样先校验再引用。</p>
 *
 * <p>只服务 {@link MetaQuery} 驱动的查询与实体驱动的写。命名语句不在这里拼——那部分 SQL 文本来自
 * Mapper 文件（{@link MetaStatementStore}），两条路各自完整，互不掺混。</p>
 *
 * <p>写语句看的是 {@link MetaClass} 的<b>变更集</b>而不是「哪些值非 null」：赋值过就是这一列要写
 * （显式赋 null 就是置空），没赋值就是不碰。</p>
 *
 * <p>产出的是抽取阶段的 {@link MarkedSql}（形状 + 引用名 + 实参），<b>不做类型转换</b>：
 * 每个槽的类型判断与 setter 选择由 {@link MetaPlanCompiler} 编译一次，运行期只是绑值。
 * 早期版本在这里就调 {@link MetaTypeCoercion}，同一条查询每执行一次都重算一遍同样的转换。</p>
 */
public final class MetaSqlBuilder {

	private final Dialect dialect;

	public MetaSqlBuilder(Dialect dialect) {
		this.dialect = dialect;
	}

	/** 全列查询 + 条件 + 排序。 */
	public MarkedSql select(MetaTable table, MetaQuery query) {
		return select(table, query, null);
	}

	/**
	 * 指定投影列查询。
	 *
	 * @param projection 需要查询的列（大小写不敏感）；{@code null}/空表示全列
	 */
	public MarkedSql select(MetaTable table, MetaQuery query, List<String> projection) {
		StringBuilder sql = new StringBuilder("SELECT ")
				.append(quoteNames(resolveProjection(table, projection))).append(" FROM ")
				.append(quote(table.name()));
		Binds binds = new Binds();
		appendWhere(sql, table, query, binds);
		appendOrder(sql, table, query);
		return binds.of(sql.toString());
	}

	/** COUNT 查询（与 {@link #select} 共用同一组条件参数）。 */
	public MarkedSql count(MetaTable table, MetaQuery query) {
		StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(quote(table.name()));
		Binds binds = new Binds();
		appendWhere(sql, table, query, binds);
		return binds.of(sql.toString());
	}

	/**
	 * INSERT：写「这行里被赋过值的列」。
	 *
	 * <p>判定依据是 {@link MetaClass#isChanged(String)} 而不是「值是不是 null」——显式写 null
	 * 就是要把这一列落成 NULL，没写过才是交给数据库默认值。自增主键没赋值时整列省略。</p>
	 */
	public MarkedSql insert(MetaTable table, MetaClass entity) {
		requireChanges(table, entity, "insert");
		for (MetaProperty key : table.primaryKeys()) {
			if (key.isAutoIncrement()) {
				continue;
			}
			if (!entity.isChanged(key.getName())) {
				throw new MetaSqlException("table '" + table.name() + "' has a non-generated primary key '"
						+ key.getName() + "', a value is required for insert");
			}
			if (entity.get(key.getName()) == null) {
				throw new MetaSqlException("primary key column '" + key.getName() + "' must not be null");
			}
		}
		Binds binds = new Binds();
		StringBuilder columns = new StringBuilder();
		StringBuilder placeholders = new StringBuilder();
		for (MetaProperty column : table.columns()) {
			if (!entity.isChanged(column.getName())) {
				continue;
			}
			if (!columns.isEmpty()) {
				columns.append(", ");
				placeholders.append(", ");
			}
			columns.append(quote(column.getName()));
			placeholders.append('?');
			binds.add(column, entity.get(column.getName()));
		}
		if (columns.isEmpty()) {
			throw new MetaSqlException("no writable columns provided for insert into '" + table.name() + "'");
		}
		return binds.of("INSERT INTO " + quote(table.name()) + " (" + columns + ") VALUES (" + placeholders + ")");
	}

	/**
	 * 按主键 UPDATE（部分更新）：SET 只放写过的列，主键列取值自实体且不允许被改。
	 *
	 * <p>主键之外的列没写过就完全不出现在语句里，所以「读出来—改一格—写回去」不会顺手把别的列
	 * 刷成当前值；显式 {@code set(column, null)} 才是把这一列置空。</p>
	 */
	public MarkedSql update(MetaTable table, MetaClass entity) {
		requireChanges(table, entity, "update");
		Binds binds = new Binds();
		List<String> setClauses = new ArrayList<>();
		for (MetaProperty column : table.columns()) {
			if (!entity.isChanged(column.getName())) {
				continue;
			}
			if (column.isPrimaryKey()) {
				throw new MetaSqlException("primary key column '" + column.getName() + "' cannot be updated");
			}
			setClauses.add(quote(column.getName()) + " = ?");
			binds.add(column, entity.get(column.getName()));
		}
		if (setClauses.isEmpty()) {
			throw new MetaSqlException("no writable columns provided for update on '" + table.name() + "'");
		}
		if (table.primaryKeys().isEmpty()) {
			throw new MetaSqlException("table '" + table.name() + "' has no primary key");
		}
		List<String> keyClauses = new ArrayList<>();
		for (MetaProperty column : table.primaryKeys()) {
			Object value = entity.get(column.getName());
			if (value == null) {
				throw new MetaSqlException("primary key column '" + column.getName() + "' must not be null");
			}
			keyClauses.add(quote(column.getName()) + " = ?");
			binds.add(column, value);
		}
		return binds.of("UPDATE " + quote(table.name()) + " SET " + String.join(", ", setClauses) + " WHERE "
				+ String.join(" AND ", keyClauses));
	}

	/** 表名（已加引号），供 Mapper 语句的 {@code ${table}}。 */
	public String quoteTable(MetaTable table) {
		return quote(table.name());
	}

	/**
	 * 写入前的两道检查：这一次确实赋值过，且赋名的每一列都真实存在。
	 *
	 * <p>后者是原来 Map 入口自带的保护，换成实体之后必须留着：拼错列名时 {@code set} 只会把值
	 * 挂到列以外的动态属性上，不检查就会变成「SQL 少写一列、库里静默保持默认值」。</p>
	 */
	private static void requireChanges(MetaTable table, MetaClass entity, String kind) {
		if (entity == null) {
			throw new MetaSqlException("entity must not be null for " + kind + " on '" + table.name() + "'");
		}
		if (!entity.hasChanges()) {
			throw new MetaSqlException("entity of table '" + table.name()
					+ "' has no values written; use newEntity(...) and set the columns to " + kind);
		}
		for (String name : entity.changedNames()) {
			if (table.findColumn(name) == null) {
				throw new MetaSqlException("unknown column '" + name + "' for table '" + table.name() + "'");
			}
		}
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
	 * 拼 WHERE，槽位记进 {@code binds}；逻辑删除列在查询未显式引用时自动追加 {@code = 0}。
	 */
	private void appendWhere(StringBuilder sql, MetaTable table, MetaQuery query, Binds binds) {
		StringBuilder where = new StringBuilder();
		for (MetaQuery.Condition condition : query.conditions()) {
			MetaProperty column = table.requireColumn(condition.column());
			appendCondition(where, column, condition, binds);
		}
		String logicColumn = table.logicDeleteColumn();
		if (logicColumn != null && !query.references(logicColumn)) {
			MetaProperty column = table.requireColumn(logicColumn);
			if (!where.isEmpty()) {
				where.append(" AND ");
			}
			where.append(quote(column.getName())).append(" = ?");
			binds.add(column, 0);
		}
		if (!where.isEmpty()) {
			sql.append(" WHERE ").append(where);
		}
	}

	private void appendCondition(StringBuilder where, MetaProperty column, MetaQuery.Condition condition,
			Binds binds) {
		if (!where.isEmpty()) {
			where.append(" AND ");
		}
		String quoted = quote(column.getName());
		switch (condition.op()) {
			case EQ -> {
				where.append(quoted).append(" = ?");
				binds.add(column, condition.values().get(0));
			}
			case NE -> {
				where.append(quoted).append(" <> ?");
				binds.add(column, condition.values().get(0));
			}
			case GT -> {
				where.append(quoted).append(" > ?");
				binds.add(column, condition.values().get(0));
			}
			case GE -> {
				where.append(quoted).append(" >= ?");
				binds.add(column, condition.values().get(0));
			}
			case LT -> {
				where.append(quoted).append(" < ?");
				binds.add(column, condition.values().get(0));
			}
			case LE -> {
				where.append(quoted).append(" <= ?");
				binds.add(column, condition.values().get(0));
			}
			case LIKE -> {
				where.append(quoted).append(" LIKE ?");
				binds.add(column, condition.values().get(0));
			}
			case IN -> {
				where.append(quoted).append(" IN (");
				for (int i = 0; i < condition.values().size(); i++) {
					if (i > 0) {
						where.append(", ");
					}
					where.append('?');
					binds.add(column, condition.values().get(i));
				}
				where.append(')');
			}
			case BETWEEN -> {
				where.append(quoted).append(" BETWEEN ? AND ?");
				binds.add(column, condition.values().get(0));
				binds.add(column, condition.values().get(1));
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

	/**
	 * 抽取阶段的槽位收集器：每写一个 {@code ?} 就同时记下「引用的是哪一列」和「这次的实参」。
	 *
	 * <p>两者与 SQL 文本在同一次遍历里按同一顺序产出，因此不会出现文本与参数各自成形、
	 * 事后靠下标对齐的静默错位；个数对不上时由 {@link MarkedSql} 的构造校验兜住。</p>
	 */
	private static final class Binds {

		private final List<String> refs = new ArrayList<>();

		private final List<Object> values = new ArrayList<>();

		void add(MetaProperty column, Object value) {
			this.refs.add(column.getName());
			this.values.add(value);
		}

		MarkedSql of(String sql) {
			return new MarkedSql(sql, this.refs, this.values);
		}
	}
}
