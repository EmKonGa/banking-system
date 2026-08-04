package com.banking.reconciliation.service;

import com.banking.reconciliation.entity.FindingStatus;
import com.banking.reconciliation.entity.FindingType;
import com.banking.reconciliation.entity.ReconciliationFinding;
import com.banking.reconciliation.entity.SweepState;
import com.banking.reconciliation.repository.ReconciliationFindingRepository;
import com.banking.reconciliation.repository.SweepStateRepository;
import com.banking.reconciliation.service.FindingRecorder.Observation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two-sightings rule.
 *
 * <p>There is no consistent cut across two databases: balances and the ledger are read at different
 * instants, so a transfer committing mid-sweep makes an account look wrong when nothing is. Rather
 * than pretend to a distributed snapshot, a discrepancy must survive a second independent sweep
 * before it is believed. This is the same asymmetry the transfer recovery poller uses — the call
 * that is expensive to get wrong is the one made to wait.
 */
@ExtendWith(MockitoExtension.class)
class FindingRecorderTest {

    @Mock ReconciliationFindingRepository findings;
    @Mock SweepStateRepository sweepState;

    FindingRecorder recorder;

    private static final UUID SUBJECT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        recorder = new FindingRecorder(findings, sweepState);
        when(findings.findUnresolved()).thenReturn(List.of());
    }

    private Observation observation() {
        return Observation.of(FindingType.BALANCE_MISMATCH, SUBJECT, "off by 500");
    }

    private ReconciliationFinding existing(FindingStatus status, int timesSeen) {
        return ReconciliationFinding.builder()
                .id(UUID.randomUUID())
                .type(FindingType.BALANCE_MISMATCH)
                .subjectId(SUBJECT)
                .status(status)
                .timesSeen(timesSeen)
                .detail("off by 500")
                .lastSeenAt(Instant.now().minusSeconds(300))
                .build();
    }

    private ReconciliationFinding captureSaved() {
        ArgumentCaptor<ReconciliationFinding> captor =
                ArgumentCaptor.forClass(ReconciliationFinding.class);
        verify(findings).save(captor.capture());
        return captor.getValue();
    }

    /** First sighting is provisional — most are money that was in flight, not breakage. */
    @Test
    void aFirstSightingIsSuspectedAndNotCounted() {
        when(findings.findByTypeAndSubjectId(FindingType.BALANCE_MISMATCH, SUBJECT))
                .thenReturn(Optional.empty());

        FindingRecorder.SweepOutcome outcome = recorder.record(List.of(observation()));

        assertThat(captureSaved().getStatus()).isEqualTo(FindingStatus.SUSPECTED);
        assertThat(outcome.confirmed()).as("nothing to alert on yet").isZero();
    }

    /** Surviving a second, independent sweep is what makes it worth waking someone for. */
    @Test
    void aSecondSightingConfirmsAndCounts() {
        when(findings.findByTypeAndSubjectId(FindingType.BALANCE_MISMATCH, SUBJECT))
                .thenReturn(Optional.of(existing(FindingStatus.SUSPECTED, 1)));

        FindingRecorder.SweepOutcome outcome = recorder.record(List.of(observation()));

        ReconciliationFinding saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(FindingStatus.CONFIRMED);
        assertThat(saved.getTimesSeen()).isEqualTo(2);
        assertThat(outcome.confirmed()).isEqualTo(1);
    }

    /**
     * One discrepancy seen twenty times is one finding, not twenty. Otherwise an alert on open
     * findings would grow with how often the sweep runs rather than with how much is wrong.
     */
    @Test
    void repeatedSightingsAccumulateOnOneFinding() {
        when(findings.findByTypeAndSubjectId(FindingType.BALANCE_MISMATCH, SUBJECT))
                .thenReturn(Optional.of(existing(FindingStatus.CONFIRMED, 19)));

        recorder.record(List.of(observation()));

        assertThat(captureSaved().getTimesSeen()).isEqualTo(20);
    }

    /** No longer detected means no longer true — but the row stays, because it happened. */
    @Test
    void aFindingTheSweepNoLongerSeesIsResolvedNotDeleted() {
        when(findings.findUnresolved()).thenReturn(List.of(existing(FindingStatus.CONFIRMED, 3)));

        FindingRecorder.SweepOutcome outcome = recorder.record(List.of());

        ReconciliationFinding saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(FindingStatus.RESOLVED);
        assertThat(saved.getResolvedAt()).isNotNull();
        assertThat(outcome.resolved()).isEqualTo(1);
        verify(findings, org.mockito.Mockito.never()).delete(any());
    }

    /**
     * A discrepancy that comes back is a new one that happens to share a subject. Inheriting the old
     * count would confirm it instantly and skip the sighting rule that exists to filter out money in
     * flight — precisely when a flickering finding makes that filtering most valuable.
     */
    @Test
    void aRecurrenceAfterResolutionStartsOverAsSuspected() {
        when(findings.findByTypeAndSubjectId(FindingType.BALANCE_MISMATCH, SUBJECT))
                .thenReturn(Optional.of(existing(FindingStatus.RESOLVED, 7)));

        FindingRecorder.SweepOutcome outcome = recorder.record(List.of(observation()));

        ReconciliationFinding saved = captureSaved();
        assertThat(saved.getStatus()).isEqualTo(FindingStatus.SUSPECTED);
        assertThat(saved.getTimesSeen()).isEqualTo(1);
        assertThat(saved.getResolvedAt()).as("no longer resolved").isNull();
        assertThat(outcome.confirmed()).isZero();
    }

    // ---- the liveness marker ----------------------------------------------------------------

    private SweepState captureSweepState() {
        ArgumentCaptor<SweepState> captor = ArgumentCaptor.forClass(SweepState.class);
        verify(sweepState).save(captor.capture());
        return captor.getValue();
    }

    /**
     * The case that matters most, and the easy one to get backwards: a sweep that found nothing is
     * the *normal* outcome, and it still has to advance the marker. If only sweeps with findings
     * counted as proof of life, a healthy system would look identical to a dead auditor — which is
     * the exact confusion this marker exists to remove.
     */
    @Test
    void aCleanSweepStillMarksItselfSuccessful() {
        recorder.record(List.of());

        assertThat(captureSweepState().getLastSuccessfulSweepAt())
                .isCloseTo(Instant.now(), within(10, ChronoUnit.SECONDS));
    }

    /** First ever sweep: there is no row yet, and one has to be created rather than skipped. */
    @Test
    void theFirstSweepCreatesTheMarker() {
        when(sweepState.findById(SweepState.SINGLETON_ID)).thenReturn(Optional.empty());

        recorder.record(List.of(observation()));

        assertThat(captureSweepState().getId()).isEqualTo(SweepState.SINGLETON_ID);
    }

    /** Later sweeps move the existing row forward — one row, not one per pass. */
    @Test
    void aLaterSweepAdvancesTheExistingMarker() {
        Instant stale = Instant.now().minusSeconds(3600);
        SweepState existing = new SweepState(SweepState.SINGLETON_ID, stale);
        when(sweepState.findById(SweepState.SINGLETON_ID)).thenReturn(Optional.of(existing));

        recorder.record(List.of(observation()));

        assertThat(captureSweepState()).isSameAs(existing);
        assertThat(existing.getLastSuccessfulSweepAt()).isAfter(stale);
    }

    /** A finding this sweep did see must not also be resolved by the same pass. */
    @Test
    void aFindingStillPresentIsNotResolved() {
        ReconciliationFinding present = existing(FindingStatus.CONFIRMED, 2);
        when(findings.findByTypeAndSubjectId(FindingType.BALANCE_MISMATCH, SUBJECT))
                .thenReturn(Optional.of(present));
        when(findings.findUnresolved()).thenReturn(List.of(present));

        recorder.record(List.of(observation()));

        assertThat(present.getStatus()).isEqualTo(FindingStatus.CONFIRMED);
        assertThat(present.getResolvedAt()).isNull();
    }
}
