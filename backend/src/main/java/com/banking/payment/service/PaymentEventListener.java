package com.banking.payment.service;

import com.banking.payment.event.PaymentCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        try {
            messagingTemplate.convertAndSendToUser(event.fromUserId(), "/queue/balance", event.fromSnapshot());
            messagingTemplate.convertAndSendToUser(event.toUserId(), "/queue/balance", event.toSnapshot());
            messagingTemplate.convertAndSendToUser(event.fromUserId(), "/queue/transaction", event.txSnapshot());
            messagingTemplate.convertAndSendToUser(event.toUserId(), "/queue/transaction", event.txSnapshot());
        } catch (Exception e) {
            log.warn("[WS-PUSH] Failed to push balance/transaction update: {}", e.getMessage());
        }
    }
}
