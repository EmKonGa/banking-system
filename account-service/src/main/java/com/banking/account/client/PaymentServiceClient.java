package com.banking.account.client;

import com.banking.account.config.FeignConfig;
import com.banking.account.dto.TransactionResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

@FeignClient(
        name = "payment-service",
        url = "${services.payment-service.url}",
        configuration = FeignConfig.class
)
public interface PaymentServiceClient {

    @GetMapping("/internal/payments/transactions/by-account/{accountId}")
    @CircuitBreaker(name = "payment-service", fallbackMethod = "transactionsByAccountFallback")
    List<TransactionResponse> transactionsByAccount(@PathVariable UUID accountId);

    default List<TransactionResponse> transactionsByAccountFallback(UUID accountId, Throwable t) {
        return List.of();
    }
}
