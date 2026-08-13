package com.banking.payment.resilience;

import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The account-service retry and its timeouts, as the running application actually binds them.
 *
 * <p>{@link ConnectFailurePredicateTest} proves the predicate decides correctly. This proves it is
 * <em>reached</em> — which is the half that was broken before, and the half a unit test cannot see.
 * The previous config was syntactically valid, started cleanly, showed up in
 * {@code /actuator/retries}, and retried nothing. A class name typo in {@code retry-exception-predicate}
 * or a wrong property prefix would reproduce exactly that, silently.
 *
 * <p>So this boots the real context against the real {@code application.yml} and asks the registry
 * what it built, rather than restating the configuration in {@code withPropertyValues} — which would
 * only test that the test agrees with itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class AccountClientResilienceWiringTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO banking_payment");
        registry.add("app.jwt.secret", () -> "test-jwt-secret-that-is-long-enough-for-hs256-256bits");
        registry.add("internal.secret", () -> "test-internal-secret");
        registry.add("management.tracing.enabled", () -> "false");

        // Park the schedulers: @EnableScheduling starts the outbox and recovery pollers, and Spring
        // caches this context past the last test while @Container stops PostgreSQL when the class
        // ends. A poll firing in that gap blocks for Hikari's full 30s connection-timeout.
        registry.add("outbox.poll-interval-ms", () -> "3600000");
        registry.add("transfer.recovery.poll-interval-ms", () -> "3600000");
    }

    @Autowired
    RetryRegistry retries;

    @Autowired
    FeignClientProperties feignProperties;

    private Predicate<Throwable> registeredPredicate() {
        return retries.retry("account-service").getRetryConfig().getExceptionPredicate();
    }

    @Test
    void theRegisteredRetryResendsARealConnectionRefusal() throws Exception {
        // The rolling-restart case, end to end: application.yml → ConnectFailurePredicate → a
        // failure Feign genuinely threw. Before the fix this was false, and nothing said so.
        assertThat(registeredPredicate().test(FeignFailures.connectionRefused())).isTrue();
    }

    @Test
    void theRegisteredRetryLeavesARealReadTimeoutAlone() throws Exception {
        // Sent, possibly processed. Resending is the one thing the saga must not do on its own.
        assertThat(registeredPredicate().test(FeignFailures.readTimeout())).isFalse();
    }

    @Test
    void theClientCarriesExplicitTimeouts() {
        // Feign's defaults are 10s connect / 60s read and nothing overrode them. Asserted through
        // the bound properties because the prefix moved to spring.cloud.openfeign in Spring Cloud
        // 4.x — a yaml block under the old `feign.client.config` key binds to nothing and throws no
        // error, leaving the defaults in place.
        FeignClientProperties.FeignClientConfiguration config =
                feignProperties.getConfig().get("account-service");

        assertThat(config).as("no feign config bound for account-service").isNotNull();
        assertThat(config.getConnectTimeout()).isEqualTo(2000);
        assertThat(config.getReadTimeout()).isEqualTo(15000);
    }
}
