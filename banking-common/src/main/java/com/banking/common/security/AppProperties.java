package com.banking.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class AppProperties {
    private String secret;
    private long accessExpirationMs;
    private long refreshExpirationMs;
    private long maxSessionMs;
}
