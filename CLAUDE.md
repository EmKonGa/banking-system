# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

**Start everything (infrastructure + all services) via Docker:**
```bash
docker compose up -d --build
```

> Copy `.env.example` to `.env` before the first run. Maven runs inside Docker via multi-stage builds — no local Java/Maven required. All env vars have defaults baked into each service's `application.yml`.

**Run a single service locally** (requires Java 21 + Maven; infrastructure must already be running via Docker):
```bash
# Replace <service> with auth-service, account-service, payment-service, or notification-service
mvn spring-boot:run -pl <service>
```

**Run all tests:**
```bash
./mvnw test          # or `verify`, which is what CI runs
```

> The Maven wrapper pins **3.9.9** (`.mvn/wrapper/maven-wrapper.properties`, script-only — no wrapper jar, because `.gitignore` excludes `*.jar`). Use `./mvnw` rather than a system `mvn` so local builds match CI. Some tests use **Testcontainers** and need a running Docker daemon.

**Run a single test class:**
```bash
./mvnw test -Dtest=ClassName -pl <module-name>
```

**Run the cross-service saga tests** (`integration-tests`, failsafe-bound `*IT`):
```bash
./mvnw verify                                   # the whole reactor; what CI runs
./mvnw verify -pl integration-tests -Dit.test=CrashMidSagaIT
```

> These run account-service and payment-service as **containers built from the jars the reactor just packaged**, so the modules must be built first — `-pl integration-tests` alone against a clean tree fails saying so. `./mvnw test` does not run them (surefire ignores `*IT`).

## Architecture

Spring Boot 3.3 / Java 21 microservices. All external traffic enters through the **API Gateway** (port 8080); individual services are not meant to be called directly by clients.

### Modules

| Module | Package | Responsibility |
|---|---|---|
| `api-gateway` | `com.banking.gateway` | Spring Cloud Gateway: JWT validation, routing, blocks internal paths from external callers |
| `auth-service` | `com.banking.auth` | Register/login/refresh/logout, JWT issuance, token blacklist (Redis) |
| `account-service` | `com.banking.account` | Bank accounts, balances, transfer logs; calls payment-service via Feign |
| `payment-service` | `com.banking.payment` | Transfers, transaction ledger, Transactional Outbox → Kafka; calls account-service via Feign |
| `notification-service` | `com.banking.notification` | Kafka consumer, per-user notification inbox, WebSocket push |
| `reconciliation-service` | `com.banking.reconciliation` | Independent auditor: sweeps balances against the ledger, records findings, exposes an alertable gauge |
| `banking-common` | `com.banking.common` | Shared `AppException` + `GlobalExceptionHandler` |
| `banking-events` | `com.banking.events` | Shared Kafka event DTOs (e.g. `PaymentEvent`) used by producer and consumer |
| `integration-tests` | `com.banking.it` | Saga tests across a real process boundary — account-service and payment-service as containers, with a controllable proxy between them |

### Service Communication

