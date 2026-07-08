package com.banking.payment.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfig {

    @Value("${internal.secret:internal-secret-change-me}")
    private String internalSecret;

    @Bean
    public RequestInterceptor internalSecretFeignInterceptor() {
        return template -> template.header("X-Internal-Secret", internalSecret);
    }
}
