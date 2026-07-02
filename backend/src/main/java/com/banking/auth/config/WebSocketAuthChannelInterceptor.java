package com.banking.auth.config;

import com.banking.auth.service.CustomUserDetailsService;
import com.banking.auth.service.JwtService;
import com.banking.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                try {
                    String token = authHeader.substring(7);
                    String username = jwtService.extractUsername(token);
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    if (jwtService.isTokenValid(token, userDetails)) {
                        String userId = ((User) userDetails).getId().toString();
                        accessor.setUser(() -> userId);
                        log.info("WebSocket authenticated: userId={}", userId);
                    } else {
                        log.warn("WebSocket auth failed: token invalid");
                        throw new IllegalStateException("Unauthorized");
                    }
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
