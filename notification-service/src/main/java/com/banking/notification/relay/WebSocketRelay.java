package com.banking.notification.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Replaces every direct {@code SimpMessagingTemplate.convertAndSendToUser} call in this service.
 *
 * <p>{@code enableSimpleBroker} keeps its registry of "which user is connected on which session"
 * in the JVM heap of whichever pod holds the WebSocket connection. With more than one replica, a
 * push issued by the pod that happens to process the Kafka event reaches that user only if they
 * are also connected to <i>that same pod</i> — otherwise {@code convertAndSendToUser} finds no
 * local session and silently drops it. This is the bug demonstrated in 21-notification-service.yaml.
 *
 * <p>The fix is a broker relay, same as the manifest comment calls for — Redis pub/sub standing in
 * for RabbitMQ/ActiveMQ STOMP relay because Redis is already a dependency of every service here.
 * Every pod (via {@link WebSocketRelayListener}) subscribes to one channel and receives every
 * push regardless of which pod published it; whichever pod actually holds the target user's local
 * session is the one that delivers, and every other pod's attempt is a local no-op. This also
 * fixes the OTHER half of the bug for free: it no longer matters which pod's Kafka consumer owns
 * the single {@code payment.events} partition, because that pod no longer needs to be the one
 * with the user's connection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketRelay {

    static final String CHANNEL = "ws-relay";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @CircuitBreaker(name = "redis")
    @Retry(name = "redis", fallbackMethod = "publishFallback")
    public void publish(String userId, String destination, Object payload) {
        WsRelayMessage message = new WsRelayMessage(userId, destination, objectMapper.valueToTree(payload));
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            // Checked only because Jackson declares it -- serializing our own record of a String,
            // a String and a JsonNode does not fail in practice. Rethrown unchecked so the method
            // signature stays clean for callers, and so it still reaches publishFallback below.
            throw new IllegalStateException("Failed to serialize WS relay message", e);
        }
    }

    // Fail open, same stance as TokenBlacklistService: the notification row is already durable in
    // Postgres by the time anything reaches this class (see PaymentEventConsumer), so a dropped
    // live push is a cosmetic loss, not a lost notification. This also catches a JSON-serialization
    // failure, not just a Redis outage -- Resilience4j routes every exception the guarded method
    // throws to this fallback, not only the ones "redis" retry-exceptions lists as retryable.
    void publishFallback(String userId, String destination, Object payload, Throwable t) {
        log.warn("WS relay publish failed for userId={} destination={}: {} — push dropped",
                userId, destination, t.getMessage());
    }
}
