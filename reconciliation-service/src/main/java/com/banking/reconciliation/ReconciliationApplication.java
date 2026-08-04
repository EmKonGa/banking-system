package com.banking.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

// scanBasePackages reaches com.banking.common for JwtService and the shared security filter;
// without it the security config cannot be built.
@SpringBootApplication(scanBasePackages = "com.banking")
@EnableFeignClients(basePackages = "com.banking.reconciliation.client")
@EnableScheduling
public class ReconciliationApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconciliationApplication.class, args);
    }
}
