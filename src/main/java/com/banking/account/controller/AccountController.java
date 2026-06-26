package com.banking.account.controller;

import com.banking.account.dto.AccountResponse;
import com.banking.account.dto.CreateAccountRequest;
import com.banking.account.service.AccountService;
import com.banking.payment.dto.TransactionResponse;
import com.banking.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> list() {
        return ResponseEntity.ok(accountService.listMyAccounts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.getAccount(id));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<TransactionResponse>> transactions(@PathVariable UUID id) {
        // findOwnedAccount enforces that this account belongs to the authenticated user
        accountService.findOwnedAccount(id);
        return ResponseEntity.ok(paymentService.transactionsByAccount(id));
    }
}
