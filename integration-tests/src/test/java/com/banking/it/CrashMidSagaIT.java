package com.banking.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window {@code TransferLedger.openIntent} exists to survive: account-service has committed the
 * money and payment-service has not recorded the outcome.
 *
 * <p>This is the failure that made the saga necessary. Before it, {@code PaymentService.transfer}
 * was {@code @Transactional}, which looked like it covered both steps and covered only one — a crash
 * here rolled the local side back and left money moved with no record anywhere in payment-service.
 * No in-process test can reach it: the whole question is what survives the process.
 */
class CrashMidSagaIT extends SagaTestBase {

    @Test
    @DisplayName("payment-service is SIGKILLed after the money moves; recovery settles the intent it left behind")
    void aKilledPaymentServiceLeavesARecoverableIntent() throws Exception {
        Api.User sender = Api.User.customer();
        Api.User recipient = Api.User.customer();
        Api.Account from = fundedAccount(sender, "500.00");
        Api.Account to = Api.createAccount(recipient);
        UUID key = UUID.randomUUID();

        // The response never comes back, so payment-service cannot settle no matter how long it
        // waits — the same state a crash produces, arranged rather than raced for.
        holdTheResponse();

        CompletableFuture<Void> inFlight = CompletableFuture.runAsync(
                () -> Api.transfer(sender, from, to.number(), "120.00", key));

        // Both halves of the premise. Without the first, a missing settlement would be ambiguous;
        // without the second, the test could be passing because nothing had started yet.
        awaitMovementCommitted(key);
        assertThat(Ledgers.intentStatus(key)).contains("PENDING");

        BankingStack.killAndRestartPaymentService();
        inFlight.completeExceptionally(new IllegalStateException("the caller's connection died with the process"));

        // The lookup the poller is about to make has to be able to reach account-service.
        deliverResponsesAgain();

        awaitIntentStatus(key, "COMPLETED", recoveryWindow());

        // Settled from account-service's record, not from anything payment-service remembered.
        assertThat(Ledgers.balanceOf(from.number())).isEqualByComparingTo(money("380.00"));
        assertThat(Ledgers.balanceOf(to.number())).isEqualByComparingTo(money("120.00"));

        // And exactly once. The poller resolves; it does not replay.
        assertThat(Ledgers.intentCount(key)).isEqualTo(1);
    }

    @Test
    @DisplayName("a deposit crashing mid-saga recovers the same way, through the same movement log")
    void aKilledDepositRecoversThroughTheSharedLog() throws Exception {
        Api.User holder = Api.User.customer();
        Api.Account account = Api.createAccount(holder);
        UUID key = UUID.randomUUID();

        holdTheResponse();
        CompletableFuture<Void> inFlight = CompletableFuture.runAsync(
                () -> Api.deposit(Api.User.admin(), account.number(), "250.00", key));

        awaitMovementCommitted(key);

        BankingStack.killAndRestartPaymentService();
        inFlight.completeExceptionally(new IllegalStateException("the caller's connection died with the process"));
        deliverResponsesAgain();

        awaitIntentStatus(key, "COMPLETED", recoveryWindow());

        // The point of deposits reusing account_transfer_log: recovery covers them with no second
        // lookup path and no second write-off rule. The poller never learns which kind it resolved.
        assertThat(Ledgers.intentType(key)).contains("DEPOSIT");
        assertThat(Ledgers.balanceOf(account.number())).isEqualByComparingTo(money("250.00"));
    }

    @Test
    @DisplayName("a movement that never committed is not completed by recovery")
    void recoveryDoesNotInventAMovementThatNeverHappened() throws Exception {
        Api.User sender = Api.User.customer();
        Api.Account from = fundedAccount(sender, "100.00");
        Api.Account to = Api.createAccount(Api.User.customer());
        UUID key = UUID.randomUUID();

        // Nothing reaches account-service at all, so there is no movement to find. The mirror image
        // of the first test, and the reason the poller asks rather than assuming: identical local
        // state, opposite correct outcome.
        accountServiceUnreachable();
        Api.Response response = Api.transfer(sender, from, to.number(), "40.00", key);

        assertThat(response.status())
                .as("an unreachable account-service is indeterminate, not a rejection")
                .isEqualTo(503);
        assertThat(Ledgers.movementCommitted(key)).isFalse();

        accountServiceReachableAgain();

        // It stays PENDING through the grace period rather than being completed on a guess.
        awaitIntentStatus(key, "FAILED", writeOffWindow());
        assertThat(Ledgers.balanceOf(from.number())).isEqualByComparingTo(money("100.00"));
        assertThat(Ledgers.balanceOf(to.number())).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
