package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementType;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.SqlBuild;

/**
 * 绑定到某个数据源（池）的动态客户端。
 *
 * <p>{@link MetaMapper} 的静态方法默认走默认池；多数据源显式用 {@link MetaMapper#of(String)}
 * 拿到本类型，把“哪个库”固定在调用边界上，而不是藏在线程上下文里。表名/列名/类型仍然在每次
 * 调用时从 {@link MetaTableRegistry} 解析，所以运行期建表后无需重启。</p>
 *
 * <p>两组入口各走一条路，不存在“同一次调用可能被两套实现分走”的情况：</p>
 * <ul>
 *   <li>{@link MetaQuery} 驱动的 CRUD（{@code findById/findList/findPage/count/save/updateById}）：
 *       SQL 由 {@link MetaSqlBuilder} 按元数据现拼。条件运算符是开集（{@code >}/{@code LIKE}/
 *       {@code IN}/{@code BETWEEN}/排序……），固定文件表达不了，所以这条路不读 XML；</li>
 *   <li>命名语句（{@code sqlFor/selectList/selectOne/selectCount/execute}）：SQL 来自
 *       {@link MetaStatementStore} 的 Mapper 文件——文件存在就用文件，缺失时按表结构生成一次，
 *       之后归使用方编辑。</li>
 * </ul>
 *
 * <p><b>查询结果一律是 {@link MetaClass}</b>：实例是按当前表结构用 FreeMarker 生成源码、
 * 运行期编译加载的 {@code MetaClass} 子类，有真实类型化字段和 getter；
 * 类型可以通过 {@link #entityType(String)} 取到。完全静态的实体类在运行期建表时做不到，
 * 完全动态的 Map 又没有类型，这一层就是要落在两者之间。</p>
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

	private final MetaStatementStore statementStore;

	private final MetaEntityRegistry entityRegistry;

	private final int defaultListLimit;

	/** 兼容构造：自带一个实体注册表（不额外留存生成的 .java 源码）。 */
	public MetaMapperClient(String poolName, AutumnJdbc jdbc, ConnectionSource connectionSource,
			MetaTableRegistry registry, MetaSqlBuilder sqlBuilder, MetaStatementStore statementStore,
			int defaultListLimit) {
		this(poolName, jdbc, connectionSource, registry, sqlBuilder, statementStore,
				new MetaEntityRegistry(), defaultListLimit);
	}

	public MetaMapperClient(String poolName, AutumnJdbc jdbc, ConnectionSource connectionSource,
			MetaTableRegistry registry, MetaSqlBuilder sqlBuilder, MetaStatementStore statementStore,
			MetaEntityRegistry entityRegistry, int defaultListLimit) {
		if (defaultListLimit <= 0) {
			throw new IllegalArgumentException("defaultListLimit must be > 0");
		}
		if (statementStore == null) {
			throw new IllegalArgumentException("statementStore must not be null");
		}
		if (entityRegistry == null) {
			throw new IllegalArgumentException("entityRegistry must not be null");
		}
		this.poolName = poolName;
		this.jdbc = jdbc;
		this.connectionSource = connectionSource;
		this.registry = registry;
		this.sqlBuilder = sqlBuilder;
		this.statementStore = statementStore;
		this.entityRegistry = entityRegistry;
		this.defaultListLimit = defaultListLimit;
	}

	/** 该客户端绑定的池名（默认池也在这里显示）。 */
	public String poolName() {
		return this.poolName;
	}

	// ------------------------------------------------------------------
	// 查询（MetaQuery 驱动）
	// ------------------------------------------------------------------

	public Optional<MetaClass> findById(String tableName, Object id) {
		MetaTable table = this.registry.require(tableName);
		MetaProperty pk = table.singlePrimaryKey();
		if (pk == null) {
			throw new MetaSqlException("table '" + tableName + "' has no single primary key; use findByKey(...)");
		}
		return findOne(tableName, MetaQuery.by(pk.getName(), id));
	}

	public Optional<MetaClass> findByKey(String tableName, Map<String, ?> key) {
		MetaTable table = this.registry.require(tableName);
		if (table.primaryKeys().isEmpty()) {
			throw new MetaSqlException("table '" + tableName + "' has no primary key");
		}
		return findOne(tableName, queryOfKey(table, key));
	}

	public Optional<MetaClass> findOne(String tableName, MetaQuery query) {
		List<MetaClass> rows = findList(tableName, query, 1);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	public List<MetaClass> findList(String tableName, MetaQuery query) {
		return findList(tableName, query, this.defaultListLimit);
	}

	/**
	 * 查询列表并限制返回行数（防止动态查询一次拉全表）。
	 */
	public List<MetaClass> findList(String tableName, MetaQuery query, long limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be > 0");
		}
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlBuild sql = this.sqlBuilder.select(table, query);
			return this.jdbc.query(limit, sql.sql(), mapper(table), sql.args());
		});
	}

	public Page<MetaClass> findPage(String tableName, MetaQuery query, Pageable pageable) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlBuild select = this.sqlBuilder.select(table, query);
			SqlBuild count = this.sqlBuilder.count(table, query);
			return this.jdbc.page(select.sql(), count.sql(), mapper(table), pageable, select.args());
		});
	}

	public long count(String tableName, MetaQuery query) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlBuild sql = this.sqlBuilder.count(table, query);
			return this.jdbc.count(sql.sql(), sql.args());
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
		SqlBuild sql = this.sqlBuilder.insert(table, values);
		MetaProperty pk = table.singlePrimaryKey();
		if (pk != null && pk.isAutoIncrement()) {
			return withConnection(() -> this.jdbc.insertReturningKey(sql.sql(), pk.getName(), sql.args()));
		}
		withConnection(() -> this.jdbc.update(sql.sql(), sql.args()));
		return pk == null ? null : primaryKeyValue(pk, values);
	}

	/**
	 * 批量新增；SQL 文本一致时走 JDBC batch，否则退化为逐条 save。
	 *
	 * @return 按入参顺序排列的主键列表；无主键返回空列表
	 */
	public List<Object> saveAll(String tableName, List<Map<String, ?>> rows) {
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		MetaTable table = this.registry.require(tableName);
		List<SqlBuild> statements = new ArrayList<>(rows.size());
		for (Map<String, ?> row : rows) {
			statements.add(this.sqlBuilder.insert(table, row));
		}
		MetaProperty pk = table.singlePrimaryKey();
		String sql = statements.get(0).sql();
		if (statements.stream().anyMatch(statement -> !statement.sql().equals(sql))) {
			List<Object> keys = new ArrayList<>(rows.size());
			for (Map<String, ?> row : rows) {
				keys.add(save(tableName, row));
			}
			return keys;
		}
		List<Object[]> args = statements.stream().map(SqlBuild::args).toList();
		if (pk != null && pk.isAutoIncrement()) {
			List<Object> keys = new ArrayList<>(rows.size());
			withConnection(() -> this.jdbc.batchInsertReturningKeys(sql, pk.getName(), args)).forEach(keys::add);
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
			SqlBuild sql = this.sqlBuilder.update(table, key, values);
			return this.jdbc.update(sql.sql(), sql.args());
		});
	}

	// ------------------------------------------------------------------
	// 命名语句（Mapper 文件）
	// ------------------------------------------------------------------

	/**
	 * 渲染命名语句，只返回 SQL 与参数、不执行；用来核对语句或交给别的执行器。
	 */
	public SqlBuild sqlFor(String tableName, String statementId, Map<String, ?> params) {
		MetaTable table = this.registry.require(tableName);
		return render(table, this.statementStore.statement(table, statementId), params);
	}

	/** 执行 {@code <select>} 语句取列表，行数上限用默认值。 */
	public List<MetaClass> selectList(String tableName, String statementId, Map<String, ?> params) {
		return selectList(tableName, statementId, params, this.defaultListLimit);
	}

	public List<MetaClass> selectList(String tableName, String statementId, Map<String, ?> params, long limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be > 0");
		}
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlBuild sql = render(table, selectStatement(table, statementId), params);
			return this.jdbc.query(limit, sql.sql(), mapper(table), sql.args());
		});
	}

	public Optional<MetaClass> selectOne(String tableName, String statementId, Map<String, ?> params) {
		List<MetaClass> rows = selectList(tableName, statementId, params, 1);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	/** 执行返回单个数字的 {@code <select>}（{@code COUNT(*)} 之类）。 */
	public long selectCount(String tableName, String statementId, Map<String, ?> params) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlBuild sql = render(table, selectStatement(table, statementId), params);
			return this.jdbc.count(sql.sql(), sql.args());
		});
	}

	/** 执行 {@code <insert>}/{@code <update>} 语句，返回影响行数。 */
	public int execute(String tableName, String statementId, Map<String, ?> params) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlTemplate statement = this.statementStore.statement(table, statementId);
			if (statement.type() == StatementType.SELECT) {
				throw new MetaSqlException("statement '" + statementId + "' of table '" + table.name()
						+ "' is a <select>; use selectList/selectCount(...)");
			}
			if (statement.type() == StatementType.DELETE) {
				throw new MetaSqlException("statement '" + statementId + "' of table '" + table.name()
						+ "' is a <delete>; the dynamic layer does not run physical deletes");
			}
			SqlBuild sql = render(table, statement, params);
			return this.jdbc.update(sql.sql(), sql.args());
		});
	}

	/**
	 * 当前表结构对应的动态实体类。
	 *
	 * <p>这是 FreeMarker 生成 + javac 编译出来的 {@link MetaClass} 子类：每个列都有真实类型的字段、
	 * getter/setter 和 {@code getProperties()} 里的类型描述。调用方可以据此检查字段类型、
	 * 做校验或走框架集成，而不必面对一个没有类型的 Map。</p>
	 */
	public Class<? extends MetaClass> entityType(String tableName) {
		return this.entityRegistry.entityType(this.registry.require(tableName));
	}

	/** 表结构变化后刷新元数据快照、实体类与 Mapper 语句缓存；只重读文件，不重写文件。 */
	public void refresh(String tableName) {
		this.registry.refresh(tableName);
		this.entityRegistry.refresh(tableName);
		this.statementStore.refresh(tableName);
	}

	/** 刷新当前客户端可见的全部表快照。 */
	public void refreshAll() {
		this.registry.refreshAll();
		this.entityRegistry.refreshAll();
		this.statementStore.refreshAll();
	}

	/** 结果集映射器：来自已编译实体类的 READER，逐行是写死的 setter 调用（零反射）。 */
	private RowMapper<MetaClass> mapper(MetaTable table) {
		return this.entityRegistry.model(table).mapper();
	}

	// ------------------------------------------------------------------
	// 命名语句的渲染上下文
	// ------------------------------------------------------------------

	private SqlBuild render(MetaTable table, SqlTemplate statement, Map<String, ?> params) {
		return statement.render(model(table, params), binder(table));
	}

	/**
	 * 参数键先按元数据归一：语句里的 {@code test}/{@code #{}} 引用的就是列名本身，调用方传
	 * {@code ORDER_NO} 还是 {@code order_no} 都能对上；非列名的键（循环变量、业务开关）原样保留。
	 *
	 * <p>查表也大小写不敏感：H2/Oracle 的元数据列名是大写，手写文件里通常按 MySQL 习惯写小写，
	 * 两边必须能对上，否则 {@code #{order_no}} 会静默绑成 {@code null}。</p>
	 */
	private Map<String, Object> model(MetaTable table, Map<String, ?> params) {
		Map<String, Object> model = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		model.put("table", this.sqlBuilder.quoteTable(table));
		if (params != null) {
			for (Map.Entry<String, ?> entry : params.entrySet()) {
				MetaProperty column = table.findColumn(entry.getKey());
				model.put(column == null ? entry.getKey() : column.getName(), entry.getValue());
			}
		}
		return model;
	}

	/** 绑定值按列类型收口（表单字符串 → 数值/日期）；对不上列名的键直接交给 JDBC。 */
	private BiFunction<String, Object, Object> binder(MetaTable table) {
		return (name, value) -> {
			MetaProperty column = table.findColumn(name);
			return column == null ? value : MetaTypeCoercion.toJdbc(column, value);
		};
	}

	private SqlTemplate selectStatement(MetaTable table, String statementId) {
		SqlTemplate statement = this.statementStore.statement(table, statementId);
		if (statement.type() != StatementType.SELECT) {
			throw new MetaSqlException("statement '" + statementId + "' of table '" + table.name()
					+ "' is not a <select>; use execute(...)");
		}
		return statement;
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
