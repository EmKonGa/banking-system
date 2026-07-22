package com.banking.account.cache;

import com.banking.common.exception.AppException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the three properties {@link StripedLockRedisCache} exists for: concurrent misses on one
 * key collapse to a single load, misses on unrelated keys stay parallel, and a Redis outage
 * degrades to a database read rather than an error.
 */
class StripedLockRedisCacheTest {

    private final InMemoryCacheWriter writer = new InMemoryCacheWriter();

    /** Defaults need 100 calls before the breaker can open, so it stays closed for these tests. */
    private final CircuitBreaker breaker = CircuitBreaker.ofDefaults("test");

    private final StripedLockRedisCache cache = new StripedLockRedisCache(
            "test", writer, RedisCacheConfiguration.defaultCacheConfig(), breaker);

    @Test
    void concurrentMissesOnTheSameKeyLoadOnce() throws Exception {
        int threads = 32;
        AtomicInteger loads = new AtomicInteger();
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    startTogether.await();
                    return cache.get("hot-key", () -> {
                        loads.incrementAndGet();
                        Thread.sleep(100); // hold the lock long enough for the rest to pile up
                        return "loaded";
                    });
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(loads.get()).isEqualTo(1);
        assertThat(cache.get("hot-key").get()).isEqualTo("loaded");
    }

    /**
     * The reason for striping rather than Spring Data Redis' cache-wide lock. Each loader parks
     * until every other loader has entered — if misses on unrelated keys were serialised, only one
     * would ever enter and the latch would never reach zero.
     *
     * <p>Integer keys 0..7 are deliberate: {@code Integer.hashCode()} is the value itself, so with
     * 256 stripes these land on eight distinct stripes every run rather than colliding by chance.
     */
    @Test
    void missesOnDifferentKeysRunInParallel() throws Exception {
        int keys = 8;
        CountDownLatch allEntered = new CountDownLatch(keys);
        ExecutorService pool = Executors.newFixedThreadPool(keys);
        List<Future<?>> results = new ArrayList<>();

        try {
            for (int i = 0; i < keys; i++) {
                int key = i;
                results.add(pool.submit(() -> cache.get(key, () -> {
                    allEntered.countDown();
                    // Each loader blocks until every other loader has also entered. Under a
                    // cache-wide lock only one can be inside at a time, so this await times out.
                    if (!allEntered.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("loaders were serialised across keys");
                    }
                    return "value-" + key;
                })));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Inspecting every Future is the point: a loader that threw is otherwise captured by the
        // Future and silently discarded, and the latch still drains as timed-out loaders retire
        // one by one — so counting the latch alone would pass even when fully serialised.
        for (Future<?> result : results) {
            result.get();
        }
        assertThat(cache.get(3).get()).isEqualTo("value-3");
    }

    @Test
    void redisFailureFallsThroughToTheLoader() {
        writer.failing = true;

        String value = cache.get("any-key", () -> "from-database");

        assertThat(value).isEqualTo("from-database");
    }

    /**
     * With a 2s command timeout, a failed read followed by a re-check under the lock and then a
     * write costs ~6s per request while Redis is down — enough to exhaust the request thread pool
     * and turn a cache outage into a service outage. Once a probe fails, nothing further should be
     * attempted against Redis on that request.
     */
    @Test
    void aFailedProbeSkipsTheSecondReadAndTheWrite() {
        writer.failing = true;

        cache.get("any-key", () -> "from-database");

        assertThat(writer.reads.get()).isEqualTo(1);
        assertThat(writer.writes.get()).isZero();
    }

    /** An open breaker must not touch Redis at all — that is the point of it being open. */
    @Test
    void openBreakerSkipsRedisEntirely() {
        breaker.transitionToOpenState();

        String value = cache.get("any-key", () -> "from-database");

        assertThat(value).isEqualTo("from-database");
        assertThat(writer.reads.get()).isZero();
        assertThat(writer.writes.get()).isZero();
    }

    /**
     * The loader's own exception must stay wrapped, because {@code CacheAspectSupport} unwraps it
     * and rethrows the cause — that is how a missing account still surfaces as a 404.
     */
    @Test
    void loaderExceptionIsWrappedSoTheCauseSurvives() {
        assertThatThrownBy(() -> cache.get("missing", () -> {
            throw new AppException("Account not found", HttpStatus.NOT_FOUND);
        }))
                .isInstanceOf(Cache.ValueRetrievalException.class)
                .cause()
                .isInstanceOf(AppException.class)
                .hasMessage("Account not found");
    }

    /** Minimal in-memory stand-in for Redis, with a switch to simulate an outage. */
    private static class InMemoryCacheWriter implements RedisCacheWriter {

        private final Map<String, byte[]> store = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        volatile boolean failing = false;

        private String id(String name, byte[] key) {
            return name + "::" + new String(key, StandardCharsets.UTF_8);
        }

        private void failIfDown() {
            if (failing) {
                throw new RedisConnectionFailureException("simulated outage");
            }
        }

        @Override
        public byte[] get(String name, byte[] key) {
            reads.incrementAndGet();
            failIfDown();
            return store.get(id(name, key));
        }

        @Override
        public CompletableFuture<byte[]> retrieve(String name, byte[] key, @Nullable Duration ttl) {
            return CompletableFuture.completedFuture(get(name, key));
        }

        @Override
        public void put(String name, byte[] key, byte[] value, @Nullable Duration ttl) {
            writes.incrementAndGet();
            failIfDown();
            store.put(id(name, key), value);
        }

        @Override
        public CompletableFuture<Void> store(String name, byte[] key, byte[] value, @Nullable Duration ttl) {
            put(name, key, value, ttl);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public byte[] putIfAbsent(String name, byte[] key, byte[] value, @Nullable Duration ttl) {
            failIfDown();
            return store.putIfAbsent(id(name, key), value);
        }

        @Override
        public void remove(String name, byte[] key) {
            store.remove(id(name, key));
        }

        @Override
        public void clean(String name, byte[] pattern) {
            store.clear();
        }

        @Override
        public void clearStatistics(String name) {
        }

        @Override
        public RedisCacheWriter withStatisticsCollector(CacheStatisticsCollector collector) {
            return this;
        }

        @Override
        public CacheStatistics getCacheStatistics(String cacheName) {
            return CacheStatisticsCollector.none().getCacheStatistics(cacheName);
        }
    }
}
