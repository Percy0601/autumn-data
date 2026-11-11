package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 有界本地缓存契约：按表名/结构指纹缓存元数据、动态实体类与 Mapper AST。
 *
 * <p><b>API 刻意对齐 {@code com.github.benmanes.caffeine.cache.Cache}</b>：本项目核心层不引入任何
 * 缓存库（也不引 Spring），用 {@link BoundedCaches#lru(int)} 拿 JDK 自带的 LRU 实现；将来若决定
 * 换成 Caffeine，只需新增一个适配器实现本接口并把工厂的返回值换掉，三个 Registry 一行都不用改。
 * 同名同义的方法如下：</p>
 *
 * <pre>
 * getIfPresent(key)                  Caffeine: getIfPresent(key)
 * get(key, mappingFunction)          Caffeine: get(key, mappingFunction)
 * put(key, value)                    Caffeine: put(key, value)
 * invalidate(key) / invalidateAll()  Caffeine: invalidate(key) / invalidateAll()
 * estimatedSize() / cleanUp()        Caffeine: estimatedSize() / cleanUp()
 * asMap()                           Caffeine: asMap()
 * </pre>
 *
 * <p>扩展方法在 Caffeine 下也有一行等价写法（{@code invalidateIf} 可直接走 Caffeine 的 live view）：</p>
 *
 * <pre>
 * invalidateIf(condition)   →  cache.asMap().keySet().removeIf(condition)
 * maximumSize()             →  (int) cache.policy().eviction().orElseThrow().getMaximum()
 * </pre>
 *
 * <p>{@code asMap()} 的差别：LRU 实现返回一份<b>不可修改快照</b>，Caffeine 的 {@code asMap()} 是
 * 可写的 live view。本层只用它做诊断遍历，写入/失效一律走 {@code put} / {@code invalidate*}，
 * 因此两种语义都够用。</p>
 *
 * <p>语义约定：键与值都不允许为 {@code null}；{@code get} 的加载函数返回 {@code null} 表示不缓存，
 * 抛异常则只传播不缓存；同一个 key 并发未命中时只有一次加载（单飞），加载过程不持有全局锁；
 * <b>加载期间发生 {@code invalidate*} / {@code put}，旧加载结果不得再写回缓存</b>（在途调用者仍可拿到
 * 它当时读到的值，之后的请求重新加载），否则运行期改表后 {@code refresh} 会失效；
 * 淘汰是<b>同步</b>的，{@link #estimatedSize()} 恒不超过 {@link #maximumSize()}
 * （Caffeine 的淘汰是异步的，切过去后测试断言要改用 {@code cleanUp()} 后再看 size）。</p>
 */
public interface BoundedCache<K, V> {

	/** 默认缓存上限：一万张表。 */
	int DEFAULT_MAXIMUM_SIZE = 10_000;

	/** 命中返回值并记一次访问；未命中返回 {@code null}。 */
	V getIfPresent(K key);

	/** 命中直接返回；未命中时用加载函数原子地填充，同 key 并发只加载一次。 */
	V get(K key, Function<? super K, ? extends V> mappingFunction);

	/** 写入/覆盖一个条目；超出上限时淘汰最久未访问的条目。 */
	void put(K key, V value);

	/** 丢掉一个条目。 */
	void invalidate(K key);

	/** 清空全部条目。 */
	void invalidateAll();

	/** 按条件批量失效（{@code MetaEntityRegistry} 按表名前缀清掉该表所有结构版本）。 */
	void invalidateIf(Predicate<? super K> condition);

	/** 当前条目数。 */
	long estimatedSize();

	/** 当前全部条目的只读快照（诊断用）。 */
	Map<K, V> asMap();

	/** 触发一次维护工作；LRU 实现是同步淘汰，因此这里无需做事。 */
	void cleanUp();

	/** 配置的上限。 */
	int maximumSize();
}
