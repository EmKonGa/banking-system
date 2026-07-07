package com.banking.payment.client;

import com.banking.events.TransferExecutionRequest;
import com.banking.events.TransferExecutionResult;
import com.banking.payment.config.FeignConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "account-service",
        url = "${services.account-service.url}",
        configuration = FeignConfig.class
)
public interface AccountServiceClient {

    @PostMapping("/internal/accounts/execute-transfer")
    @CircuitBreaker(name = "account-service")
    @Retry(name = "account-service")
    TransferExecutionResult executeTransfer(@RequestBody TransferExecutionRequest request);
}
