package com.banking.notification.service;

import com.banking.notification.dto.NotificationResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPushService {

    private final SimpMessagingTemplate messagingTemplate;

    @CircuitBreaker(name = "websocket", fallbackMethod = "pushFallback")
    public void push(UUID userId, NotificationResponse response) {
        messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/notifications", response);
    }

    void pushFallback(UUID userId, NotificationResponse response, Throwable t) {
        log.warn("WebSocket push failed for userId={}: {} — notification persisted in DB", userId, t.getMessage());
    }
}
