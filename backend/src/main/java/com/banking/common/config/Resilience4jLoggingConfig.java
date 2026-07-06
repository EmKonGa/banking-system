package com.banking.common.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Slf4j
@Configuration
public class Resilience4jLoggingConfig {

    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public Resilience4jLoggingConfig(RetryRegistry retryRegistry,
                                     CircuitBreakerRegistry circuitBreakerRegistry) {
        this.retryRegistry = retryRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerEventListeners() {
        // Register on already-existing instances (CBs are created eagerly by actuator health)
        retryRegistry.getAllRetries().forEach(this::attachRetryListeners);
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(this::attachCircuitBreakerListeners);

        // Register on future instances — Retry instances are created lazily on first use
        retryRegistry.getEventPublisher()
                .onEntryAdded(e -> attachRetryListeners(e.getAddedEntry()));

        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(e -> attachCircuitBreakerListeners(e.getAddedEntry()));
    }

    private void attachRetryListeners(Retry retry) {
        String name = retry.getName();

        retry.getEventPublisher().onRetry(e -> {
            Throwable t = e.getLastThrowable();
            log.warn("[RETRY] name={} attempt={} lastError={}",
                    name, e.getNumberOfRetryAttempts(), t != null ? t.getMessage() : "unknown");
        });

        retry.getEventPublisher().onSuccess(e ->
            log.info("[RETRY] name={} succeeded after {} attempt(s)",
                    name, e.getNumberOfRetryAttempts()));

        retry.getEventPublisher().onError(e -> {
            Throwable t = e.getLastThrowable();
            log.error("[RETRY] name={} FAILED after {} attempt(s) — giving up. lastError={}",
                    name, e.getNumberOfRetryAttempts(), t != null ? t.getMessage() : "unknown");
        });
    }

    private void attachCircuitBreakerListeners(CircuitBreaker cb) {
        String name = cb.getName();

        cb.getEventPublisher().onStateTransition(e ->
            log.warn("[CIRCUIT-BREAKER] name={} {} -> {}",
                    name, e.getStateTransition().getFromState(), e.getStateTransition().getToState()));

        cb.getEventPublisher().onCallNotPermitted(e ->
            log.warn("[CIRCUIT-BREAKER] name={} call REJECTED — circuit is OPEN", name));

        cb.getEventPublisher().onFailureRateExceeded(e ->
            log.warn("[CIRCUIT-BREAKER] name={} failure rate exceeded threshold: {}%",
                    name, String.format("%.1f", e.getFailureRate())));
    }
}
