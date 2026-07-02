package com.banking.common.config;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.common.KeyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration
public class ObservabilityConfig {

    @Bean
    ObservationPredicate noActuatorTraces() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext ctx) {
                return !ctx.getCarrier().getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
