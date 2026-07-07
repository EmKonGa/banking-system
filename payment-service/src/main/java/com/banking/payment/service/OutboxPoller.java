package com.banking.payment.service;

import com.banking.payment.resilience.KafkaEventPublisher;
import com.banking.events.PaymentEvent;
import com.banking.payment.entity.OutboxEvent;
import com.banking.payment.entity.OutboxStatus;
import com.banking.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final int MAX_RETRIES = 5;

    private final OutboxEventRepository outboxRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 15000)
    public void pollAndPublish() {
        List<OutboxEvent> pending = outboxRepository
                .findTop10ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        if (pending.isEmpty()) return;

        log.debug("[OUTBOX] Processing {} pending event(s)", pending.size());

        for (OutboxEvent event : pending) {
            try {
                PaymentEvent paymentEvent = objectMapper.readValue(event.getPayload(), PaymentEvent.class);
                kafkaEventPublisher.publish(event.getTopic(), event.getAggregateId(), paymentEvent);
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setProcessedAt(Instant.now());
                log.info("[OUTBOX] Published event id={} transaction={}", event.getId(), event.getAggregateId());
            } catch (Exception e) {
                int attempts = event.getRetryCount() + 1;
                event.setRetryCount(attempts);
                String err = e.getMessage() != null ? e.getMessage() : "unknown error";
                event.setLastError(err.length() > 1000 ? err.substring(0, 1000) : err);

                if (attempts >= MAX_RETRIES) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("[OUTBOX] Event id={} PERMANENTLY FAILED after {} attempts", event.getId(), attempts);
                } else {
                    log.warn("[OUTBOX] Event id={} failed (attempt {}), will retry", event.getId(), attempts);
                }
            }
            outboxRepository.save(event);
        }
    }
}
