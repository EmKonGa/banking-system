package com.banking.account.service;

import com.banking.account.controller.InternalAccountController;
import com.banking.account.entity.Account;
import com.banking.account.entity.AccountStatus;
import com.banking.account.entity.AccountType;
import com.banking.account.repository.AccountRepository;
import com.banking.account.repository.AccountTransferLogRepository;
import com.banking.common.exception.AppException;
import com.banking.events.TransferExecutionRequest;
import com.banking.events.TransferExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency stress tests for the transfer money-path against a real PostgreSQL
 * (Testcontainers) — H2 cannot reproduce Postgres row-locking / deadlock semantics.
 *
 * <p>These exercise {@link InternalTransferService#execute} and the idempotent
 * {@link InternalAccountController#executeTransfer} under heavy contention to prove:
 * <ul>
 *   <li>opposing concurrent transfers on the same pair never deadlock (ordered locking),</li>
 *   <li>concurrent drains can never overdraw an account (atomic conditional debit),</li>
 *   <li>concurrent requests sharing one idempotency key apply exactly once.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true) // skip where Testcontainers can't reach Docker; runs in CI
class TransferConcurrencyStressTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Satisfy the startup SecretValidator (non-placeholder, >=256-bit) and quiet tracing export.
        registry.add("app.jwt.secret", () -> "test-jwt-secret-that-is-long-enough-for-hs256-256bits");
        registry.add("internal.secret", () -> "test-internal-secret");
        registry.add("management.tracing.enabled", () -> "false");
    }

    @Autowired
    InternalTransferService transferService;
    @Autowired
    InternalAccountController controller;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    AccountTransferLogRepository transferLogRepository;

    @BeforeEach
    void clean() {
        // Bulk DELETE, not entity deleteAll(): AccountTransferLog#isNew() is always true, and
        // Spring Data skips entity-based deletes for "new" entities — deleteAllInBatch bypasses that.
        transferLogRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
    }

    /**
     * Half the threads transfer A→B, half B→A, all on the same account pair at once.
     * Without deterministic lock ordering, Postgres would abort some transactions with a
     * deadlock (CannotAcquireLockException). We assert zero failures and conserved balances.
     */
    @Test
    void opposingConcurrentTransfers_neverDeadlock_andConserveMoney() throws Exception {
        UUID user = UUID.randomUUID();
        Account a = newAccount(user, new BigDecimal("100000"));
        Account b = newAccount(user, new BigDecimal("100000"));
        BigDecimal total = a.getBalance().add(b.getBalance());

        int threads = 16, perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final boolean aToB = (t % 2 == 0);
            pool.submit(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < perThread; i++) {
                        UUID from = aToB ? a.getId() : b.getId();
                        String toNumber = aToB ? b.getAccountNumber() : a.getAccountNumber();
                        transferService.execute(new TransferExecutionRequest(
                                from, toNumber, BigDecimal.ONE, UUID.randomUUID(), user));
                    }
                } catch (Throwable ex) {
                    // Deadlock / lock-acquisition failures (and any unexpected error) land here.
                    failures.add(ex);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(90, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        pool.shutdownNow();

        assertThat(failures).as("no deadlock or lock-acquisition failures").isEmpty();

        Account fa = accountRepository.findById(a.getId()).orElseThrow();
        Account fb = accountRepository.findById(b.getId()).orElseThrow();
        assertThat(fa.getBalance().add(fb.getBalance()))
                .as("total money conserved").isEqualByComparingTo(total);
        // Equal thread counts × equal 1-unit transfers in each direction → each account nets to its start.
        assertThat(fa.getBalance()).isEqualByComparingTo(a.getBalance());
        assertThat(fb.getBalance()).isEqualByComparingTo(b.getBalance());
    }

    /**
     * 32 threads each try to move 10 out of an account holding only 100. The atomic
     * {@code WHERE balance >= :amount} debit must let exactly 10 succeed and never go negative.
     */
    @Test
    void concurrentDrains_neverOverdraw() throws Exception {
        UUID user = UUID.randomUUID();
        Account a = newAccount(user, new BigDecimal("100"));
        Account b = newAccount(user, BigDecimal.ZERO);

        int threads = 32;
        BigDecimal amount = BigDecimal.TEN;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    barrier.await();
                    transferService.execute(new TransferExecutionRequest(
                            a.getId(), b.getAccountNumber(), amount, UUID.randomUUID(), user));
                    success.incrementAndGet();
                } catch (AppException e) {
                    if (e.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY) {
                        insufficient.incrementAndGet();
                    } else {
                        unexpected.add(e);
                    }
                } catch (Throwable e) {
                    unexpected.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(90, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        pool.shutdownNow();

        assertThat(unexpected).as("no unexpected failures").isEmpty();
        assertThat(success.get()).as("exactly 100/10 transfers succeed").isEqualTo(10);
        assertThat(insufficient.get()).as("the rest are rejected as insufficient").isEqualTo(threads - 10);

        Account fa = accountRepository.findById(a.getId()).orElseThrow();
        Account fb = accountRepository.findById(b.getId()).orElseThrow();
        assertThat(fa.getBalance()).as("source never negative").isEqualByComparingTo("0");
        assertThat(fb.getBalance()).as("destination received exactly 100").isEqualByComparingTo("100");
    }

    /**
     * 16 threads submit the SAME idempotency key concurrently. The unique PK on the transfer
     * log plus the ordered row locks must apply the debit exactly once; every caller gets a
     * result and no error surfaces (the controller re-reads the winning row on conflict).
     */
    @Test
    void sameIdempotencyKey_concurrent_appliesExactlyOnce() throws Exception {
        UUID user = UUID.randomUUID();
        Account a = newAccount(user, new BigDecimal("100"));
        Account b = newAccount(user, BigDecimal.ZERO);
        UUID key = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("30");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<TransferExecutionResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    barrier.await();
                    results.add(controller.executeTransfer(new TransferExecutionRequest(
                            a.getId(), b.getAccountNumber(), amount, key, user)));
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(90, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        pool.shutdownNow();

        assertThat(failures).as("idempotent conflict handled, no error surfaced").isEmpty();
        assertThat(results).as("every caller got a result").hasSize(threads);

        Account fa = accountRepository.findById(a.getId()).orElseThrow();
        assertThat(fa.getBalance()).as("debit applied exactly once").isEqualByComparingTo("70");
        assertThat(transferLogRepository.count()).as("exactly one transfer recorded").isEqualTo(1);
        assertThat(results).allSatisfy(r ->
                assertThat(r.toAccountNumber()).isEqualTo(b.getAccountNumber()));
    }

    private Account newAccount(UUID userId, BigDecimal balance) {
        return accountRepository.saveAndFlush(Account.builder()
                .userId(userId)
                .accountNumber(UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .balance(balance)
                .type(AccountType.CHECKING)
                .status(AccountStatus.ACTIVE)
                .build());
    }
}
