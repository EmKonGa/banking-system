package com.banking.auth.config;

import com.banking.common.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                try {
                    String token = authHeader.substring(7);
                    if (!jwtService.isTokenValid(token)) {
                        log.warn("WebSocket auth failed: token invalid or expired");
                        throw new IllegalStateException("Unauthorized");
                    }
                    String userId = jwtService.extractUserId(token);
                    accessor.setUser(() -> userId);
                    log.info("WebSocket authenticated: userId={}", userId);
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("WebSocket auth failed: {}", e.getMessage());
                    throw new IllegalStateException("Unauthorized");
                }
            }
        }
        return message;
    }
}
