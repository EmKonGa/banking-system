package com.banking.payment.repository;

import com.banking.payment.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    // FOR UPDATE SKIP LOCKED ensures each payment-service instance claims its own
    // batch of rows — concurrent instances never process the same outbox event.
    // next_retry_at filter skips events still inside their exponential backoff window.
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            AND (next_retry_at IS NULL OR next_retry_at <= NOW())
            ORDER BY created_at ASC
            LIMIT 10
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingWithLock();
}
