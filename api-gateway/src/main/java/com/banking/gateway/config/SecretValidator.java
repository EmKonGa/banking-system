package com.banking.gateway.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Fails gateway startup if the JWT secret is missing or still the shipped placeholder default.
 *
 * <p>The gateway validates every inbound token's signature with this key, so a known value lets
 * anyone forge tokens. The gateway intentionally does not depend on banking-common (servlet vs.
 * reactive), so this mirrors {@code com.banking.common.security.SecretValidator}.
 */
@Component
public class SecretValidator implements InitializingBean {

    private static final Set<String> JWT_PLACEHOLDERS = Set.of(
            "dev-secret-change-in-production-must-be-long-enough",
            "your-256-bit-secret-here-change-this-in-production");
    private static final int MIN_JWT_SECRET_BYTES = 32; // HS256 requires a 256-bit key

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Override
    public void afterPropertiesSet() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Generate a strong secret (openssl rand -base64 64) and set it before startup.");
        }
        if (JWT_PLACEHOLDERS.contains(jwtSecret)) {
            throw new IllegalStateException(
                    "JWT_SECRET is still a shipped placeholder default. Set JWT_SECRET to a unique, strong value "
                            + "(openssl rand -base64 64) — a known signing key lets anyone forge admin tokens.");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET is too short: HS256 requires at least " + MIN_JWT_SECRET_BYTES + " bytes (256 bits).");
        }
    }
}
