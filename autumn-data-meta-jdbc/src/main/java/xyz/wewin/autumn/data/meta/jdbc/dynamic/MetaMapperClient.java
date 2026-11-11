package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;

/**
 * 绑定到某个数据源（池）的动态 CRUD 客户端。
 *
 * <p>{@link MetaMapper} 的静态方法默认走默认池；多数据源显式用 {@link MetaMapper#of(String)}
 * 拿到本类型，把“哪个库”固定在调用边界上，而不是藏在线程上下文里。表名/列名/类型仍然在每次
 * 调用时从 {@link MetaTableRegistry} 解析，所以运行期建表后无需重启。</p>
 *
 * <p>只提供查询、保存、更新；<b>不提供物理删除</b>。业务上的作废用一次
 * {@code updateById(..., Map.of("status", "VOID"))}。</p>
 */
public final class MetaMapperClient {

	private static final Logger LOG = Logger.getLogger(MetaMapperClient.class.getName());

	private final String poolName;

	private final AutumnJdbc jdbc;

	private final ConnectionSource connectionSource;

	private final MetaTableRegistry registry;

	private final MetaSqlBuilder sqlBuilder;

	private final MetaMapperXmlStore xmlStore;

	private final int defaultListLimit;

	public MetaMapperClient(String poolName, AutumnJdbc jdbc, ConnectionSource connectionSource,
			MetaTableRegistry registry, MetaSqlBuilder sqlBuilder, int defaultListLimit) {
		this(poolName, jdbc, connectionSource, registry, sqlBuilder, null, defaultListLimit);
	}

	public MetaMapperClient(String poolName, AutumnJdbc jdbc, ConnectionSource connectionSource,
			MetaTableRegistry registry, MetaSqlBuilder sqlBuilder, MetaMapperXmlStore xmlStore, int defaultListLimit) {
		if (defaultListLimit <= 0) {
			throw new IllegalArgumentException("defaultListLimit must be > 0");
		}
		this.poolName = poolName;
		this.jdbc = jdbc;
		this.connectionSource = connectionSource;
		this.registry = registry;
		this.sqlBuilder = sqlBuilder;
		this.xmlStore = xmlStore;
		this.defaultListLimit = defaultListLimit;
	}

	/** 该客户端绑定的池名（默认池也在这里显示）。 */
	public String poolName() {
		return this.poolName;
	}

	// ------------------------------------------------------------------
	// 查询
	// ------------------------------------------------------------------

	public Optional<MetaRow> findById(String tableName, Object id) {
		MetaTable table = this.registry.require(tableName);
		MetaProperty pk = table.singlePrimaryKey();
		if (pk == null) {
			throw new MetaSqlException("table '" + tableName + "' has no single primary key; use findByKey(...)");
		}
		return findOne(tableName, MetaQuery.by(pk.getName(), id));
	}

	public Optional<MetaRow> findByKey(String tableName, Map<String, ?> key) {
		MetaTable table = this.registry.require(tableName);
		if (table.primaryKeys().isEmpty()) {
			throw new MetaSqlException("table '" + tableName + "' has no primary key");
		}
		return findOne(tableName, queryOfKey(table, key));
	}

