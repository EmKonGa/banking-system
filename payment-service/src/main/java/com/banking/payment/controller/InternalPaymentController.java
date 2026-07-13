package com.banking.payment.controller;

import com.banking.payment.dto.TransactionResponse;
import com.banking.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;

    @GetMapping("/transactions/by-account/{accountId}")
    public Page<TransactionResponse> transactionsByAccount(
            @PathVariable UUID accountId,
            @PageableDefault(size = 20) Pageable pageable) {
        return paymentService.transactionsByAccount(accountId, pageable);
    }
}
