package xyz.wewin.autumn.data.meta.jdbc;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.engine.MetaMapperEngine;

/**
 * 动态表 CRUD 的静态门面，读写的操作对象一律是 {@link MetaClass}。
 *
 * <p><b>进出都不是通用 Map 容器，而是运行期生成的实体实例</b>：首次访问某张表时，引擎按
 * {@code DatabaseMetaData} 读出的列结构，用 FreeMarker 生成一份 {@code MetaClass} 子类源码，
 * 交给 {@code javax.tools.JavaCompiler} 编译并加载。生成类每列都有真实类型的字段与 getter/setter，
 * 结果集映射是写死的 setter 调用（逐行零反射），同时通过 {@code getProperties()} 保留
 * {@code type / jdbcType / 主键 / 可空} 等类型描述，后续校验、转换、序列化都不用再猜类型。</p>
 *
 * <p>写哪几列由实体的变更集决定（{@link MetaClass#isChanged(String)}）：{@code newEntity} 之后
 * 赋了值的列才进 INSERT，读出来的行改过的列才进 UPDATE，赋 {@code null} 就是真的写 NULL。
 * 因此不需要「传了这个 key 才算改」这类 Map 约定，也没有额外的空值哨兵。</p>
 *
 * <p>这层刻意落在「完全静态」与「完全动态」之间：编译期写死的实体类应付不了运行期建表，
 * 而全 Map 的写法把类型信息丢光；运行期生成类两者兼得。表结构变了 {@link #refresh(String)}
 * 之后会生成新结构对应的新类，旧实例不受影响。</p>
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
 * <p><b>没有 delete</b>：应用侧不提供物理删除；需要作废时改状态列。</p>
 */
public final class MetaMapper {

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

	/** 当前表结构对应的动态实体类型（FreeMarker 生成 + 运行期编译）。 */
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
}
