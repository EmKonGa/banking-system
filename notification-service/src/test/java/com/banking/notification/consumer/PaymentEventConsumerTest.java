package com.banking.notification.consumer;

import com.banking.events.PaymentEvent;
import com.banking.notification.entity.NotificationType;
import com.banking.notification.entity.ProcessedEvent;
import com.banking.notification.repository.ProcessedEventRepository;
import com.banking.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The consumer sits at the end of an at-least-once pipeline, so the two properties that matter are
 * that a redelivery is harmless and that a genuine failure is visible. The previous implementation
 * had neither: it created notifications unconditionally, and it swallowed every exception and
 * returned normally, which committed the offset and left the container's error handler unreachable.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock NotificationService notificationService;
    @Mock ProcessedEventRepository processedEvents;
    @Mock SimpMessagingTemplate messagingTemplate;

    PaymentEventConsumer consumer;

    private static final UUID TX = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consumer = new PaymentEventConsumer(notificationService, processedEvents, messagingTemplate);
    }

    private PaymentEvent event() {
        return new PaymentEvent(
                TX, UUID.randomUUID(), UUID.randomUUID(),
                "000000000001", "000000000002",
                SENDER, RECIPIENT,
                new BigDecimal("25.005"),
                new BigDecimal("75.0000"), new BigDecimal("125.0000"),
                "TRANSFER", Instant.now());
    }

    @Test
    void bothPartiesAreNotifiedOnAFirstDelivery() {
        when(processedEvents.existsById(TX)).thenReturn(false);

        consumer.consume(event());

        verify(notificationService).create(eq(SENDER), anyString(), eq(NotificationType.PAYMENT_SENT));
        verify(notificationService).create(eq(RECIPIENT), anyString(), eq(NotificationType.PAYMENT_RECEIVED));
    }

    /**
     * The core regression. The outbox is at-least-once by design, so redelivery is expected — and
     * the old consumer told the user about the same transfer again every time it happened.
     */
    @Test
    void aRedeliveredEventProducesNoSecondNotification() {
        when(processedEvents.existsById(TX)).thenReturn(true);

        consumer.consume(event());

        verifyNoInteractions(notificationService, messagingTemplate);
        verify(processedEvents, never()).save(any());
    }

    /**
     * The marker has to be written by the same transaction as the notifications. If it were
     * committed separately and the handler then failed, the retry would skip an event whose
     * notifications were never created — losing it silently, which is the failure this replaced.
     */
    @Test
    void theProcessedMarkerIsRecordedForTheEventId() {
        when(processedEvents.existsById(TX)).thenReturn(false);

        consumer.consume(event());

        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo(TX);
    }

    /**
     * Failures must escape. Returning normally commits the offset, which is what made retries and
     * the dead-letter topic unreachable — the message was gone and nothing recorded that it failed.
     */
    @Test
    void aFailurePropagatesInsteadOfBeingSwallowed() {
        when(processedEvents.existsById(TX)).thenReturn(false);
        doThrow(new IllegalStateException("database unavailable"))
                .when(notificationService).create(any(), anyString(), any());

        assertThatThrownBy(() -> consumer.consume(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    /**
     * A dropped WebSocket frame is cosmetic — the notification is already durable. Letting it
     * escape would retry the message and eventually dead-letter a perfectly good event.
     */
    @Test
    void aFailedWebSocketPushDoesNotFailTheHandler() {
        when(processedEvents.existsById(TX)).thenReturn(false);
        doThrow(new IllegalStateException("no broker"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        consumer.consume(event());

        verify(notificationService, org.mockito.Mockito.times(2)).create(any(), anyString(), any());
    }

    /** Money is rendered to cents; an unrounded BigDecimal would surface as "$25.005". */
    @Test
    void amountsAreRoundedToTwoDecimalPlaces() {
        when(processedEvents.existsById(TX)).thenReturn(false);

        consumer.consume(event());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(eq(SENDER), message.capture(), any());
        assertThat(message.getValue()).contains("$25.01").doesNotContain("25.005");
    }
}
