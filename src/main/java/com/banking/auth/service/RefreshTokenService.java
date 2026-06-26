package com.banking.auth.service;

import com.banking.auth.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    public String create(String userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(
                PREFIX + token,
                userId,
                Duration.ofMillis(props.getRefreshExpirationMs())
        );
        return token;
    }

    public Optional<String> getUserId(String token) {
        return Optional.ofNullable(redis.opsForValue().get(PREFIX + token));
    }

    public void delete(String token) {
        redis.delete(PREFIX + token);
    }

    // Rotate: invalidate old token, issue a new one
    public String rotate(String oldToken, String userId) {
        delete(oldToken);
        return create(userId);
    }
}