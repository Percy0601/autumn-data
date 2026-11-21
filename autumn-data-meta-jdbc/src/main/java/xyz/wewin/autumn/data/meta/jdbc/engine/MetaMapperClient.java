package xyz.wewin.autumn.data.meta.jdbc.engine;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
import xyz.wewin.autumn.data.meta.jdbc.MetaSqlException;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityModel;
import xyz.wewin.autumn.data.meta.jdbc.entity.MetaEntityRegistry;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTableRegistry;
import xyz.wewin.autumn.data.meta.jdbc.sql.MarkedSql;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaPlanCache;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlFileStore;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlGenerator;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlPlan;
import xyz.wewin.autumn.data.meta.jdbc.sql.SqlTemplate;

/**
 * 绑定「数据源 + 命名空间」的动态客户端。
 *
 * <p><b>SQL 只有一个来源</b>：{@link MetaSqlFileStore} 里那张表对应的 XML 文件
 * （{@code {根}/{数据源}/{命名空间}/{表名}.xml}）。核心七个方法各自执行文件里同 id 的语句：</p>
 *
 * <pre>
 * findList / findPage / findOne / findById / save / update / delete
 * </pre>
 *
 * <p>这七条由框架按表结构默认生成在 {@code GENERATED START/END} 之间，并<b>落进那份 XML 文件</b>：
 * {@code findOne} 之类的语句都能在文件里看到、也能直接改。标记之外的使用方自定义语句
 * 由 {@code sqlFor/selectList/selectOne/selectCount/selectPage/execute} 按 id 调用；
 * 表结构变化时只重写生成块，自定义语句逐字保留，同名 id 以自定义语句为准。</p>
 *
 * <p><b>查询条件用参数实体 + {@code <if>}</b>：传 {@link MetaClass}，赋了值的列才进 WHERE；复杂条件
 * （比较、模糊、IN、排序）写在 XML 自定义语句里，用 {@code <if>/<foreach>/<choose>} 表达，
 * 不在 Java 侧现拼 SQL。写哪几列由实体的变更集决定（{@code changed('col')}），因此「显式置 NULL」
 * 和「这一列没动」是两件事。</p>
 *
 * <p>所有入口产出同一种东西（{@link MarkedSql}），再走同一个后半程：{@link MetaPlanCache} 按
 * 「结构指纹 + SQL 文本」取 {@link MetaSqlPlan}，交给 {@link AutumnJdbc} 执行。有绑定槽走
 * {@code PreparedStatement}，零槽走 {@code Statement}。</p>
 *
 * <p>操作对象一律是 {@link MetaClass}：读出来的行、写进去的行、语句参数都是它。
 * {@link MetaMapper#of(String, String)} 把数据源与命名空间固定在调用边界上；
 * {@link #forNamespace(String)} 只是换一个命名空间的轻量视图，共享全部缓存与注册表。</p>
 */
public final class MetaMapperClient {

	private static final Logger LOG = Logger.getLogger(MetaMapperClient.class.getName());

	private final String poolName;

	private final String namespace;

	private final AutumnJdbc jdbc;

	private final ConnectionSource connectionSource;

	private final MetaTableRegistry registry;

	private final MetaEntityRegistry entityRegistry;

	private final MetaPlanCache plans;

	private final MetaSqlFileStore statements;

	private final int defaultListLimit;

	public MetaMapperClient(String poolName, AutumnJdbc jdbc, ConnectionSource connectionSource,
			MetaTableRegistry registry, MetaEntityRegistry entityRegistry, MetaPlanCache plans,
			int defaultListLimit, MetaSqlFileStore statements) {
		this(poolName, MetaSqlFileStore.DEFAULT_NAMESPACE, jdbc, connectionSource, registry, entityRegistry, plans,
				defaultListLimit, statements);
	}

