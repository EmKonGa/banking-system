package com.banking.reconciliation.client;

import com.banking.events.LedgerKeySnapshot;
import com.banking.events.LedgerNetSnapshot;
import com.banking.reconciliation.config.FeignConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "payment-service-reconciliation",
        url = "${services.payment-service.url}",
        configuration = FeignConfig.class
)
public interface PaymentLedgerClient {

    @GetMapping("/internal/payments/reconciliation/net-by-account")
    @CircuitBreaker(name = "payment-service")
    Page<LedgerNetSnapshot> netByAccount(@RequestParam("page") int page, @RequestParam("size") int size);

    @GetMapping("/internal/payments/reconciliation/keys")
    @CircuitBreaker(name = "payment-service")
    Page<LedgerKeySnapshot> keys(@RequestParam("page") int page, @RequestParam("size") int size);

    @GetMapping("/internal/payments/reconciliation/stale-pending")
    @CircuitBreaker(name = "payment-service")
    Page<LedgerKeySnapshot> stalePending(
            @RequestParam("olderThanSeconds") long olderThanSeconds,
            @RequestParam("page") int page,
            @RequestParam("size") int size);
}
