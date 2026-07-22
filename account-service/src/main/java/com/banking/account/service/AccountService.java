package com.banking.account.service;

import com.banking.account.cache.AccountReader;
import com.banking.account.cache.CachedAccount;
import com.banking.account.client.PaymentServiceClient;
import com.banking.account.dto.AccountResponse;
import com.banking.account.dto.CreateAccountRequest;
import com.banking.account.dto.TransactionResponse;
import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.repository.AccountRepository;
import com.banking.common.exception.AppException;
import com.banking.account.event.AccountsChangedEvent;
import com.banking.common.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final PaymentServiceClient paymentServiceClient;
    private final AccountReader accountReader;
    private final ApplicationEventPublisher events;

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        UUID userId = currentUserId();
        Account account = Account.builder()
                .userId(userId)
                .accountNumber(generateUniqueAccountNumber())
                .type(request.type())
                .build();
        Account saved = accountRepository.save(account);
        events.publishEvent(AccountsChangedEvent.of(saved.getId(), userId));
        return AccountResponse.from(saved);
    }

    public List<AccountResponse> listMyAccounts() {
        JwtPrincipal principal = currentPrincipal();
        boolean isAdmin = "ADMIN".equals(principal.role());
        // The cache holds every account the user owns; CLOSED ones are filtered here rather than
        // in the key, so one eviction per user covers both the admin and non-admin views.
        return accountReader.byUser(principal.id()).stream()
                .filter(account -> isAdmin || account.status() != AccountStatus.CLOSED)
                .map(CachedAccount::toResponse)
                .toList();
    }

    public AccountResponse getAccount(UUID id) {
        return findOwnedAccount(id).toResponse();
    }

    @Transactional
    public AccountResponse deposit(UUID accountId, BigDecimal amount) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AppException("Account not found", HttpStatus.NOT_FOUND));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AppException("Account is not active", HttpStatus.BAD_REQUEST);
        }
        account.setBalance(account.getBalance().add(amount));
        Account saved = accountRepository.save(account);
        events.publishEvent(AccountsChangedEvent.of(saved.getId(), saved.getUserId()));
        return AccountResponse.from(saved);
    }

    /**
     * Reads through the cache, then authorizes. The ownership check runs on every call — hit or
     * miss — so a cached entry can never be a way around it. This is why the cache stores
     * {@link CachedAccount}, which carries {@code userId}, instead of the response DTO.
     */
    public CachedAccount findOwnedAccount(UUID id) {
        CachedAccount account = accountReader.byId(id);
        if (!account.userId().equals(currentUserId())) {
            throw new AppException("Account not found", HttpStatus.NOT_FOUND);
        }
        return account;
    }

    @Transactional
    public void closeAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AppException("Account not found", HttpStatus.NOT_FOUND));
        account.setStatus(AccountStatus.CLOSED);
        accountRepository.save(account);
        events.publishEvent(AccountsChangedEvent.of(account.getId(), account.getUserId()));
    }

    @Transactional
    public AccountResponse freezeAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AppException("Account not found", HttpStatus.NOT_FOUND));
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new AppException("Cannot freeze a closed account", HttpStatus.BAD_REQUEST);
        }
        account.setStatus(account.getStatus() == AccountStatus.FROZEN ? AccountStatus.ACTIVE : AccountStatus.FROZEN);
        Account saved = accountRepository.save(account);
        events.publishEvent(AccountsChangedEvent.of(saved.getId(), saved.getUserId()));
        return AccountResponse.from(saved);
    }

    public Page<TransactionResponse> transactionsByAccount(UUID accountId, Pageable pageable) {
        return paymentServiceClient.transactionsByAccount(accountId, pageable);
    }

    private String generateUniqueAccountNumber() {
        Random rng = new Random();
        String number;
        do {
            number = String.format("%012d", (long) (rng.nextDouble() * 1_000_000_000_000L));
        } while (accountRepository.existsByAccountNumber(number));
        return number;
    }

    private UUID currentUserId() {
        return currentPrincipal().id();
    }

    private JwtPrincipal currentPrincipal() {
        return (JwtPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
