package com.banking.reconciliation.dto;

import com.banking.reconciliation.entity.FindingStatus;
import com.banking.reconciliation.entity.FindingType;
import com.banking.reconciliation.entity.ReconciliationFinding;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FindingResponse(
        UUID id,
        FindingType type,
        UUID subjectId,
        FindingStatus status,
        BigDecimal observed,
        BigDecimal expected,
        BigDecimal difference,
        String detail,
        int timesSeen,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant resolvedAt
) {
    public static FindingResponse from(ReconciliationFinding finding) {
        BigDecimal difference = finding.getObserved() == null || finding.getExpected() == null
                ? null
                : finding.getObserved().subtract(finding.getExpected());
        return new FindingResponse(
                finding.getId(), finding.getType(), finding.getSubjectId(), finding.getStatus(),
                finding.getObserved(), finding.getExpected(), difference, finding.getDetail(),
                finding.getTimesSeen(), finding.getFirstSeenAt(), finding.getLastSeenAt(),
                finding.getResolvedAt());
    }
}
