package com.banking.account.client;

import com.banking.account.config.FeignConfig;
import com.banking.account.dto.TransactionResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(
        name = "payment-service",
        url = "${services.payment-service.url}",
        configuration = FeignConfig.class
)
public interface PaymentServiceClient {

    @GetMapping("/internal/payments/transactions/by-account/{accountId}")
    @CircuitBreaker(name = "payment-service", fallbackMethod = "transactionsByAccountFallback")
    Page<TransactionResponse> transactionsByAccount(@PathVariable UUID accountId, Pageable pageable);

    default Page<TransactionResponse> transactionsByAccountFallback(UUID accountId, Pageable pageable, Throwable t) {
        return Page.empty();
    }
}
