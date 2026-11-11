package xyz.wewin.autumn.data.meta.jdbc.dynamic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 有界 LRU 缓存的行为契约：上限、淘汰顺序、并发单飞、加载失败语义。
 * 这些断言在将来换成 Caffeine 适配器后依然成立（除淘汰时序需先 {@code cleanUp()}）。
 */
class BoundedCacheTest {

	@Test
	void defaultMaximumSizeIsTenThousand() {
		assertThat(BoundedCache.DEFAULT_MAXIMUM_SIZE).isEqualTo(10_000);
		assertThat(BoundedCaches.<String, String>lru().maximumSize()).isEqualTo(10_000);
	}

	@Test
	void rejectsNonPositiveMaximumSize() {
		assertThatThrownBy(() -> BoundedCaches.lru(0)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maximumSize");
		assertThatThrownBy(() -> BoundedCaches.lru(-1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void evictsLeastRecentlyUsedEntry() {
		BoundedCache<String, String> cache = BoundedCaches.lru(2);
		assertThat(cache.get("a", key -> "A")).isEqualTo("A");
		assertThat(cache.get("b", key -> "B")).isEqualTo("B");
		assertThat(cache.getIfPresent("a")).isEqualTo("A"); // 读命中把 a 提到最近使用
		assertThat(cache.get("c", key -> "C")).isEqualTo("C");

		assertThat(cache.estimatedSize()).isEqualTo(2);
		assertThat(cache.getIfPresent("b")).isNull();
		assertThat(cache.getIfPresent("a")).isEqualTo("A");
		assertThat(cache.getIfPresent("c")).isEqualTo("C");
		cache.cleanUp();
		assertThat(cache.estimatedSize()).isEqualTo(2);
	}

	@Test
	void neverExceedsMaximumSize() {
		BoundedCache<Integer, Integer> cache = BoundedCaches.lru(3);
		for (int i = 0; i < 500; i++) {
			cache.put(i, i);
			assertThat(cache.estimatedSize()).isLessThanOrEqualTo(3);
		}
		assertThat(cache.getIfPresent(499)).isEqualTo(499);
		assertThat(cache.getIfPresent(0)).isNull();
	}

	@Test
	void loadsOnlyOnceForConcurrentMissOnSameKey() throws Exception {
		BoundedCache<String, Object> cache = BoundedCaches.lru(8);
		AtomicInteger loads = new AtomicInteger();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			Future<Object> first = pool.submit(() -> cache.get("k", key -> {
				loads.incrementAndGet();
				entered.countDown();
				await(release);
				return new Object();
			}));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

			List<Future<Object>> others = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				others.add(pool.submit(() -> cache.get("k", key -> {
					loads.incrementAndGet();
					return new Object();
				})));
			}
			release.countDown();
			Object expected = first.get(5, TimeUnit.SECONDS);
			for (Future<Object> other : others) {
				assertThat(other.get(5, TimeUnit.SECONDS)).isSameAs(expected);
			}
			assertThat(loads.get()).isEqualTo(1);
			assertThat(cache.estimatedSize()).isEqualTo(1);
		}
		finally {
			release.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void slowLoaderDoesNotBlockReadsOfOtherKeys() throws Exception {
		BoundedCache<String, String> cache = BoundedCaches.lru(8);
		cache.put("fast", "FAST");
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService loader = Executors.newSingleThreadExecutor();
		ExecutorService reader = Executors.newSingleThreadExecutor();
		try {
			Future<String> slow = loader.submit(() -> cache.get("slow", key -> {
				entered.countDown();
				await(release);
				return "SLOW";
			}));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			// 加载在锁外执行：别的 key 即使在这个 loader 阻塞期间也必须能立刻读到
			assertThat(reader.submit(() -> cache.getIfPresent("fast")).get(1, TimeUnit.SECONDS))
					.isEqualTo("FAST");
			release.countDown();
			assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo("SLOW");
		}
		finally {
			release.countDown();
			loader.shutdownNow();
			reader.shutdownNow();
		}
	}

	@Test
	void failedLoadIsNotCachedAndCanBeRetried() {
		BoundedCache<String, String> cache = BoundedCaches.lru(4);
		AtomicInteger attempts = new AtomicInteger();
		assertThatThrownBy(() -> cache.get("k", key -> {
			if (attempts.incrementAndGet() == 1) {
				throw new IllegalStateException("boom");
			}
			return "OK";
		})).isInstanceOf(IllegalStateException.class).hasMessage("boom");
		assertThat(cache.estimatedSize()).isZero();

		assertThat(cache.get("k", key -> "OK")).isEqualTo("OK");
		assertThat(cache.estimatedSize()).isEqualTo(1);
	}

	@Test
	void nullLoadResultIsNotCached() {
		BoundedCache<String, String> cache = BoundedCaches.lru(4);
		assertThat(cache.get("k", key -> null)).isNull();
		assertThat(cache.getIfPresent("k")).isNull();
		assertThat(cache.estimatedSize()).isZero();
	}

	@Test
	void asMapReturnsAnImmutableSnapshot() {
		BoundedCache<String, String> cache = BoundedCaches.lru(4);
		cache.put("a", "A");
		Map<String, String> snapshot = cache.asMap();
		assertThat(snapshot).containsEntry("a", "A");

		cache.put("b", "B");
		assertThat(snapshot).hasSize(1); // 快照，不是 live view
		assertThat(cache.asMap()).hasSize(2);
		assertThatThrownBy(() -> snapshot.put("c", "C"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void invalidateAndInvalidateIf() {
		BoundedCache<String, String> cache = BoundedCaches.lru(8);
		cache.put("order:a", "A");
		cache.put("order:b", "B");
		cache.put("item:a", "I");

		cache.invalidateIf(key -> key.startsWith("order:"));
		assertThat(cache.estimatedSize()).isEqualTo(1);
		assertThat(cache.getIfPresent("item:a")).isEqualTo("I");

		cache.invalidate("item:a");
		assertThat(cache.estimatedSize()).isZero();
		cache.put("x", "X");
		cache.invalidateAll();
		assertThat(cache.estimatedSize()).isZero();
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
