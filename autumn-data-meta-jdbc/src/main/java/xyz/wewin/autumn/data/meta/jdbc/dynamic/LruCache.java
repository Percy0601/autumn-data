package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@link BoundedCache} 的 JDK-only 实现：{@code LinkedHashMap(accessOrder=true)} 记 LRU 顺序，
 * 一把 {@link ReentrantLock} 保护链表结构性改动，一个 pending 表做「同 key 单飞」。
 *
 * <p>为什么不直接在 {@code ConcurrentHashMap} 上做淘汰：CHM 没有访问顺序，靠外部链表维护顺序时，
 * 「读命中」与「淘汰」之间必然有竞态，很容易短暂超出上限或在淘汰中丢掉刚被访问的条目。
 * 这里把顺序与淘汰收进同一把锁，语义简单且可验证；加载函数（读数据库元数据、javac 编译、
 * 解析 XML）在锁外执行，慢操作不会阻塞其它表的缓存读。</p>
 */
final class LruCache<K, V> implements BoundedCache<K, V> {

	private final int maximumSize;

	private final ReentrantLock lock = new ReentrantLock();

	/** accessOrder=true：get 会把条目移到链表尾部，头部就是最久未访问的。 */
	private final LinkedHashMap<K, V> entries;

	/** 正在加载中的 key → 结果占位；只在锁内访问，与 entries 共用一把锁避免竞态。 */
	private final Map<K, CompletableFuture<V>> pendingLoads = new HashMap<>();

	LruCache(int maximumSize) {
		if (maximumSize <= 0) {
			throw new IllegalArgumentException("maximumSize must be > 0 but was " + maximumSize);
		}
		this.maximumSize = maximumSize;
		this.entries = new LinkedHashMap<>(Math.min(maximumSize, 1024), 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > LruCache.this.maximumSize;
			}
		};
	}

	@Override
	public V getIfPresent(K key) {
		Objects.requireNonNull(key, "key");
		this.lock.lock();
		try {
			return this.entries.get(key);
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public V get(K key, Function<? super K, ? extends V> mappingFunction) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(mappingFunction, "mappingFunction");
		CompletableFuture<V> future;
		boolean owner;
		this.lock.lock();
		try {
			V hit = this.entries.get(key);
			if (hit != null) {
				return hit;
			}
			future = this.pendingLoads.get(key);
			owner = future == null;
			if (owner) {
				future = new CompletableFuture<>();
				this.pendingLoads.put(key, future);
			}
		}
		finally {
			this.lock.unlock();
		}
		if (!owner) {
			return await(future);
		}

		V loaded;
		try {
			// 加载在锁外：慢的元数据读取 / 编译不能挡住其它 key 的命中。
			loaded = mappingFunction.apply(key);
		}
		catch (RuntimeException | Error ex) {
			this.lock.lock();
			try {
				this.pendingLoads.remove(key);
			}
			finally {
				this.lock.unlock();
			}
			future.completeExceptionally(ex);
			throw ex;
		}
		this.lock.lock();
		try {
			if (loaded != null) {
				this.entries.put(key, loaded);
			}
			this.pendingLoads.remove(key);
		}
		finally {
			this.lock.unlock();
		}
		future.complete(loaded);
		return loaded;
	}

	@Override
	public void put(K key, V value) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(value, "value");
		this.lock.lock();
		try {
			this.entries.put(key, value);
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void invalidate(K key) {
		Objects.requireNonNull(key, "key");
		this.lock.lock();
		try {
			this.entries.remove(key);
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void invalidateAll() {
		this.lock.lock();
		try {
			this.entries.clear();
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void invalidateIf(Predicate<? super K> condition) {
		Objects.requireNonNull(condition, "condition");
		this.lock.lock();
		try {
			this.entries.keySet().removeIf(condition::test);
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public long estimatedSize() {
		this.lock.lock();
		try {
			return this.entries.size();
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public Map<K, V> asMap() {
		this.lock.lock();
		try {
			return Map.copyOf(this.entries);
		}
		finally {
			this.lock.unlock();
		}
	}

	@Override
	public void cleanUp() {
		// put 时 removeEldestEntry 已同步淘汰，没有待处理的维护工作。
	}

	@Override
	public int maximumSize() {
		return this.maximumSize;
	}

	private static <V> V await(CompletableFuture<V> future) {
		try {
			return future.join();
		}
		catch (CompletionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException("cache load failed", cause == null ? ex : cause);
		}
	}
}
