package com.banking.it;

import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared setup for the saga integration tests: the stack, the fault injection vocabulary, and the
 * one piece of arithmetic every test needs.
 */
abstract class SagaTestBase {

    /** Names are per-toxin, so tests that add more than one do not collide. */
    private static final String RESPONSE_HELD = "response-held";

    /** Both money-moving endpoints answer 201, not 200 — they create a ledger row. */
    static final int CREATED = 201;

    @BeforeAll
    static void startStack() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "these tests run two services as containers; a Docker daemon is required");
        BankingStack.start();
    }

    /**
     * Waits until a call through payment-service to account-service actually succeeds.
     *
     * <p>Necessary because the circuit breaker is <em>per-process and shared by every test</em>: a
     * test that arranges connection failures opens it, and the next test's first transfer is then
     * short-circuited into a 503 that has nothing to do with what it was testing. Clearing the
     * toxins is not enough — the breaker has its own state, and no endpoint resets it.
     *
     * <p>The probe is a real deposit rather than a status read, and it has to be: with the breaker
     * half-open, only a <em>successful call</em> closes it. Reading
     * {@code /actuator/circuitbreakers} would watch it sit half-open forever.
     *
     * <p>Worth knowing while reading this: {@code record-exceptions} on that breaker lists
     * {@code feign.FeignException}, which a 4xx also is. So the refusals in
     * {@link FailureClassificationIT} count toward opening it just as connection failures do.
     */
    @BeforeEach
    void callPathIsHealthy() {
        await("a healthy call path through payment-service to account-service")
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .until(this::probeSucceeds);

        // Two more, and they are not belt-and-braces. Closing the breaker is not the same as
        // clearing its sliding window: a window that already holds the previous test's failures can
        // cross the 50% threshold partway through this test's *first* transfer — which, for a test
        // whose subject is a retry, means the breaker short-circuits the attempt that was supposed
        // to succeed. Leaving behind a window dominated by successes is what stops one test's
        // arranged failures from being charged to the next.
        probeSucceeds();
        probeSucceeds();
    }

    private boolean probeSucceeds() {
        if (probeAccount == null) {
            probeAccount = Api.createAccount(Api.User.customer());
        }
        return Api.deposit(Api.User.admin(), probeAccount.number(), "0.01", UUID.randomUUID())
                .status() == CREATED;
    }

    /** Shared by the whole suite; it only ever accumulates one-cent probe deposits. */
    private static Api.Account probeAccount;

    /**
     * Faults are per-test, and a leaked one would fail the next test somewhere unrelated to its
     * cause. Cleared here rather than at the start of each test so a test that ends by throwing
     * still leaves the proxy healthy.
     */
    @AfterEach
    void clearFaults() throws IOException {
        for (Toxic toxic : BankingStack.accountProxy().toxics().getAll()) {
            toxic.remove();
        }
        BankingStack.accountProxy().enable();
    }

    // --- arranging the failures ---------------------------------------------------------------

    /**
     * Lets the request reach account-service and never delivers the response.
     *
     * <p>The toxin is DOWNSTREAM only, which is the whole point: account-service receives the call,
     * does the work and commits, and payment-service learns nothing. A {@code timeout} of 0 holds
     * the connection open rather than closing it, so what payment-service eventually sees is its own
     * Feign read timeout — the indeterminate case, not a refusal.
     */
    void holdTheResponse() throws IOException {
        BankingStack.accountProxy().toxics().timeout(RESPONSE_HELD, ToxicDirection.DOWNSTREAM, 0);
    }

    void deliverResponsesAgain() throws IOException {
        BankingStack.accountProxy().toxics().get(RESPONSE_HELD).remove();
    }

    /**
     * Refuses connections, the way a terminating pod does during a rolling restart. Nothing reaches
     * account-service, which is what makes a retry safe.
     */
    void accountServiceUnreachable() throws IOException {
        BankingStack.accountProxy().disable();
    }

    void accountServiceReachableAgain() throws IOException {
        BankingStack.accountProxy().enable();
    }

    // --- fixtures --------------------------------------------------------------------------

    /** An account with money in it, funded through the deposit saga — itself a two-service write. */
    Api.Account fundedAccount(Api.User owner, String amount) {
        Api.Account account = Api.createAccount(owner);
        UUID key = UUID.randomUUID();

        Api.Response response = Api.deposit(Api.User.admin(), account.number(), amount, key);
        assertThat(response.status())
                .as("funding deposit failed, so the test never got to its own subject: %s", response)
                .isEqualTo(CREATED);

        return account;
    }

    // --- waiting ----------------------------------------------------------------------------

    /**
     * Waits for the movement to commit in account-service. Every test that arranges a lost response
     * needs this first: without it, "payment-service did not settle" would be indistinguishable from
     * "the request never arrived", and the two have opposite correct outcomes.
     */
    void awaitMovementCommitted(UUID idempotencyKey) {
        await("account-service committing the movement")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> Ledgers.movementCommitted(idempotencyKey));
    }

    void awaitIntentStatus(UUID idempotencyKey, String expected, Duration timeout) {
        await("intent " + idempotencyKey + " reaching " + expected)
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> Ledgers.intentStatus(idempotencyKey).orElse("<no intent>").equals(expected));
    }

    /** The recovery poller's own timing, plus a margin for a loaded CI runner. */
    Duration recoveryWindow() {
        return Duration.ofSeconds(BankingStack.GRACE_PERIOD_SECONDS + 30L);
    }

    Duration writeOffWindow() {
        return Duration.ofSeconds(BankingStack.WRITE_OFF_SECONDS + 30L);
    }

    static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(4);
    }
}