	public MetaMapperClient(String poolName, String namespace, AutumnJdbc jdbc, ConnectionSource connectionSource,
			MetaTableRegistry registry, MetaEntityRegistry entityRegistry, MetaPlanCache plans,
			int defaultListLimit, MetaSqlFileStore statements) {
		if (defaultListLimit <= 0) {
			throw new IllegalArgumentException("defaultListLimit must be > 0");
		}
		this.poolName = Objects.requireNonNull(poolName, "poolName");
		this.namespace = Objects.requireNonNull(namespace, "namespace");
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
		this.connectionSource = Objects.requireNonNull(connectionSource, "connectionSource");
		this.registry = Objects.requireNonNull(registry, "registry");
		this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
		this.statements = Objects.requireNonNull(statements, "statements");
		this.plans = plans == null ? new MetaPlanCache() : plans;
		this.defaultListLimit = defaultListLimit;
	}

	/** 换一个命名空间的轻量视图：共享连接源、注册表、语句库与计划缓存，不复制状态。 */
	private MetaMapperClient(MetaMapperClient base, String namespace) {
		this.poolName = base.poolName;
		this.namespace = Objects.requireNonNull(namespace, "namespace");
		this.jdbc = base.jdbc;
		this.connectionSource = base.connectionSource;
		this.registry = base.registry;
		this.entityRegistry = base.entityRegistry;
		this.plans = base.plans;
		this.statements = base.statements;
		this.defaultListLimit = base.defaultListLimit;
	}

	/** 该客户端绑定的池名（数据源）。 */
	public String poolName() {
		return this.poolName;
	}

	/** 该客户端绑定的命名空间（业务划分 / 包名称）。 */
	public String namespace() {
		return this.namespace;
	}

	/** 切换到另一命名空间，语句会落到 {@code {根}/{数据源}/{新命名空间}/{表名}.xml}。 */
	public MetaMapperClient forNamespace(String namespace) {
		if (namespace == null || namespace.isBlank()) {
			throw new IllegalArgumentException("namespace must not be blank");
		}
		return this.namespace.equals(namespace) ? this : new MetaMapperClient(this, namespace);
	}

	/** 唯一语句库（诊断 / 显式生成文件用）。 */
	public MetaSqlFileStore statementStore() {
		return this.statements;
	}

	// ------------------------------------------------------------------
	// 核心 CRUD：SQL 一律来自 XML 里同 id 的语句
	// ------------------------------------------------------------------

	/** 按单主键取一行；复合主键把各主键列都写进参数实体交给 {@link #findOne(String, MetaClass)}。 */
	public Optional<MetaClass> findById(String tableName, Object id) {
		MetaTable table = this.registry.require(tableName);
		MetaProperty pk = table.singlePrimaryKey();
		if (pk == null) {
			throw new MetaSqlException("table '" + tableName + "' has no single primary key; "
					+ "fill every primary-key column into a params entity and use findOne(...)");
		}
		MetaClass params = new MetaClass();
		params.set(pk.getName(), id);
		return findOne(table, MetaSqlGenerator.FIND_BY_ID, params, 1);
	}

	public Optional<MetaClass> findOne(String tableName, MetaClass params) {
		return findOne(this.registry.require(tableName), MetaSqlGenerator.FIND_ONE, params, 1);
	}

	public List<MetaClass> findList(String tableName, MetaClass params) {
		return query(tableName, MetaSqlGenerator.FIND_LIST, params, this.defaultListLimit);
	}

	/**
	 * 限行列表：只跑一条 SELECT，不再算总数。需要总数用 {@link #findPage}；
	 * 这个重载留给批量拉取 / 压测这类对「少一次 count」敏感的场景。
	 */
	public List<MetaClass> findList(String tableName, MetaClass params, long limit) {
		return query(tableName, MetaSqlGenerator.FIND_LIST, params, limit);
	}

