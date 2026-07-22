package com.banking.account.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Builds {@link StripedLockRedisCache} instances instead of plain {@link RedisCache}, so every
 * cache in this service gets per-key stampede protection and fail-open reads.
 */
public class StripedLockRedisCacheManager extends RedisCacheManager {

    private final CircuitBreaker breaker;

    public StripedLockRedisCacheManager(RedisCacheWriter cacheWriter,
                                        RedisCacheConfiguration defaultCacheConfiguration,
                                        Map<String, RedisCacheConfiguration> initialCacheConfigurations,
                                        CircuitBreaker breaker) {
        super(cacheWriter, defaultCacheConfiguration, initialCacheConfigurations);
        this.breaker = breaker;
    }

    @Override
    protected RedisCache createRedisCache(String name, @Nullable RedisCacheConfiguration cacheConfiguration) {
        return new StripedLockRedisCache(name, getCacheWriter(),
                cacheConfiguration != null ? cacheConfiguration : getDefaultCacheConfiguration(),
                breaker);
    }
}
