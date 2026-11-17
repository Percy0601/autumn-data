package xyz.wewin.autumn.data.meta.jdbc;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import xyz.wewin.autumn.data.common.mapper.MapperDefinition;
import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperEngine;
import xyz.wewin.autumn.data.meta.jdbc.sql.MarkedSql;
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlFileStore;

/**
 * 动态表 CRUD 的静态门面，读写的操作对象一律是 {@link MetaClass}。
 *
 * <p><b>进出都不是通用 Map 容器，而是按表结构确定的实体实例</b>，由 {@code MetaEntityStrategy} 两档实现：</p>
 * <ul>
 *   <li>{@code CODE}（普通 JVM 默认）：首次访问某张表时按 {@code DatabaseMetaData} 的列结构生成一份
 *       {@code MetaClass} 子类源码，交给 {@code javax.tools.JavaCompiler} 编译加载；每列有真实类型的
 *       字段与 getter/setter，结果集映射是写死的 setter 调用（逐行零反射）；</li>
 *   <li>{@code GENERIC}（Native Image 默认）：不生成类，逐行写通用 {@code MetaClass}；零反射、零代码生成，
 *       契约与 API 不变，只是没有类型化 getter，{@link #entityType(String)} 返回 {@code MetaClass.class}。</li>
 * </ul>
 *
 * <p>两档都通过 {@code getProperties()} 保留 {@code type / jdbcType / 主键 / 可空} 等类型描述，
 * 后续校验、转换、序列化都不用再猜类型。</p>
 *
 * <p>写哪几列由实体的变更集决定（{@link MetaClass#isChanged(String)}）：{@code newEntity} 之后
 * 赋了值的列才进 INSERT，读出来的行改过的列才进 UPDATE，赋 {@code null} 就是真的写 NULL。
 * 因此不需要「传了这个 key 才算改」这类 Map 约定，也没有额外的空值哨兵。</p>
 *
 * <p>这层刻意落在「完全静态」与「完全动态」之间：编译期写死的实体类应付不了运行期建表，
 * 而全 Map 的写法把类型信息丢光；两种实体形态都保留了列类型。表结构变了 {@link #refresh(String)}
 * 之后会构建新结构对应的新模型，旧实例不受影响。</p>
 *
 * <pre>{@code
 * MetaMapper.install(MetaMapperEngine.standalone("default"));
 *
 * MetaClass order = MetaMapper.newEntity("form_order");
 * order.set("order_no", "A001");
 * order.set("amount", "12.50");
 * MetaMapper.save(order);                              // 自增主键回填到 order 上
 *
 * order.setStatus("PAID");                             // 生成的 setter，类型化
 * MetaMapper.update(order);                            // 只写 status 这一列
 *
 * MetaClass row = MetaMapper.findById("form_order", 1L).orElseThrow();
 * BigDecimal amount = row.getBigDecimal("amount");     // 类型化读取
 * Class<? extends MetaClass> type = MetaMapper.entityType("form_order"); // 生成出来的类
 *
 * Page<MetaClass> page = MetaMapper.findPage("form_order", MetaQuery.by("status", 1), Pageable.of(0, 20));
 * MetaMapper.of("tenant-a").findById("form_order", 1L);
 * }</pre>
 *
 * <p>默认池直接用静态方法；多数据源用 {@link #of(String)} 拿绑定句柄，把“哪个库”固定
 * 在调用边界上，而不是塞进 {@code ThreadLocal}。静态字段只有一个 {@code volatile} 引擎引用，
 * 不缓存表结构、不持有连接，因此可被容器安全替换。</p>
 *
 * <p><b>写哪几列</b>由实体驱动（{@link MetaClass#isChanged(String)}）；<b>删不删</b>由语句驱动：
 * {@code metaFind/metaSave/metaUpdate/metaDelete} 先看
 * {@code meta/{数据源}/{命名空间}/{表}.xml} 里有没有同名 id 的语句，有就用文件、没有就按元数据现拼
 * （{@code metaDelete} 例外：没有语句时只退化逻辑删除，绝不自己拼 {@code DELETE}）。</p>
 */