	public Page<MetaClass> findPage(String tableName, MetaClass params, Pageable pageable) {
		if (pageable == null) {
			throw new IllegalArgumentException("pageable must not be null");
		}
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlTemplate statement = selectStatement(table, MetaSqlGenerator.FIND_PAGE);
			MarkedSql select = tidy(render(table, statement, params));
			MetaSqlPlan selectPlan = this.plans.plan(table, select);
			MetaSqlPlan countPlan = this.plans.plan(table, derivedCount(select));
			return this.jdbc.page(selectPlan, countPlan, select.values().toArray(), mapper(table, statement), pageable);
		});
	}

	// ------------------------------------------------------------------
	// 实体：写与读共用同一个操作对象
	// ------------------------------------------------------------------

	/** 造一个当前表结构的空实体（干净、无变更集）。 */
	public MetaClass newEntity(String tableName) {
		return this.entityRegistry.model(this.registry.require(tableName)).factory().get();
	}

	/** 新增：写实体上被赋过值的列，自增主键回填到实体里，之后实体是干净的。 */
	public MetaClass save(MetaClass entity) {
		MetaTable table = table(entity);
		validateWritable(table, entity, true);
		SqlTemplate statement = statement(table, MetaSqlGenerator.SAVE, StatementType.INSERT);
		Bound sql = bind(table, tidy(render(table, statement, entity)));
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

	/** 批量新增：变更集形状一致的走 JDBC batch，否则逐条 {@link #save(MetaClass)}。 */
	public List<MetaClass> saveAll(List<MetaClass> entities) {
		if (entities == null || entities.isEmpty()) {
			return List.of();
		}
		MetaTable table = table(entities.get(0));
		SqlTemplate statement = statement(table, MetaSqlGenerator.SAVE, StatementType.INSERT);
		List<MarkedSql> rendered = new ArrayList<>(entities.size());
		for (MetaClass entity : entities) {
			validateWritable(table, entity, true);
			rendered.add(tidy(render(table, statement, entity)));
		}
		String shape = rendered.get(0).sql();
		if (rendered.stream().anyMatch(marked -> !marked.sql().equals(shape))) {
			for (MetaClass entity : entities) {
				save(entity);
			}
			return entities;
		}
		MetaSqlPlan plan = this.plans.plan(table, rendered.get(0));
		List<Object[]> args = rendered.stream().map(marked -> marked.values().toArray()).toList();
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

	/** 部分更新：只写实体上改过的列，WHERE 用实体上的主键值。 */
	public int update(MetaClass entity) {
		MetaTable table = table(entity);
		validateWritable(table, entity, false);
		SqlTemplate s = statement(table, MetaSqlGenerator.UPDATE, StatementType.UPDATE);
		Bound sql = bind(table, tidy(render(table, s, entity)));
		int rows = withConnection(() -> this.jdbc.update(sql.plan(), sql.args()));
		if (rows > 0) {
			entity.clearChanged();
		}
		return rows;
	}

	/**
	 * 删除：执行 XML 里的 {@code delete} 语句。有逻辑删除列时默认语句是 {@code UPDATE ... SET deleted = 1}，
	 * 没有时是物理 {@code DELETE}；使用方可以在自定义区覆盖这条语句。
	 */
	public int delete(MetaClass entity) {
		MetaTable table = table(entity);
		validateKey(table, entity);
		SqlTemplate s = this.statements.requireStatement(this.poolName, this.namespace, table,
				MetaSqlGenerator.DELETE);
		if (s.type() == StatementType.SELECT || s.type() == StatementType.INSERT) {
			throw new MetaSqlException("statement '" + MetaSqlGenerator.DELETE + "' of table '" + table.name()
					+ "' must be a <delete>/<update>; it is " + tag(s));
		}
		Bound sql = bind(table, tidy(render(table, s, entity)));
		int rows = withConnection(() -> this.jdbc.update(sql.plan(), sql.args()));
		if (rows > 0) {
			entity.clearChanged();
		}
		return rows;
	}

	// ------------------------------------------------------------------
	// 自定义语句：同一个语句库，按 id 执行
	// ------------------------------------------------------------------

	/** 抽取命名语句，只返回形状与实参、不执行。 */
	public MarkedSql sqlFor(String tableName, String statementId, MetaClass params) {
		MetaTable table = this.registry.require(tableName);
		SqlTemplate s = require(table, statementId);
		return render(table, s, params);
	}

	public List<MetaClass> selectList(String tableName, String statementId, MetaClass params) {
		return query(tableName, statementId, params, this.defaultListLimit);
	}

	public Optional<MetaClass> selectOne(String tableName, String statementId, MetaClass params) {
		List<MetaClass> rows = query(tableName, statementId, params, 1);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	/** 执行返回单个数字的 {@code <select>}（{@code COUNT(*)} 之类）。 */
	public long selectCount(String tableName, String statementId, MetaClass params) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			Bound sql = bind(table, tidy(render(table, selectStatement(table, statementId), params)));
			return this.jdbc.count(sql.plan(), sql.args());
		});
	}

	/** 执行 {@code <insert>}/{@code <update>}/{@code <delete>} 语句，返回影响行数。 */
	public int execute(String tableName, String statementId, MetaClass params) {
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlTemplate s = require(table, statementId);
			if (s.type() == StatementType.SELECT) {
				throw new MetaSqlException("statement '" + statementId + "' of table '" + table.name()
						+ "' is a <select>; use selectList/selectCount(...)");
			}
			Bound sql = bind(table, tidy(render(table, s, params)));
			return this.jdbc.update(sql.plan(), sql.args());
		});
	}

	/**
	 * 按命名语句分页：内容用语句本身，计数自动包一层 {@code SELECT COUNT(*) FROM (...)}。
	 * 这样分页不依赖「默认 findPage 一条语句」，自定义的复杂查询也能直接翻页。
	 */
	public Page<MetaClass> selectPage(String tableName, String statementId, MetaClass params, Pageable pageable) {
		if (pageable == null) {
			throw new IllegalArgumentException("pageable must not be null");
		}
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlTemplate statement = selectStatement(table, statementId);
			MarkedSql select = tidy(render(table, statement, params));
			MetaSqlPlan selectPlan = this.plans.plan(table, select);
			MetaSqlPlan countPlan = this.plans.plan(table, derivedCount(select));
			List<MetaClass> content = this.jdbc.query(selectPlan, select.values().toArray(), mapper(table, statement),
					pageable.limit(), pageable.offset());
			long total = this.jdbc.count(countPlan, select.values().toArray());
			return Page.of(content, pageable, total);
		});
	}

	/** 该表全部可见的语句 id：七条默认 + 文件里的自定义。 */
	public List<String> statementIds(String tableName) {
		return this.statements.statementIds(this.poolName, this.namespace, this.registry.require(tableName));
	}

	/** 显式生成 / 更新该表的 XML 文件并返回其位置（表结构变化后想立刻看到文件时用）。 */
	public String ensureStatementFile(String tableName) {
		return this.statements.ensure(this.poolName, this.namespace, this.registry.require(tableName));
	}

	// ------------------------------------------------------------------
	// 实体类 / 语句文件
	// ------------------------------------------------------------------

	/** 当前表结构对应的动态实体类。 */
	public Class<? extends MetaClass> entityType(String tableName) {
		return this.entityRegistry.entityType(this.registry.require(tableName));
	}

	// ------------------------------------------------------------------
	// 缓存：分级清理 / 重新加载 / 诊断
	// ------------------------------------------------------------------

	public void clearTableCache(String tableName) {
		this.registry.refresh(tableName);
	}

	public void clearTableCache() {
		this.registry.refreshAll();
	}

	public void clearEntityCache(String tableName) {
		this.entityRegistry.refresh(tableName);
	}

	public void clearEntityCache() {
		this.entityRegistry.refreshAll();
	}

	/** 只清语句 AST 与执行计划缓存（表结构与实体类不动）。 */
	public void clearStatementCache(String tableName) {
		this.statements.refresh(this.poolName, this.namespace, tableName);
		this.plans.invalidate(tableName);
	}

	public void clearStatementCache() {
		this.statements.refreshAll();
		this.plans.invalidateAll();
	}

	/** 表结构变化后的标准入口：清元数据、实体类、语句与计划缓存。 */
	public void refresh(String tableName) {
		clearTableCache(tableName);
		clearEntityCache(tableName);
		clearStatementCache(tableName);
	}

	public void refreshAll() {
		clearTableCache();
		clearEntityCache();
		clearStatementCache();
	}

	/** 清缓存并立即重新加载：元数据当场回读、实体类当场重新编译。 */
	public MetaClass reload(String tableName) {
		refresh(tableName);
		MetaTable table = this.registry.require(tableName);
		MetaEntityModel model = this.entityRegistry.model(table);
		return model.factory().get();
	}

	public void reloadAll() {
		List<String> tables = this.registry.tableNames();
		refreshAll();
		for (String tableName : tables) {
			reload(tableName);
		}
	}

	public Map<String, Object> cacheStats() {
		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("pool", this.poolName);
		stats.put("namespace", this.namespace);
		stats.put("tables", this.registry.size());
		stats.put("tableLimit", this.registry.maximumSize());
		stats.put("entityModels", this.entityRegistry.size());
		stats.put("entityLimit", this.entityRegistry.maximumSize());
		stats.put("statementTables", this.statements.size());
		stats.put("statementLimit", this.statements.maximumSize());
		stats.put("compiledShapes", this.plans.size());
		stats.put("planLimit", this.plans.maximumSize());
		return stats;
	}

	/** 编译产物数量（计划缓存里已编译的物理语句形状数）。 */
	public long compiledShapes() {
		return this.plans.size();
	}

	// ------------------------------------------------------------------
	// 语句取值 / 渲染
	// ------------------------------------------------------------------

	/** 一条 SELECT 语句的统一执行路径：默认语句与自定义语句都汇到这里。 */
	private List<MetaClass> query(String tableName, String statementId, MetaClass params, long limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be > 0");
		}
		return withConnection(() -> {
			MetaTable table = this.registry.require(tableName);
			SqlTemplate s = selectStatement(table, statementId);
			Bound sql = bind(table, tidy(render(table, s, params)));
			return this.jdbc.query(sql.plan(), sql.args(), mapper(table, s), limit);
		});
	}

	private Optional<MetaClass> findOne(MetaTable table, String statementId, MetaClass params, long limit) {
		List<MetaClass> rows = query(table.name(), statementId, params, limit);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	private SqlTemplate statement(MetaTable table, String statementId, StatementType expected) {
		SqlTemplate s = this.statements.requireStatement(this.poolName, this.namespace, table, statementId);
		if (s.type() != expected) {
			throw new MetaSqlException("statement '" + statementId + "' of table '" + table.name()
					+ "' must be " + expected + "; it is " + tag(s));
		}
		return s;
	}

	private SqlTemplate require(MetaTable table, String statementId) {
		return this.statements.requireStatement(this.poolName, this.namespace, table, statementId);
	}

	private SqlTemplate selectStatement(MetaTable table, String statementId) {
		SqlTemplate s = require(table, statementId);
		if (s.type() != StatementType.SELECT) {
			throw new MetaSqlException("statement '" + statementId + "' of table '" + table.name()
					+ "' is not a <select>; use execute(...) instead — it is " + tag(s));
		}
		return s;
	}

	private static String tag(SqlTemplate statement) {
		return switch (statement.type()) {
			case SELECT -> "a <select>";
			case INSERT -> "an <insert>";
			case UPDATE -> "an <update>";
			case DELETE -> "a <delete>";
		};
	}

	/** 去掉文件里常见的收尾分号：子查询包裹与部分驱动都不接受它。 */
	private static MarkedSql tidy(MarkedSql marked) {
		String sql = marked.sql();
		int end = sql.length();
		while (end > 0 && (Character.isWhitespace(sql.charAt(end - 1)) || sql.charAt(end - 1) == ';')) {
			end--;
		}
		if (end == sql.length()) {
			return marked;
		}
		if (end == 0) {
			throw new MetaSqlException("statement renders to empty SQL; check the placeholders and <if> conditions");
		}
		return new MarkedSql(sql.substring(0, end), marked.refs(), marked.values());
	}

	private static MarkedSql derivedCount(MarkedSql select) {
		return new MarkedSql("SELECT COUNT(*) FROM (" + select.sql() + ") autumn_page_count", select.refs(),
				select.values());
	}

	// ------------------------------------------------------------------
	// 抽取 → 再转换
	// ------------------------------------------------------------------

	private record Bound(MetaSqlPlan plan, Object[] args) {
	}

	private Bound bind(MetaTable table, MarkedSql marked) {
		return new Bound(this.plans.plan(table, marked), marked.values().toArray());
	}

	private MarkedSql render(MetaTable table, SqlTemplate statement, MetaClass params) {
		return statement.render(model(table, params));
	}

	/**
	 * 参数摊成模板可见的名字 → 值：
	 * <ul>
	 *   <li>列名按元数据归一，大小写不敏感（{@code ORDER_NO} 与 {@code order_no} 都能对上）；</li>
	 *   <li>{@code table} 是方言引号后的表名，供自定义语句的 {@code ${table}} 使用；</li>
	 *   <li>{@link SqlTemplate#CHANGED_KEY} 是实体变更集，供默认 {@code save}/{@code update} 的
	 *       {@code changed('col')} 判断这一列有没有被写过。</li>
	 * </ul>
	 * 值本身不转换：类型收口是槽位编译时定好的事（{@code MetaPlanCompiler}）。
	 */
	private Map<String, Object> model(MetaTable table, MetaClass params) {
		Map<String, Object> model = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		model.put("table", this.statements.generator().quoteIdentifier(table.name()));
		if (params == null) {
			model.put(SqlTemplate.CHANGED_KEY, java.util.Set.of());
			return model;
		}
		model.put(SqlTemplate.CHANGED_KEY, params.changedNames());
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

	/**
	 * 写库前把三类错误挡在 SQL 之外：一列没写、写了不存在的列、主键缺失/被改。
	 * 默认语句靠 {@code changed('col')} 决定列清单，静默忽略这些情况比直接报错更危险。
	 */
	private void validateWritable(MetaTable table, MetaClass entity, boolean insert) {
		Set<String> changed = entity.changedNames();
		if (changed.isEmpty()) {
			throw new MetaSqlException("entity for table '" + table.name() + "' has no values written");
		}
		for (String name : changed) {
			MetaProperty column = table.findColumn(name);
			if (column == null) {
				throw new MetaSqlException("unknown column '" + name + "' for table '" + table.name() + "'");
			}
			if (!insert && column.isPrimaryKey()) {
				throw new MetaSqlException("primary key column '" + column.getName() + "' cannot be updated");
			}
		}
		for (MetaProperty key : table.primaryKeys()) {
			if (!key.isAutoIncrement() && entity.get(key.getName()) == null) {
				throw new MetaSqlException("non-generated primary key '" + key.getName()
						+ "' must be written for table '" + table.name() + "'");
			}
		}
		if (!insert) {
			validateKey(table, entity);
		}
	}

	/** 更新/删除的 WHERE 全靠主键值：缺一列都会让语句绑上 NULL，静默命中 0 行。 */
	private void validateKey(MetaTable table, MetaClass entity) {
		for (MetaProperty key : table.primaryKeys()) {
			if (entity.get(key.getName()) == null) {
				throw new MetaSqlException("primary key column '" + key.getName()
						+ "' must not be null for table '" + table.name() + "'");
			}
		}
	}

	// ------------------------------------------------------------------
	// 结果集映射
	// ------------------------------------------------------------------

	private RowMapper<MetaClass> mapper(MetaTable table) {
		return this.entityRegistry.model(table).mapper();
	}

	/** 语句带 {@code <resultMap>} 时按映射逐列取值，否则用生成实体的固定列 READER。 */
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

	/** 驼峰 → 下划线：{@code orderNo} → {@code order_no}。 */
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
	// 连接生命周期
	// ------------------------------------------------------------------

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
