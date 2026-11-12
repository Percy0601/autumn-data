package xyz.wewin.autumn.data.meta.jdbc.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 动态表的元数据快照：{@link MetaClass} 的物理表视图。
 *
 * <p>不另建列模型：表名 = {@link MetaClass#getName()}，schema = {@link MetaClass#getNamespace()}，
 * 列 = {@link MetaProperty}（JDBC 类型、可空、主键、自增都在属性上）。</p>
 *
 * <p>实例不可变，由 {@link MetaTableRegistry} 缓存在有界 LRU {@link BoundedCache} 里；
 * 表结构变化后调用 {@link MetaTableRegistry#refresh(String)} 移除旧快照、下次访问重建，
 * 不影响已经拿到旧快照的执行中查询。</p>
 */
public final class MetaTable {

	private final MetaClass metaClass;

	private final List<MetaProperty> columns;

	private final List<MetaProperty> primaryKeys;

	private final Map<String, MetaProperty> lookup;

	private final String logicDeleteColumn;

	private final String fingerprint;

	public MetaTable(MetaClass metaClass, String logicDeleteColumn) {
		if (metaClass == null || metaClass.getName() == null || metaClass.getName().isBlank()) {
			throw new IllegalArgumentException("metaClass name must not be blank");
		}
		if (metaClass.getProperties() == null) {
			throw new IllegalArgumentException("metaClass properties must not be null");
		}
		this.metaClass = metaClass;
		this.columns = List.copyOf(metaClass.getProperties());
		this.lookup = new LinkedHashMap<>();
		for (MetaProperty column : this.columns) {
			if (column.getName() == null || column.getName().isBlank()) {
				throw new IllegalArgumentException("column name must not be blank on table '" + metaClass.getName() + "'");
			}
			MetaProperty previous = this.lookup.putIfAbsent(normalize(column.getName()), column);
			if (previous != null) {
				throw new IllegalArgumentException(
						"duplicate column '" + column.getName() + "' on table '" + metaClass.getName() + "'");
			}
		}
		List<MetaProperty> keys = new ArrayList<>();
		for (MetaProperty column : this.columns) {
			if (column.isPrimaryKey()) {
				keys.add(column);
			}
		}
		this.primaryKeys = List.copyOf(keys);
		this.logicDeleteColumn = logicDeleteColumn;
		this.fingerprint = fingerprint(metaClass.getName(), metaClass.getNamespace(), this.columns);
	}

	/** 底层的 {@link MetaClass}；列即其 properties。 */
	public MetaClass metaClass() {
		return this.metaClass;
	}

	/** 物理表名（与数据库元数据一致）。 */
	public String name() {
		return this.metaClass.getName();
	}

	/** 表所在 schema（诊断用；SQL 只引用表名，依赖连接的默认 schema）。 */
	public String schema() {
		return this.metaClass.getNamespace();
	}

	/** 全部列，按数据库的 ordinal position 排序。 */
	public List<MetaProperty> columns() {
		return this.columns;
	}

	/** 主键列（按列顺序）。 */
	public List<MetaProperty> primaryKeys() {
		return this.primaryKeys;
	}

	/** 单主键；复合主键或无主键返回 {@code null}。 */
	public MetaProperty singlePrimaryKey() {
		return this.primaryKeys.size() == 1 ? this.primaryKeys.get(0) : null;
	}

	/** 自增列；没有返回 {@code null}。 */
	public MetaProperty autoIncrementColumn() {
		for (MetaProperty column : this.columns) {
			if (column.isAutoIncrement()) {
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
	public MetaProperty findColumn(String column) {
		if (column == null) {
			return null;
		}
		return this.lookup.get(normalize(column));
	}

	/** 按列名查列（大小写不敏感）；不存在抛 {@link MetaSqlException}。 */
	public MetaProperty requireColumn(String column) {
		MetaProperty found = findColumn(column);
		if (found == null) {
			throw new MetaSqlException("unknown column '" + column + "' on table '" + this.metaClass.getName() + "'");
		}
		return found;
	}

	/** 该列是否存在。 */
	public boolean hasColumn(String column) {
		return findColumn(column) != null;
	}

	/**
	 * 结构指纹：表名、schema 与全部列的名字/类型/主键/自增/可空一起参与哈希。
	 *
	 * <p>它是「这一版结构」的身份证号：动态实体类按它定包名，执行计划按它做缓存键 ——
	 * 结构一变，两边都自动落到新条目上，不会出现旧类/旧计划配新列的静默错位。</p>
	 */
	public String fingerprint() {
		return this.fingerprint;
	}

	/** 与 {@link #fingerprint()} 同样的输入，只在快照构造时算一次。 */
	private static String fingerprint(String tableName, String schema, List<MetaProperty> columns) {
		StringBuilder raw = new StringBuilder(256);
		raw.append(tableName).append('|').append(schema).append('|');
		for (MetaProperty column : columns) {
			raw.append(column.getName()).append(':')
					.append(column.getJdbcType()).append(':')
					.append(column.getType()).append(':')
					.append(column.getSqlTypeName()).append(':')
					.append(column.isNullable() ? '1' : '0').append(':')
					.append(column.isPrimaryKey() ? '1' : '0').append(':')
					.append(column.isAutoIncrement() ? '1' : '0').append(';');
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(24);
			for (int i = 0; i < 12; i++) {
				hex.append(Character.forDigit((digest[i] >>> 4) & 0xF, 16));
				hex.append(Character.forDigit(digest[i] & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new MetaSqlException("SHA-256 is unavailable", ex);
		}
	}

	private static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
