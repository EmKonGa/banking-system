package com.banking.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * One rate-limit bucket per client IP, used by the pre-auth routes (register, login, refresh).
 *
 * <p>Trusts the first {@code X-Forwarded-For} value because in this stack the ingress
 * (ingress-nginx in k8s, nothing in compose) is the only thing meant to set it. A caller reaching
 * the gateway directly can forge XFF and pretend to be any IP; the production fix is a
 * trusted-proxy allowlist and is deliberately out of scope here. Without this trust the bucket key
 * behind an ingress is the ingress pod's own IP — every real client shares one bucket and the
 * limiter throttles the wrong thing.
 *
 * <p>Falls back to a fixed "unknown" bucket rather than an empty {@link Mono} so a caller with
 * neither XFF nor a resolvable remote address still contributes to a shared throttle, instead of
 * being rejected by the filter's {@code deny-empty-key} guard.
 */
@Component("ipKeyResolver")
public class IpKeyResolver implements KeyResolver {

    private static final String UNKNOWN = "unknown";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty()) {
                return Mono.just(first);
            }
        }
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return Mono.just(remote.getAddress().getHostAddress());
        }
        return Mono.just(UNKNOWN);
    }
}
