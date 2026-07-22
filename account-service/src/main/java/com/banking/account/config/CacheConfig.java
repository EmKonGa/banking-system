package com.banking.account.config;

import com.banking.account.cache.CacheNames;
import com.banking.account.cache.CachedAccount;
import com.banking.account.cache.StripedLockRedisCacheManager;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Redis-backed cache for account reads.
 * <p>
 * Two deliberate choices here:
 * <ul>
 *   <li><b>Typed serializers, no default typing.</b> Each cache declares the exact type it holds,
 *       so Jackson never has to embed {@code @class} hints. Records are final, so polymorphic
 *       typing would not tag them anyway and {@code List<CachedAccount>} would deserialize back as
 *       {@code LinkedHashMap}. Naming the type also keeps deserialization off the
 *       arbitrary-class path.</li>
 *   <li><b>Fail open.</b> A Redis outage must not take account reads down with it, matching the
 *       posture already used by {@code TokenBlacklistService}. The error handler swallows cache
 *       faults and the call falls through to Postgres.</li>
 * </ul>
 * The TTL is a safety net, not the correctness mechanism — eviction is. It bounds how long a
 * missed eviction could serve a stale balance.
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            CircuitBreakerRegistry circuitBreakerRegistry,
            @Value("${banking.cache.account-ttl:60s}") Duration ttl) {

        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        JavaType listType = mapper.getTypeFactory()
                .constructCollectionType(List.class, CachedAccount.class);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .prefixCacheNameWith("account-svc::")
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()));

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                CacheNames.ACCOUNT_BY_ID, base.serializeValuesWith(
                        SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(mapper, CachedAccount.class))),
                CacheNames.ACCOUNTS_BY_USER, base.serializeValuesWith(
                        SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(mapper, listType))));

        // Deliberately the same "redis" breaker instance that TokenBlacklistService uses: it is the
        // same Redis, so one health signal should govern both. A cache outage tripping the breaker
        // for the blacklist check is correct — that call is about to time out too.
        return new StripedLockRedisCacheManager(
                RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory), base, perCache,
                circuitBreakerRegistry.circuitBreaker("redis"));
    }

    /**
     * Fail-open: log and continue so a Redis outage degrades latency, not availability.
     * A swallowed read error means the annotated method runs and hits the database.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache read failed cache={} key={}: {} — falling through to database",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache write failed cache={} key={}: {}", cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                // Louder than the others: a dropped eviction is how stale balances survive,
                // bounded only by the TTL.
                log.error("Cache eviction FAILED cache={} key={}: {} — stale until TTL expiry",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.error("Cache clear failed cache={}: {}", cache.getName(), e.getMessage());
            }
        };
    }
}
