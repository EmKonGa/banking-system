package com.banking.notification.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs in every pod, including the one that published. Redis pub/sub has no "not myself" filter,
 * so the publishing pod gets its own message back through this same path -- which is correct: it
 * may itself hold the target user's session, and there is no cheaper way to find that out than to
 * just attempt the local delivery like every other pod does.
 *
 * <p>{@code convertAndSendToUser} against a session that is not on this pod is a silent no-op, so
 * every pod attempting delivery for every message is the intended behaviour, not wasted work to
 * optimise away.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketRelayListener implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @CircuitBreaker(name = "websocket", fallbackMethod = "onMessageFallback")
    public void onMessage(Message message, byte[] pattern) {
        WsRelayMessage relayed = deserialize(message);
        messagingTemplate.convertAndSendToUser(relayed.userId(), relayed.destination(), relayed.payload());
    }

    void onMessageFallback(Message message, byte[] pattern, Throwable t) {
        log.warn("Local WebSocket delivery failed: {}", t.getMessage());
    }

    private WsRelayMessage deserialize(Message message) {
        try {
            return objectMapper.readValue(message.getBody(), WsRelayMessage.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize WS relay message", e);
        }
    }
}
