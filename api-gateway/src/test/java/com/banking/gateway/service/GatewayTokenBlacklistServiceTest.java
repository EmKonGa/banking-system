package com.banking.gateway.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Boots the service against the real {@code application.yml}, because the things most likely to be
 * wrong here are configuration, not logic: {@code record-exceptions} is a whitelist that silently
 * treats unlisted failures as successes, and the annotation aspect cannot wrap a {@code Mono} at
 * all unless {@code resilience4j-reactor} is on the classpath. A hand-built breaker in the test
 * would verify neither.
 */
@SpringBootTest(
        classes = GatewayTokenBlacklistService.class,
        properties = "spring.main.web-application-type=none")
@ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
class GatewayTokenBlacklistServiceTest {

    @Autowired GatewayTokenBlacklistService service;
    @Autowired CircuitBreakerRegistry registry;
    @MockBean ReactiveStringRedisTemplate redisTemplate;

    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = registry.circuitBreaker("redis");
        breaker.reset();
        reset(redisTemplate);
    }

    @Test
    void blacklistedTokenIsReported() {
        when(redisTemplate.hasKey("blacklist:jti-123")).thenReturn(Mono.just(true));

        assertThat(service.isBlacklisted("jti-123").block()).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * A blacklist lookup that cannot reach Redis must not 500 the request. Rejecting everything
     * during a Redis outage would be a system-wide outage; letting a logged-out token live until
     * its 5-minute expiry is the smaller failure.
     */
    @Test
    void redisFailureFailsOpenInsteadOfPropagating() {
        when(redisTemplate.hasKey(anyString()))
                .thenReturn(Mono.error(new QueryTimeoutException("Redis command timed out")));

        assertThat(service.isBlacklisted("jti-123").block()).isFalse();
    }

    /**
     * The reason this class has a breaker at all. Failing open was already correct; what was
     * missing is that each request paid the full 2s Lettuce timeout to arrive at the same answer.
     *
     * <p>Counts <em>subscriptions</em>, not calls to {@code hasKey}. The aspect wraps the Mono the
     * method returns, so an open breaker still invokes the method body to obtain that Mono — it
     * just never subscribes, which is where the Redis command and its timeout actually live.
     */
    @Test
    void breakerOpensOnRepeatedTimeoutsAndThenStopsIssuingRedisCommands() {
        AtomicInteger commandsIssued = new AtomicInteger();
        when(redisTemplate.hasKey(anyString())).thenReturn(
                Mono.<Boolean>error(new QueryTimeoutException("Redis command timed out"))
                        .doOnSubscribe(s -> commandsIssued.incrementAndGet()));

        // minimum-number-of-calls is 5 at a 50% failure-rate threshold
        for (int i = 0; i < 5; i++) {
            assertThat(service.isBlacklisted("jti-" + i).block()).isFalse();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(commandsIssued).hasValue(5);

        assertThat(service.isBlacklisted("jti-after-open").block()).isFalse();
        assertThat(commandsIssued).as("open breaker must not reach Redis").hasValue(5);
    }

    /**
     * QueryTimeoutException is the one that matters — Spring Data translates Lettuce's command
     * timeout into it, and it was the entry missing from the downstream services' whitelist, so
     * their breakers never opened on the most common Redis failure. Pinning it here keeps the
     * gateway from repeating that.
     */
    @Test
    void connectionRefusalIsAlsoRecorded() {
        when(redisTemplate.hasKey(anyString()))
                .thenReturn(Mono.error(new org.springframework.data.redis.RedisConnectionFailureException("no route")));

        for (int i = 0; i < 5; i++) {
            service.isBlacklisted("jti-" + i).block();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
