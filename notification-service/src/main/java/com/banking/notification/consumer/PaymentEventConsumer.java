package com.banking.notification.consumer;

import com.banking.events.BalanceUpdate;
import com.banking.events.PaymentEvent;
import com.banking.events.TransactionUpdate;
import com.banking.notification.entity.NotificationType;
import com.banking.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final NotificationService notificationService;
    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(topics = "payment.events", groupId = "notification-group")
    public void consume(PaymentEvent event) {
        try {
            String amount = event.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();

            notificationService.create(
                    event.fromUserId(),
                    String.format("You sent $%s from account %s to account %s",
                            amount, event.fromAccountNumber(), event.toAccountNumber()),
                    NotificationType.PAYMENT_SENT
            );

            notificationService.create(
                    event.toUserId(),
                    String.format("You received $%s to account %s from account %s",
                            amount, event.toAccountNumber(), event.fromAccountNumber()),
                    NotificationType.PAYMENT_RECEIVED
            );

            pushBalanceAndTransaction(event);
        } catch (Exception e) {
            log.error("Failed to process payment event {}: {}", event.transactionId(), e.getMessage(), e);
        }
    }

    private void pushBalanceAndTransaction(PaymentEvent event) {
        try {
            BalanceUpdate fromBalance = new BalanceUpdate(
                    event.fromAccountId(), event.fromAccountNumber(), event.fromAccountBalance());
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

            messagingTemplate.convertAndSendToUser(event.fromUserId().toString(), "/queue/balance", fromBalance);
            messagingTemplate.convertAndSendToUser(event.toUserId().toString(), "/queue/balance", toBalance);
            messagingTemplate.convertAndSendToUser(event.fromUserId().toString(), "/queue/transaction", tx);
            messagingTemplate.convertAndSendToUser(event.toUserId().toString(), "/queue/transaction", tx);
        } catch (Exception e) {
            log.warn("WebSocket push failed for event {}: {}", event.transactionId(), e.getMessage());
        }
    }
}
