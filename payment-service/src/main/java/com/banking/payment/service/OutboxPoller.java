package com.banking.payment.service;

import com.banking.payment.resilience.KafkaEventPublisher;
import com.banking.events.PaymentEvent;
import com.banking.payment.entity.OutboxEvent;
import com.banking.payment.entity.OutboxStatus;
import com.banking.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxEventRepository outboxRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${outbox.max-retries:5}")
    private int maxRetries;

    @Value("${outbox.base-backoff-seconds:15}")
    private long baseBackoffSeconds;

    /**
     * Claims a batch of PENDING rows and publishes each one, awaiting the broker's acknowledgement.
     *
     * <p>Two callers, and neither of them is a request thread any more. The {@code @Scheduled} tick
     * is the durable path; {@link OutboxTrigger} wakes it early on the dedicated publish executor
     * once a settlement commits. It used to be woken <em>inline</em> from an AFTER_COMMIT listener
     * on this class, which meant a batch of up to ten blocking acks ran on the thread serving the
     * user's transfer.
     *
     * <p>Note the cost this still carries on its own thread: with the broker reachable but not
     * acking, one pass is up to ten times {@code delivery.timeout.ms}. That is why
     * {@code spring.task.scheduling.pool.size} is above 1 — {@code TransferRecoveryPoller} shares
     * this scheduler, and a Kafka outage must not be able to stop the saga settling money in flight.
     */
    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:15000}")
    @Transactional
    public void pollAndPublish() {
        List<OutboxEvent> pending = outboxRepository.findPendingWithLock();

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

                if (attempts >= maxRetries) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("[OUTBOX] DEAD LETTER event id={} transaction={} after {} attempts — manual intervention required",
                            event.getId(), event.getAggregateId(), attempts);
                } else {
                    // 2^attempts * base: 30s → 60s → 120s → 240s
                    long backoffSeconds = (long) Math.pow(2, attempts) * baseBackoffSeconds;
                    event.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));
                    log.warn("[OUTBOX] Event id={} failed (attempt {}/{}), next retry in {}s",
                            event.getId(), attempts, maxRetries, backoffSeconds);
                }
            }
            outboxRepository.save(event);
        }
    }
}
