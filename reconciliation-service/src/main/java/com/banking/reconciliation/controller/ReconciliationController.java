package com.banking.reconciliation.controller;

import com.banking.reconciliation.dto.FindingResponse;
import com.banking.reconciliation.entity.FindingStatus;
import com.banking.reconciliation.repository.ReconciliationFindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-only: a finding names accounts and amounts across the whole system, which is not
 * something any account holder should be able to read.
 */
@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationFindingRepository findings;

    @GetMapping("/findings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Slice<FindingResponse>> findings(
            @RequestParam(defaultValue = "true") boolean openOnly,
            @PageableDefault(size = 20) Pageable pageable) {
        Slice<FindingResponse> page = (openOnly
                ? findings.findByStatusNotOrderByLastSeenAtDesc(FindingStatus.RESOLVED, pageable)
                : findings.findAllByOrderByLastSeenAtDesc(pageable))
                .map(FindingResponse::from);
        return ResponseEntity.ok(page);
    }
}
