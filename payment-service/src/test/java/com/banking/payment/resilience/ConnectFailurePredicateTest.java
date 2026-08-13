package com.banking.payment.resilience;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the retry predicate will and will not resend.
 *
 * <p>The two failures that decide the split are produced by <strong>actually causing them</strong> —
 * a real Feign client against a closed port and against a server that accepts and never answers —
 * rather than by constructing the exception the code hopes for. That is the entire lesson of the
 * finding this fixes. The old config named {@code java.net.ConnectException}, which Feign never
 * throws, and a test that fabricated a bare {@code ConnectException} would have agreed with the
 * broken config and confirmed nothing. {@code shape_aConnectionRefusalIsNotAConnectException} pins
 * that specific mistake so it cannot come back.
 */
class ConnectFailurePredicateTest {

    private final ConnectFailurePredicate predicate = new ConnectFailurePredicate();

    // ---------------------------------------------------------------------------------------
    // The shape of the bug itself
    // ---------------------------------------------------------------------------------------

    @Test
    void shape_aConnectionRefusalIsNotAConnectException() throws Exception {
        Throwable thrown = FeignFailures.connectionRefused();

        // What the retry config used to ask for, and why it never fired: the class Feign throws is
        // RetryableException. ConnectException is only ever the cause, and Resilience4j matches
        // with isAssignableFrom on the thrown class without unwrapping.
        assertThat(thrown).isInstanceOf(RetryableException.class);
        assertThat(thrown).isNotInstanceOf(ConnectException.class);
        assertThat(thrown.getCause()).isInstanceOf(ConnectException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Retried: the request provably never arrived
    // ---------------------------------------------------------------------------------------

    @Test
    void retriesARealConnectionRefusal() throws Exception {
        // The rolling-restart case. No bytes reached account-service, so no money moved and
        // resending with the same idempotency key is safe.
        assertThat(predicate.test(FeignFailures.connectionRefused())).isTrue();
    }

    @Test
    void retriesAnUnresolvableHost() {
        // Kept synthetic on purpose: a real .invalid lookup depends on the DNS resolver in front of
        // whoever runs the suite, and a test that fails on a captive resolver teaches nothing.
        assertThat(predicate.test(new RuntimeException("wrapped", new UnknownHostException("account-service"))))
                .isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Not retried: the outcome is unknown, which is what PENDING is for
    // ---------------------------------------------------------------------------------------

    @Test
    void doesNotRetryARealReadTimeout() throws Exception {
        Throwable thrown = FeignFailures.readTimeout();

        // Same wrapper class as a refusal — which is exactly why adding feign.RetryableException to
        // the whitelist would have been the wrong fix. The request was sent and may have been
        // processed, so the saga leaves the intent PENDING and lets recovery establish the truth.
        assertThat(thrown).isInstanceOf(RetryableException.class);
        assertThat(thrown.getCause()).isInstanceOf(SocketTimeoutException.class);
        assertThat(predicate.test(thrown)).isFalse();
    }

    @Test
    void doesNotRetryAnHttpErrorResponse() {
        // A 500 means account-service received the request and got as far as answering it.
        FeignException serverError = FeignException.errorStatus("executeTransfer",
                feign.Response.builder()
                        .status(500)
                        .reason("Internal Server Error")
                        .request(Request.create(Request.HttpMethod.POST, "http://account-service:8082/x",
                                Collections.emptyMap(), null, null, null))
                        .headers(Collections.emptyMap())
                        .build());

        assertThat(predicate.test(serverError)).isFalse();
    }

    @Test
    void doesNotRetryAnOpenCircuitBreaker() {
        // Resilience4j applies @Retry outside @CircuitBreaker, so a short-circuited call reaches
        // this predicate. Retrying would burn attempts against a breaker that is open to stop calls.
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("account-service");
        breaker.transitionToOpenState();

        assertThat(predicate.test(CallNotPermittedException.createCallNotPermittedException(breaker)))
                .isFalse();
    }

    // ---------------------------------------------------------------------------------------
    // Mechanics
    // ---------------------------------------------------------------------------------------

    @Test
    void survivesACyclicCauseChain() throws Exception {
        // Two links, not one: initCause rejects a direct self-reference outright ("Self-causation
        // not permitted"), but nothing stops a → b → a. Which means MAX_DEPTH is the real guard and
        // an identity check on getCause() would have been dead code. This runs on every failed
        // call, so a cycle would hang the request thread rather than fail it.
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);

        assertThat(predicate.test(a)).isFalse();
    }
}
