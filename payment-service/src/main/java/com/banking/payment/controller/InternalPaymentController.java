package com.banking.payment.controller;

import com.banking.payment.dto.TransactionResponse;
import com.banking.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;

    @GetMapping("/transactions/by-account/{accountId}")
    public List<TransactionResponse> transactionsByAccount(@PathVariable UUID accountId) {
        return paymentService.transactionsByAccount(accountId);
    }
}
