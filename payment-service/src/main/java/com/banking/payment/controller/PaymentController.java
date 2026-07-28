package com.banking.payment.controller;

import com.banking.payment.dto.DepositRequest;
import com.banking.payment.dto.TransactionResponse;
import com.banking.payment.dto.TransferRequest;
import com.banking.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(@Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.transfer(request));
    }

    /**
     * Operator-only, as it was when it lived on account-service: this credits an account with money
     * that came from outside the system, so nothing about it is self-service.
     */
    @PostMapping("/deposit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TransactionResponse> deposit(@Valid @RequestBody DepositRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.deposit(request));
    }

    @GetMapping("/transactions")
    public ResponseEntity<Slice<TransactionResponse>> myTransactions(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(paymentService.myTransactions(pageable));
    }
}
