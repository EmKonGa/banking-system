package com.banking.gateway.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Every authenticated request through the gateway hits this check, so its behaviour during a Redis
 * outage is the gateway's behaviour during a Redis outage.
 *
 * <p>Failing open is deliberate: a blacklist miss lets a logged-out token live until its 5-minute
 * expiry, whereas failing closed would reject every request in the system. The breaker exists to
 * make that fail-open <em>fast</em> — without it each request still pays the full 2s Lettuce
 * timeout before returning the same answer.
 *
 * <p>The {@code onErrorReturn(false)} this class used to carry has moved into the fallback on
 * purpose. Recovering inside the method body hands the aspect a successful {@code Mono}, so the
 * breaker would record every outage as a success and never open.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayTokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "blacklist:";

    private final ReactiveStringRedisTemplate redisTemplate;

    @CircuitBreaker(name = "redis", fallbackMethod = "isBlacklistedFallback")
    public Mono<Boolean> isBlacklisted(String jti) {
        return redisTemplate.hasKey(BLACKLIST_PREFIX + jti);
    }

    /**
     * Also the open-circuit path: Resilience4j signals a short-circuited call as
     * {@code CallNotPermittedException}, which arrives here like any other failure.
     */
    Mono<Boolean> isBlacklistedFallback(String jti, Throwable t) {
        log.warn("Redis unavailable for blacklist check jti={}: {} — failing open", jti, t.toString());
        return Mono.just(false);
    }
}
