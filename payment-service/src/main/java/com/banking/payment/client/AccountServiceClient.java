package com.banking.payment.client;

import com.banking.events.TransferExecutionRequest;
import com.banking.events.TransferExecutionResult;
import com.banking.payment.config.FeignConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

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

    /**
     * Asks whether a transfer with this idempotency key committed. 404 means it did not — the log
     * row is written in the same transaction as the balance change, so its absence is conclusive.
     *
     * <p>No {@code @Retry}: the recovery poller runs on a schedule and will simply ask again on its
     * next tick, so retrying inside a call it makes while holding row locks buys nothing.
     */
    @GetMapping("/internal/accounts/transfers/{idempotencyKey}")
    @CircuitBreaker(name = "account-service")
    TransferExecutionResult findTransfer(@PathVariable("idempotencyKey") UUID idempotencyKey);
}
