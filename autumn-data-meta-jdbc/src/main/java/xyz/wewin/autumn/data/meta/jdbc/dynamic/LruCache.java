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

	/**
	 * 正在加载中的 key → 结果占位；只在锁内访问，与 entries 共用一把锁避免竞态。
	 * 失效操作会把对应的占位从表里摘掉：这样 refresh 之后的新请求不会 join 到过期的在途加载，
	 * 而在途加载收尾时通过「自己仍是当前占位」的判断决定要不要把结果写回缓存。
	 */
	private final Map<K, Load<V>> pendingLoads = new HashMap<>();

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
		Load<V> load;
		boolean owner;
		this.lock.lock();
		try {
			V hit = this.entries.get(key);
			if (hit != null) {
				return hit;
			}
			load = this.pendingLoads.get(key);
			owner = load == null;
			if (owner) {
				load = new Load<>();
				this.pendingLoads.put(key, load);
			}
		}
		finally {
			this.lock.unlock();
		}
		if (!owner) {
			return await(load.future);
		}

		V loaded;
		try {
			// 加载在锁外：慢的元数据读取 / 编译不能挡住其它 key 的命中。
			loaded = mappingFunction.apply(key);
		}
		catch (RuntimeException | Error ex) {
			this.lock.lock();
			try {
				removePendingIfCurrent(key, load);
			}
			finally {
				this.lock.unlock();
			}
			load.future.completeExceptionally(ex);
			throw ex;
		}
		this.lock.lock();
		try {
			// 加载期间若被 invalidate / put 取代，旧结果只交给本次调用者，绝不写回缓存：
			// 否则「改表 → refresh」之后，慢查询会把旧表结构重新塞回来。
			if (removePendingIfCurrent(key, load) && loaded != null) {
				this.entries.put(key, loaded);
			}
		}
		finally {
			this.lock.unlock();
		}
		load.future.complete(loaded);
		return loaded;
	}

	/** 只在「自己仍是该 key 的当前占位」时摘掉占位；调用方必须持有 lock。 */
	private boolean removePendingIfCurrent(K key, Load<V> load) {
		if (this.pendingLoads.get(key) != load) {
			return false;
		}
		this.pendingLoads.remove(key);
		return true;
	}

	@Override
	public void put(K key, V value) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(value, "value");
		this.lock.lock();
		try {
			// 手工登记要压过同 key 的在途加载，不能被稍后完成的旧结果覆盖。
			this.pendingLoads.remove(key);
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
			this.pendingLoads.remove(key);
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
			this.pendingLoads.clear();
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
			this.pendingLoads.keySet().removeIf(condition::test);
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

	/** 一次在途加载的占位；失效时被移出 pendingLoads，旧 owner 据此放弃写回。 */
	private static final class Load<V> {

		private final CompletableFuture<V> future = new CompletableFuture<>();
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
