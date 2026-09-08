package com.banking.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 4xx-versus-indeterminate split, against failures the runtime actually produced.
 *
 * <p>{@code PaymentServiceTest} covers the same decision with a hand-constructed
 * {@code FeignException}, which proves the branch and assumes the input. What it cannot check is
 * that a real refusal arrives as a 4xx at all — that depends on account-service's status choices,
 * on {@code GlobalExceptionHandler} preserving them, and on Feign reconstructing the status from the
 * wire. Three components, one of them in another process.
 *
 * <p>The asymmetry is the whole design: a refusal is written off immediately, and anything else is
 * left alone. Getting it backwards in either direction destroys money — writing off a transfer whose
 * money moved, or leaving a rejected one pending forever.
 */
class FailureClassificationIT extends SagaTestBase {

    @Test
    @DisplayName("insufficient balance is a considered refusal: settled FAILED at once, nothing moved")
    void aRefusalIsSettledImmediately() {
        Api.User sender = Api.User.customer();
        Api.Account from = fundedAccount(sender, "100.00");
        Api.Account to = Api.createAccount(Api.User.customer());
        UUID key = UUID.randomUUID();

        Api.Response response = Api.transfer(sender, from, to.number(), "999.00", key);

        // account-service answers 422; the status and the message both survive the hop, which is
        // what stops "insufficient balance" reaching the user as a 500.
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body()).contains("Insufficient balance");

        // FAILED already — not after the grace period, so no recovery poller was involved. This is
        // the assertion that separates "classified as a rejection" from "eventually written off",
        // which is a distinction the end state alone cannot make.
        assertThat(Ledgers.intentStatus(key)).contains("FAILED");
        assertThat(Ledgers.movementCommitted(key)).isFalse();
        assertThat(Ledgers.balanceOf(from.number())).isEqualByComparingTo(money("100.00"));
    }

    @Test
    @DisplayName("an unknown destination is a refusal too, and 404 does not read as 'not found yet'")
    void anUnknownDestinationIsARefusal() {
        Api.User sender = Api.User.customer();
        Api.Account from = fundedAccount(sender, "100.00");
        UUID key = UUID.randomUUID();

        Api.Response response = Api.transfer(sender, from, "9999999999999999", "10.00", key);

        assertThat(response.status()).isEqualTo(404);
        assertThat(Ledgers.intentStatus(key)).contains("FAILED");
        assertThat(Ledgers.balanceOf(from.number())).isEqualByComparingTo(money("100.00"));
    }

    @Test
    @DisplayName("a transport failure is not a refusal: the intent is left PENDING, not written off")
    void aTransportFailureLeavesTheIntentAlone() throws Exception {
        Api.User sender = Api.User.customer();
        Api.Account from = fundedAccount(sender, "100.00");
        Api.Account to = Api.createAccount(Api.User.customer());
        UUID key = UUID.randomUUID();

        accountServiceUnreachable();
        Api.Response response = Api.transfer(sender, from, to.number(), "10.00", key);

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body()).contains("being processed");

        // Asserted immediately, and it has to be: the intent is only a second or two old here, well
        // inside the grace period, so the recovery poller has not looked at it yet. That is the
        // point — this is the state the saga *chooses*, not one it settles into later.
        assertThat(Ledgers.intentStatus(key))
                .as("an unknown outcome must not be guessed as FAILED")
                .contains("PENDING");
    }

    @Test
    @DisplayName("the same idempotency key twice moves the money once and writes one ledger row")
    void aResubmittedKeyIsDedupedOnBothSidesOfTheBoundary() {
        Api.User sender = Api.User.customer();
        Api.Account from = fundedAccount(sender, "500.00");
        Api.Account to = Api.createAccount(Api.User.customer());
        UUID key = UUID.randomUUID();

        Api.Response first = Api.transfer(sender, from, to.number(), "70.00", key);
        Api.Response second = Api.transfer(sender, from, to.number(), "70.00", key);

        assertThat(first.status()).isEqualTo(CREATED);
        assertThat(second.status())
                .as("a duplicate submit returns the original outcome rather than a conflict")
                .isEqualTo(CREATED);

        // Both halves of the bug the unique constraint fixed: account-service deduped the money even
        // before it, but a resubmitted key still wrote a second ledger row and a second outbox
        // event — double-counting the transfer in history and notifying the user twice.
        assertThat(Ledgers.intentCount(key)).isEqualTo(1);
        assertThat(Ledgers.balanceOf(from.number())).isEqualByComparingTo(money("430.00"));
        assertThat(Ledgers.balanceOf(to.number())).isEqualByComparingTo(money("70.00"));
    }
}
