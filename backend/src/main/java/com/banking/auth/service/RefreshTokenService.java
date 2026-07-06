package com.banking.auth.service;

import com.banking.auth.config.AppProperties;
import com.banking.common.exception.AppException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String PREFIX = "refresh:";

    private final StringRedisTemplate redis;
    private final AppProperties props;

    @CircuitBreaker(name = "redis")
    @Retry(name = "redis", fallbackMethod = "createFallback")
    public String create(String userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(
                PREFIX + token,
                userId,
                Duration.ofMillis(props.getRefreshExpirationMs())
        );
        return token;
    }

    String createFallback(String userId, Throwable t) {
        throw new AppException("Authentication service temporarily unavailable. Please try again.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @CircuitBreaker(name = "redis")
    @Retry(name = "redis", fallbackMethod = "getUserIdFallback")
    public Optional<String> getUserId(String token) {
        return Optional.ofNullable(redis.opsForValue().get(PREFIX + token));
    }

    Optional<String> getUserIdFallback(String token, Throwable t) {
        throw new AppException("Authentication service temporarily unavailable. Please try again.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "deleteFallback")
    public void delete(String token) {
        redis.delete(PREFIX + token);
    }

    void deleteFallback(String token, Throwable t) {
        // Fail-open: old token expires naturally at its TTL
    }

    // Rotate: invalidate old token, issue a new one
    public String rotate(String oldToken, String userId) {
        delete(oldToken);
        return create(userId);
    }
}
