package com.banking.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class IpKeyResolverTest {

    private final IpKeyResolver resolver = new IpKeyResolver();

    @Test
    void usesFirstXForwardedForValueWhenPresent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1, 10.0.0.2"));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("203.0.113.7");
    }

    @Test
    void trimsSingleXForwardedForValue() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/login")
                        .header("X-Forwarded-For", "  203.0.113.7  "));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("203.0.113.7");
    }

    /**
     * Compose deploys have no ingress and no XFF — the direct connection IP is the real client.
     */
    @Test
    void fallsBackToRemoteAddressWhenNoXff() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/login")
                        .remoteAddress(new java.net.InetSocketAddress("198.51.100.4", 55123)));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("198.51.100.4");
    }

    /**
     * XFF wins over remote address — behind the ingress, remoteAddress is the ingress pod.
     */
    @Test
    void xffWinsOverRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.7")
                        .remoteAddress(new java.net.InetSocketAddress("10.0.0.99", 55123)));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("203.0.113.7");
    }

    /**
     * Every request must produce a key — an empty {@link reactor.core.publisher.Mono} would trip
     * the filter's {@code deny-empty-key} 403. A shared "unknown" bucket is the fallback so
     * odd requests still contribute to a throttle instead of being refused outright.
     */
    @Test
    void fallsBackToUnknownWhenNoXffAndNoRemote() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/login"));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("unknown");
    }

    @Test
    void blankXffFallsThroughToRemote() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/login")
                        .header("X-Forwarded-For", "   ")
                        .remoteAddress(new java.net.InetSocketAddress("198.51.100.4", 55123)));

        assertThat(resolver.resolve(exchange).block()).isEqualTo("198.51.100.4");
    }
}
