package com.banking.account.service;

import com.banking.account.dto.AccountResponse;
import com.banking.account.dto.CreateAccountRequest;
import com.banking.account.entity.Account;
import com.banking.account.repository.AccountRepository;
import com.banking.common.exception.AppException;
import com.banking.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        User user = currentUser();
        Account account = Account.builder()
                .user(user)
                .accountNumber(generateUniqueAccountNumber())
                .type(request.type())
                .build();
        return AccountResponse.from(accountRepository.save(account));
    }

    public List<AccountResponse> listMyAccounts() {
        return accountRepository.findByUserId(currentUser().getId()).stream()
                .map(AccountResponse::from)
                .toList();
    }

    public AccountResponse getAccount(UUID id) {
        return AccountResponse.from(findOwnedAccount(id));
    }

    public Account findOwnedAccount(UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AppException("Account not found", HttpStatus.NOT_FOUND));
        if (!account.getUser().getId().equals(currentUser().getId())) {
            throw new AppException("Account not found", HttpStatus.NOT_FOUND);
        }
        return account;
    }

    private String generateUniqueAccountNumber() {
        Random rng = new Random();
        String number;
        do {
            number = "BA" + String.format("%010d", (long) (rng.nextDouble() * 10_000_000_000L));
        } while (accountRepository.existsByAccountNumber(number));
        return number;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
