package xyz.wewin.autumn.data.meta.jdbc;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import xyz.wewin.autumn.data.common.meta.MetaClass;
import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaMapperEngine;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaQuery;

/**
 * 动态表 CRUD 的静态门面，查询一律返回 {@link MetaClass}。
 *
 * <p><b>返回的不是通用 Map 容器，而是运行期生成的实体实例</b>：首次访问某张表时，引擎按
 * {@code DatabaseMetaData} 读出的列结构，用 FreeMarker 生成一份 {@code MetaClass} 子类源码，
 * 交给 {@code javax.tools.JavaCompiler} 编译并加载。生成类每列都有真实类型的字段与 getter/setter，
 * 结果集映射是写死的 setter 调用（逐行零反射），同时通过 {@code getProperties()} 保留
 * {@code type / jdbcType / 主键 / 可空} 等类型描述，后续校验、转换、序列化都不用再猜类型。</p>
 *
 * <p>这层刻意落在「完全静态」与「完全动态」之间：编译期写死的实体类应付不了运行期建表，
 * 而全 Map 的写法把类型信息丢光；运行期生成类两者兼得。表结构变了 {@link #refresh(String)}
 * 之后会生成新结构对应的新类，旧实例不受影响。</p>
 *
 * <pre>{@code
 * MetaMapper.install(MetaMapperEngine.standalone("default"));
 *
 * MetaMapper.save("form_order", Map.of("order_no", "A001", "amount", "12.50"));
 * MetaClass row = MetaMapper.findById("form_order", 1L).orElseThrow();
 * BigDecimal amount = row.getBigDecimal("amount");       // 类型化读取
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
 * <p><b>没有 delete</b>：应用侧不提供物理删除；需要作废时用 {@code updateById} 改状态列。</p>
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

	public static Optional<MetaClass> findById(String tableName, Object id) {
		return defaultClient().findById(tableName, id);
	}

	public static Optional<MetaClass> findByKey(String tableName, Map<String, ?> key) {
		return defaultClient().findByKey(tableName, key);
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

	public static Object save(String tableName, Map<String, ?> values) {
		return defaultClient().save(tableName, values);
	}

	public static List<Object> saveAll(String tableName, List<Map<String, ?>> rows) {
		return defaultClient().saveAll(tableName, rows);
	}

	public static int updateById(String tableName, Object id, Map<String, ?> values) {
		return defaultClient().updateById(tableName, id, values);
	}

	public static int updateByKey(String tableName, Map<String, ?> key, Map<String, ?> values) {
		return defaultClient().updateByKey(tableName, key, values);
	}

	/** 表结构变化后刷新默认池的元数据快照、动态实体类与 Mapper 语句缓存。 */
	public static void refresh(String tableName) {
		defaultClient().refresh(tableName);
	}
}
