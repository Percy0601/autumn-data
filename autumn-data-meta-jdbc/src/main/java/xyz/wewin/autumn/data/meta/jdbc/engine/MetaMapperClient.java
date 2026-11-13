package xyz.wewin.autumn.data.meta.jdbc.engine;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import xyz.wewin.autumn.data.common.mapper.MapperDefinition.ResultMapping;
import xyz.wewin.autumn.data.common.mapper.MapperDefinition.StatementType;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.jdbc.runtime.AutumnJdbc;
import xyz.wewin.autumn.data.jdbc.runtime.ConnectionSource;
import xyz.wewin.autumn.data.jdbc.runtime.RowMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaMapper;
import xyz.wewin.autumn.data.meta.jdbc.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityModel;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;
import xyz.wewin.autumn.data.meta.jdbc.sql.MarkedSql;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaPlanCache;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlBuilder;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlPlan;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaStatementStore;
import xyz.wewin.autumn.data.meta.jdbc.sql.SqlTemplate;

/**
 * 绑定到某个数据源（池）的动态客户端。
 *
 * <p>{@link MetaMapper} 的静态方法默认走默认池；多数据源显式用 {@link MetaMapper#of(String)}
 * 拿到本类型，把“哪个库”固定在调用边界上，而不是藏在线程上下文里。表名/列名/类型仍然在每次
 * 调用时从 {@link MetaTableRegistry} 解析，所以运行期建表后无需重启。</p>
 *
 * <p>两组入口各走一条路，不存在“同一次调用可能被两套实现分走”的情况：</p>
 * <ul>
 *   <li>{@link MetaQuery} 驱动的查询（{@code findById/findByKey/findOne/findList/findPage/count/exists}）
 *       与实体驱动的写（{@code newEntity/save/saveAll/update}）：SQL 由 {@link MetaSqlBuilder} 按元数据
 *       现拼。条件运算符是开集（{@code >}/{@code LIKE}/{@code IN}/{@code BETWEEN}/排序……），
 *       固定文件表达不了，所以这条路不读 XML；</li>
 *   <li>命名语句（{@code sqlFor/selectList/selectOne/selectCount/execute}）：SQL 来自
 *       {@link MetaStatementStore} 的 Mapper 文件——文件存在就用文件，缺失时按表结构生成一次，
 *       之后归使用方编辑。</li>
 * </ul>
 *
 * <p>两条路产出同一种东西（{@link MarkedSql}），再走同一个后半程：
 * {@link MetaPlanCache} 按「结构指纹 + SQL 文本」取 {@link MetaSqlPlan}（未命中才
 * {@link MetaPlanCompiler} 编译一次），交给 {@link AutumnJdbc} 的执行入口。于是</p>
 * <ul>
 *   <li>有绑定槽 → {@link java.sql.PreparedStatement}，每个 {@code ?} 的类型转换与 setter
 *       是编译期定好的（见 {@link MetaBindSlot}）；</li>
 *   <li>零绑定槽（全是 {@code ${}} 拼接或字面量）→ {@link java.sql.Statement}，
 *       本来就没有东西可绑，不必再让驱动 parse 一遍占位符。</li>
 * </ul>
 *
 * <p><b>唯一的操作对象是 {@link MetaClass}</b>：读出来的行、写进去的行、命名语句的参数都是它。
 * 实例按当前表结构直接生成源码、运行期编译加载，有真实类型化字段和 getter，
 * 类型可以通过 {@link #entityType(String)} 取到；写哪几列看的是实体的变更集
 * （{@link MetaClass#isChanged(String)}），所以「显式置空」和「这一列不动」是两件事，
 * 不需要额外的空值哨兵。完全静态的实体类在运行期建表时做不到，完全动态的 Map 又没有类型，
 * 这一层就是要落在两者之间。</p>
 *
 * <p>只提供查询、保存、更新；<b>不提供物理删除</b>。业务上的作废是
 * {@code entity.setStatus("VOID"); client.update(entity);}。</p>
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

	private final MetaPlanCache plans;

	private final int defaultListLimit;

	public MetaMapperClient(String poolName, AutumnJdbc jdbc, ConnectionSource connectionSource,
			MetaTableRegistry registry, MetaSqlBuilder sqlBuilder, MetaStatementStore statementStore,
			MetaEntityRegistry entityRegistry, MetaPlanCache plans, int defaultListLimit) {
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
		this.plans = plans == null ? new MetaPlanCache() : plans;
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

	/**
	 * 按主键取一行：主键值读自实体，所以既可以是「只填了主键的空实体」，也可以是「刚查出来的行」。
	 *
	 * <p>表名取自 {@link MetaClass#getName()}（生成实体在构造时就写死了自己的表），复合主键必须
	 * 每一列都有值，少一列就是少一个条件，不会静默放宽查询范围。</p>
	 */
	public Optional<MetaClass> findByKey(MetaClass key) {
		MetaTable table = table(key);
		if (table.primaryKeys().isEmpty()) {
			throw new MetaSqlException("table '" + table.name() + "' has no primary key");
		}
		MetaQuery query = MetaQuery.all();
		for (MetaProperty column : table.primaryKeys()) {
			Object value = key.get(column.getName());
			if (value == null) {
				throw new MetaSqlException("primary key column '" + column.getName()
						+ "' must not be null for table '" + table.name() + "'");
			}
			query = query.and(column.getName(), value);
		}
		return findOne(table.name(), query);
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
			Bound sql = bind(table, this.sqlBuilder.select(table, query));
			return this.jdbc.query(sql.plan(), sql.args(), mapper(table), limit);
		});
	}

	public Page<MetaClass> findPage(String tableName, MetaQuery query, Pageable pageable) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			MarkedSql select = this.sqlBuilder.select(table, query);
			MetaSqlPlan selectPlan = this.plans.plan(table, select);
			MetaSqlPlan countPlan = this.plans.plan(table, this.sqlBuilder.count(table, query));
			Object[] args = select.values().toArray();
			return this.jdbc.page(selectPlan, countPlan, args, mapper(table), pageable);
		});
	}

	public long count(String tableName, MetaQuery query) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			Bound sql = bind(table, this.sqlBuilder.count(table, query));
			return this.jdbc.count(sql.plan(), sql.args());
		});
	}

	public boolean exists(String tableName, MetaQuery query) {
		return count(tableName, query) > 0;
	}

	// ------------------------------------------------------------------
	// 实体：写与读共用同一个操作对象
	// ------------------------------------------------------------------

	/**
	 * 造一个当前表结构的空实体（干净、无变更集）。
	 *
	 * <p>新增走它：{@code newEntity} → 逐列赋值 → {@link #save(MetaClass)}。查询出来的行已经是
	 * 同一个形态的实例，改几列再 {@link #update(MetaClass)} 就是部分更新。</p>
	 *
	 * <p>CODE 模式的工厂是生成类里的 {@code NEW}（构造器方法引用），GENERIC 模式直接
	 * {@code new MetaClass()}；两条路都不需要反射。</p>
	 */
	public MetaClass newEntity(String tableName) {
		MetaTable table = this.registry.require(tableName);
		return this.entityRegistry.model(table).factory().get();
	}

	/**
	 * 新增一行：写实体上被赋过值的列，自增主键回填到实体里，之后实体是干净的。
	 *
	 * @return 同一个实体实例（主键已填），便于链式继续用
	 */
	public MetaClass save(MetaClass entity) {
		MetaTable table = table(entity);
		Bound sql = bind(table, this.sqlBuilder.insert(table, entity));
		MetaProperty pk = table.singlePrimaryKey();
		if (pk != null && pk.isAutoIncrement() && !entity.isChanged(pk.getName())) {
			Object key = withConnection(() -> this.jdbc.insertReturningKey(sql.plan(), sql.args(), pk.getName()));
			if (key != null) {
				entity.set(pk.getName(), key);
			}
		}
		else {
			withConnection(() -> this.jdbc.update(sql.plan(), sql.args()));
		}
		entity.clearChanged();
		return entity;
	}

	/**
	 * 批量新增；赋值的列一致（同一形状）时走 JDBC batch，否则退化为逐条 {@link #save(MetaClass)}。
	 *
	 * @return 按入参顺序返回的同一批实体，自增主键已回填
	 */
	public List<MetaClass> saveAll(List<MetaClass> entities) {
		if (entities == null || entities.isEmpty()) {
			return List.of();
		}
		MetaTable table = table(entities.get(0));
		List<MarkedSql> statements = new ArrayList<>(entities.size());
		for (MetaClass entity : entities) {
			statements.add(this.sqlBuilder.insert(table, entity));
		}
		String shape = statements.get(0).sql();
		if (statements.stream().anyMatch(statement -> !statement.sql().equals(shape))) {
			for (MetaClass entity : entities) {
				save(entity);
			}
			return entities;
		}
		MetaSqlPlan plan = this.plans.plan(table, statements.get(0));
		List<Object[]> args = statements.stream().map(statement -> statement.values().toArray()).toList();
		MetaProperty pk = table.singlePrimaryKey();
		if (pk != null && pk.isAutoIncrement()) {
			List<Number> keys = withConnection(() -> this.jdbc.batchInsertReturningKeys(plan, args, pk.getName()));
			for (int i = 0; i < entities.size(); i++) {
				MetaClass entity = entities.get(i);
				Object key = keys.size() > i ? keys.get(i) : null;
				if (key != null && !entity.isChanged(pk.getName())) {
					entity.set(pk.getName(), key);
				}
				entity.clearChanged();
			}
			return entities;
		}
		withConnection(() -> this.jdbc.batchUpdate(plan, args));
		entities.forEach(MetaClass::clearChanged);
		return entities;
	}

	/**
	 * 部分更新：SET 只包含实体上写过的列，WHERE 用实体上的主键值。
	 *
	 * <p>成功写回（影响行数 &gt; 0）才清变更集；一行都没改到就把改动留着，调用方可以修正主键后重试。</p>
	 *
	 * @return 影响行数
	 */
	public int update(MetaClass entity) {
		MetaTable table = table(entity);
		Bound sql = bind(table, this.sqlBuilder.update(table, entity));
		int rows = withConnection(() -> this.jdbc.update(sql.plan(), sql.args()));
		if (rows > 0) {
			entity.clearChanged();
		}
		return rows;
	}

	// ------------------------------------------------------------------
	// 命名语句（Mapper 文件）
	// ------------------------------------------------------------------

	/**
	 * 抽取命名语句，只返回形状与实参、不执行；用来核对语句或交给别的执行器。
	 *
	 * <p>{@code params} 是参数对象：列名直接按元数据对上（传实体也行，传一个只 set 了参数的
	 * {@link MetaClass} 也行），非列名的键（业务开关、排序字段、{@code <foreach>} 的集合）
	 * 落在实体的动态属性上。</p>
	 */
	public MarkedSql sqlFor(String tableName, String statementId, MetaClass params) {
		MetaTable table = this.registry.require(tableName);
		return render(table, this.statementStore.statement(table, statementId), params);
	}

	/** 执行 {@code <select>} 语句取列表，行数上限用默认值。 */
	public List<MetaClass> selectList(String tableName, String statementId, MetaClass params) {
		return selectList(tableName, statementId, params, this.defaultListLimit);
	}

	public List<MetaClass> selectList(String tableName, String statementId, MetaClass params, long limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be > 0");
		}
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlTemplate statement = selectStatement(table, statementId);
			Bound sql = bind(table, render(table, statement, params));
			return this.jdbc.query(sql.plan(), sql.args(), mapper(table, statement), limit);
		});
	}

	public Optional<MetaClass> selectOne(String tableName, String statementId, MetaClass params) {
		List<MetaClass> rows = selectList(tableName, statementId, params, 1);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	/** 执行返回单个数字的 {@code <select>}（{@code COUNT(*)} 之类）。 */
	public long selectCount(String tableName, String statementId, MetaClass params) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			Bound sql = bind(table, render(table, selectStatement(table, statementId), params));
			return this.jdbc.count(sql.plan(), sql.args());
		});
	}

	/** 执行 {@code <insert>}/{@code <update>} 语句，返回影响行数。 */
	public int execute(String tableName, String statementId, MetaClass params) {
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
			Bound sql = bind(table, render(table, statement, params));
			return this.jdbc.update(sql.plan(), sql.args());
		});
	}

	/**
	 * 当前表结构对应的动态实体类。
	 *
	 * <p>这是运行期生成 + javac 编译出来的 {@link MetaClass} 子类：每个列都有真实类型的字段、
	 * getter/setter 和 {@code getProperties()} 里的类型描述。调用方可以据此检查字段类型、
	 * 做校验或走框架集成，而不必面对一个没有类型的 Map。</p>
	 */
	public Class<? extends MetaClass> entityType(String tableName) {
		return this.entityRegistry.entityType(this.registry.require(tableName));
	}

	/** 表结构变化后刷新元数据快照、实体类、Mapper 语句与执行计划缓存；只重读文件，不重写文件。 */
	public void refresh(String tableName) {
		this.registry.refresh(tableName);
		this.entityRegistry.refresh(tableName);
		this.statementStore.refresh(tableName);
		this.plans.invalidate(tableName);
	}

	/** 刷新当前客户端可见的全部表快照。 */
	public void refreshAll() {
		this.registry.refreshAll();
		this.entityRegistry.refreshAll();
		this.statementStore.refreshAll();
		this.plans.invalidateAll();
	}

	/** 已编译形状数（诊断用）。 */
	public long compiledShapes() {
		return this.plans.size();
	}

	/** 结果集映射器：来自已编译实体类的 READER，逐行是写死的 setter 调用（零反射）。 */
	private RowMapper<MetaClass> mapper(MetaTable table) {
		return this.entityRegistry.model(table).mapper();
	}

	/**
	 * 语句带 {@code <resultMap>} 时按映射逐列取值，否则用生成实体的固定列 READER。
	 *
	 * <p>{@code property} 允许写元数据列名，也允许写实体字段的驼峰名（{@code orderNo}），
	 * 后者运行期换成 {@code order_no} 再交给生成类；都对不上时按动态属性处理，
	 * 留给「SELECT 出一个表达式列、只想在返回对象上挂个名字」这种用法。</p>
	 */
	private RowMapper<MetaClass> mapper(MetaTable table, SqlTemplate statement) {
		List<ResultMapping> mappings = statement.resultMappings();
		if (mappings.isEmpty()) {
			return mapper(table);
		}
		MetaEntityModel model = this.entityRegistry.model(table);
		List<MetaMappedRowMapper.Column> columns = new ArrayList<>(mappings.size());
		for (ResultMapping mapping : mappings) {
			columns.add(MetaMappedRowMapper.Column.of(mapping.column(), propertyName(table, mapping.property())));
		}
		return new MetaMappedRowMapper(model.factory(), columns);
	}

	private static String propertyName(MetaTable table, String property) {
		MetaProperty column = table.findColumn(property);
		if (column != null) {
			return column.getName();
		}
		column = table.findColumn(snakeCase(property));
		return column == null ? property : column.getName();
	}

	/** 驼峰 → 下划线：{@code orderNo} → {@code order_no}，{@code userId} → {@code user_id}。 */
	private static String snakeCase(String name) {
		if (name == null || name.isEmpty()) {
			return name;
		}
		StringBuilder out = new StringBuilder(name.length() + 4);
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isUpperCase(c)) {
				boolean previousLower = i > 0 && Character.isLowerCase(name.charAt(i - 1));
				boolean nextLower = i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
				if (i > 0 && name.charAt(i - 1) != '_' && (previousLower || nextLower)) {
					out.append('_');
				}
				out.append(Character.toLowerCase(c));
			}
			else {
				out.append(c);
			}
		}
		return out.toString();
	}

	// ------------------------------------------------------------------
	// 抽取 → 再转换
	// ------------------------------------------------------------------

	/**
	 * 一次执行的两个部件：编译好的形状（可复用）与这次的实参（同序）。
	 *
	 * <p>所有入口都收敛到这里再交给 {@link AutumnJdbc}，因此「同一条动态语句一会儿走 PreparedStatement、
	 * 一会儿走另一套绑定」这种情况不存在：走哪条路只由 {@link MetaSqlPlan#parameterCount()} 决定。</p>
	 */
	private record Bound(MetaSqlPlan plan, Object[] args) {
	}

	private Bound bind(MetaTable table, MarkedSql marked) {
		return new Bound(this.plans.plan(table, marked), marked.values().toArray());
	}

	private MarkedSql render(MetaTable table, SqlTemplate statement, MetaClass params) {
		return statement.render(model(table, params));
	}

	/**
	 * 参数摊成模板可见的名字→值。
	 *
	 * <p>列名先按元数据归一：语句里的 {@code test}/{@code #{}} 引用的就是列名本身，调用方传
	 * {@code ORDER_NO} 还是 {@code order_no} 都能对上；非列名的键（循环变量、业务开关）走实体的
	 * 动态属性，名字原样保留。</p>
	 *
	 * <p>查表也大小写不敏感：H2/Oracle 的元数据列名是大写，手写文件里通常按 MySQL 习惯写小写，
	 * 两边必须能对上，否则 {@code #{order_no}} 会静默绑成 {@code null}。</p>
	 *
	 * <p>这里只归一<b>名字</b>，不碰<b>值</b>：值按列类型收口是槽位编译时定好的事
	 * （{@link MetaPlanCompiler}），每次渲染再转一边是重复劳动。</p>
	 */
	private Map<String, Object> model(MetaTable table, MetaClass params) {
		Map<String, Object> model = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		model.put("table", this.sqlBuilder.quoteTable(table));
		if (params == null) {
			return model;
		}
		List<MetaProperty> properties = params.getProperties();
		if (properties == null) {
			return model;
		}
		for (MetaProperty property : properties) {
			if (property == null || property.getName() == null) {
				continue;
			}
			MetaProperty column = table.findColumn(property.getName());
			model.put(column == null ? property.getName() : column.getName(), property.getValue());
		}
		return model;
	}

	/** 实体属于哪张表：表名写在实体自己身上，不再让调用方重复传一遍。 */
	private MetaTable table(MetaClass entity) {
		if (entity == null) {
			throw new MetaSqlException("entity must not be null");
		}
		if (entity.getName() == null || entity.getName().isBlank()) {
			throw new MetaSqlException(
					"entity has no table name; use newEntity(...) or setName(\"<table>\") first");
		}
		return this.registry.require(entity.getName());
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

}
