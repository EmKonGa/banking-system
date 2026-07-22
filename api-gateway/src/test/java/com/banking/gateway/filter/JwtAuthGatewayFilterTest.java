package com.banking.gateway.filter;

import com.banking.gateway.service.GatewayJwtService;
import com.banking.gateway.service.GatewayTokenBlacklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The gateway is the only place JWTs are checked before traffic reaches a service, so each way a
 * request can fail authentication needs to be pinned: no header, wrong scheme, invalid signature,
 * unparseable claims, and a token that was explicitly logged out.
 *
 * <p>Every rejection asserts that the chain was <em>not</em> invoked — a 401 status on a request
 * that was still forwarded downstream would be a full auth bypass that a status-only assertion
 * would not catch.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtAuthGatewayFilterTest {

    @Mock GatewayJwtService jwtService;
    @Mock GatewayTokenBlacklistService blacklistService;

    JwtAuthGatewayFilter filter;

    /** Records whether the request was passed downstream. */
    private final AtomicBoolean chainInvoked = new AtomicBoolean(false);
    private final GatewayFilterChain chain = exchange -> {
        chainInvoked.set(true);
        return Mono.empty();
    };

    private static final String VALID_TOKEN = "header.payload.signature";

    @BeforeEach
    void setUp() {
        filter = new JwtAuthGatewayFilter(jwtService, blacklistService);
        chainInvoked.set(false);
    }

    private MockServerWebExchange exchangeWith(String authHeader) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/accounts");
        if (authHeader != null) {
            builder = MockServerHttpRequest.get("/api/accounts").header("Authorization", authHeader);
        }
        return MockServerWebExchange.from(builder);
    }

    private void assertRejected(ServerWebExchange exchange) {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainInvoked).as("request must not reach the downstream service").isFalse();
    }

    @Test
    void requestWithoutAuthorizationHeaderIsRejected() {
        MockServerWebExchange exchange = exchangeWith(null);

        filter.filter(exchange, chain).block();

        assertRejected(exchange);
        verifyNoInteractions(jwtService, blacklistService);
    }

    /** Anything that is not a Bearer token is refused before the JWT parser ever sees it. */
    @Test
    void nonBearerAuthorizationSchemeIsRejected() {
        MockServerWebExchange exchange = exchangeWith("Basic dXNlcjpwYXNz");

        filter.filter(exchange, chain).block();

        assertRejected(exchange);
        verifyNoInteractions(jwtService, blacklistService);
    }

    @Test
    void invalidTokenIsRejectedWithoutCheckingTheBlacklist() {
        when(jwtService.isTokenValid(VALID_TOKEN)).thenReturn(false);
        MockServerWebExchange exchange = exchangeWith("Bearer " + VALID_TOKEN);

        filter.filter(exchange, chain).block();

        assertRejected(exchange);
        verifyNoInteractions(blacklistService);
    }

    /**
     * A token can pass signature validation and still have unusable claims. Extracting the jti
     * must not surface as a 500 — that would leak parser detail and skip the blacklist check.
     */
    @Test
    void tokenWithUnreadableClaimsIsRejectedNotErrored() {
        when(jwtService.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwtService.extractJti(VALID_TOKEN)).thenThrow(new IllegalArgumentException("no jti claim"));
        MockServerWebExchange exchange = exchangeWith("Bearer " + VALID_TOKEN);

        filter.filter(exchange, chain).block();

        assertRejected(exchange);
        verifyNoInteractions(blacklistService);
    }

    /**
     * Logout blacklists the jti for the access token's remaining TTL. Since the token itself stays
     * cryptographically valid until it expires, this check is the only thing that stops a stolen
     * or logged-out token from being replayed.
     */
    @Test
    void blacklistedTokenIsRejectedEvenThoughItIsSignedAndUnexpired() {
        when(jwtService.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwtService.extractJti(VALID_TOKEN)).thenReturn("jti-123");
        when(blacklistService.isBlacklisted("jti-123")).thenReturn(Mono.just(true));
        MockServerWebExchange exchange = exchangeWith("Bearer " + VALID_TOKEN);

        filter.filter(exchange, chain).block();

        assertRejected(exchange);
    }

    @Test
    void validNonBlacklistedTokenReachesTheDownstreamService() {
        when(jwtService.isTokenValid(VALID_TOKEN)).thenReturn(true);
        when(jwtService.extractJti(VALID_TOKEN)).thenReturn("jti-123");
        when(blacklistService.isBlacklisted("jti-123")).thenReturn(Mono.just(false));
        MockServerWebExchange exchange = exchangeWith("Bearer " + VALID_TOKEN);

        filter.filter(exchange, chain).block();

        assertThat(chainInvoked).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Public routes (login, register, refresh) carry skip-jwt metadata. Without this branch the
     * gateway would demand a token to obtain a token.
     */
    @Test
    void routeMarkedSkipJwtBypassesAuthenticationEntirely() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login"));
        Route route = Route.async()
                .id("auth-public")
                .uri(URI.create("http://auth-service:8081"))
                .predicate(e -> true)
                .metadata(Map.of("skip-jwt", true))
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

        filter.filter(exchange, chain).block();

        assertThat(chainInvoked).isTrue();
        verifyNoInteractions(jwtService, blacklistService);
    }

    /** The filter must run before routing, and after the internal-path block at HIGHEST_PRECEDENCE. */
    @Test
    void filterRunsImmediatelyAfterTheInternalPathBlock() {
        assertThat(filter.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1);
    }
}
