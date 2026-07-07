package com.banking.payment.repository;

import com.banking.payment.entity.OutboxEvent;
import com.banking.payment.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop10ByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
