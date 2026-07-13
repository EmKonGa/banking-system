package com.banking.account.controller;

import com.banking.account.dto.AccountResponse;
import com.banking.account.dto.CreateAccountRequest;
import com.banking.account.dto.DepositRequest;
import com.banking.account.dto.TransactionResponse;
import com.banking.account.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

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

    @PostMapping("/{id}/deposit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountResponse> deposit(@PathVariable UUID id, @Valid @RequestBody DepositRequest request) {
        return ResponseEntity.ok(accountService.deposit(id, request.amount()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        accountService.closeAccount(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountResponse> freeze(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.freezeAccount(id));
    }

    @GetMapping("/{id}/transactions")
    public ResponseEntity<Page<TransactionResponse>> transactions(
            @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        accountService.findOwnedAccount(id);
        return ResponseEntity.ok(accountService.transactionsByAccount(id, pageable));
    }
}
