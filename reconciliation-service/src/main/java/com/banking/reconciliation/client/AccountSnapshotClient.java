package com.banking.reconciliation.client;

import com.banking.events.AccountBalanceSnapshot;
import com.banking.events.MovementKeySnapshot;
import com.banking.reconciliation.config.FeignConfig;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "account-service-reconciliation",
        url = "${services.account-service.url}",
        configuration = FeignConfig.class
)
public interface AccountSnapshotClient {

    /**
     * No {@code @Retry} on any of these. The sweep runs on a schedule and will simply ask again on
     * its next pass, so retrying inside a pass buys nothing and lengthens the window over which the
     * two sides are read — which is the very thing that produces false discrepancies.
     */
    @GetMapping("/internal/accounts/reconciliation/balances")
    @CircuitBreaker(name = "account-service")
    Page<AccountBalanceSnapshot> balances(@RequestParam("page") int page, @RequestParam("size") int size);

    @GetMapping("/internal/accounts/reconciliation/movement-keys")
    @CircuitBreaker(name = "account-service")
    Page<MovementKeySnapshot> movementKeys(@RequestParam("page") int page, @RequestParam("size") int size);
}
