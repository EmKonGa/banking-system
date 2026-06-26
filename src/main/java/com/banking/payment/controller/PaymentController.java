package com.banking.payment.controller;

import com.banking.payment.dto.TransactionResponse;
import com.banking.payment.dto.TransferRequest;
import com.banking.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(@Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.transfer(request));
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<TransactionResponse>> myTransactions() {
        return ResponseEntity.ok(paymentService.myTransactions());
    }
}
