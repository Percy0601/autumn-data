package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import xyz.wewin.autumn.data.common.dialect.Dialect;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 动态表元数据注册表：表名 → {@link MetaTable} 快照。
 *
 * <p>元数据默认直接从 {@link DatabaseMetaData} 读取，因此“运行期建表 → 不改代码/不重启服务”
 * 即可查询；建表/加列/删列提交后调用 {@link #refresh(String)} 换新快照，旧快照上正在执行的语句
 * 不受影响。</p>
 *
 * <p>缓存键大小写不敏感，值是不可变 {@link MetaTable}；{@link ConcurrentHashMap} 只保证“同一个 key
 * 只加载一次”，刷新用 remove + 下次按需加载实现，不做全局锁。</p>
 */
public final class MetaTableRegistry {

	private final DataSource dataSource;

	private final Dialect dialect;

	private final Set<String> logicDeleteColumns;

	private final ConcurrentHashMap<String, MetaTable> cache = new ConcurrentHashMap<>();

	public MetaTableRegistry(DataSource dataSource, Dialect dialect) {
		this(dataSource, dialect, Set.of());
	}

	/**
	 * @param logicDeleteColumns 逻辑删除列名（大小写不敏感）；查询未显式引用时自动追加 {@code = 0}
	 */
	public MetaTableRegistry(DataSource dataSource, Dialect dialect, Set<String> logicDeleteColumns) {
		this.dataSource = dataSource;
		this.dialect = dialect;
		this.logicDeleteColumns = Set.copyOf(logicDeleteColumns.stream()
				.map(name -> name.toLowerCase(Locale.ROOT)).toList());
	}

	/** 取表描述；首次访问从数据库元数据加载。 */
	public MetaTable require(String tableName) {
		if (tableName == null || tableName.isBlank()) {
			throw new MetaSqlException("table name must not be blank");
		}
		String key = normalize(tableName);
		return this.cache.computeIfAbsent(key, ignored -> introspect(tableName));
	}

	/** 手工登记/覆盖一张表的描述（业务元数据覆盖、测试或非标准数据源用）。 */
	public MetaTable register(MetaTable table) {
		this.cache.put(normalize(table.name()), table);
		return table;
	}

	/** 表结构变化后刷新；下次访问重新读取元数据。 */
	public void refresh(String tableName) {
		this.cache.remove(normalize(tableName));
	}

	/** 清空全部快照。 */
	public void refreshAll() {
		this.cache.clear();
	}

	private MetaTable introspect(String requested) {
		try (Connection connection = this.dataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			String catalog = safeCatalog(connection);
			String schema = safeSchema(connection);
			TableRef ref = findTable(metaData, catalog, schema, requested);
			List<MetaColumn> columns = readColumns(metaData, catalog, ref.schema(), ref.name());
			if (columns.isEmpty()) {
				throw new MetaSqlException("table '" + requested + "' has no readable columns");
			}
			List<MetaColumn> withKeys = markPrimaryKeys(metaData, catalog, ref.schema(), ref.name(), columns);
			String logicDelete = detectLogicDelete(withKeys);
			return new MetaTable(ref.name(), ref.schema(), withKeys, logicDelete);
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

	private List<MetaColumn> readColumns(DatabaseMetaData metaData, String catalog, String schema, String table)
			throws SQLException {
		List<MetaColumn> columns = readColumnsOnce(metaData, catalog, schema, table);
		if (columns.isEmpty() && schema != null) {
			columns = readColumnsOnce(metaData, catalog, null, table);
		}
		return columns;
	}

	private List<MetaColumn> readColumnsOnce(DatabaseMetaData metaData, String catalog, String schema, String table)
			throws SQLException {
		List<MetaColumn> columns = new ArrayList<>();
		try (ResultSet rs = metaData.getColumns(catalog, schema, table, "%")) {
			while (rs.next()) {
				int jdbcType = rs.getInt("DATA_TYPE");
				String typeName = rs.getString("TYPE_NAME");
				int nullableCode = rs.getInt("NULLABLE");
				boolean nullable = nullableCode != DatabaseMetaData.columnNoNulls;
				String autoIncrement = rs.getString("IS_AUTOINCREMENT");
				columns.add(new MetaColumn(rs.getString("COLUMN_NAME"), jdbcType, typeName, nullable,
						"YES".equalsIgnoreCase(autoIncrement), false, rs.getString("COLUMN_DEF")));
			}
		}
		return columns;
	}

	private List<MetaColumn> markPrimaryKeys(DatabaseMetaData metaData, String catalog, String schema, String table,
			List<MetaColumn> columns) throws SQLException {
		Map<String, Integer> keyOrder = readPrimaryKeyOrder(metaData, catalog, schema, table);
		if (keyOrder.isEmpty() && schema != null) {
			keyOrder = readPrimaryKeyOrder(metaData, catalog, null, table);
		}
		if (keyOrder.isEmpty()) {
			return columns;
		}
		List<MetaColumn> marked = new ArrayList<>(columns.size());
		for (MetaColumn column : columns) {
			marked.add(keyOrder.containsKey(normalize(column.name())) ? column.asPrimaryKey() : column);
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

	private String detectLogicDelete(List<MetaColumn> columns) {
		for (MetaColumn column : columns) {
			if (this.logicDeleteColumns.contains(normalize(column.name()))) {
				return column.name();
			}
		}
		return null;
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
