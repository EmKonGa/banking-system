package com.banking.auth.service;

import com.banking.common.exception.AppException;
import com.banking.common.security.AppProperties;
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
    private static final String SEP = "|";

    private final StringRedisTemplate redis;
    private final AppProperties props;

    /**
     * A refresh token's stored session: who owns it and when the session began.
     */
    public record Session(String userId, long sessionStart) {
    }

    /**
     * Start a brand-new session (login/register): sessionStart = now.
     */
    @CircuitBreaker(name = "redis")
    @Retry(name = "redis", fallbackMethod = "createFallback")
    public String create(String userId) {
        return store(userId, System.currentTimeMillis());
    }

    String createFallback(String userId, Throwable t) {
        throw new AppException("Authentication service temporarily unavailable. Please try again.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @CircuitBreaker(name = "redis")
    @Retry(name = "redis", fallbackMethod = "recreateFallback")
    public String recreate(String userId, long sessionStart) {
        return store(userId, sessionStart);
    }

    String recreateFallback(String userId, long sessionStart, Throwable t) {
        throw new AppException("Authentication service temporarily unavailable. Please try again.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private String store(String userId, long sessionStart) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(
                PREFIX + token,
                userId + SEP + sessionStart,
                Duration.ofMillis(props.getRefreshExpirationMs())
        );
        return token;
    }

    @CircuitBreaker(name = "redis")
    @Retry(name = "redis", fallbackMethod = "getSessionFallback")
    public Optional<Session> getSession(String token) {
        String value = redis.opsForValue().get(PREFIX + token);
        if (value == null) {
            return Optional.empty();
        }
        int sep = value.indexOf(SEP);
        if (sep < 0) {
            // Legacy value (userId only, pre-session-cap) — treat as starting now.
            return Optional.of(new Session(value, System.currentTimeMillis()));
        }
        String userId = value.substring(0, sep);
        long sessionStart;
        try {
            sessionStart = Long.parseLong(value.substring(sep + 1));
        } catch (NumberFormatException e) {
            sessionStart = System.currentTimeMillis();
        }
        return Optional.of(new Session(userId, sessionStart));
    }

    Optional<Session> getSessionFallback(String token, Throwable t) {
        throw new AppException("Authentication service temporarily unavailable. Please try again.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @CircuitBreaker(name = "redis", fallbackMethod = "deleteFallback")
    public void delete(String token) {
        redis.delete(PREFIX + token);
    }

    void deleteFallback(String token, Throwable t) {
        // Fail-open: old token expires naturally at its TTL
    }

    /**
     * Rotate: delete the old token and issue a fresh one keeping the session's start time.
     */
    public String rotate(String oldToken, Session session) {
        delete(oldToken);
        return recreate(session.userId(), session.sessionStart());
    }
}
