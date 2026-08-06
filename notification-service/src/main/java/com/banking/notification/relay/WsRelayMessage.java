package com.banking.notification.relay;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wire shape published to the {@link WebSocketRelay#CHANNEL} Redis channel. {@code payload} is
 * kept as a {@link JsonNode} rather than a typed DTO so the relay does not need to know the
 * concrete class of everything it forwards ({@code NotificationResponse}, {@code BalanceUpdate},
 * {@code TransactionUpdate}, ...) — it round-trips whatever JSON the publisher handed it.
 */
public record WsRelayMessage(String userId, String destination, JsonNode payload) {
}
