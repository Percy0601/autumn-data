package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.common.meta.MetaType;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 动态表元数据注册表：表名 → {@link MetaTable} 快照。
 *
 * <p>不另建元模型：读取 {@link DatabaseMetaData} 后填入 {@link MetaClass} / {@link MetaProperty}，
 * 其中 {@code MetaClass.name} 是表名、{@code namespace} 是 schema、properties 是列。
 * 元数据默认直接从数据库读取，因此“运行期建表 → 不改代码/不重启服务”即可查询；
 * 建表/加列/删列提交后调用 {@link #refresh(String)} 换新快照。</p>
 *
 * <p>缓存键大小写不敏感，值是不可变 {@link MetaTable}。快照走 {@link BoundedCache} 的有界 LRU：
 * 上限默认一万张表，触顶后淘汰最久未访问的表（下次访问重新读元数据即可，不影响正确性），
 * 因此表数量再大也不会把元数据常驻内存。同一个 key 并发未命中时只加载一次、加载不持有全局锁。</p>
 *
 * <p>手工 {@link #register(MetaTable)} 的覆盖同样走有界 LRU：低代码场景下「表」本身就是运行期
 * 设计出来的，任何登记都不该永久常驻。覆盖条目触顶淘汰后，{@code require} 会像普通表一样
 * <b>懒加载回源</b>到 {@code DatabaseMetaData}；需要长期保真就调大 {@code maximumSize} 或重新登记。
 * {@link #refresh(String)} 仍按原语义同时清掉该表的快照与覆盖。</p>
 */
public final class MetaTableRegistry {

	private final DataSource dataSource;

	private final Dialect dialect;

	private final Set<String> logicDeleteColumns;

	/** 数据库元数据读出来的表结构快照。 */
	private final BoundedCache<String, MetaTable> cache;

	/** 使用方手工登记的结构覆盖，同样有界、可淘汰。 */
	private final BoundedCache<String, MetaTable> overrides;

	public MetaTableRegistry(DataSource dataSource, Dialect dialect) {
		this(dataSource, dialect, Set.of(), BoundedCache.DEFAULT_MAXIMUM_SIZE);
	}

	/**
	 * @param logicDeleteColumns 逻辑删除列名（大小写不敏感）；查询未显式引用时自动追加 {@code = 0}
	 */
	public MetaTableRegistry(DataSource dataSource, Dialect dialect, Set<String> logicDeleteColumns) {
		this(dataSource, dialect, logicDeleteColumns, BoundedCache.DEFAULT_MAXIMUM_SIZE);
	}

	/**
	 * @param maximumSize 表结构快照的缓存上限（默认 {@value BoundedCache#DEFAULT_MAXIMUM_SIZE}）
	 */
	public MetaTableRegistry(DataSource dataSource, Dialect dialect, Set<String> logicDeleteColumns,
			int maximumSize) {
		this.dataSource = dataSource;
		this.dialect = dialect;
		this.logicDeleteColumns = Set.copyOf(logicDeleteColumns.stream()
				.map(name -> name.toLowerCase(Locale.ROOT)).toList());
		this.cache = BoundedCaches.lru(maximumSize);
		this.overrides = BoundedCaches.lru(maximumSize);
	}

	/** 取表描述；首次访问从数据库元数据加载。 */
	public MetaTable require(String tableName) {
		if (tableName == null || tableName.isBlank()) {
			throw new MetaSqlException("table name must not be blank");
		}
		String key = normalize(tableName);
		MetaTable override = this.overrides.getIfPresent(key);
		if (override != null) {
			return override;
		}
		return this.cache.get(key, ignored -> introspect(tableName));
	}

	/** 手工登记/覆盖一张表的描述（业务元数据覆盖、测试或非标准数据源用）。 */
	public MetaTable register(MetaTable table) {
		this.overrides.put(normalize(table.name()), table);
		return table;
	}

	/** 表结构变化后刷新；下次访问重新读取元数据。 */
	public void refresh(String tableName) {
		this.cache.invalidate(normalize(tableName));
		this.overrides.invalidate(normalize(tableName));
	}

	/** 清空全部快照（含手工登记）。 */
	public void refreshAll() {
		this.cache.invalidateAll();
		this.overrides.invalidateAll();
	}

	/** 已缓存的表数（元数据快照 + 手工覆盖，诊断用）。 */
	public long size() {
		return this.cache.estimatedSize() + this.overrides.estimatedSize();
	}

	/** 缓存上限（诊断用）。 */
	public int maximumSize() {
		return this.cache.maximumSize();
	}

	private MetaTable introspect(String requested) {
		try (Connection connection = this.dataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			String catalog = safeCatalog(connection);
			String schema = safeSchema(connection);
			TableRef ref = findTable(metaData, catalog, schema, requested);
			List<MetaProperty> columns = readColumns(metaData, catalog, ref.schema(), ref.name());
			if (columns.isEmpty()) {
				throw new MetaSqlException("table '" + requested + "' has no readable columns");
			}
			List<MetaProperty> withKeys = markPrimaryKeys(metaData, catalog, ref.schema(), ref.name(), columns);
			String logicDelete = detectLogicDelete(withKeys);
			MetaClass metaClass = new MetaClass();
			metaClass.setName(ref.name());
			metaClass.setNamespace(ref.schema());
			metaClass.setMetaType(MetaType.META_TYPE_ENTITY);
			metaClass.setProperties(withKeys);
			return new MetaTable(metaClass, logicDelete);
		}
		catch (SQLException ex) {
			throw new MetaSqlException("failed to read metadata for table '" + requested + "'", ex);
		}
	}

	private TableRef findTable(DatabaseMetaData metaData, String catalog, String schema, String requested)
			throws SQLException {
		TableRef exact = scanTables(metaData, catalog, schema, requested, false);
		if (exact != null) {
			return exact;
		}
		TableRef fuzzy = scanTables(metaData, catalog, schema, requested, true);
		if (fuzzy != null) {
			return fuzzy;
		}
		if (schema != null) {
			exact = scanTables(metaData, catalog, null, requested, false);
			if (exact != null) {
				return exact;
			}
			fuzzy = scanTables(metaData, catalog, null, requested, true);
			if (fuzzy != null) {
				return fuzzy;
			}
		}
		throw new MetaSqlException("table not found in database metadata: " + requested);
	}

	private TableRef scanTables(DatabaseMetaData metaData, String catalog, String schema, String requested,
			boolean fuzzy) throws SQLException {
		try (ResultSet rs = metaData.getTables(catalog, schema, fuzzy ? "%" : requested, null)) {
			while (rs.next()) {
				String type = rs.getString("TABLE_TYPE");
				if (type != null && type.toUpperCase(Locale.ROOT).contains("VIEW")) {
					continue;
				}
				String name = rs.getString("TABLE_NAME");
				boolean hit = fuzzy ? requested.equalsIgnoreCase(name) : requested.equals(name);
				if (hit) {
					return new TableRef(name, rs.getString("TABLE_SCHEM"));
				}
			}
		}
		return null;
	}

	private List<MetaProperty> readColumns(DatabaseMetaData metaData, String catalog, String schema, String table)
			throws SQLException {
		List<MetaProperty> columns = readColumnsOnce(metaData, catalog, schema, table);
		if (columns.isEmpty() && schema != null) {
			columns = readColumnsOnce(metaData, catalog, null, table);
		}
		return columns;
	}

	private List<MetaProperty> readColumnsOnce(DatabaseMetaData metaData, String catalog, String schema,
			String table) throws SQLException {
		List<MetaProperty> columns = new ArrayList<>();
		try (ResultSet rs = metaData.getColumns(catalog, schema, table, "%")) {
			while (rs.next()) {
				int jdbcType = rs.getInt("DATA_TYPE");
				String typeName = rs.getString("TYPE_NAME");
				int nullableCode = rs.getInt("NULLABLE");
				String autoIncrement = rs.getString("IS_AUTOINCREMENT");
				MetaProperty column = new MetaProperty();
				column.setName(rs.getString("COLUMN_NAME"));
				column.setJdbcType(jdbcType);
				column.setSqlTypeName(typeName);
				column.setType(javaTypeName(jdbcType));
				column.setNullable(nullableCode != DatabaseMetaData.columnNoNulls);
				column.setAutoIncrement("YES".equalsIgnoreCase(autoIncrement));
				column.setDefaultValue(rs.getString("COLUMN_DEF"));
				columns.add(column);
			}
		}
		return columns;
	}

	private List<MetaProperty> markPrimaryKeys(DatabaseMetaData metaData, String catalog, String schema, String table,
			List<MetaProperty> columns) throws SQLException {
		Map<String, Integer> keyOrder = readPrimaryKeyOrder(metaData, catalog, schema, table);
		if (keyOrder.isEmpty() && schema != null) {
			keyOrder = readPrimaryKeyOrder(metaData, catalog, null, table);
		}
		if (keyOrder.isEmpty()) {
			return columns;
		}
		List<MetaProperty> marked = new ArrayList<>(columns.size());
		for (MetaProperty column : columns) {
			marked.add(keyOrder.containsKey(normalize(column.getName())) ? asPrimaryKey(column) : column);
		}
		return marked;
	}

	private Map<String, Integer> readPrimaryKeyOrder(DatabaseMetaData metaData, String catalog, String schema,
			String table) throws SQLException {
		Map<String, Integer> order = new LinkedHashMap<>();
		try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, table)) {
			while (rs.next()) {
				order.put(normalize(rs.getString("COLUMN_NAME")), rs.getInt("KEY_SEQ"));
			}
		}
		return order;
	}

	private String detectLogicDelete(List<MetaProperty> columns) {
		for (MetaProperty column : columns) {
			if (this.logicDeleteColumns.contains(normalize(column.getName()))) {
				return column.getName();
			}
		}
		return null;
	}

	private static MetaProperty asPrimaryKey(MetaProperty column) {
		MetaProperty key = copy(column);
		key.setPrimaryKey(true);
		return key;
	}

	private static MetaProperty copy(MetaProperty source) {
		MetaProperty property = new MetaProperty();
		property.setName(source.getName());
		property.setType(source.getType());
		property.setTypeName(source.getTypeName());
		property.setJdbcType(source.getJdbcType());
		property.setSqlTypeName(source.getSqlTypeName());
		property.setNullable(source.isNullable());
		property.setPrimaryKey(source.isPrimaryKey());
		property.setAutoIncrement(source.isAutoIncrement());
		property.setDefaultValue(source.getDefaultValue());
		return property;
	}

	private static String javaTypeName(int jdbcType) {
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

	private static String safeCatalog(Connection connection) {
		try {
			return connection.getCatalog();
		}
		catch (SQLException | AbstractMethodError ex) {
			return null;
		}
	}

	private static String safeSchema(Connection connection) {
		try {
			return connection.getSchema();
		}
		catch (SQLException | AbstractMethodError ex) {
			return null;
		}
	}

	private static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	private record TableRef(String name, String schema) {
	}
}
