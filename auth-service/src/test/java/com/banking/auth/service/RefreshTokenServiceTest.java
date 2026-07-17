package com.banking.auth.service;

import com.banking.common.security.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the refresh-token Redis encoding. The stored value packs
 * "&lt;userId&gt;|&lt;sessionStart&gt;" so the absolute session cap survives rotation;
 * these lock down that encoding, its parsing, and the legacy fallback.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    AppProperties props;
    RefreshTokenService service;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.setRefreshExpirationMs(Duration.ofMinutes(15).toMillis());
        service = new RefreshTokenService(redis, props);
    }

    @Test
    void create_stampsSessionStartAsNow() {
        when(redis.opsForValue()).thenReturn(valueOps);
        long before = System.currentTimeMillis();

        service.create("user-1");

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), value.capture(), any(Duration.class));
        String[] parts = value.getValue().split("\\|");
        assertThat(parts[0]).isEqualTo("user-1");
        assertThat(Long.parseLong(parts[1])).isBetween(before, System.currentTimeMillis());
    }

    @Test
    void rotate_deletesOldTokenAndPreservesSessionStart() {
        when(redis.opsForValue()).thenReturn(valueOps);
        var session = new RefreshTokenService.Session("user-1", 12345L);

        service.rotate("old-token", session);

        verify(redis).delete("refresh:old-token");
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(key.capture(), value.capture(), any(Duration.class));
        assertThat(key.getValue()).startsWith("refresh:");
        assertThat(value.getValue()).isEqualTo("user-1|12345"); // start time carried, not reset
    }

    @Test
    void getSession_parsesUserIdAndSessionStart() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("refresh:tok")).thenReturn("user-1|1000");

        var session = service.getSession("tok").orElseThrow();

        assertThat(session.userId()).isEqualTo("user-1");
        assertThat(session.sessionStart()).isEqualTo(1000L);
    }

    @Test
    void getSession_legacyValueWithoutStart_treatedAsStartingNow() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("refresh:tok")).thenReturn("legacy-user");
        long before = System.currentTimeMillis();

        var session = service.getSession("tok").orElseThrow();

        assertThat(session.userId()).isEqualTo("legacy-user");
        assertThat(session.sessionStart()).isBetween(before, System.currentTimeMillis());
    }

    @Test
    void getSession_missingToken_isEmpty() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("refresh:tok")).thenReturn(null);

        assertThat(service.getSession("tok")).isEmpty();
    }
}
