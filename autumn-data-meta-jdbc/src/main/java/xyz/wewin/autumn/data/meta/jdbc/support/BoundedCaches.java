package xyz.wewin.autumn.data.meta.jdbc.support;

/**
 * {@link BoundedCache} 的工厂：上层只依赖接口，不依赖具体实现。
 *
 * <p>将来引入 Caffeine 时，在这里返回 {@code Caffeine.newBuilder().maximumSize(size).build()}
 * 的适配器即可，{@link MetaTableRegistry} / {@link MetaEntityRegistry} /
 * {@link MetaStatementStore} 的调用代码不用改。</p>
 */
public final class BoundedCaches {

	private BoundedCaches() {
	}

	/** 默认上限 {@value BoundedCache#DEFAULT_MAXIMUM_SIZE} 的 LRU 缓存。 */
	public static <K, V> BoundedCache<K, V> lru() {
		return lru(BoundedCache.DEFAULT_MAXIMUM_SIZE);
	}

	/** 指定上限的 LRU 缓存。 */
	public static <K, V> BoundedCache<K, V> lru(int maximumSize) {
		return new LruCache<>(maximumSize);
	}
}
