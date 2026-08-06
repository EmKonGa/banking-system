package com.banking.notification.relay;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Wires {@link WebSocketRelayListener} to the {@link WebSocketRelay#CHANNEL} topic. The
 * {@link RedisConnectionFactory} it depends on is already auto-configured by
 * spring-boot-starter-data-redis from the same {@code spring.data.redis.*} properties every other Redis
 * usage in this repo reads -- nothing new to configure.
 */
@Configuration
@RequiredArgsConstructor
public class WebSocketRelayConfig {

    private final WebSocketRelayListener listener;

    @Bean
    public RedisMessageListenerContainer webSocketRelayListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Without this the container falls back to createDefaultTaskExecutor() -> a
        // SimpleAsyncTaskExecutor with no concurrency limit, and dispatchMessage submits one task
        // PER MESSAGE -- so a fresh platform thread, ~1MB of stack, for work that is a JSON parse
        // plus a handoff to the messaging channel. The fan-out makes that scale the wrong way:
        // every pod receives every push, so each pod creates threads at the FULL system-wide push
        // rate, not its share of it -- adding replicas multiplies this cost instead of dividing it.
        //
        // Deliberately not a bounded ThreadPoolTaskExecutor. Anything that makes the dispatch block
        // (full queue, CallerRunsPolicy) stalls the Redis subscriber thread, which stops draining
        // the connection, which trips Redis' pubsub client-output-buffer-limit -- and Redis then
        // kills the subscription, losing every message in the gap with no error at the publisher.
        // On a pub/sub listener you absorb; you do not push back.
        container.setTaskExecutor(new VirtualThreadTaskExecutor("ws-relay-"));
        container.addMessageListener(listener, new ChannelTopic(WebSocketRelay.CHANNEL));
        return container;
    }
}