public final class MetaMapper {

	/** 默认命名空间：语句落在 {@code meta/{数据源}/default/{表}.xml}。 */
	public static final String DEFAULT_NAMESPACE = MetaSqlFileStore.DEFAULT_NAMESPACE;

	/** 查询语句的标准 id。 */
	public static final String META_FIND = "metaFind";

	/** 新增语句的标准 id。 */
	public static final String META_SAVE = "metaSave";

	/** 更新语句的标准 id。 */
	public static final String META_UPDATE = "metaUpdate";

	/** 删除语句的标准 id。 */
	public static final String META_DELETE = "metaDelete";

	private static volatile MetaMapperEngine engine;

	private MetaMapper() {
	}

	/** 安装引擎；容器启动/测试初始化时调用，重复安装会替换旧引擎。 */
	public static synchronized void install(MetaMapperEngine metaMapperEngine) {
		engine = Objects.requireNonNull(metaMapperEngine, "metaMapperEngine");
	}

	/** 当前引擎；未安装时 fail-fast。 */
	public static MetaMapperEngine engine() {
		MetaMapperEngine current = engine;
		if (current == null) {
			throw new IllegalStateException("MetaMapper is not installed; call MetaMapper.install(...) first");
		}
		return current;
	}

	/** 指定数据源池的绑定客户端。 */
	public static MetaMapperClient of(String poolName) {
		return engine().client(poolName);
	}

	/** 默认池客户端。 */
	public static MetaMapperClient defaultClient() {
		return engine().defaultClient();
	}

	/** 当前表结构对应的实体类型：CODE 是运行期生成并加载的子类；GENERIC 恒为 {@link MetaClass}。 */
	public static Class<? extends MetaClass> entityType(String tableName) {
		return defaultClient().entityType(tableName);
	}

	// ------------------------------------------------------------------
	// 查询（默认池）
	// ------------------------------------------------------------------

	/** 造一个当前表结构的空实体（新增用它，改完列再 {@link #save(MetaClass)}）。 */
	public static MetaClass newEntity(String tableName) {
		return defaultClient().newEntity(tableName);
	}

	public static Optional<MetaClass> findById(String tableName, Object id) {
		return defaultClient().findById(tableName, id);
	}

	/** 按主键取一行，主键值读自实体（表名也读自实体）。 */
	public static Optional<MetaClass> findByKey(MetaClass key) {
		return defaultClient().findByKey(key);
	}

	public static Optional<MetaClass> findOne(String tableName, MetaQuery query) {
		return defaultClient().findOne(tableName, query);
	}

	public static List<MetaClass> findList(String tableName, MetaQuery query) {
		return defaultClient().findList(tableName, query);
	}

	public static List<MetaClass> findList(String tableName, MetaQuery query, long limit) {
		return defaultClient().findList(tableName, query, limit);
	}

	public static Page<MetaClass> findPage(String tableName, MetaQuery query, Pageable pageable) {
		return defaultClient().findPage(tableName, query, pageable);
	}

	public static long count(String tableName, MetaQuery query) {
		return defaultClient().count(tableName, query);
	}

	public static boolean exists(String tableName, MetaQuery query) {
		return defaultClient().exists(tableName, query);
	}

	// ------------------------------------------------------------------
	// 保存 / 更新（默认池）
	// ------------------------------------------------------------------

	/** 新增：写实体上赋过值的列，自增主键回填到实体上。 */
	public static MetaClass save(MetaClass entity) {
		return defaultClient().save(entity);
	}

	/** 批量新增：赋值的列一致时走 JDBC batch，否则逐条 {@link #save(MetaClass)}。 */
	public static List<MetaClass> saveAll(List<MetaClass> entities) {
		return defaultClient().saveAll(entities);
	}

	/** 部分更新：只写实体上改过的列，WHERE 用实体上的主键。 */
	public static int update(MetaClass entity) {
		return defaultClient().update(entity);
	}

