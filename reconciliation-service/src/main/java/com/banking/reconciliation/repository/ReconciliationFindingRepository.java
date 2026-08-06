package com.banking.reconciliation.repository;

import com.banking.reconciliation.entity.FindingStatus;
import com.banking.reconciliation.entity.FindingType;
import com.banking.reconciliation.entity.ReconciliationFinding;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, UUID> {

    Optional<ReconciliationFinding> findByTypeAndSubjectId(FindingType type, UUID subjectId);

    @Query("SELECT f FROM ReconciliationFinding f WHERE f.status <> 'RESOLVED'")
    List<ReconciliationFinding> findUnresolved();

    long countByStatus(FindingStatus status);

    Slice<ReconciliationFinding> findByStatusNotOrderByLastSeenAtDesc(
            FindingStatus status, Pageable pageable);

    Slice<ReconciliationFinding> findAllByOrderByLastSeenAtDesc(Pageable pageable);
}
