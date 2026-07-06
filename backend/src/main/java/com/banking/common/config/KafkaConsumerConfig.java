package com.banking.common.config;

import com.banking.common.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Slf4j
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxAttempts(3);
        backOff.setMaxInterval(10_000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, ex) -> log.error("Kafka consumer exhausted retries for topic={} key={}: {}",
                        record.topic(), record.key(), ex.getMessage(), ex),
                backOff
        );
        errorHandler.addNotRetryableExceptions(AppException.class);
        return errorHandler;
    }
}