	/** 表结构变化后刷新默认池的元数据快照、动态实体类与 Mapper 语句缓存。 */
	public static void refresh(String tableName) {
		defaultClient().refresh(tableName);
	}

	// ------------------------------------------------------------------
	// metaFind / metaSave / metaUpdate / metaDelete（默认数据源 + 默认命名空间）
	// ------------------------------------------------------------------

	/**
	 * 列表查询：SQL 来自 {@code meta/{默认池名}/{@value #DEFAULT_NAMESPACE}/{表}.xml} 里的
	 * {@code <select id="metaFind">}；文件或语句缺失时按元数据现拼（等价于 {@link #findList}）。
	 *
	 * <p>走了文件时，{@code query} 的条件与排序叠在文件那份基础语句外面；文件负责投影、连接、自己的
	 * 过滤条件（含逻辑删除），{@code query} 只负责调用方这次的筛选。</p>
	 */
	public static List<MetaClass> metaFind(String tableName, MetaQuery query) {
		return defaultClient().metaFind(tableName, query);
	}

	public static List<MetaClass> metaFind(String tableName, MetaQuery query, long limit) {
		return defaultClient().metaFind(tableName, query, limit);
	}

	/**
	 * 完整形态：数据源 / 命名空间 / 表 / 语句 id 全部显式给出，SQL 一律来自
	 * {@code meta/{datasource}/{namespace}/{table}.xml}，缺文件或缺该 id 直接报错。
	 */
	public static List<MetaClass> metaFind(String datasource, String namespace, String tableName, String queryId,
			MetaClass params) {
		return engine().client(datasource).metaFind(datasource, namespace, tableName, queryId, params);
	}

	/** 新增：优先用文件里的 {@code <insert id="metaSave">}，没有就等价于 {@link #save(MetaClass)}。 */
	public static MetaClass metaSave(MetaClass entity) {
		return defaultClient().metaSave(entity);
	}

	public static int metaSave(String datasource, String namespace, String tableName, String queryId,
			MetaClass params) {
		return engine().client(datasource).metaSave(datasource, namespace, tableName, queryId, params);
	}

	/** 更新：优先用文件里的 {@code <update id="metaUpdate">}，没有就等价于 {@link #update(MetaClass)}。 */
	public static int metaUpdate(MetaClass entity) {
		return defaultClient().metaUpdate(entity);
	}

	public static int metaUpdate(String datasource, String namespace, String tableName, String queryId,
			MetaClass params) {
		return engine().client(datasource).metaUpdate(datasource, namespace, tableName, queryId, params);
	}

	/**
	 * 删除：文件里写了 {@code <delete id="metaDelete">} 才物理删除；没有该语句时退化为逻辑删除
	 * （配置了逻辑删除列的话），两者都没有就报错。
	 */
	public static int metaDelete(MetaClass entity) {
		return defaultClient().metaDelete(entity);
	}

	public static int metaDelete(String datasource, String namespace, String tableName, String queryId,
			MetaClass params) {
		return engine().client(datasource).metaDelete(datasource, namespace, tableName, queryId, params);
	}

	/** 该表在指定数据源/命名空间下的可用语句 id（诊断用）。 */
	public static List<String> metaStatementIds(String datasource, String namespace, String tableName) {
		return engine().client(datasource).metaStatementIds(datasource, namespace, tableName);
	}

	/** 丢掉该表的语句解析结果，下次访问重新读盘（改完 XML 想立刻生效时用；平时自动按修改时间重读）。 */
	public static void clearMetaSqlCache(String datasource, String namespace, String tableName) {
		engine().client(datasource).clearMetaSqlCache(datasource, namespace, tableName);
	}

	// ------------------------------------------------------------------
	// 手写 SQL / 自定义语句（默认池）
	// ------------------------------------------------------------------

	/**
	 * 直接执行一段手写 SQL（{@code #{}} 绑定、{@code ${}} 标识符），不建 Mapper 文件。
	 * 与命名语句共用一条执行路径：绑定、类型收口、计划缓存都一样。
	 */
	public static List<MetaClass> findListBySql(String tableName, String sql, MetaClass params) {
		return defaultClient().findListBySql(tableName, sql, params);
	}

