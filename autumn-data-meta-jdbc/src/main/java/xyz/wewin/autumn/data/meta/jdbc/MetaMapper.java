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
import xyz.wewin.autumn.data.meta.jdbc.sql.MetaSqlGenerator;

/**
 * 动态表 CRUD 的静态门面，读写的操作对象一律是 {@link MetaClass}。
 *
 * <p><b>SQL 只有一个来源：XML 语句文件。</b>一张表一个文件，路径
 * {@code {根}/{数据源}/{命名空间}/{表名}.xml}，语法与 MyBatis 一致（{@code #{}} 绑定、
 * {@code ${}} 标识符、{@code <if>/<where>/<trim>/<foreach>/<choose>}、{@code <sql>/<include>}）。
 * 框架在文件里维护一个 {@code GENERATED START/END} 自动生成区，按表结构生成七条默认语句：</p>
 *
 * <pre>
 * findList / findPage / findOne / findById / save / update / delete
 * </pre>
 *
 * <p>标记之外的自定义语句归使用方所有：表结构变化时框架<b>只重写生成块</b>，其余内容逐字保留。
 * 文件读过后进有界缓存，磁盘文件按「修改时间 + 长度」自动重读，classpath 内资源只读一次。</p>
 *
 * <p><b>查询条件 = 参数实体 + {@code <if>}</b>：传一个 {@link MetaClass}，赋了值的列才进 WHERE；
 * 比较、模糊、IN、排序等复杂条件写在 XML 里（自定义语句用 {@code selectList/selectOne/selectCount/
 * execute} 按 id 调用），不在 Java 侧现拼 SQL。</p>
 *
 * <p><b>写哪几列 = 实体的变更集</b>：默认 {@code save}/{@code update} 用框架内置谓词
 * {@code changed('col')}（{@link MetaClass#isChanged(String)}），因此「显式置 NULL」和
 * 「这一列没动」是两件事，读出来的行改过的列才进 UPDATE。</p>
 *
 * <p>进出都不是通用 Map 容器，而是按表结构确定的实体实例：{@code CODE} 档在首次访问时按
 * {@code DatabaseMetaData} 生成并编译 {@code MetaClass} 子类（真实类型字段、逐行零反射）；
 * {@code GENERIC} 档写通用 {@link MetaClass}（Native Image 用）。两档都通过
 * {@code getProperties()} 保留列类型描述。</p>
 *
 * <pre>{@code
 * MetaMapper.install(MetaMapperEngine.forDataSource("primary", dataSource));
 *
 * MetaClass order = MetaMapper.newEntity("form_order");
 * order.set("order_no", "A001");
 * order.set("amount", new BigDecimal("12.50"));
 * MetaMapper.save(order);                                   // 自增主键回填到 order 上
 *
 * MetaClass query = new MetaClass();
 * query.set("status", 1);
 * List<MetaClass> rows = MetaMapper.findList("form_order", query);
 * Page<MetaClass> page = MetaMapper.findPage("form_order", query, Pageable.of(0, 20));
 *
 * MetaMapper.delete(order);                                 // 执行 XML 里的 delete 语句
 * MetaMapper.of("tenant-a", "erp").findById("form_order", 1L);   // 绑定数据源 + 命名空间
 * }</pre>
 *
 * <p>默认池直接用静态方法；多数据源 / 业务划分用 {@link #of(String, String)} 把「哪个库、哪个命名空间」
 * 固定在调用边界上，不塞进 {@code ThreadLocal}。静态字段只有一个 {@code volatile} 引擎引用，
 * 不缓存表结构、不持有连接，因此可被容器安全替换。</p>
 */
public final class MetaMapper {

	/** 默认命名空间：语句落在 {@code {根}/{数据源}/default/{表名}.xml}。 */
	public static final String DEFAULT_NAMESPACE = MetaSqlFileStore.DEFAULT_NAMESPACE;

	/** 框架自动生成的七条默认语句 id。 */
	public static final List<String> DEFAULT_STATEMENT_IDS = MetaSqlGenerator.DEFAULT_IDS;

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

	/** 指定数据源池的绑定客户端（默认命名空间）。 */
	public static MetaMapperClient of(String poolName) {
		return engine().client(poolName);
	}

	/** 指定「数据源池 + 命名空间」的绑定客户端：业务划分或包名称可以各有一套语句文件。 */
	public static MetaMapperClient of(String poolName, String namespace) {
		return engine().client(poolName, namespace);
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

	/** 条件来自参数实体：赋了值的列按 XML 里的 {@code <if>} 进 WHERE，传 {@code null} 表示无条件。 */
	public static Optional<MetaClass> findOne(String tableName, MetaClass params) {
		return defaultClient().findOne(tableName, params);
	}

	public static List<MetaClass> findList(String tableName, MetaClass params) {
		return defaultClient().findList(tableName, params);
	}

	public static List<MetaClass> findList(String tableName, MetaClass params, long limit) {
		return defaultClient().findList(tableName, params, limit);
	}

	public static Page<MetaClass> findPage(String tableName, MetaClass params, Pageable pageable) {
		return defaultClient().findPage(tableName, params, pageable);
	}

	public static long count(String tableName, MetaClass params) {
		return defaultClient().count(tableName, params);
	}

	public static boolean exists(String tableName, MetaClass params) {
		return defaultClient().exists(tableName, params);
	}

	// ------------------------------------------------------------------
	// 保存 / 更新 / 删除（默认池）
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

	/** 删除：执行 XML 里的 {@code delete} 语句（有逻辑删除列时默认是逻辑删除，否则物理删除）。 */
	public static int delete(MetaClass entity) {
		return defaultClient().delete(entity);
	}

	/** 表结构变化后刷新默认池的元数据快照、动态实体类与语句缓存。 */
	public static void refresh(String tableName) {
		defaultClient().refresh(tableName);
	}

	// ------------------------------------------------------------------
	// 手写 SQL / 自定义语句（默认池）
	// ------------------------------------------------------------------

	/**
	 * 直接执行一段手写 SQL（{@code #{}} 绑定、{@code ${}} 标识符），不落语句文件。
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

	/** 手写 INSERT / UPDATE / DELETE，返回影响行数。 */
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

	/** 该表全部可见的语句 id：七条默认 + 文件里的自定义 + 代码注册的。 */
	public static List<String> statementIds(String tableName) {
		return defaultClient().statementIds(tableName);
	}

	/** 显式生成 / 更新该表的 XML 文件并返回其位置（想立刻看到文件时用；首次访问也会自动做）。 */
	public static String ensureStatementFile(String tableName) {
		return defaultClient().ensureStatementFile(tableName);
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

	/** 只清语句 AST 与执行计划缓存（代码注册的语句保留）。 */
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
