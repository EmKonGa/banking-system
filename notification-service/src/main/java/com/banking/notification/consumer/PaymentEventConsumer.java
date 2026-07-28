package com.banking.notification.consumer;

import com.banking.events.BalanceUpdate;
import com.banking.events.PaymentEvent;
import com.banking.events.TransactionUpdate;
import com.banking.notification.entity.NotificationType;
import com.banking.notification.entity.ProcessedEvent;
import com.banking.notification.repository.ProcessedEventRepository;
import com.banking.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.RoundingMode;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final NotificationService notificationService;
    private final ProcessedEventRepository processedEvents;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Handles a payment event exactly once per transaction, and lets failures escape.
     *
     * <p>This method used to wrap its whole body in {@code catch (Exception e) { log.error(...) }}
     * and return normally. That committed the offset even on failure, which made the configured
     * {@code DefaultErrorHandler} unreachable: retries never ran, and nothing ever reached a
     * dead-letter topic because the container was never told anything went wrong. Failures now
     * propagate, which is what puts the retry and DLT path in play.
     *
     * <p>The trade for letting failures retry is that redelivery must be harmless. It already had
     * to be — the outbox is at-least-once, so a rebalance or a poller republish delivers the same
     * event again — but nothing enforced it, and every redelivery notified the user a second time.
     * The {@code processed_events} insert closes that, and being in this transaction is the point:
     * the marker and the notifications commit together or not at all.
     */
    @KafkaListener(topics = "payment.events", groupId = "notification-group")
    @Transactional
    public void consume(PaymentEvent event) {
        if (processedEvents.existsById(event.transactionId())) {
            log.debug("[INBOX] event {} already processed — skipping", event.transactionId());
            return;
        }
        processedEvents.save(ProcessedEvent.of(event.transactionId()));

        String amount = event.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();

        // A deposit is money entering the system: there is no sender to notify, and every from_*
        // field on the event is null. Reading them unguarded is an NPE, which — now that failures
        // propagate — would retry the event and dead-letter it.
        if (hasSender(event)) {
            notificationService.create(
                    event.fromUserId(),
                    String.format("You sent $%s from account %s to account %s",
                            amount, event.fromAccountNumber(), event.toAccountNumber()),
                    NotificationType.PAYMENT_SENT
            );
        }

        notificationService.create(
                event.toUserId(),
                hasSender(event)
                        ? String.format("You received $%s to account %s from account %s",
                                amount, event.toAccountNumber(), event.fromAccountNumber())
                        : String.format("A deposit of $%s was credited to account %s",
                                amount, event.toAccountNumber()),
                NotificationType.PAYMENT_RECEIVED
        );

        afterCommit(() -> pushBalanceAndTransaction(event));
    }

    /**
     * Live updates are deferred until the transaction commits, for the same reason
     * {@code NotificationService.create} defers its own push: a rolled-back handler will be retried,
     * and pushing from an attempt that never committed tells the UI about a notification no query
     * will return.
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * Best-effort, and deliberately swallows its own failures: the notification is already durable,
     * so a dropped WebSocket frame is a cosmetic loss. Letting it escape would fail the handler,
     * retry the message, and — worse — republish a perfectly good event to the dead-letter topic.
     */
    private void pushBalanceAndTransaction(PaymentEvent event) {
        try {
            BalanceUpdate toBalance = new BalanceUpdate(
                    event.toAccountId(), event.toAccountNumber(), event.toAccountBalance());

            TransactionUpdate tx = new TransactionUpdate(
                    event.transactionId(),
                    event.fromAccountNumber(),
                    event.toAccountNumber(),
                    event.amount(),
                    event.transactionType(),
                    event.timestamp()
            );

            if (hasSender(event)) {
                BalanceUpdate fromBalance = new BalanceUpdate(
                        event.fromAccountId(), event.fromAccountNumber(), event.fromAccountBalance());
                messagingTemplate.convertAndSendToUser(event.fromUserId().toString(), "/queue/balance", fromBalance);
                messagingTemplate.convertAndSendToUser(event.fromUserId().toString(), "/queue/transaction", tx);
            }
            messagingTemplate.convertAndSendToUser(event.toUserId().toString(), "/queue/balance", toBalance);
            messagingTemplate.convertAndSendToUser(event.toUserId().toString(), "/queue/transaction", tx);
        } catch (Exception e) {
            log.warn("WebSocket push failed for event {}: {}", event.transactionId(), e.getMessage());
        }
    }

    /**
     * Distinguishes a transfer from a deposit by the thing that actually differs — whether money
     * came from an account inside the system — rather than by string-matching the event's type.
     */
    private boolean hasSender(PaymentEvent event) {
        return event.fromUserId() != null;
    }
}
