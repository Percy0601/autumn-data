package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.util.Locale;

import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCache;
import xyz.wewin.autumn.data.meta.jdbc.support.BoundedCaches;

/**
 * 形状 → 执行计划的有界缓存：同一形状只编译一次。
 *
 * <p>键是 <code>表名@结构指纹/SQL 文本</code>，三个部分各有各的用处：</p>
 * <ul>
 *   <li>表名让 {@link #invalidate(String)} 能按表清掉全部形状；</li>
 *   <li>结构指纹让改表后的同一段 SQL 落到新条目上 —— 槽位里的列对象、类型、setter 都是按旧结构编的，
 *       复用会静默绑错类型；</li>
 *   <li>SQL 文本本身参与键，所以 {@code IN (?,?,?)} 的不同长度、{@code <if>} 的不同命中分支
 *       都是不同形状。这与数据库端复用游标的粒度一致：文本不同，服务端本来也不会复用计划。</li>
 * </ul>
 *
 * <p>上限按 LRU 淘汰：低代码场景下形状数量随表与语句线性增长，冷形状被淘汰后下次访问重编一次，
 * 内存不随使用时间无限增长。淘汰掉的计划仍可能被正在执行的操作持有，因此不影响正确性。</p>
 */
public final class MetaPlanCache {

	private final BoundedCache<String, MetaSqlPlan> plans;

	public MetaPlanCache(int maximumSize) {
		this.plans = BoundedCaches.lru(maximumSize);
	}

	public MetaPlanCache() {
		this(BoundedCache.DEFAULT_MAXIMUM_SIZE);
	}

	/** 命中直接返回；未命中时编译一次并放进缓存。 */
	public MetaSqlPlan plan(MetaTable table, MarkedSql marked) {
		return this.plans.get(key(table, marked), ignored -> MetaPlanCompiler.compile(table, marked));
	}

	/** 丢掉某张表已编过的全部形状。 */
	public void invalidate(String tableName) {
		String prefix = normalize(tableName) + '@';
		this.plans.invalidateIf(key -> key.startsWith(prefix));
	}

	public void invalidateAll() {
		this.plans.invalidateAll();
	}

	/** 已缓存的形状数（诊断用）。 */
	public long size() {
		return this.plans.estimatedSize();
	}

	/** 缓存上限（诊断用）。 */
	public int maximumSize() {
		return this.plans.maximumSize();
	}

	static String key(MetaTable table, MarkedSql marked) {
		return normalize(table.name()) + '@' + table.fingerprint() + '/' + marked.sql();
	}

	private static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
