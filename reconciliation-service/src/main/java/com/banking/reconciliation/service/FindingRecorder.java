package com.banking.reconciliation.service;

import com.banking.reconciliation.entity.FindingStatus;
import com.banking.reconciliation.entity.FindingType;
import com.banking.reconciliation.entity.ReconciliationFinding;
import com.banking.reconciliation.entity.SweepState;
import com.banking.reconciliation.repository.ReconciliationFindingRepository;
import com.banking.reconciliation.repository.SweepStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns what a sweep observed into the register of findings, applying the two-sightings rule.
 *
 * <p>A first sighting is recorded {@code SUSPECTED} and deliberately not alerted on. Balances and
 * the ledger are read from two databases at two different instants, so a transfer committing
 * mid-sweep makes an account look wrong when nothing is. There is no consistent cut to be had
 * without a distributed snapshot, so instead a discrepancy has to survive a second, independent
 * sweep before it is believed. Artefacts of in-flight money do not; real breakage does.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FindingRecorder {

    private final ReconciliationFindingRepository findings;
    private final SweepStateRepository sweepState;

    /**
     * Records everything one sweep saw, and resolves anything it did not.
     *
     * <p>One transaction for the whole sweep: a partially applied sweep would leave findings that
     * this pass saw sitting next to stale ones it did not, and the difference between those two is
     * exactly what the next pass reads to decide what to confirm.
     */
    @Transactional
    public SweepOutcome record(List<Observation> observed) {
        Set<String> seen = new HashSet<>();
        int confirmed = 0;

        for (Observation observation : observed) {
            seen.add(key(observation.type(), observation.subjectId()));
            if (upsert(observation) == FindingStatus.CONFIRMED) {
                confirmed++;
            }
        }

        int resolved = resolveUnseen(seen);
        markSweepCompleted();
        return new SweepOutcome(observed.size(), confirmed, resolved);
    }

    /**
     * The liveness marker, written in the same transaction as the findings above.
     *
     * <p>Committing it separately would let the pair disagree in the direction that matters: a
     * failure between the two could mark the sweep successful while its findings rolled back,
     * leaving a stale-clean register vouched for by a fresh timestamp. Same reasoning as
     * notification-service committing its processed-event marker with the notifications it stands
     * for. The sweeper only reaches this method at all when the whole pass completed — an aborted
     * sweep never calls {@code record}.
     */
    private void markSweepCompleted() {
        Instant now = Instant.now();
        SweepState state = sweepState.findById(SweepState.SINGLETON_ID)
                .orElseGet(() -> new SweepState(SweepState.SINGLETON_ID, now));
        state.setLastSuccessfulSweepAt(now);
        sweepState.save(state);
    }

    private FindingStatus upsert(Observation observation) {
        Optional<ReconciliationFinding> existing =
                findings.findByTypeAndSubjectId(observation.type(), observation.subjectId());

        if (existing.isEmpty()) {
            findings.save(ReconciliationFinding.builder()
                    .type(observation.type())
                    .subjectId(observation.subjectId())
                    .status(FindingStatus.SUSPECTED)
                    .observed(observation.observed())
                    .expected(observation.expected())
                    .detail(truncate(observation.detail()))
                    .timesSeen(1)
                    .lastSeenAt(Instant.now())
                    .build());
            log.info("[RECON] {} on {} — suspected (first sighting, not alerting yet)",
                    observation.type(), observation.subjectId());
            return FindingStatus.SUSPECTED;
        }

        ReconciliationFinding finding = existing.get();
        boolean wasResolved = finding.getStatus() == FindingStatus.RESOLVED;

        // A recurrence after a resolution starts over rather than inheriting the old count: it is a
        // fresh discrepancy that happens to be about the same subject, and it has been seen once.
        finding.setTimesSeen(wasResolved ? 1 : finding.getTimesSeen() + 1);
        finding.setStatus(wasResolved ? FindingStatus.SUSPECTED : FindingStatus.CONFIRMED);
        finding.setResolvedAt(null);
        finding.setObserved(observation.observed());
        finding.setExpected(observation.expected());
        finding.setDetail(truncate(observation.detail()));
        finding.setLastSeenAt(Instant.now());
        findings.save(finding);

        if (finding.getStatus() == FindingStatus.CONFIRMED) {
            log.error("[RECON] {} on {} CONFIRMED after {} sightings: {}",
                    finding.getType(), finding.getSubjectId(), finding.getTimesSeen(),
                    finding.getDetail());
        }
        return finding.getStatus();
    }

    /**
     * Anything the register holds that this sweep did not see is no longer true. Resolved rows are
     * kept rather than deleted — that a balance was ever wrong is worth knowing after it stops
     * being wrong, and a finding that flickers is itself a finding.
     */
    private int resolveUnseen(Set<String> seen) {
        int resolved = 0;
        for (ReconciliationFinding finding : findings.findUnresolved()) {
            if (seen.contains(key(finding.getType(), finding.getSubjectId()))) continue;
            finding.setStatus(FindingStatus.RESOLVED);
            finding.setResolvedAt(Instant.now());
            findings.save(finding);
            resolved++;
        }
        return resolved;
    }

    private static String key(FindingType type, java.util.UUID subjectId) {
        return type.name() + ":" + subjectId;
    }

    private static String truncate(String detail) {
        return detail.length() > 500 ? detail.substring(0, 500) : detail;
    }

    /** What one invariant observed about one subject. */
    public record Observation(
            FindingType type,
            java.util.UUID subjectId,
            BigDecimal observed,
            BigDecimal expected,
            String detail
    ) {
        public static Observation of(FindingType type, java.util.UUID subjectId, String detail) {
            return new Observation(type, subjectId, null, null, detail);
        }
    }

    public record SweepOutcome(int observed, int confirmed, int resolved) {}
}
