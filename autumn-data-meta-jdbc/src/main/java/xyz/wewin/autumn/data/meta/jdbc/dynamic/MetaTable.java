package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 动态表的元数据快照：列清单、主键、自增列，以及可选的逻辑删除列。
 *
 * <p>实例不可变，由 {@link MetaTableRegistry} 缓存在 {@link java.util.concurrent.ConcurrentHashMap} 里；
 * 表结构变化后调用 {@link MetaTableRegistry#refresh(String)} 移除旧快照、下次访问重建，
 * 不影响已经拿到旧快照的执行中查询。</p>
 */
public final class MetaTable {

	private final String name;

	private final String schema;

	private final List<MetaColumn> columns;

	private final List<MetaColumn> primaryKeys;

	private final Map<String, MetaColumn> lookup;

	private final String logicDeleteColumn;

	public MetaTable(String name, String schema, List<MetaColumn> columns) {
		this(name, schema, columns, null);
	}

	public MetaTable(String name, String schema, List<MetaColumn> columns, String logicDeleteColumn) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("table name must not be blank");
		}
		this.name = name;
		this.schema = schema;
		this.columns = List.copyOf(columns);
		this.lookup = new LinkedHashMap<>();
		for (MetaColumn column : this.columns) {
			MetaColumn previous = this.lookup.putIfAbsent(normalize(column.name()), column);
			if (previous != null) {
				throw new IllegalArgumentException("duplicate column '" + column.name() + "' on table '" + name + "'");
			}
		}
		List<MetaColumn> keys = new ArrayList<>();
		for (MetaColumn column : this.columns) {
			if (column.primaryKey()) {
				keys.add(column);
			}
		}
		this.primaryKeys = List.copyOf(keys);
		this.logicDeleteColumn = logicDeleteColumn;
	}

	/** 物理表名（与数据库元数据一致）。 */
	public String name() {
		return this.name;
	}

	/** 表所在 schema（诊断用；SQL 只引用表名，依赖连接的默认 schema）。 */
	public String schema() {
		return this.schema;
	}

	/** 全部列，按数据库的 ordinal position 排序。 */
	public List<MetaColumn> columns() {
		return this.columns;
	}

	/** 主键列（按列顺序）。 */
	public List<MetaColumn> primaryKeys() {
		return this.primaryKeys;
	}

	/** 单主键；复合主键或无主键返回 {@code null}。 */
	public MetaColumn singlePrimaryKey() {
		return this.primaryKeys.size() == 1 ? this.primaryKeys.get(0) : null;
	}

	/** 自增列；没有返回 {@code null}。 */
	public MetaColumn autoIncrementColumn() {
		for (MetaColumn column : this.columns) {
			if (column.autoIncrement()) {
				return column;
			}
		}
		return null;
	}

	/** 逻辑删除列（未配置/未命中返回 {@code null}）。 */
	public String logicDeleteColumn() {
		return this.logicDeleteColumn;
	}

	/** 按列名查列（大小写不敏感）；不存在返回 {@code null}。 */
	public MetaColumn findColumn(String column) {
		if (column == null) {
			return null;
		}
		return this.lookup.get(normalize(column));
	}

	/** 按列名查列（大小写不敏感）；不存在抛 {@link MetaSqlException}。 */
	public MetaColumn requireColumn(String column) {
		MetaColumn found = findColumn(column);
		if (found == null) {
			throw new MetaSqlException("unknown column '" + column + "' on table '" + this.name + "'");
		}
		return found;
	}

	/** 该列是否存在。 */
	public boolean hasColumn(String column) {
		return findColumn(column) != null;
	}

	private static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