	public static List<MetaClass> findListBySql(String tableName, String sql, MetaClass params, long limit) {
		return defaultClient().findListBySql(tableName, sql, params, limit);
	}

	public static Optional<MetaClass> findOneBySql(String tableName, String sql, MetaClass params) {
		return defaultClient().findOneBySql(tableName, sql, params);
	}

	public static long countBySql(String tableName, String sql, MetaClass params) {
		return defaultClient().countBySql(tableName, sql, params);
	}

	/** 手写 SQL 分页；{@code countSql} 为空时自动包一层子查询计数。 */
	public static Page<MetaClass> findPageBySql(String tableName, String sql, String countSql, MetaClass params,
			Pageable pageable) {
		return defaultClient().findPageBySql(tableName, sql, countSql, params, pageable);
	}

	/** 手写 INSERT / UPDATE，返回影响行数。 */
	public static int executeBySql(String tableName, String sql, MetaClass params) {
		return defaultClient().executeBySql(tableName, sql, params);
	}

	/** 只抽取不执行：查看手写 SQL 最终拼成的文本与绑定的实参。 */
	public static MarkedSql sqlForText(String tableName, String sql, MetaClass params) {
		return defaultClient().sqlForText(tableName, sql, params);
	}

	/** 把一段手写 SQL 注册成默认池的命名语句（同 id 覆盖，{@code refresh} 不会撤销）。 */
	public static MetaMapperClient registerStatement(String tableName, String statementId,
			MapperDefinition.StatementType type, String sql) {
		return defaultClient().registerStatement(tableName, statementId, type, sql);
	}

	public static MetaMapperClient registerStatement(String tableName, String statementId, String sql) {
		return defaultClient().registerStatement(tableName, statementId, sql);
	}

	public static boolean unregisterStatement(String tableName, String statementId) {
		return defaultClient().unregisterStatement(tableName, statementId);
	}

	public static List<String> statementIds(String tableName) {
		return defaultClient().statementIds(tableName);
	}

	// ------------------------------------------------------------------
	// 缓存：分级清理 / 重新加载 / 诊断（默认池）
	// ------------------------------------------------------------------

	/** 只清该表的表结构快照（{@code MetaTable}），实体类与语句不动。 */
	public static void clearTableCache(String tableName) {
		defaultClient().clearTableCache(tableName);
	}

	public static void clearTableCache() {
		defaultClient().clearTableCache();
	}

	/** 只清该表运行期编译的实体类（{@code MetaClass} 子类），表结构快照不动。 */
	public static void clearEntityCache(String tableName) {
		defaultClient().clearEntityCache(tableName);
	}

	public static void clearEntityCache() {
		defaultClient().clearEntityCache();
	}

	/** 只清 Mapper 语句 AST 与执行计划缓存。 */
	public static void clearStatementCache(String tableName) {
		defaultClient().clearStatementCache(tableName);
	}

	public static void clearStatementCache() {
		defaultClient().clearStatementCache();
	}

	/** 清掉该表的全部派生缓存（元数据 + 实体类 + 语句 + 计划）。 */
	public static void clearCache(String tableName) {
		defaultClient().clearCache(tableName);
	}

	/** 清掉所有已创建池的派生缓存；代码注册的语句保留。 */
	public static void clearAllCaches() {
		engine().clearCaches();
	}

	/** 清缓存并立即重建（元数据当场回读、实体类当场编译），失败就在这一行暴露。 */
	public static MetaClass reload(String tableName) {
		return defaultClient().reload(tableName);
	}

	/** 默认池里已有快照的表全部重读一遍。 */
	public static void reloadAll() {
		defaultClient().reloadAll();
	}

	/** 默认池各级缓存的条目数与上限。 */
	public static Map<String, Object> cacheStats() {
		return defaultClient().cacheStats();
	}
}
