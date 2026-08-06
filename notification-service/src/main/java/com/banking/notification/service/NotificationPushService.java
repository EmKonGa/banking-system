package com.banking.notification.service;

import com.banking.notification.dto.NotificationResponse;
import com.banking.notification.relay.WebSocketRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationPushService {

    private final WebSocketRelay relay;

    public void push(UUID userId, NotificationResponse response) {
        relay.publish(userId.toString(), "/queue/notifications", response);
    }
}
