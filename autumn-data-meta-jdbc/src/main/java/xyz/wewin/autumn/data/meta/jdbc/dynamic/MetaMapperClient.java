package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.SqlBuild;

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

	private final int defaultListLimit;

	public MetaMapperClient(String poolName, AutumnJdbc jdbc, ConnectionSource connectionSource,
			MetaTableRegistry registry, MetaSqlBuilder sqlBuilder, int defaultListLimit) {
		if (defaultListLimit <= 0) {
			throw new IllegalArgumentException("defaultListLimit must be > 0");
		}
		this.poolName = poolName;
		this.jdbc = jdbc;
		this.connectionSource = connectionSource;
		this.registry = registry;
		this.sqlBuilder = sqlBuilder;
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
		MetaColumn pk = table.singlePrimaryKey();
		if (pk == null) {
			throw new MetaSqlException("table '" + tableName + "' has no single primary key; use findByKey(...)");
		}
		return findOne(tableName, MetaQuery.by(pk.name(), id));
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
			SqlBuild build = this.sqlBuilder.select(table, query);
			return this.jdbc.query(limit, build.sql(), MetaRow.mapper(table), build.args());
		});
	}

	public Page<MetaRow> findPage(String tableName, MetaQuery query, Pageable pageable) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlBuild select = this.sqlBuilder.select(table, query);
			SqlBuild count = this.sqlBuilder.count(table, query);
			return this.jdbc.page(select.sql(), count.sql(), MetaRow.mapper(table), pageable, select.args());
		});
	}

	public long count(String tableName, MetaQuery query) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlBuild build = this.sqlBuilder.count(table, query);
			return this.jdbc.count(build.sql(), build.args());
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
		SqlBuild build = this.sqlBuilder.insert(table, values);
		MetaColumn pk = table.singlePrimaryKey();
		if (pk != null && pk.autoIncrement()) {
			return withConnection(() -> this.jdbc.insertReturningKey(build.sql(), pk.name(), build.args()));
		}
		withConnection(() -> this.jdbc.update(build.sql(), build.args()));
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
		List<SqlBuild> builds = new ArrayList<>(rows.size());
		for (Map<String, ?> row : rows) {
			builds.add(this.sqlBuilder.insert(table, row));
		}
		MetaColumn pk = table.singlePrimaryKey();
		boolean sameSql = builds.stream().allMatch(build -> build.sql().equals(builds.get(0).sql()));
		if (sameSql) {
			List<Object[]> args = builds.stream().map(SqlBuild::args).toList();
			if (pk != null && pk.autoIncrement()) {
				List<Object> keys = new ArrayList<>(rows.size());
				withConnection(() -> this.jdbc.batchInsertReturningKeys(builds.get(0).sql(), pk.name(), args))
						.forEach(keys::add);
				return keys;
			}
			withConnection(() -> this.jdbc.batchUpdate(builds.get(0).sql(), args));
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
		MetaColumn pk = table.singlePrimaryKey();
		if (pk == null) {
			throw new MetaSqlException("table '" + tableName + "' has no single primary key; use updateByKey(...)");
		}
		return updateByKey(tableName, Map.of(pk.name(), id), values);
	}

	/**
	 * 按主键（含复合主键）更新；只更新传入的列。
	 */
	public int updateByKey(String tableName, Map<String, ?> key, Map<String, ?> values) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlBuild build = this.sqlBuilder.update(table, key, values);
			return this.jdbc.update(build.sql(), build.args());
		});
	}

	/** 表结构变化后刷新元数据快照。 */
	public void refresh(String tableName) {
		this.registry.refresh(tableName);
	}

	/** 刷新当前客户端可见的全部表快照。 */
	public void refreshAll() {
		this.registry.refreshAll();
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
			MetaColumn column = table.requireColumn(entry.getKey());
			if (!column.primaryKey()) {
				throw new MetaSqlException("column '" + column.name() + "' is not a primary key of table '"
						+ table.name() + "'");
			}
			query = query.and(column.name(), entry.getValue());
		}
		for (MetaColumn column : table.primaryKeys()) {
			boolean present = key.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(column.name()));
			if (!present) {
				throw new MetaSqlException("missing primary key column '" + column.name() + "' for table '"
						+ table.name() + "'");
			}
		}
		return query;
	}

	private static Object primaryKeyValue(MetaColumn pk, Map<String, ?> values) {
		for (Map.Entry<String, ?> entry : values.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(pk.name())) {
				return MetaTypeCoercion.toJdbc(pk, entry.getValue());
			}
		}
		return null;
	}
}