	public Optional<MetaRow> findOne(String tableName, MetaQuery query) {
		List<MetaRow> rows = findList(tableName, query, 1);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	public List<MetaRow> findList(String tableName, MetaQuery query) {
		return findList(tableName, query, this.defaultListLimit);
	}

	/**
	 * 查询列表并限制返回行数（防止动态查询一次拉全表）。
	 */
	public List<MetaRow> findList(String tableName, MetaQuery query, long limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be > 0");
		}
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			MetaSqlBuilder.SelectParts parts = this.sqlBuilder.selectParts(table, query);
			String sql = this.xmlStore == null ? this.sqlBuilder.select(table, query).sql()
					: this.xmlStore.render(table, "findList", selectModel(table, parts));
			return this.jdbc.query(limit, sql, MetaRow.mapper(table), parts.args());
		});
	}

	public Page<MetaRow> findPage(String tableName, MetaQuery query, Pageable pageable) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			MetaSqlBuilder.SelectParts selectParts = this.sqlBuilder.selectParts(table, query);
			MetaSqlBuilder.CountParts countParts = this.sqlBuilder.countParts(table, query);
			String selectSql = this.xmlStore == null ? this.sqlBuilder.select(table, query).sql()
					: this.xmlStore.render(table, "findList", selectModel(table, selectParts));
			String countSql = this.xmlStore == null ? this.sqlBuilder.count(table, query).sql()
					: this.xmlStore.render(table, "count", countModel(table, countParts));
			return this.jdbc.page(selectSql, countSql, MetaRow.mapper(table), pageable, selectParts.args());
		});
	}

	public long count(String tableName, MetaQuery query) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			MetaSqlBuilder.CountParts parts = this.sqlBuilder.countParts(table, query);
			String sql = this.xmlStore == null ? this.sqlBuilder.count(table, query).sql()
					: this.xmlStore.render(table, "count", countModel(table, parts));
			return this.jdbc.count(sql, parts.args());
		});
	}

	public boolean exists(String tableName, MetaQuery query) {
		return count(tableName, query) > 0;
	}

	// ------------------------------------------------------------------
	// 保存（只做 INSERT）
	// ------------------------------------------------------------------

	/**
	 * 新增一行。
	 *
	 * @return 自增主键回填值；非自增主键返回调用方提供的值；无主键返回 {@code null}
	 */
	public Object save(String tableName, Map<String, ?> values) {
		MetaTable table = this.registry.require(tableName);
		MetaSqlBuilder.InsertParts parts = this.sqlBuilder.insertParts(table, values);
		String sql = this.xmlStore == null ? this.sqlBuilder.insert(table, values).sql()
				: this.xmlStore.render(table, "insert", insertModel(table, parts));
		MetaProperty pk = table.singlePrimaryKey();
		if (pk != null && pk.isAutoIncrement()) {
			return withConnection(() -> this.jdbc.insertReturningKey(sql, pk.getName(), parts.args()));
		}
		withConnection(() -> this.jdbc.update(sql, parts.args()));
		return pk == null ? null : primaryKeyValue(pk, values);
	}

	/**
	 * 批量新增；列集合一致时走 JDBC batch，否则退化为逐条 save。
	 *
	 * @return 按入参顺序排列的主键列表；无主键返回空列表
	 */
	public List<Object> saveAll(String tableName, List<Map<String, ?>> rows) {
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		MetaTable table = this.registry.require(tableName);
		List<MetaSqlBuilder.InsertParts> partsList = new ArrayList<>(rows.size());
		for (Map<String, ?> row : rows) {
			partsList.add(this.sqlBuilder.insertParts(table, row));
		}
		MetaSqlBuilder.InsertParts first = partsList.get(0);
		MetaProperty pk = table.singlePrimaryKey();
		boolean sameSql = partsList.stream().allMatch(parts -> parts.columnsSql().equals(first.columnsSql())
				&& parts.valuesSql().equals(first.valuesSql()));
		if (sameSql) {
			String sql = this.xmlStore == null ? this.sqlBuilder.insert(table, rows.get(0)).sql()
					: this.xmlStore.render(table, "insert", insertModel(table, first));
			List<Object[]> args = partsList.stream().map(MetaSqlBuilder.InsertParts::args).toList();
			if (pk != null && pk.isAutoIncrement()) {
				List<Object> keys = new ArrayList<>(rows.size());
				withConnection(() -> this.jdbc.batchInsertReturningKeys(sql, pk.getName(), args))
						.forEach(keys::add);
				return keys;
			}
			withConnection(() -> this.jdbc.batchUpdate(sql, args));
			if (pk == null) {
				return List.of();
			}
			List<Object> keys = new ArrayList<>(rows.size());
			for (Map<String, ?> row : rows) {
				keys.add(primaryKeyValue(pk, row));
			}
			return keys;
		}
		List<Object> keys = new ArrayList<>(rows.size());
		for (Map<String, ?> row : rows) {
			keys.add(save(tableName, row));
		}
		return keys;
	}

	// ------------------------------------------------------------------
	// 更新（部分更新）
	// ------------------------------------------------------------------

	/**
	 * 按单主键更新；只更新传入的列。
	 */
	public int updateById(String tableName, Object id, Map<String, ?> values) {
		MetaTable table = this.registry.require(tableName);
		MetaProperty pk = table.singlePrimaryKey();
		if (pk == null) {
			throw new MetaSqlException("table '" + tableName + "' has no single primary key; use updateByKey(...)");
		}
		return updateByKey(tableName, Map.of(pk.getName(), id), values);
	}

	/**
	 * 按主键（含复合主键）更新；只更新传入的列。
	 */
	public int updateByKey(String tableName, Map<String, ?> key, Map<String, ?> values) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			MetaSqlBuilder.UpdateParts parts = this.sqlBuilder.updateParts(table, key, values);
			String sql = this.xmlStore == null ? this.sqlBuilder.update(table, key, values).sql()
					: this.xmlStore.render(table, "update", updateModel(table, parts));
			return this.jdbc.update(sql, parts.args());
		});
	}

	/** 表结构变化后刷新元数据快照与 Mapper XML 缓存。 */
	public void refresh(String tableName) {
		this.registry.refresh(tableName);
		if (this.xmlStore != null) {
			this.xmlStore.refresh(tableName);
		}
	}

	/** 刷新当前客户端可见的全部表快照。 */
	public void refreshAll() {
		this.registry.refreshAll();
		if (this.xmlStore != null) {
			this.xmlStore.refreshAll();
		}
	}

	// ------------------------------------------------------------------
	// Mapper XML 渲染模型
	// ------------------------------------------------------------------

	private Map<String, Object> selectModel(MetaTable table, MetaSqlBuilder.SelectParts parts) {
		Map<String, Object> model = new HashMap<>();
		model.put("table", this.sqlBuilder.quoteTable(table));
		model.put("columnsSql", parts.columnsSql());
		model.put("whereSql", parts.whereSql());
		model.put("orderSql", parts.orderSql());
		return model;
	}

	private Map<String, Object> countModel(MetaTable table, MetaSqlBuilder.CountParts parts) {
		Map<String, Object> model = new HashMap<>();
		model.put("table", this.sqlBuilder.quoteTable(table));
		model.put("whereSql", parts.whereSql());
		return model;
	}

	private Map<String, Object> insertModel(MetaTable table, MetaSqlBuilder.InsertParts parts) {
		Map<String, Object> model = new HashMap<>();
		model.put("table", this.sqlBuilder.quoteTable(table));
		model.put("insertColumnsSql", parts.columnsSql());
		model.put("insertValuesSql", parts.valuesSql());
		return model;
	}

	private Map<String, Object> updateModel(MetaTable table, MetaSqlBuilder.UpdateParts parts) {
		Map<String, Object> model = new HashMap<>();
		model.put("table", this.sqlBuilder.quoteTable(table));
		model.put("setSql", parts.setSql());
		model.put("keyWhereSql", parts.keyWhereSql());
		return model;
	}

	// ------------------------------------------------------------------
	// 连接生命周期
	// ------------------------------------------------------------------

	/**
	 * 每次操作取当前线程的连接，结束后归还/关闭；同一操作内的多条语句共用同一条连接。
	 */
	private <T> T withConnection(java.util.function.Supplier<T> action) {
		try {
			T result = action.get();
			closeQuietly(null);
			return result;
		}
		catch (RuntimeException ex) {
			closeQuietly(ex);
			throw ex;
		}
	}

	private void closeQuietly(Throwable primary) {
		try {
			this.connectionSource.close();
		}
		catch (SQLException ex) {
			if (primary != null) {
				primary.addSuppressed(ex);
			}
			else {
				LOG.log(Level.WARNING, "failed to release connection for pool '" + this.poolName + "'", ex);
			}
		}
	}

	private static MetaQuery queryOfKey(MetaTable table, Map<String, ?> key) {
		if (key == null || key.isEmpty()) {
			throw new MetaSqlException("primary key values must not be empty for table '" + table.name() + "'");
		}
		MetaQuery query = MetaQuery.all();
		for (Map.Entry<String, ?> entry : key.entrySet()) {
			MetaProperty column = table.requireColumn(entry.getKey());
			if (!column.isPrimaryKey()) {
				throw new MetaSqlException("column '" + column.getName() + "' is not a primary key of table '"
						+ table.name() + "'");
			}
			query = query.and(column.getName(), entry.getValue());
		}
		for (MetaProperty column : table.primaryKeys()) {
			boolean present = key.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(column.getName()));
			if (!present) {
				throw new MetaSqlException("missing primary key column '" + column.getName() + "' for table '"
						+ table.name() + "'");
			}
		}
		return query;
	}

	private static Object primaryKeyValue(MetaProperty pk, Map<String, ?> values) {
		for (Map.Entry<String, ?> entry : values.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(pk.getName())) {
				return MetaTypeCoercion.toJdbc(pk, entry.getValue());
			}
		}
		return null;
	}
}