- **Sync (Feign):** `account-service` → `payment-service` (fetch transactions); `payment-service` → `account-service` (execute a transfer or deposit, and `GET /internal/accounts/transfers/{idempotencyKey}` to ask whether one committed — used by the saga's recovery poller for both)
- **Async (Kafka):** `payment-service` publishes events via Transactional Outbox → `OutboxPoller` → Kafka → `notification-service` consumes
- **Internal auth:** service-to-service calls on `/internal/**` paths use a shared `X-Internal-Secret` header; the API Gateway blocks these paths from external access

## API Endpoints (all via gateway on port 8080)

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Register, returns token pair |
| POST | `/api/auth/login` | Public | Login, returns token pair |
| POST | `/api/auth/refresh` | Public | Rotate refresh token |
| POST | `/api/auth/logout` | JWT | Blacklist access + delete refresh token |
| POST | `/api/accounts` | JWT | Create account (`{"type":"SAVINGS"\|"CHECKING"}`) |
| GET | `/api/accounts` | JWT | List authenticated user's accounts |
| GET | `/api/accounts/{id}` | JWT | Get single account |
| GET | `/api/accounts/{id}/transactions` | JWT | Transactions for one account |
| POST | `/api/payments/transfer` | JWT | Transfer between accounts |
| POST | `/api/payments/deposit` | JWT (ADMIN) | Credit an account from outside the system |
| GET | `/api/payments/transactions` | JWT | All transactions for current user |
| GET | `/api/notifications` | JWT | Notification inbox |
| PATCH | `/api/notifications/{id}/read` | JWT | Mark one notification read |
| PATCH | `/api/notifications/read-all` | JWT | Mark all notifications read |
| GET | `/api/reconciliation/findings` | JWT (ADMIN) | Open reconciliation findings (`?openOnly=false` for history) |
| WS | `/ws/**` | (auth in handshake) | WebSocket for real-time notifications |

## Auth Flow

Stateless JWT, two-token strategy:

- **Access token** (5 min, `JWT_ACCESS_EXPIRATION_MS`): HS256 JWT; carries `jti` (UUID) for blacklisting and `sub` (email).
- **Refresh token** (15 min, `JWT_REFRESH_EXPIRATION_MS`): opaque UUID in Redis under `refresh:<token>` → `<userId>|<sessionStartMillis>`. Rotation re-issues on every refresh, so this TTL is an **idle timeout**.

JWT validation happens **at the gateway** (`JwtAuthGatewayFilter`) — it checks the Redis blacklist on every request and forwards the JWT downstream. Individual services also validate JWTs for their own security config.

- **Logout** blacklists the access token's `jti` (`blacklist:<jti>`) for its remaining TTL and deletes the refresh token key.
- **Refresh rotation** — old token deleted and new one issued atomically in `RefreshTokenService.rotate()`, carrying the original `sessionStart` forward.
- **Absolute session cap** (`JWT_MAX_SESSION_MS`, default 8h; `0` disables) — `AuthService.refresh` rejects a refresh once the session is older than the cap; rotation can't reset it because `sessionStart` is preserved.
- **Activity-aware refresh (frontend)** — `AuthService` (Angular) refreshes on a timer only while the user is active; after the idle window it shows a countdown "stay logged in?" modal (`SessionTimeoutDialog`). Keep the frontend `IDLE_TIMEOUT_MS` in step with `JWT_REFRESH_EXPIRATION_MS`.
- **Pre-auth rate limiting** — `/api/auth/register|login|refresh` are throttled at the gateway with SCG's `RequestRateLimiter` (Redis token bucket, shared across gateway replicas). Per client IP via `IpKeyResolver`; defaults 1 req/sec sustained, burst 5 (`RATE_LIMIT_AUTH_REPLENISH_PER_SEC` / `RATE_LIMIT_AUTH_BURST`). Excluded on purpose: `/api/auth/logout` and the catch-all `auth-service` route (they carry a JWT — `sub` is the right key, per-user throttling is a follow-up), plus every JWT-guarded route.
  - **Key is IP, and XFF is trusted.** `IpKeyResolver` reads the first `X-Forwarded-For` value, falls back to `remoteAddress`, else a shared `"unknown"` bucket so the filter never trips its `deny-empty-key` 403. Trusting XFF is required behind ingress-nginx (`remoteAddress` there is the ingress pod). A caller reaching the gateway directly can forge XFF — a trusted-proxy allowlist is the production fix, out of scope here.
  - **Fail-open is inherited** — SCG 4.1's `RedisRateLimiter.isAllowed` already `onErrorResume`s Redis failures to `allowed=true`. ⚠️ It only logs at DEBUG, so an outage is silent locally; visibility relies on existing Redis health/alerting.
  - **No breaker on this call path**, unlike the blacklist — pre-auth traffic is a small fraction of gateway load and login is already BCrypt-heavy, so the 2s Lettuce timeout per attempt during an outage is bounded.
  - **Ingress-nginx rate-limit annotations deliberately not used** — per-controller-replica (not shared), so scaling the ingress out breaks them.

## Key Design Decisions

- **Shared JWT secret**: all services share `JWT_SECRET` so any service can verify tokens without calling auth-service.

- **Transfer saga (durable intent + recovery)** — moving money spans account-service's database and the payment-service ledger; no single `@Transactional` covers both, and wrapping `PaymentService.transfer` in one only created the illusion it did (a crash after account-service committed left money moved with no record in payment-service). Steps commit separately, intent first:
  1. `TransferLedger.openIntent` commits a `PENDING` row carrying the idempotency key — *before* the money is asked to move.
  2. `AccountServiceClient.executeTransfer`.
  3. `TransferLedger.settleCompleted` (writes the outbox event) or `settleFailed`.
  - **Failure classification is the crux.** A `FeignException` **4xx** is account-service's considered refusal — no money moved — so the intent is settled `FAILED` at once. **Anything else** (5xx, timeout, open breaker) means the outcome is *unknown*, so the intent is deliberately left `PENDING`. Guessing `FAILED` there would write off a transfer whose money actually moved.
  - `TransferLedger` is a separate bean on purpose: `@Transactional` only applies through a proxy, so keeping these methods on `PaymentService` and calling them from its own `transfer` would run them in one transaction and restore the original bug. They are `REQUIRES_NEW` so settlement also commits independently from the recovery poller's batch transaction.
  - **`TransferRecoveryPoller`** settles strays: it asks account-service what the key did (`GET /internal/accounts/transfers/{key}`) and completes or writes off. Authoritative because `AccountTransferLog` is written in the *same transaction* as the balance change — absence is conclusive.
  - It **resolves rather than replays** — re-POSTing `execute-transfer` is idempotent-safe but would *execute* a transfer whose original attempt may never have arrived, long after the user gave up, against changed balances.
  - Asymmetric timing: "it happened" is acted on after a 60s grace period; "it did not happen" must persist past a 900s write-off window. Declaring a live transfer failed is the one unrecoverable move.
  - **The unique constraint on `idempotency_key` fixed a live bug**: account-service deduped the *money*, but a resubmitted key still wrote a second ledger row and outbox event, double-counting the transfer and double-notifying. A duplicate submit now returns the original outcome.

- **Deposits are ledgered, and run as the same saga** — money entering is a cross-service dual write (credit in `banking_account`, ledger row in `banking_payment`).
  - It used to be neither: `AccountService.deposit` mutated a balance and published only a cache-eviction event — no ledger row, no Kafka event, no notification. Deposits never appeared in history, and the reconciliation invariant `sum(debits) == sum(credits) == sum(balance deltas)` reads every unledgered deposit as money from nothing.
  - So the entry point **moved to payment-service** (`POST /api/payments/deposit`, ADMIN-only): `openDepositIntent` → `executeDeposit` → `settleCompleted`, sharing `PaymentService.executeAndSettle` so the 4xx-vs-indeterminate classification exists in one place.
  - **Deposits reuse `account_transfer_log`** rather than a new table — it's what makes "did money move for this key?" answerable (commits with the balance change), so `TransferRecoveryPoller` covers deposits with no second lookup path. The table's name is now narrower than its contents; renaming it is cosmetic churn.
  - **The `from_*` columns stay null** on both `transactions` and `account_transfer_log` — filling them with the destination makes a deposit look like a self-transfer and hides the money entering the system from the reconciliation pass this exists to enable.
  - **The credit is an atomic `addBalance`**, not read-modify-write — `setBalance(getBalance().add(amount))` with no row lock or `@Version` loses one of two concurrent deposits. One-row update, so unlike the transfer it needs no ordered locking.
  - `TransferLedger.writeOutboxEvent` publishes **`tx.getType()`**, not a hardcoded `TRANSFER` — a deposit published as a transfer tells notification-service to notify a nonexistent sender.
  - `PaymentEventConsumer` branches on **whether the event has a sender** (`fromUserId != null`) rather than string-matching the type. Reading `fromUserId().toString()` unguarded is an NPE that would now dead-letter a good event.

- **Reconciliation is an independent auditor, and only an auditor** — `reconciliation-service` sweeps every account on a schedule and compares what account-service holds against what the ledger says it should.
  - **It detects; it never corrects.** A reconciler that adjusts balances destroys money whenever its own logic is wrong — and that logic is the one thing in the system with no second opinion.
  - **It reads both sides from their owners' storage, not from Kafka.** A reconciler rebuilt from the same events the ledger consumes shares a failure mode with what it audits: a lost event is invisible to both. Independence is the entire value — also why it's a separate service.
  - **The sweep is a singleton across replicas, enforced by ShedLock** (`@SchedulerLock` on `ReconciliationSweeper.sweep`, `shedlock` table in `V3__shedlock.sql`). Concurrency here is not merely duplicated work: `resolveUnseen` clears every unresolved finding absent from *this* sweep's observation set, and one replica's set never contains the other's — so the replicas ping-pong findings between `SUSPECTED` and resolved, `times_seen` never reaches 2, nothing is promoted to `CONFIRMED` (the only thing the alerting gauge counts), and both keep the liveness marker fresh: a permanently blind auditor reporting itself healthy. This is the one service that would be scaled without a second thought.
    - **The lock lives in Postgres, not Redis** — it must share a fate with the register it protects; a Redis outage must not admit a second sweep against a healthy findings table.
    - **`lockAtLeastFor` (4m) is load-bearing, not padding.** The second sighting must be *independent*, meaning separated in time — that is what lets money in flight during one pass be settled by the next. With only `lockAtMostFor`, two sweeps seconds apart confirm precisely the in-flight artefacts the debounce exists to discard. Stays below `sweep-interval-ms` so a lone replica never blocks its own next run.
    - **`lockAtMostFor` (10m)** is the backstop for a replica that dies mid-sweep — exceeds the slowest realistic pass, well inside the 1800s staleness alert.
  - **Three invariants.** `BALANCE_MISMATCH` — an account holds exactly what its COMPLETED ledger rows sum to (accounts start at zero, so an unledgered deposit is a balance the ledger can't explain). `MOVEMENT_NOT_LEDGERED` / `LEDGER_WITHOUT_MOVEMENT` — the two services agree on which movements happened; the first also catches a transfer written off `FAILED` after its money moved, which is why status travels with the key rather than being filtered server-side. `STUCK_INTENT` — an intent `PENDING` past the write-off window, meaning the recovery poller has stopped (otherwise silent).
  - **A finding must be seen twice before it is believed** (`SUSPECTED` → `CONFIRMED`). There is no consistent cut across two databases — balances and the ledger are read at different instants, so a transfer committing mid-sweep makes an account look wrong when nothing is — so a discrepancy has to survive a second independent sweep. The alerting gauge counts `CONFIRMED` only; paging on `SUSPECTED` would train the pager-holder to ignore it.
  - **`PENDING` ledger rows are excluded from the balance sum (C1), and deliberately *not* from the key comparison (C2).** C1 must exclude them (their money may or may not have moved). But `/keys` returns every status on purpose (so a bad write-off is distinguishable from a lost settlement) and the sweeper flags anything not `COMPLETED`, **so a transfer whose movement committed but whose ledger row hasn't settled is reported `MOVEMENT_NOT_LEDGERED`** — a busy system produces routine `SUSPECTED` findings of that type that clear next pass. Expected, not an incident — which makes the two-sightings rule **load-bearing here rather than belt-and-braces**, and is why `lockAtLeastFor` keeps consecutive sweeps far apart.
  - Findings are keyed `(type, subject_id)` — one discrepancy seen twenty times is one row with `times_seen = 20`, so alerts scale with how much is wrong, not sweep frequency. A recurrence after resolution resets to `SUSPECTED`.
  - **A sweep that cannot complete records nothing** — a partial pass would resolve findings merely because the run never got far enough to see them, turning a dependency outage into a clean bill of health.
  - **The auditor is itself audited, by a liveness marker.** Both findings gauges are read from the register, so they hold last-known values when sweeps stop — a clean register plus a dead sweeper reads exactly like a healthy system, and *every* failure mode produces that state. So `FindingRecorder` writes `reconciliation_sweep_state` **in the findings transaction** (a separate commit could mark a sweep successful while its findings rolled back), and `reconciliation_last_successful_sweep_timestamp_seconds` is alerted for staleness (`time() - <gauge> > 1800`).
    - Persisted, not a field — a crash-looping service would report itself freshly started on every restart and never look stale.
    - Not seeded — absence reads as epoch 0, so *never audited* alerts like *stopped auditing*.
    - **`up == 0` is a separate alert** — if the process is down the gauge isn't ingested; after 5 min Prometheus marks the series stale, `time() - <gauge>` returns no data, and the staleness rule silently stops.
    - Rules live in `observability/alerts.yml` (mounted into Prometheus, `promtool check rules`). **No Alertmanager** — nothing is routed yet. `ReconciliationConfirmedDiscrepancy` carries no `for:` on purpose.
  - **`max-keys` (200k) is a cliff, not a slope.** `forEachPage` throws past `maxKeys / page-size + 2` pages rather than truncating, so crossing it aborts every subsequent sweep — the service stops auditing permanently. C1 must stay a full comparison (skipping quiet accounts is blind by construction to an unledgered deposit). When the ceiling warning or staleness alert fires, the fix is a **C2 watermark** — safe because `account_transfer_log` is insert-only and a `COMPLETED` transaction is terminal. Traps: watermark on *settlement* time not `created_at`; keep `FAILED` rows in the window past the write-off horizon (a late movement flips the verdict); a watermark may skip *scanning for new* discrepancies but never skip *re-verifying open* ones (else `resolveUnseen` mass-resolves and nothing is promoted to `CONFIRMED`).
  - ⚠️ **Anything that is not JPA does not get `hibernate.default_schema`** — it resolves against the connection's `search_path`, which in production comes from `?currentSchema=` in the JDBC URL. Bitten twice: (1) ShedLock issues plain JDBC, so `JdbcTemplateLockProvider` gets a **schema-qualified** table name (from `spring.flyway.default-schema` so it can't drift from the migration); with a bare name `SweepLockTest` fails with `relation "shedlock" does not exist`. (2) The ledger aggregate is native SQL (`UNION ALL` — one row feeds two accounts, no single `GROUP BY` yields both sides); a Testcontainers test using `@ServiceConnection` replaces the URL and the query silently looks in `public`, so `LedgerNetQueryTest` sets `spring.datasource.hikari.connection-init-sql` to restore it.

- **Closing an account requires a zero balance** — `closeAccount` used to close unconditionally, orphaning leftover money on a row nothing can reach (balance queries are predicated on `status = 'ACTIVE'`) with no ledger entry recording it leaving; reconciliation would rightly report it destroyed. The check is a conditional `UPDATE … WHERE balance = 0` (`closeIfEmpty`), not a read-then-write, so a credit can't land in between. Emptying an account is a ledgered transfer; closing is not a way to move money.

- **Transactional Outbox**: payment-service writes `OutboxEvent` rows in the settlement transaction; `OutboxPoller` publishes to Kafka asynchronously — at-least-once, **bounded by `outbox.max-retries`**.
  - **After 5 attempts the row is `FAILED`, terminal.** `findPendingWithLock` selects `PENDING` only — nothing retries or replays it, no requeue endpoint. Backoff is `2^attempts * 15s` → 30, 60, 120, 240, so **~450s of Kafka being unavailable permanently abandons every pending event** — well within one bad node drain on a single-broker StatefulSet.
  - **Reconciliation cannot see this, and is right not to** — the money moved, the ledger says `COMPLETED`, the balance matches; only the notification is lost. Every reconciler invariant is about money, so delivery failures get their own metrics.
  - **`payment_outbox_abandoned_events`** (`payment-service/.../config/MetricsConfig`) counts those rows, read from the DB on every scrape rather than accumulated in memory. `V4__outbox_failed_index.sql` is a partial index on `status = 'FAILED'` so the count is index-only.
  - **`notification_events_dead_lettered_total`** (`KafkaConsumerConfig`) counts the other end, incremented **after** `DeadLetterPublishingRecoverer.accept` returns: it defaults to `failIfSendResultIsError = true` and blocks in `verifySendResult`, so a return means the broker accepted the record. Counting first repeats the outbox's discarded-`send()`-future mistake.
  - Alerted in `observability/alerts.yml` `event-delivery` group: `OutboxEventsAbandoned` (no `for:` — terminal), `PaymentEventsDeadLettered` (`increase(...[1h])` — a depth gauge would keep firing post-incident), `EventDeliveryServiceDown` (an unscraped process makes both rules above return no data).
  - ⚠️ **These move *after* the loss, not before.** Nothing fires during the 450s backoff. An early warning would need the age of the oldest `PENDING` row vs the abandonment budget — not built.
  - **The broker ack is awaited** (`KafkaEventPublisher`, `kafka.publish.ack-timeout-ms`, default 35s). `KafkaTemplate.send` returns once the record is buffered, *not* when a broker accepts it — so discarding the future marked rows `PUBLISHED` for undelivered messages. Blocking keeps the ack inside the poller's transaction: failure throws, the row stays `PENDING`, backoff retries.
  - **No request thread calls a poll** — the ack is blocking and `findPendingWithLock` is `LIMIT 10`, so one pass is up to ten times `delivery.timeout.ms`. The early-publish trigger used to be a `@TransactionalEventListener(AFTER_COMMIT)` **on `OutboxPoller` itself**, run *synchronously on the committing thread* — for a transfer, the thread serving `POST /api/payments/transfer`, which could hold its HTTP response for minutes (api-gateway sets no `response-timeout`).
    - `OutboxTrigger` now owns that listener and hands the poll to a **single-threaded executor, queue of one, discarding the rest** — safe because the trigger means "publish whatever is `PENDING`", and the 15s `@Scheduled` poll is the durable path. `CallerRunsPolicy` would hand the work back to the protected thread.
    - **Separate bean** (like `TransferLedger`): `executor.execute(this::pollAndPublish)` from inside `OutboxPoller` runs with **no transaction**, so `FOR UPDATE SKIP LOCKED` claims nothing.
    - ⚠️ The executor is built in a constructor, **not a `@Bean`**. Boot's `TaskExecutorConfiguration` is `@ConditionalOnMissingBean(Executor.class)`, so publishing any `Executor` bean deletes `applicationTaskExecutor` and hands Spring MVC async / `@Async` a single thread that blocks 30s per Kafka ack.
  - **`spring.task.scheduling.pool.size` is 2, load-bearing.** Boot's default is 1; payment-service has two `@Scheduled` methods (`OutboxPoller.pollAndPublish` 15s, `TransferRecoveryPoller.settleStrandedTransfers` 30s), and on one thread a blocked outbox poll stopped the recovery poller entirely. Raise it with the number of `@Scheduled` methods, not with load.
  - The timeout sits **above** the producer's `delivery.timeout.ms` (30s) so what surfaces is the producer's own typed error; lowering it below 30s would abandon sends the producer may still complete → duplicate deliveries.
  - `ExecutionException` is **unwrapped** before rethrowing — `record-exceptions` is a whitelist of Kafka types matching no wrapper, so rethrowing as-is scores every failed publish as a *success*.
  - **No `@Retry` on the publish** — the producer already exhausted its internal retries over `delivery.timeout.ms`, so an outer retry is a fresh send that turns 30s into 90s while the poller holds its row locks. The outbox is the durable retry layer.

- **The saga is tested across a real process boundary** (`integration-tests`, `*IT`, failsafe). Unit tests mock `AccountServiceClient`/`TransferLedger` and verify the poller's *decision table*; three claims need two processes — a crash between account-service's commit and payment-service's settlement leaves recoverable state, the 4xx-vs-indeterminate split holds against a runtime Feign failure, and `/internal/accounts/transfers/{key}` is authoritative because `AccountTransferLog` commits with the balance change.
  - Both services run as containers **from the jars the reactor just packaged**, into `eclipse-temurin:21-jre-jammy` (building via their Dockerfiles would run a full Maven build per service — trade: doesn't test the Dockerfiles). **One Postgres, two schemas** — what compose and k8s run; what matters is two owners with independent transactions.
  - **Toxiproxy sits between payment-service and account-service** — a DOWNSTREAM `timeout` toxin lets the request commit and never delivers the response (identical to a crash). `CrashMidSagaIT` then SIGKILLs payment-service and restarts *the same container* (`GenericContainer.stop()` would discard the state under test).
  - **No Kafka is started** — settlement writes the outbox row in the ledger transaction, so no assertion depends on publication; the outbox poller failing in the background is the R7 fix being exercised.
  - ⚠️ **The circuit breaker is per-process and outlives a test** — one test's arranged failures short-circuit the next test's first transfer, and only a *successful call* closes a half-open Resilience4j breaker, so the `@BeforeEach` gate drives real traffic.
  - Every service Dockerfile must `COPY integration-tests/pom.xml` — Maven parses every `<modules>` entry before `-pl` filters, so an absent pom fails the reactor.

- **`idempotencyKey` is required on a transfer** (`TransferRequest`, `@NotNull` + a service-side guard). It used to fall back to a server-generated UUID, which is the opposite of idempotent: a fresh key per attempt means a client retrying after a timeout arrives with a key account-service has never seen and moves the money twice. The two retry layers differ — Resilience4j's `@Retry` on `AccountServiceClient` is safe because the key is generated before the call and reused, while any client-visible retry is not.

- **The account-service retry fires on a predicate, not a class whitelist** (`ConnectFailurePredicate`, wired via `retry-exception-predicate`).
  - It used to name `java.net.ConnectException`, which **Feign never throws**: `FeignException.errorExecuting` wraps every `IOException` in `feign.RetryableException`, and Resilience4j matches with `isAssignableFrom` without unwrapping causes. So `@Retry` on `executeTransfer` retried *nothing*, and a rolling restart of account-service produced a single attempt, indeterminate classification, and a stranded `PENDING` intent.
  - **Whitelisting `feign.RetryableException` would be wrong** — it also wraps `SocketTimeoutException`, and a read timeout means the request was sent and may have been processed. The split is by **what the failure proves**: retry only when the connection was never established (`ConnectException`, `UnknownHostException`, `NoRouteToHostException`). `SocketTimeoutException` is excluded even though a *connect* timeout also throws it — only message text distinguishes the two, so a connect timeout is treated as indeterminate. The circuit breaker on this client was never affected (it lists `feign.FeignException`, and `RetryableException extends FeignException`).
  - **The client now carries explicit timeouts** (`spring.cloud.openfeign.client.config.account-service`, 2s connect / 15s read; Feign's defaults are 10s/60s). The read timeout is the judgment call: a transfer completing at 20s now surfaces as indeterminate and is settled by the recovery poller — safe direction, inside the 60s grace period, but a genuinely slow account-service produces `PENDING` intents rather than slow successes.
  - ⚠️ The prefix is `spring.cloud.openfeign.client.config`, **not** the pre-4.x `feign.client.config` — a block under the old key binds to nothing, raises no error, and leaves the 60s default in place. `AccountClientResilienceWiringTest` asserts the bound values.

- **Resilience4j** circuit breakers and retries on Redis, Kafka, and inter-service Feign calls in every service.

- **Account read cache (Redis)** — `account-service` caches `accounts::<accountId>` and `accountsByUser::<userId>` (`AccountReader`), short TTL (`ACCOUNT_CACHE_TTL`, default 60s) as a backstop only; eviction is the correctness mechanism.
  - **Authorization is never cached** — `AccountReader` does no ownership check; `AccountService.findOwnedAccount` re-checks `userId` on every call. Hence the cache stores `CachedAccount` (carries `userId`), not `AccountResponse`.
  - **Eviction is event-driven, deferred to after commit.** Mutations publish `AccountsChangedEvent`; `AccountCacheEvictor` consumes via `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`. Evicting inline lets a concurrent reader re-populate with the pre-commit balance. `fallbackExecution = true` is required or evictions outside a transaction never fire.
  - **A transfer evicts both sides** — the destination account usually belongs to a different user.
  - Redis failures **fail open**: reads fall through to Postgres.
  - **Stampede protection** — `@Cacheable(sync = true)` backed by `StripedLockRedisCache`, locking per key (256 stripes) instead of Spring Data Redis' single cache-wide lock. Re-implements fail-open internally because `CacheAspectSupport.executeSynchronized` bypasses the configured `CacheErrorHandler`.
  - **Fast degradation** — the cache shares the `redis` Resilience4j breaker with `TokenBlacklistService`; a failed probe skips the re-check under the lock and the write. Redis paused: ~4.2s per request before, ~0.23s once the breaker opens.

> ⚠️ `record-exceptions` on a Resilience4j breaker is a **whitelist** — anything unlisted counts as a *success*. Spring Data translates Lettuce command timeouts into `org.springframework.dao.QueryTimeoutException`, which was missing from the `redis` instance, so the breaker never opened on the most common Redis failure. Fixed in all four services with a `redis` breaker (account, auth, payment, notification). Recorded but **not** retried — the command already waited out its full timeout.
>
> ⚠️ `api-gateway` has a `redis` breaker on `GatewayTokenBlacklistService.isBlacklisted`, mirroring the four downstream services. Reactive specifics:
> - **`resilience4j-reactor` is an explicit dependency** — `resilience4j-spring-boot3` doesn't pull it in, and without it the aspect can't wrap a `Mono` (silently does nothing).
> - **The old `onErrorReturn(false)` moved into the fallback** — recovering in the method body hands the aspect a successful `Mono`, so the breaker records every outage as success and never opens. `CallNotPermittedException` arrives in the fallback once open.
> - **An open breaker still invokes the method** — the aspect wraps the returned `Mono`, so short-circuiting happens at *subscription*. `GatewayTokenBlacklistServiceTest` counts subscriptions, not `hasKey` calls.
>
> No `retry` instance for the gateway — its only Redis call is a read on every authenticated request's hot path. `ignore-exceptions: AppException` is absent too — the gateway doesn't depend on `banking-common` and Resilience4j fails at startup on a class it can't load.

- **Consumer inbox + dead-letter topic (notification-service)** — the outbox is at-least-once, so redelivery is expected.
  - `PaymentEventConsumer` records `processed_events(event_id)` (the `PaymentEvent.transactionId`) **in the same transaction** as the notifications. A redelivery finds the row and returns. A separate marker commit would let a later failure skip an event whose notifications were never written.
  - **The catch-all had to go first.** The listener used to `catch (Exception e) { log.error(...) }` and return, committing the offset on failure and making `DefaultErrorHandler` **unreachable** — retries never ran, nothing reached a DLT.
  - **`ErrorHandlingDeserializer` wraps `JsonDeserializer`** — without it a malformed payload throws inside the container *before* the listener, retrying the poison message forever and blocking the partition.
  - **The DLT template delegates by type** (`DelegatingByTypeSerializer`): a handler failure republishes the deserialized `PaymentEvent` as JSON; a *deserialization* failure has no object and republishes the original `byte[]`. A plain `JsonSerializer` would re-encode those bytes as base64.
  - WebSocket pushes are deferred to after commit and swallow their failures — the notification is already durable, and a dropped frame escaping would retry and dead-letter a good event.
  - Anything in `payment.events.DLT` means a user wasn't told about a transfer that happened — alerted, see `notification_events_dead_lettered_total`.
  - `processed_events` grows one row per transfer; pruning is safe once rows are older than the broker's retention.

- **WebSocket push is relayed through Redis pub/sub, not the local broker directly** — `enableSimpleBroker` keeps its user→session registry in the pod's heap, so with >1 `notification-service` replica a push from the pod holding the Kafka partition reaches a user only if they're *also* connected to that pod. `convertAndSendToUser` doesn't fail when there's no local session, so this was silent.
  - `WebSocketRelay.publish` (Redis `PUBLISH` on `ws-relay`, guarded by the same `redis` breaker/retry as `TokenBlacklistService`) replaces every direct `SimpMessagingTemplate` call. Every pod subscribes via `WebSocketRelayListener` — including the publisher (Redis pub/sub has no "not myself" filter) — and attempts local delivery; only the pod holding the session is where it's not a no-op. Also fixes the Kafka side for free (one partition, one consumer no longer matters).
  - Deliberately not a real STOMP broker relay (RabbitMQ/ActiveMQ) — Redis is already a dependency everywhere.

- **`User` implements `UserDetails`** directly — no adapter wrapper.

- **`GlobalExceptionHandler`** centralizes error responses: `AppException` → typed HTTP status, `BadCredentialsException` → 401, `MethodArgumentNotValidException` → field-keyed map, `CallNotPermittedException` → 503, and anything else → 500 with a generic message.
  - **An unhandled exception used to reach the client as an empty 403** — Spring Security applies its filter chain to the **ERROR dispatch** too, so a failure forwarded to `/error` hit `.anyRequest().authenticated()`. A DB outage was indistinguishable from an auth failure (and the frontend logs the user out on 403). Fixed with `dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` in all five services' `SecurityConfig`, **preferred over permitting `/error` as a path** (which would expose it to a direct external GET). `ErrorDispatchSecurityTest` covers both halves.
  - **The catch-all is safe only because the handler extends `ResponseEntityExceptionHandler`** — `@ExceptionHandler` resolution picks the most specific match, so Spring's own MVC exceptions keep framework statuses (malformed body 400, unmatched path 404). Listing those by hand would be a blacklist. `HttpMessageNotReadableException` does **not** implement `ErrorResponse`, so an `instanceof ErrorResponse` guard is not a substitute.
  - **`AccessDeniedException` and `AuthenticationException` are rethrown, not answered** — `ExceptionTranslationFilter` decides 401 (anonymous) vs 403 (known, forbidden); handling them here collapses that into a 500. Rethrow the *original* exception to decline.
  - The catch-all answers most failures before the forward, so the ERROR-dispatch permit now matters only for failures thrown *outside* the handler (a filter, a container `sendError`) — hence its own test.

- **Flyway migrations** — schema is managed by Flyway per service (`db/migration`); `spring.jpa.hibernate.ddl-auto=validate` so Hibernate only checks the schema against the entities, it does not mutate it.

- **List endpoints return `Slice`, not `Page`** — a `Page` issues a second `COUNT` on every fetch and no consumer uses the totals (frontend reads only `.content`); `Slice` fetches `size + 1` rows. Exception: `/internal/payments/transactions/by-account/{id}` stays a `Page` because Spring Cloud OpenFeign ships a `PageJacksonModule` but no `Slice` equivalent.

- **Transaction list pagination is a known future bottleneck.** `TransactionRepository.findByUserId` filters `from_user_id = ? OR to_user_id = ?` ordered by `created_at DESC`. No index serves both halves — Postgres `BitmapOr`s the two indexes, walks the heap in *physical page order*, discards index ordering, must sort, and to sort must read **every** row the user was ever party to to return 20.
  - Composite `(user_id, created_at DESC)` indexes **do not fix this** — measured identical plan, marginally worse (larger, more write amplification). A `V3` adding them was written, measured, dropped. Do not re-add them on their own.
  - The fix is splitting the `OR` into two `UNION ALL` branches, each index-ordered, merged by `Merge Append` — 28 buffers vs 113, no sort. Traps: self-transfers set `from_user_id = to_user_id` and match both branches (exclude from the second); the exclusion must use `IS DISTINCT FROM`, not `!=`, because `from_user_id` is nullable and `NULL != ?` is `NULL`, dropping every deposit.
  - Preferred destination is **keyset pagination** (`WHERE created_at < :cursor`), not `UNION ALL` over offsets. Returning `Slice` already removed page totals, so the cursor migration is unblocked.
  - `notification-service`'s `(user_id, created_at DESC)` index **is** a real win and is retained — no `OR`, so the index serves filter and ordering together (`Index Scan`, no sort, 24 buffers).

- **Kafka runs in KRaft mode** (no Zookeeper). External port `9094`; internal broker port `9092`.

## Infrastructure Ports

| Service | Host port |
|---|---|
| API Gateway | 8080 |
| auth-service | 8081 |
| account-service | 8082 |
| payment-service | 8083 |
| notification-service | 8084 |
| reconciliation-service | 8085 |
| PostgreSQL | 5432 |
| Redis | 6379 |
| Kafka (external) | 9094 |
| RedisInsight UI | 9001 |
| Kafka UI | 9002 |
| Prometheus | 9090 |
| Grafana | 3000 |
| Tempo (gRPC) | 4317 |
| Tempo (HTTP) | 4318 |
| Tempo (query) | 3200 |
