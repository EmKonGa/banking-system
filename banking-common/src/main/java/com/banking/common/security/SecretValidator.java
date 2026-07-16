package com.banking.common.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Fails application startup if security-critical secrets are missing or still set to the
 * shipped placeholder defaults.
 *
 * <p>The JWT secret is the HS256 signing key shared by every service; a known value lets
 * anyone forge tokens (including {@code role=ADMIN}). The internal secret guards the
 * service-to-service {@code /internal/**} money-movement API. Neither may run on a default.
 */
@Component
public class SecretValidator implements InitializingBean {

    // Known placeholder JWT secrets shipped in application.yml / .env.example — must never run for real.
    static final Set<String> JWT_PLACEHOLDERS = Set.of(
            "dev-secret-change-in-production-must-be-long-enough",
            "your-256-bit-secret-here-change-this-in-production");
    static final String INTERNAL_PLACEHOLDER = "internal-secret-change-me";
    private static final int MIN_JWT_SECRET_BYTES = 32; // HS256 requires a 256-bit key

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    // Only defined by services that make internal calls (account, payment); blank elsewhere.
    @Value("${internal.secret:}")
    private String internalSecret;

    @Override
    public void afterPropertiesSet() {
        validateJwtSecret();
        validateInternalSecret();
    }

    private void validateJwtSecret() {
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

    private void validateInternalSecret() {
        // Blank means this service does not use internal.secret — nothing to validate.
        if (internalSecret == null || internalSecret.isBlank()) {
            return;
        }
        if (INTERNAL_PLACEHOLDER.equals(internalSecret)) {
            throw new IllegalStateException(
                    "INTERNAL_SECRET is still the shipped placeholder default. Set INTERNAL_SECRET to a unique, strong "
                            + "value shared only between internal services — it guards the /internal money-movement API.");
        }
    }
}
