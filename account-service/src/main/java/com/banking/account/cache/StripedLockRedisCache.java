package com.banking.account.cache;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link RedisCache} that collapses concurrent misses on the <em>same key</em> into a single
 * database load, without serialising misses on unrelated keys, and that degrades quickly when
 * Redis is unhealthy.
 *
 * <p><b>Why not just {@code sync = true} on its own.</b> Spring Data Redis' own synchronized load
 * guards with {@code private final Lock lock} — one lock per <em>cache</em>, not per key — and it
 * holds that lock across the database call. Two misses on two different accounts queue behind each
 * other, so a cold cache turns a parallel warm-up into a serial one. Striping by key keeps the
 * collapsing behaviour where it is useful and drops the coupling between unrelated keys.
 *
 * <p><b>Why the error handling is repeated here.</b> {@code CacheAspectSupport.executeSynchronized}
 * calls {@code cache.get(key, loader)} directly and only catches {@link ValueRetrievalException} —
 * it never consults the configured {@code CacheErrorHandler}. So on the {@code sync} path a Redis
 * outage would propagate as a 500 rather than falling through to Postgres, quietly undoing the
 * fail-open posture set in {@code CacheConfig}. This class restores it.
 *
 * <p><b>Why the circuit breaker.</b> Falling through to the database is only useful if it is
 * <em>fast</em>. With a 2s command timeout, a naive implementation pays that timeout on the first
 * read, again on the re-check under the lock, and a third time on the write — so every request
 * costs ~6s while Redis is down, which exhausts the request thread pool and turns a cache outage
 * into a service outage. Two things prevent that: a failed probe skips the re-check and the write
 * outright, and the shared {@code redis} breaker fast-fails subsequent requests once it opens.
 *
 * <p>The lock is in-process. Across N service instances a cold key can still cause up to N loads.
 * A distributed lock would close that, at the cost of lock expiry, holder-death and partition
 * handling — not a trade worth making to save a handful of millisecond-scale primary-key reads.
 */
@Slf4j
public class StripedLockRedisCache extends RedisCache {

    /** Power of two, so the index is a mask rather than a modulo. */
    private static final int STRIPES = 256;

    private final Lock[] locks;
    private final CircuitBreaker breaker;

    protected StripedLockRedisCache(String name, RedisCacheWriter cacheWriter,
                                    RedisCacheConfiguration cacheConfiguration,
                                    CircuitBreaker breaker) {
        super(name, cacheWriter, cacheConfiguration);
        this.breaker = breaker;
        this.locks = new Lock[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            this.locks[i] = new ReentrantLock();
        }
    }

    /**
     * Outcome of a cache read. {@code reachable} distinguishes a genuine miss (Redis answered, the
     * key is absent) from a failure (Redis did not answer) — they call for different behaviour and
     * both look like "no value".
     */
    private record Probe(@Nullable ValueWrapper value, boolean reachable) {
        static final Probe UNREACHABLE = new Probe(null, false);

        static Probe of(@Nullable ValueWrapper value) {
            return new Probe(value, true);
        }
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Probe first = probe(key);
        if (first.value() != null) {
            return (T) first.value().get();
        }
        if (!first.reachable()) {
            // Redis is down or the breaker is open. Taking the lock would only serialise callers
            // behind a re-check that is going to fail the same way, and the write after it would
            // pay another timeout for nothing. Go straight to the database.
            return load(key, valueLoader);
        }

        Lock lock = lockFor(key);
        lock.lock();
        try {
            // Re-check under the lock: whoever held it before us has already populated the entry,
            // and this is what turns N concurrent misses into one load.
            Probe second = probe(key);
            if (second.value() != null) {
                return (T) second.value().get();
            }
            T value = load(key, valueLoader);
            if (second.reachable()) {
                store(key, value);
            }
            return value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Fixed stripe count rather than a per-key lock map: a map would need entries removed once
     * unused, and removing a lock another thread is about to acquire reintroduces the race the
     * lock exists to prevent. A stripe collision costs two unrelated keys a brief queue; unbounded
     * map growth or a removal race costs more.
     */
    private Lock lockFor(Object key) {
        int hash = key.hashCode();
        hash ^= (hash >>> 16); // spread, so keys differing in high bits land on distinct stripes
        return locks[hash & (256 - 1)];
    }

    /** A cache read that never fails the request — an unhealthy Redis becomes a miss, not a 500. */
    private Probe probe(Object key) {
        try {
            return Probe.of(breaker.executeSupplier(() -> super.get(key)));
        } catch (CallNotPermittedException e) {
            // Breaker is open. Expected and already reported when it tripped, so keep it quiet —
            // this fires on every request for the duration of the open window.
            log.debug("Cache read skipped, breaker open cache={} key={}", getName(), key);
            return Probe.UNREACHABLE;
        } catch (RuntimeException e) {
            log.warn("Cache read failed cache={} key={}: {}: {} — falling through to database",
                    getName(), key, e.getClass().getSimpleName(), e.getMessage());
            return Probe.UNREACHABLE;
        }
    }

    private void store(Object key, @Nullable Object value) {
        if (value == null) {
            return;
        }
        try {
            breaker.executeRunnable(() -> super.put(key, value));
        } catch (CallNotPermittedException e) {
            log.debug("Cache write skipped, breaker open cache={} key={}", getName(), key);
        } catch (RuntimeException e) {
            log.warn("Cache write failed cache={} key={}: {}: {}",
                    getName(), key, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private <T> T load(Object key, Callable<T> valueLoader) {
        try {
            return valueLoader.call();
        } catch (Exception e) {
            // Must stay wrapped: CacheAspectSupport unwraps this and rethrows the cause, which is
            // how AppException("Account not found") still surfaces as a 404 instead of a 500.
            // Deliberately outside the breaker — it measures Redis health, not the database's.
            throw new ValueRetrievalException(key, valueLoader, e);
        }
    }
}
