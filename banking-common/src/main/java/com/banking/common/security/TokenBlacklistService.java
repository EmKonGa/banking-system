package com.banking.common.security;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final StringRedisTemplate redis;

    @CircuitBreaker(name = "redis", fallbackMethod = "isBlacklistedFallback")
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey("blacklist:" + jti));
    }

    boolean isBlacklistedFallback(String jti, Throwable t) {
        log.warn("Redis unavailable for blacklist check jti={}: {} — failing open", jti, t.getMessage());
        return false;
    }

    @CircuitBreaker(name = "redis")
    @Retry(name = "redis", fallbackMethod = "blacklistTokenFallback")
    public void blacklistToken(String jti, Duration ttl) {
        redis.opsForValue().set("blacklist:" + jti, "true", ttl);
    }

    void blacklistTokenFallback(String jti, Duration ttl, Throwable t) {
        log.warn("Redis unavailable for blacklist write jti={}: {} — token expires naturally at TTL", jti, t.getMessage());
    }
}
