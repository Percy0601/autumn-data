package xyz.wewin.autumn.data.meta.jdbc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaMapperClient;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaMapperEngine;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaQuery;
import xyz.wewin.autumn.data.meta.jdbc.dynamic.MetaRow;

/**
 * 动态表 CRUD 的静态门面。
 *
 * <p>默认池直接用静态方法；多数据源用 {@link #of(String)} 拿绑定句柄，把“哪个库”固定
 * 在调用边界上，而不是塞进 {@code ThreadLocal}。静态字段只有一个
 * {@code volatile} 引擎引用，不缓存表结构、不持有连接，因此可被容器安全替换。</p>
 *
 * <pre>{@code
 * MetaMapper.install(MetaMapperEngine.standalone("default"));
 *
 * MetaMapper.save("form_order", Map.of("order_no", "A001", "amount", "12.50"));
 * MetaMapper.findPage("form_order", MetaQuery.by("status", 1), Pageable.of(0, 20));
 *
 * MetaMapper.of("tenant-a").findById("form_order", 1L);
 * }</pre>
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

	// ------------------------------------------------------------------
	// 查询（默认池）
	// ------------------------------------------------------------------

	public static Optional<MetaRow> findById(String tableName, Object id) {
		return defaultClient().findById(tableName, id);
	}

	public static Optional<MetaRow> findByKey(String tableName, Map<String, ?> key) {
		return defaultClient().findByKey(tableName, key);
	}

	public static Optional<MetaRow> findOne(String tableName, MetaQuery query) {
		return defaultClient().findOne(tableName, query);
	}

	public static List<MetaRow> findList(String tableName, MetaQuery query) {
		return defaultClient().findList(tableName, query);
	}

	public static List<MetaRow> findList(String tableName, MetaQuery query, long limit) {
		return defaultClient().findList(tableName, query, limit);
	}

	public static Page<MetaRow> findPage(String tableName, MetaQuery query, Pageable pageable) {
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

	/** 表结构变化后刷新默认池的元数据快照。 */
	public static void refresh(String tableName) {
		defaultClient().refresh(tableName);
	}
}
