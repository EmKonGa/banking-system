package com.banking.notification.consumer;

import com.banking.notification.entity.NotificationType;
import com.banking.notification.service.NotificationService;
import com.banking.payment.event.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(topics = "payment.events", groupId = "banking-group")
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
        } catch (Exception e) {
            log.error("Failed to process payment event {}: {}", event.transactionId(), e.getMessage(), e);
        }
    }
}
