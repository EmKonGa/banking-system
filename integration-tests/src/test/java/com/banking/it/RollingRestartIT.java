package com.banking.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R8, end to end: a transfer that meets a refused connection and succeeds on a retry.
 *
 * <p>This is the event the retry was configured for and never handled — a rolling restart of
 * account-service, where a request lands on a terminating pod. The old config named
 * {@code java.net.ConnectException}, which Feign never throws (it wraps every {@code IOException} in
 * a {@code RetryableException}), so {@code @Retry} matched nothing and a routine deploy turned
 * transfers into stranded PENDING intents.
 *
 * <p>{@code ConnectFailurePredicateTest} proves the predicate answers correctly against real sockets
 * and {@code AccountClientResilienceWiringTest} proves the configuration reaches it. Neither can show
 * the retry <em>completing the transfer</em>, because that needs a peer that is down and then up.
 */
class RollingRestartIT extends SagaTestBase {

    /**
     * Backoff is 500ms with a multiplier of 2, so attempts land at roughly 0ms, 500ms and 1500ms.
     * Restoring the peer at 700ms means the first attempt certainly failed and the third certainly
     * has a healthy peer — a window wide enough not to depend on scheduling precision.
     */
    private static final Duration RESTORED_AFTER = Duration.ofMillis(700);

    @Test
    @DisplayName("a connection refused mid-flight is retried, and the transfer completes normally")
    void aRefusedConnectionIsRetriedAgainstTheRestartedPeer() throws Exception {
        Api.User sender = Api.User.customer();
        Api.Account from = fundedAccount(sender, "300.00");
        Api.Account to = Api.createAccount(Api.User.customer());
        UUID key = UUID.randomUUID();

        accountServiceUnreachable();

        CompletableFuture<Api.Response> transfer = CompletableFuture.supplyAsync(
                () -> Api.transfer(sender, from, to.number(), "45.00", key));

        Thread.sleep(RESTORED_AFTER.toMillis());
        accountServiceReachableAgain();

        Api.Response response = transfer.get(60, java.util.concurrent.TimeUnit.SECONDS);

        // Before the fix this was 503 with a PENDING intent for the recovery poller to clean up
        // 60 seconds later. The user-visible difference is the entire point of the finding.
        assertThat(response.status())
                .as("the retry should have carried this to a healthy peer: %s", response.body())
                .isEqualTo(CREATED);

        assertThat(Ledgers.intentStatus(key)).contains("COMPLETED");

        // The safety property that makes this retry layer legitimate where a client-visible one is
        // not: the key is generated before the first attempt and reused, so an attempt that does
        // arrive after an earlier one is deduped rather than executed a second time. A fresh key per
        // attempt would move the money twice, and the balances are the only place that would show it.
        assertThat(Ledgers.intentCount(key)).isEqualTo(1);
        assertThat(Ledgers.balanceOf(from.number())).isEqualByComparingTo(money("255.00"));
        assertThat(Ledgers.balanceOf(to.number())).isEqualByComparingTo(money("45.00"));
    }
}
