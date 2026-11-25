package xyz.wewin.autumn.data.meta.jdbc.support;

/**
 * {@link MetaCache} 的工厂：上层只依赖接口，不依赖具体实现。
 *
 * <p>将来引入 Caffeine 时，在这里返回 {@code Caffeine.newBuilder().maximumSize(size).build()}
 * 的适配器即可，{@link MetaTableRegistry} / {@link MetaEntityRegistry} /
 * {@link MetaSqlFileStore} 的调用代码不用改。</p>
 */
public final class MetaCaches {

	private MetaCaches() {
	}

	/** 默认上限 {@value MetaCache#DEFAULT_MAXIMUM_SIZE} 的 LRU 缓存。 */
	public static <K, V> MetaCache<K, V> lru() {
		return lru(MetaCache.DEFAULT_MAXIMUM_SIZE);
	}

	/** 指定上限的 LRU 缓存。 */
	public static <K, V> MetaCache<K, V> lru(int maximumSize) {
		return new LruMetaCache<>(maximumSize);
	}
}
