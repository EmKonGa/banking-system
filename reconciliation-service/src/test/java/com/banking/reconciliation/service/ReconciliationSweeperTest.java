package com.banking.reconciliation.service;

import com.banking.events.AccountBalanceSnapshot;
import com.banking.events.LedgerKeySnapshot;
import com.banking.events.LedgerNetSnapshot;
import com.banking.events.MovementKeySnapshot;
import com.banking.reconciliation.client.AccountSnapshotClient;
import com.banking.reconciliation.client.PaymentLedgerClient;
import com.banking.reconciliation.entity.FindingType;
import com.banking.reconciliation.service.FindingRecorder.Observation;
import com.banking.reconciliation.service.FindingRecorder.SweepOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The invariants themselves. What is being pinned is mostly what the sweeper refuses to conclude:
 * that a PENDING transfer is missing money, that an empty account is wrong, or that anything at all
 * should be corrected rather than reported.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciliationSweeperTest {

    @Mock AccountSnapshotClient accounts;
    @Mock PaymentLedgerClient ledger;
    @Mock FindingRecorder recorder;

    ReconciliationSweeper sweeper;

    private static final UUID ACCOUNT_A = UUID.randomUUID();
    private static final UUID ACCOUNT_B = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sweeper = new ReconciliationSweeper(accounts, ledger, recorder);
        ReflectionTestUtils.setField(sweeper, "pageSize", 200);
        ReflectionTestUtils.setField(sweeper, "stuckIntentSeconds", 1800L);
        ReflectionTestUtils.setField(sweeper, "maxKeys", 200000);

        // Default: everything empty and agreeing. Each test overrides only what it is about.
        when(accounts.balances(anyInt(), anyInt())).thenReturn(emptyPage());
        when(accounts.movementKeys(anyInt(), anyInt())).thenReturn(emptyPage());
        when(ledger.netByAccount(anyInt(), anyInt())).thenReturn(emptyPage());
        when(ledger.keys(anyInt(), anyInt())).thenReturn(emptyPage());
        when(ledger.stalePending(anyLong(), anyInt(), anyInt())).thenReturn(emptyPage());
        when(recorder.record(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new SweepOutcome(0, 0, 0));
    }

    private static <T> Page<T> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 200), 0);
    }

    private static <T> Page<T> pageOf(T... items) {
        return new PageImpl<>(List.of(items), PageRequest.of(0, 200), items.length);
    }

    private List<Observation> capturedObservations() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Observation>> captor = ArgumentCaptor.forClass(List.class);
        verify(recorder).record(captor.capture());
        return captor.getValue();
    }

    private static AccountBalanceSnapshot account(UUID id, String balance) {
        return new AccountBalanceSnapshot(id, "000000000001", new BigDecimal(balance), "ACTIVE");
    }

    // ---- C1: balance against the ledger ---------------------------------------------------

    @Test
    void anAccountMatchingItsLedgerProducesNoFinding() {
        when(accounts.balances(anyInt(), anyInt())).thenReturn(pageOf(account(ACCOUNT_A, "125.0000")));
        when(ledger.netByAccount(anyInt(), anyInt()))
                .thenReturn(pageOf(new LedgerNetSnapshot(ACCOUNT_A, new BigDecimal("125.0000"))));

        sweeper.sweep();

        assertThat(capturedObservations()).isEmpty();
    }

    /**
     * The shape the unledgered-deposit bug would have taken: an account holding money the ledger
     * cannot account for.
     */
    @Test
    void moneyTheLedgerCannotAccountForIsReported() {
        when(accounts.balances(anyInt(), anyInt())).thenReturn(pageOf(account(ACCOUNT_A, "625.0000")));
        when(ledger.netByAccount(anyInt(), anyInt()))
                .thenReturn(pageOf(new LedgerNetSnapshot(ACCOUNT_A, new BigDecimal("125.0000"))));

        sweeper.sweep();

        assertThat(capturedObservations()).singleElement().satisfies(o -> {
            assertThat(o.type()).isEqualTo(FindingType.BALANCE_MISMATCH);
            assertThat(o.subjectId()).isEqualTo(ACCOUNT_A);
            assertThat(o.observed()).isEqualByComparingTo("625.0000");
            assertThat(o.expected()).isEqualByComparingTo("125.0000");
        });
    }

    /** An account with no movements is absent from the ledger aggregate and must hold nothing. */
    @Test
    void anAccountWithNoLedgerRowsIsExpectedToBeEmpty() {
        when(accounts.balances(anyInt(), anyInt())).thenReturn(pageOf(account(ACCOUNT_A, "0.0000")));

        sweeper.sweep();

        assertThat(capturedObservations()).isEmpty();
    }

    @Test
    void anAccountWithNoLedgerRowsButANonZeroBalanceIsReported() {
        when(accounts.balances(anyInt(), anyInt())).thenReturn(pageOf(account(ACCOUNT_A, "50.0000")));

        sweeper.sweep();

        assertThat(capturedObservations()).singleElement()
                .satisfies(o -> assertThat(o.type()).isEqualTo(FindingType.BALANCE_MISMATCH));
    }

    /**
     * Scale differences must not read as discrepancies. BigDecimal("0.00").equals(ZERO) is false,
     * and neither service's JSON round-trip promises to preserve scale.
     */
    @Test
    void differingScaleIsNotADiscrepancy() {
        when(accounts.balances(anyInt(), anyInt())).thenReturn(pageOf(account(ACCOUNT_A, "100.0000")));
        when(ledger.netByAccount(anyInt(), anyInt()))
                .thenReturn(pageOf(new LedgerNetSnapshot(ACCOUNT_A, new BigDecimal("100.00"))));

        sweeper.sweep();

        assertThat(capturedObservations()).isEmpty();
    }

    // ---- C2: the two services agree about what happened -------------------------------------

    @Test
    void aMovementWithNoLedgerRowIsReported() {
        UUID key = UUID.randomUUID();
        when(accounts.movementKeys(anyInt(), anyInt()))
                .thenReturn(pageOf(new MovementKeySnapshot(key, "TRANSFER", Instant.now())));

        sweeper.sweep();

        assertThat(capturedObservations()).singleElement().satisfies(o -> {
            assertThat(o.type()).isEqualTo(FindingType.MOVEMENT_NOT_LEDGERED);
            assertThat(o.detail()).contains("nothing at all");
        });
    }

    /**
     * The bad-write-off case, and the reason status travels with the key instead of being filtered
     * server-side: money moved, and the ledger says it did not.
     */
    @Test
    void aMovementWrittenOffAsFailedIsReported() {
        UUID key = UUID.randomUUID();
        when(accounts.movementKeys(anyInt(), anyInt()))
                .thenReturn(pageOf(new MovementKeySnapshot(key, "TRANSFER", Instant.now())));
        when(ledger.keys(anyInt(), anyInt()))
                .thenReturn(pageOf(new LedgerKeySnapshot(UUID.randomUUID(), key, "FAILED", Instant.now())));

        sweeper.sweep();

        assertThat(capturedObservations()).singleElement().satisfies(o -> {
            assertThat(o.type()).isEqualTo(FindingType.MOVEMENT_NOT_LEDGERED);
            assertThat(o.detail()).contains("FAILED");
        });
    }

    @Test
    void aCompletedLedgerRowWithNoMovementBehindItIsReported() {
        UUID key = UUID.randomUUID();
        when(ledger.keys(anyInt(), anyInt()))
                .thenReturn(pageOf(new LedgerKeySnapshot(UUID.randomUUID(), key, "COMPLETED", Instant.now())));

        sweeper.sweep();

        assertThat(capturedObservations()).singleElement()
                .satisfies(o -> assertThat(o.type()).isEqualTo(FindingType.LEDGER_WITHOUT_MOVEMENT));
    }

    /**
     * The critical false-positive guard. A PENDING intent means the outcome is unknown by design —
     * the money may be mid-flight. Reporting it as a missing movement would make every transfer in
     * progress look like breakage, and the alert would be permanently on.
     */
    @Test
    void aPendingLedgerRowWithNoMovementIsNotReported() {
        UUID key = UUID.randomUUID();
        when(ledger.keys(anyInt(), anyInt()))
                .thenReturn(pageOf(new LedgerKeySnapshot(UUID.randomUUID(), key, "PENDING", Instant.now())));

        sweeper.sweep();

        assertThat(capturedObservations()).isEmpty();
    }

    /** A cleanly failed transfer moved no money, so account-service correctly has no movement. */
    @Test
    void aFailedLedgerRowWithNoMovementIsNotReported() {
        when(ledger.keys(anyInt(), anyInt())).thenReturn(pageOf(
                new LedgerKeySnapshot(UUID.randomUUID(), UUID.randomUUID(), "FAILED", Instant.now())));

        sweeper.sweep();

        assertThat(capturedObservations()).isEmpty();
    }

    // ---- C3: the recovery poller is alive ---------------------------------------------------

    @Test
    void anIntentStuckPastTheWriteOffWindowIsReported() {
        UUID txId = UUID.randomUUID();
        when(ledger.stalePending(anyLong(), anyInt(), anyInt())).thenReturn(pageOf(
                new LedgerKeySnapshot(txId, UUID.randomUUID(), "PENDING", Instant.now().minusSeconds(3600))));

        sweeper.sweep();

        assertThat(capturedObservations()).singleElement().satisfies(o -> {
            assertThat(o.type()).isEqualTo(FindingType.STUCK_INTENT);
            assertThat(o.subjectId()).isEqualTo(txId);
        });
    }

    @Test
    void theStuckIntentThresholdIsPassedThroughToPaymentService() {
        sweeper.sweep();

        verify(ledger).stalePending(org.mockito.ArgumentMatchers.eq(1800L), anyInt(), anyInt());
    }

    // ---- failure handling -------------------------------------------------------------------

    /**
     * A sweep that cannot finish must record nothing at all. Recording a partial pass would resolve
     * findings purely because this run never got far enough to see them — turning an outage in a
     * dependency into a clean bill of health.
     *
     * <p>Not reaching the recorder is also what leaves the liveness marker untouched, since it is
     * written inside {@code record}. That is the point: an outage that persists has to show up as a
     * stale last-successful-sweep timestamp, because the findings gauges cannot show it — they read
     * the register, so they hold their last clean value for as long as sweeps keep failing.
     */
    @Test
    void aSweepThatCannotCompleteRecordsNothing() {
        when(ledger.netByAccount(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("connection refused"));

        sweeper.sweep();

        verifyNoInteractions(recorder);
    }
}
