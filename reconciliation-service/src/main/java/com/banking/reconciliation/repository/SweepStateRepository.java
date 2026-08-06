package com.banking.reconciliation.repository;

import com.banking.reconciliation.entity.SweepState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SweepStateRepository extends JpaRepository<SweepState, Short> {
}
