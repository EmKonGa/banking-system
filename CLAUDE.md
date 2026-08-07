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

> The Maven wrapper pins **3.9.9** (`.mvn/wrapper/maven-wrapper.properties`, script-only — there is
> no wrapper jar, because `.gitignore` excludes `*.jar`). Use `./mvnw` rather than a system `mvn` so
> local builds match CI. Some tests use **Testcontainers** and need a running Docker daemon.

**Run a single test class:**
```bash
./mvnw test -Dtest=ClassName -pl <module-name>
```

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

### Service Communication

- **Sync (Feign):** `account-service` → `payment-service` (fetch transactions); `payment-service` → `account-service` (execute a transfer or a deposit, and `GET /internal/accounts/transfers/{idempotencyKey}` to ask whether one committed — used by the saga's recovery poller for both)
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

Stateless JWT-based auth with two-token strategy and banking-grade session controls:

- **Access token** (5 min, `JWT_ACCESS_EXPIRATION_MS`): signed HS256 JWT; carries `jti` (UUID) for blacklisting and `sub` (email).
- **Refresh token** (15 min, `JWT_REFRESH_EXPIRATION_MS`): opaque UUID stored in Redis under `refresh:<token>` → `<userId>|<sessionStartMillis>`. Because rotation re-issues on every refresh, this TTL acts as an **idle timeout**.

JWT validation happens **at the gateway** (`JwtAuthGatewayFilter`). The gateway checks the Redis blacklist on every request and forwards the JWT to downstream services. Individual services also validate JWTs for their own security config.

**Logout** blacklists the access token's `jti` in Redis (`blacklist:<jti>`) for its remaining TTL, and deletes the refresh token key.

**Refresh rotation**: old refresh token is deleted and a new one issued atomically in `RefreshTokenService.rotate()`, carrying the original `sessionStart` forward.

**Absolute session cap** (`JWT_MAX_SESSION_MS`, default 8h; `0` disables): `AuthService.refresh` rejects a refresh once the session is older than the cap, even for a continuously active user — rotation can't reset it because `sessionStart` is preserved.

**Activity-aware refresh (frontend)**: `AuthService` (Angular) refreshes on a timer only while the user is active; after the idle window it shows a countdown "stay logged in?" modal (`SessionTimeoutDialog`) before logging out. Keep the frontend `IDLE_TIMEOUT_MS` in step with `JWT_REFRESH_EXPIRATION_MS`.

## Key Design Decisions

- **Shared JWT secret**: all services share the same `JWT_SECRET` env var so any service can verify tokens independently without calling auth-service.
- **Transfer saga (durable intent + recovery)** — moving money is a call to *another service that commits in its own database*, while the ledger row commits in `banking_payment`. No single `@Transactional` can span that, and wrapping `PaymentService.transfer` in one only created the illusion that it did: a crash after account-service committed rolled the local side back and left **money moved with no record anywhere in payment-service**. So the steps commit separately, intent first:
  1. `TransferLedger.openIntent` commits a `PENDING` row carrying the idempotency key — *before* the money is asked to move.
  2. `AccountServiceClient.executeTransfer`.
  3. `TransferLedger.settleCompleted` (writes the outbox event) or `settleFailed`.
  - **Failure classification is the crux.** A `FeignException` with a **4xx** is account-service's considered refusal — no money moved, so the intent is settled `FAILED` at once. **Anything else** (5xx, timeout, open breaker) means the outcome is *unknown*, so the intent is deliberately left `PENDING`. Guessing `FAILED` there would write off a transfer whose money actually moved.
  - `TransferLedger` is a separate bean on purpose: `@Transactional` only applies through a proxy, so keeping these methods on `PaymentService` and calling them from its own `transfer` would silently run them in one transaction and restore the original bug. They are `REQUIRES_NEW` so settlement also commits independently when called from the recovery poller's batch transaction.
  - **`TransferRecoveryPoller`** settles strays: it asks account-service what the idempotency key did (`GET /internal/accounts/transfers/{key}`) and completes or writes off. The answer is authoritative because `AccountTransferLog` is written in the *same transaction* as the balance change — absence is conclusive.
  - It **resolves rather than replays**. Re-POSTing `execute-transfer` would be safe from a duplication standpoint (it is idempotent), but it would *execute* a transfer whose original attempt may never have arrived — possibly long after the user gave up, against balances that have since changed. Recovery establishes what happened; it does not make something new happen.
  - Asymmetric timing, deliberately: "it happened" is acted on after a 60s grace period, while "it did not happen" must persist past a 900s write-off window. Declaring a live transfer failed is the one unrecoverable move.
  - **The unique constraint on `idempotency_key` fixed a live bug**: account-service deduped the *money*, but a resubmitted key still wrote a second ledger row and a second outbox event, double-counting the transfer in history and notifying the user twice. A duplicate submit now returns the original outcome.
- **Deposits are ledgered, and run as the same saga** — money entering the system is a cross-service dual write for exactly the reason a transfer is: the credit commits in `banking_account`, the ledger row commits in `banking_payment`.
  - It used to be neither. `AccountService.deposit` mutated a balance and published only a cache-eviction event — **no ledger row, no Kafka event, no notification**. Deposits never appeared in transaction history, and the planned Reconciliation Service could not be built at all: its invariant `sum(debits) == sum(credits) == sum(balance deltas)` reads every unledgered deposit as money created from nothing.
  - So the entry point **moved to payment-service** (`POST /api/payments/deposit`, ADMIN-only), because the ledger is payment-service's. `openDepositIntent` → `executeDeposit` → `settleCompleted`, sharing `PaymentService.executeAndSettle` with the transfer so the 4xx-vs-indeterminate classification exists in exactly one place.
  - **Deposits reuse `account_transfer_log`** rather than getting their own. That log is what makes "did money move for this key?" answerable — it commits with the balance change, so absence is conclusive — and sharing it means `TransferRecoveryPoller` covers deposits without a second lookup path or a second write-off rule. The table's name is now narrower than its contents; renaming it would churn the entity, the repository and the `/internal/accounts/transfers/{key}` endpoint for cosmetics.
  - **The `from_*` columns stay null** on both sides (`transactions` and `account_transfer_log`). Filling them with the destination would make a deposit look like a self-transfer, which balances — and would hide the money entering the system from the very reconciliation pass this exists to enable.
  - **The credit is an atomic `addBalance`**, not the old read-modify-write. `account.setBalance(account.getBalance().add(amount))` with no row lock and no `@Version` loses one of two concurrent deposits outright — the balance ends up short while both ledger rows survive. One-row update, so unlike the transfer it needs no ordered locking.
  - `TransferLedger.writeOutboxEvent` publishes **`tx.getType()`**, not a hardcoded `TRANSFER`. It was the constant before; a deposit published as a transfer tells notification-service to notify a sender that does not exist.
  - `PaymentEventConsumer` branches on **whether the event has a sender** (`fromUserId != null`) rather than string-matching the type: a deposit notifies only the recipient and pushes only their balance. Reading `fromUserId().toString()` unguarded is an NPE, and now that failures propagate that NPE would dead-letter a perfectly good event.
- **Reconciliation is an independent auditor, and only an auditor** — `reconciliation-service` sweeps every account on a schedule and compares what account-service holds against what the ledger says it should.
  - **It detects; it never corrects.** A reconciler that adjusts balances destroys money whenever its own logic is wrong — and its logic is the one thing in the system with no second opinion. Same stance as `TransferRecoveryPoller` resolving rather than replaying.
  - **It reads both sides from their owners' storage, not from Kafka.** A reconciler rebuilt from the same events the ledger consumes shares a failure mode with the thing it audits: a lost event is invisible to both, and the two agree perfectly about a system that has lost money. Independence is the entire value, which is also why it is a separate service rather than a poller inside payment-service.
  - **The sweep is a singleton across replicas, enforced by ShedLock** (`@SchedulerLock` on `ReconciliationSweeper.sweep`, `shedlock` table in `V3__shedlock.sql`). The damage from concurrency here is not duplicated work: `resolveUnseen` clears every unresolved finding absent from *this* sweep's observation set, and one replica's set never contains the other's. So A resolves what B just recorded, B sees it again and resets it to `SUSPECTED` with `times_seen = 1` — nothing ever reaches 2, nothing is ever promoted to `CONFIRMED`, and `CONFIRMED` is the only thing the alerting gauge counts. Both replicas keep the liveness marker fresh throughout, so the staleness alert stays quiet too: a permanently blind auditor reporting itself healthy. Every other service in the stack is replica-safe, which is exactly what makes this the one that would be scaled without a second thought.
    - **The lock lives in Postgres, not Redis**, so it shares a fate with the register it protects — a Redis outage must not be able to admit a second sweep against a perfectly healthy findings table.
    - **`lockAtLeastFor` (4m) is load-bearing, not padding.** The two-sightings rule needs the second sighting to be *independent*, which means separated in time — that is what lets money in flight during one pass be settled by the next. With only `lockAtMostFor`, a replica firing just after the holder released could sweep seconds later, and two sightings seconds apart confirm precisely the in-flight artefacts the debounce exists to discard. It stays below `sweep-interval-ms` so a lone replica never blocks its own next run.
    - **`lockAtMostFor` (10m)** is the backstop for a replica that dies mid-sweep — nothing else can sweep until it expires, so it exceeds the slowest realistic pass while staying well inside the 1800s staleness alert. A crash costs a sweep or two rather than a page.
  - **Three invariants.** `BALANCE_MISMATCH` — an account holds exactly what its COMPLETED ledger rows sum to (accounts start at zero, so an unledgered deposit shows up as a balance the ledger cannot explain). `MOVEMENT_NOT_LEDGERED` / `LEDGER_WITHOUT_MOVEMENT` — the two services agree on which movements happened; the first also catches a transfer written off `FAILED` after its money moved, which is why status travels with the key rather than being filtered server-side. `STUCK_INTENT` — an intent still `PENDING` past the write-off window, meaning the recovery poller itself has stopped working, which is otherwise silent.
  - **A finding must be seen twice before it is believed** (`SUSPECTED` → `CONFIRMED`). There is no consistent cut across two databases: balances and the ledger are read at different instants, so a transfer committing mid-sweep makes an account look wrong when nothing is. Rather than fake a distributed snapshot, a discrepancy has to survive a second independent sweep — artefacts of in-flight money do not, real breakage does. The alerting gauge counts `CONFIRMED` only; paging on `SUSPECTED` would train whoever carries the pager to ignore it.
  - **`PENDING` ledger rows are excluded from the balance sum (C1), and deliberately *not* from the key comparison (C2).** In C1 they must be: their money may or may not have moved — that is what the state means — so summing them would put the reconciler in permanent disagreement with reality on every transfer in flight. But `/keys` returns every status on purpose (status has to travel with the key so a bad write-off is distinguishable from a lost settlement), and the sweeper flags anything not `COMPLETED`. **So a transfer whose movement has committed but whose ledger row has not yet settled is reported `MOVEMENT_NOT_LEDGERED`** — a busy, healthy system produces routine `SUSPECTED` findings of that type which clear on the next pass. That is expected, not an incident.
    - Which makes the two-sightings rule **load-bearing here rather than belt-and-braces**: it is the only thing separating an in-flight transfer from a real one. Worth knowing before anyone proposes alerting on `SUSPECTED`, and it is also why `lockAtLeastFor` keeps consecutive sweeps far apart — two sightings seconds apart would confirm exactly these.
  - Findings are keyed `(type, subject_id)`, so one discrepancy seen twenty times is one row with `times_seen = 20`. Otherwise an alert on open findings grows with sweep frequency rather than with how much is wrong. A recurrence after resolution resets to `SUSPECTED` rather than inheriting the old count.
  - **A sweep that cannot complete records nothing.** Recording a partial pass would resolve findings merely because the run never got far enough to see them — turning a dependency outage into a clean bill of health.
  - **The auditor is itself audited, by a liveness marker.** Both findings gauges are read from the register, so they cannot fall to a bad value on their own — when sweeps stop, they hold whatever was last true, and a clean register plus a dead sweeper reads exactly like a healthy system. *Every* failure mode produces that state: the key-set ceiling, account-service unreachable, a bad deploy, a bug in a sweep. So `FindingRecorder` writes `reconciliation_sweep_state` **in the same transaction as the findings** (a separate commit could mark a sweep successful while its findings rolled back), and `reconciliation_last_successful_sweep_timestamp_seconds` is alerted on for staleness: `time() - <gauge> > 1800`. This is `STUCK_INTENT` turned back on the reconciler — the same reasoning that makes a silently-dead `TransferRecoveryPoller` worth detecting.
    - Persisted, not a field: a service crash-looping before it can finish a sweep would report itself freshly started on every restart and never look stale, hiding the exact outage being watched for.
    - Not seeded — absence reads as epoch 0, so *never audited* alerts like *stopped auditing*. Both mean nothing is being checked.
    - **`up == 0` is a separate alert, not redundant with staleness.** If the process is down the gauge is not ingested at all; Prometheus serves the last sample for 5 minutes and then marks the series stale, at which point `time() - <gauge>` returns no data and the staleness rule *silently stops firing*. A hard-down auditor would otherwise be the quietest of the three failures.
    - Rules live in `observability/alerts.yml` (mounted into Prometheus, validated with `promtool check rules`). They evaluate onto Prometheus' own `/alerts` page — there is **no Alertmanager in the stack**, so nothing is routed anywhere yet. `ReconciliationConfirmedDiscrepancy` carries no `for:` on purpose: two sweeps of survival is already a better debounce than a timer, since it filters on the discrepancy still being real rather than on the alert still being loud.
  - **`max-keys` (200k) is a cliff, not a slope.** `forEachPage` throws past `maxKeys / page-size + 2` pages rather than truncating, so crossing it aborts every subsequent sweep — the service stops auditing permanently. The full scan is otherwise fine (a `SUM` over the ledger is a seq scan + hash aggregate every 5 minutes, and C1 must stay a full comparison: skipping accounts with no recent ledger activity is blind by construction to an unledgered deposit, the very bug it exists to catch). When the ceiling warning or the staleness alert fires, the fix is a **C2 watermark** — safe because `account_transfer_log` is insert-only and a `COMPLETED` transaction is terminal, so a key matched on both sides can never diverge again. Two traps: watermark on *settlement* time, not `created_at` (a row can settle long after it is written, or never), and keep `FAILED` rows in the window past the write-off horizon (a late movement flips the verdict). A watermark may skip *scanning for new* discrepancies; it may never skip *re-verifying open* ones, or `resolveUnseen` mass-resolves everything outside the window and the two-sightings rule never promotes anything to `CONFIRMED`.
  - ⚠️ **Anything that is not JPA does not get `hibernate.default_schema`** — it resolves against the connection's `search_path`, which in production comes from `?currentSchema=` in the JDBC URL. This has now bitten twice, in unrelated places. ShedLock issues plain JDBC, so `JdbcTemplateLockProvider` is configured with a **schema-qualified** table name (sourced from `spring.flyway.default-schema`, so it cannot drift from the migration); with a bare name, `SweepLockTest` fails with `relation "shedlock" does not exist`. Qualifying removes the dependency on connection state rather than restoring it per-test, which is why it is preferred to the `connection-init-sql` workaround below.
  - ⚠️ **Native queries do not get `hibernate.default_schema`.** The ledger aggregate is native SQL (a transfer contributes to two accounts from one row, so no single `GROUP BY` yields both sides — it needs `UNION ALL`), and native SQL resolves against the connection's `search_path`, which in production comes from `?currentSchema=` in the JDBC URL. A Testcontainers test using `@ServiceConnection` replaces that URL and the query silently looks in `public`; `LedgerNetQueryTest` sets `spring.datasource.hikari.connection-init-sql` to restore it.
- **Closing an account requires a zero balance** — `AccountService.closeAccount` used to close unconditionally, which orphaned whatever was left: the money sits on a row nothing can reach (every balance-changing query is predicated on `status = 'ACTIVE'`) while no ledger entry records it leaving, so a reconciliation pass would report it as destroyed and be right. The check is a conditional `UPDATE … WHERE balance = 0` (`closeIfEmpty`), not a read-then-write, for the same reason `deductBalance` is: reading the balance in Java and then setting the status lets a credit land in between and close a funded account. Emptying an account is a transfer, which is ledgered — closing is not a way to move money.
- **Transactional Outbox**: payment-service writes `OutboxEvent` rows in the same DB transaction as the settlement; `OutboxPoller` publishes them to Kafka asynchronously, guaranteeing at-least-once delivery.
  - **The broker ack is awaited** (`KafkaEventPublisher`, `kafka.publish.ack-timeout-ms`, default 35s). `KafkaTemplate.send` returns once the record is buffered in the producer's accumulator, *not* when a broker accepts it — so discarding the returned future marked rows `PUBLISHED` for messages that were never delivered, defeating the guarantee the outbox exists to provide. Blocking keeps the ack inside the poller's transaction: a failure throws, the row stays `PENDING`, and the existing backoff retries it.
  - The timeout sits **above** the producer's `delivery.timeout.ms` (30s) on purpose, so what surfaces is the producer's own typed error rather than a generic backstop. Lowering it below 30s would abandon sends the producer may still complete, turning them into duplicate deliveries.
  - `ExecutionException` is **unwrapped** before rethrowing. `record-exceptions` is a whitelist of Kafka types and matches no wrapper, so rethrowing it as-is would score every failed publish as a *success* — the same trap as the `redis` breaker's missing `QueryTimeoutException`.
  - **No `@Retry` on the publish**, deliberately, same reasoning as `redis` not retrying command timeouts: the producer has already exhausted its internal retries over `delivery.timeout.ms` by the time the call returns, so an outer retry is a fresh send that turns 30s into 90s while the poller holds its row locks. The outbox is the durable retry layer.
- **`idempotencyKey` is required on a transfer** (`TransferRequest`, `@NotNull` + a service-side guard). It used to fall back to a server-generated UUID, which looks idempotent but is the opposite: a fresh key per attempt means a client retrying after a timeout arrives with a key account-service has never seen and moves the money twice. Note the two retry layers differ — Resilience4j's `@Retry` on `AccountServiceClient` is safe because the key is generated before the call and reused, while any client-visible retry is not.
- **Resilience4j** circuit breakers and retries on Redis, Kafka, and inter-service Feign calls in every service.
- **Account read cache (Redis)** — `account-service` caches `accounts::<accountId>` and `accountsByUser::<userId>` (`AccountReader`), with a short TTL (`ACCOUNT_CACHE_TTL`, default 60s) as a backstop only; eviction is the correctness mechanism. Three rules make it safe:
  - **Authorization is never cached.** `AccountReader` does no ownership check; `AccountService.findOwnedAccount` re-checks `userId` on every call, hit or miss. This is why the cache stores `CachedAccount` (carries `userId`) rather than `AccountResponse`.
  - **Eviction is event-driven and deferred to after commit.** Mutations publish `AccountsChangedEvent`; `AccountCacheEvictor` consumes it via `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` — same pattern as `OutboxPoller.onTransferCommitted`. Evicting inline would let a concurrent reader re-populate the entry with the pre-commit balance. `fallbackExecution = true` is required, or evictions published outside a transaction would silently never fire.
  - **A transfer evicts both sides** — the destination account usually belongs to a different user, so the recipient's entries must be evicted too.
  - Redis failures **fail open**: reads fall through to Postgres.
  - **Stampede protection** — reads are `@Cacheable(sync = true)` backed by `StripedLockRedisCache`, which locks per key (256 stripes) instead of Spring Data Redis' single cache-wide lock, so concurrent misses on one account collapse to one query while unrelated keys stay parallel. It also re-implements fail-open internally, because `CacheAspectSupport.executeSynchronized` bypasses the configured `CacheErrorHandler` entirely.
  - **Fast degradation** — the cache shares the `redis` Resilience4j breaker with `TokenBlacklistService`, and a failed probe skips both the re-check under the lock and the write. Measured with Redis paused: ~4.2s per request before, ~0.23s once the breaker opens.

> ⚠️ `record-exceptions` on a Resilience4j breaker is a **whitelist** — anything unlisted counts as a *success*. Spring Data translates Lettuce command timeouts into `org.springframework.dao.QueryTimeoutException`, which was missing from the `redis` instance, so the breaker never opened on the most common Redis failure. Fixed in all four services that define a `redis` breaker (account, auth, payment, notification). It is recorded by the breaker but deliberately **not** retried — the command already waited out its full timeout, so retrying turns 2s into 6s.
>
> ⚠️ `api-gateway` now has a `redis` breaker on `GatewayTokenBlacklistService.isBlacklisted`, mirroring the four downstream services. Three things are specific to it being reactive:
> - **`resilience4j-reactor` is an explicit dependency.** `resilience4j-spring-boot3` does not pull it in (verified in the dependency tree), and without it the annotation aspect cannot wrap a `Mono` — it silently does nothing.
> - **The old `onErrorReturn(false)` had to move into the fallback.** Recovering inside the method body hands the aspect an already-successful `Mono`, so the breaker records every outage as a success and never opens. Fail-open behaviour is unchanged; it is the fallback that now provides it, and `CallNotPermittedException` arrives there too once the breaker is open.
> - **An open breaker still invokes the method.** The aspect wraps the returned `Mono`, so short-circuiting happens at *subscription*. `GatewayTokenBlacklistServiceTest` therefore counts subscriptions, not calls to `hasKey` — a `verifyNoInteractions` assertion here fails even though no Redis command is issued.
>
> No `retry` instance for the gateway: its only Redis call is a read on the hot path of every authenticated request, so retrying multiplies the latency the breaker exists to hide. `ignore-exceptions: AppException` is also absent — the gateway deliberately does not depend on `banking-common`, and Resilience4j fails at startup on a class it cannot load.
- **Consumer inbox + dead-letter topic (notification-service)** — the outbox is at-least-once, so redelivery is expected, not exceptional.
  - `PaymentEventConsumer` records `processed_events(event_id)` — the `PaymentEvent.transactionId` — **in the same transaction** as the notifications it creates. Marker and notifications commit together or not at all; a redelivery finds the row and returns. Committing the marker separately would let a later failure skip an event whose notifications were never written.
  - **The catch-all had to go first.** The listener used to wrap its body in `catch (Exception e) { log.error(...) }` and return normally, which committed the offset on failure and made `DefaultErrorHandler` **unreachable** — retries never ran and nothing could reach a DLT, because the container was never told anything failed. Wiring a `DeadLetterPublishingRecoverer` behind that would have changed nothing.
  - **`ErrorHandlingDeserializer` wraps `JsonDeserializer`.** Without it a malformed payload throws inside the container *before* the listener, which retries the same poison message forever and blocks the partition — the DLT never sees it.
  - **The DLT template delegates by type** (`DelegatingByTypeSerializer`): a handler failure republishes the deserialized `PaymentEvent` as JSON, but a *deserialization* failure has no object and republishes the original `byte[]`. A plain `JsonSerializer` would re-encode those bytes as a base64 string, making the dead-lettered payload useless for replay.
  - WebSocket pushes are deferred to after commit and swallow their own failures — the notification is already durable, and letting a dropped frame escape would retry the message and dead-letter a perfectly good event.
  - Anything in `payment.events.DLT` means a user was not told about a transfer that did happen; alert on it rather than merely retaining it.
  - `processed_events` grows one row per transfer. Pruning is safe once rows are older than the broker's retention — a message that can no longer be redelivered cannot be a duplicate.
- **WebSocket push is relayed through Redis pub/sub, not sent to the local broker directly** — `enableSimpleBroker` keeps its registry of which user is connected on which session in the pod's own heap, so with more than one `notification-service` replica a push issued by the pod that happens to hold the Kafka partition reaches a user only if they are *also* connected to that same pod. `convertAndSendToUser` does not fail when there is no local session for the target user, so this was silent — degrading exactly like an unmonitored dependency, discovered only by deliberately running two replicas in k8s and connecting one client to each (see `k8s/21-notification-service.yaml`).
  - `WebSocketRelay.publish` (Redis `PUBLISH` on channel `ws-relay`, guarded by the same `redis` breaker/retry as `TokenBlacklistService`) replaces every direct `SimpMessagingTemplate` call. Every pod subscribes via `WebSocketRelayListener` — including the publisher, since Redis pub/sub has no "not myself" filter — and attempts local delivery on every message; the pod holding the user's session is the one where that attempt is not a no-op.
  - This also fixes the Kafka side for free: it no longer matters that `payment.events` has one partition and only one pod's consumer group member ever processes an event, because that pod no longer needs to *also* be the one holding the recipient's WebSocket connection.
  - Deliberately not a real STOMP broker relay (RabbitMQ/ActiveMQ) — Redis is already a dependency of every service here, so this reuses existing infrastructure instead of adding a new stateful component to run and monitor.
- **`User` implements `UserDetails`** directly — no separate adapter wrapper.
- **`GlobalExceptionHandler`** centralizes error responses: `AppException` → typed HTTP status, `BadCredentialsException` → 401, `MethodArgumentNotValidException` → field-keyed map, `CallNotPermittedException` → 503, and anything else → 500 with a generic message.
  - **An unhandled exception used to reach the client as an empty 403.** Spring Security applies its filter chain to the **ERROR dispatch** as well as the original request, so a failure forwarded to `/error` hit `.anyRequest().authenticated()` and was denied. A database outage was therefore indistinguishable from an authorization failure to any caller — including the frontend, which logs the user out in response. Demonstrated with the schema dropped: `POST /api/auth/register` returned `HTTP 403, Content-Length: 0` while the log showed `relation "banking_auth.users" does not exist`. Same class of misdirection as the actuator probe-path 403s.
  - Fixed with `dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` in all five services' `SecurityConfig`, **in preference to permitting `/error` as a path** — a path permit would also expose it to a direct external GET. `ErrorDispatchSecurityTest` covers both halves; deleting the permit turns its assertion back into `expected:<500> but was:<403>`.
  - **The catch-all is safe only because the handler extends `ResponseEntityExceptionHandler`.** `@ExceptionHandler` resolution picks the most specific match, so Spring's own MVC exceptions keep the statuses the framework assigns them — a malformed body stays 400, an unmatched path stays 404. Listing those types by hand instead would be a blacklist that silently misclassifies whatever it forgets, the same shape of bug as a Resilience4j `record-exceptions` whitelist. Note `HttpMessageNotReadableException` does **not** implement `ErrorResponse`, so an `instanceof ErrorResponse` guard is not a substitute.
  - **`AccessDeniedException` and `AuthenticationException` are rethrown, not answered.** `ExceptionTranslationFilter` is what decides 401 (caller is anonymous, should authenticate) vs 403 (caller is known, may not) — handling them here would collapse that distinction and report every `@PreAuthorize` rejection as a 500. Rethrowing the *original* exception is the supported way to decline: `ExceptionHandlerExceptionResolver` recognises the throwable it caught is the one it passed in and lets it continue up the chain.
  - Because the catch-all answers most failures before the forward ever happens, the ERROR-dispatch permit now matters only for failures thrown *outside* the handler — in a filter, or a container-issued `sendError`. That is why it has its own test rather than riding on a controller-level one.
- **Flyway migrations** — schema is managed by Flyway per service (`db/migration`); `spring.jpa.hibernate.ddl-auto=validate` so Hibernate only checks the schema against the entities, it does not mutate it.
- **List endpoints return `Slice`, not `Page`** — a `Page` issues a second `COUNT` over the same predicate on every fetch, and no consumer uses the totals (the frontend reads only `.content`). `Slice` fetches `size + 1` rows instead. The one exception is `/internal/payments/transactions/by-account/{id}`, which stays a `Page` because Spring Cloud OpenFeign ships a `PageJacksonModule` but has no `Slice` equivalent.
- **Transaction list pagination is a known future bottleneck.** `TransactionRepository.findByUserId` filters with `from_user_id = ? OR to_user_id = ?` and orders by `created_at DESC`. No index can serve both halves of that: Postgres combines the two indexes with a `BitmapOr`, which walks the heap in *physical page order* and therefore discards the index ordering — so it must sort, and to sort it must read **every** row the user has ever been party to in order to return 20. Cost grows with a user's history.
  - Composite `(user_id, created_at DESC)` indexes **do not fix this** — measured, the plan is identical to plain single-column indexes and the composites are marginally worse (larger, more write amplification on the insert-heavy path). A `V3` adding them to payment-service was written, measured, and dropped before release for this reason. Do not re-add them on their own.
  - What does fix it is splitting the `OR` into two `UNION ALL` branches, each of which *is* index-ordered, merged by `Merge Append` — verified at 28 buffers vs 113, with no sort and no growth in a user's history. Two traps if you do it: transferring between your own two accounts sets `from_user_id = to_user_id`, so those rows match both branches and duplicate (exclude them from the second branch); and that exclusion must use `IS DISTINCT FROM`, not `!=`, because `from_user_id` is nullable and `NULL != ?` is `NULL`, which would silently drop every deposit.
  - Preferred destination is **keyset pagination** (`WHERE created_at < :cursor`) rather than `UNION ALL` over offsets — offsets force each inner branch to fetch `offset + size + 1` rows, a value Spring Data will not compute for you. Returning `Slice` already removed page totals from the API, so the cursor migration is unblocked.
  - `notification-service`'s `(user_id, created_at DESC)` index **is** a real win and is retained — that query has no `OR`, so the index serves filter and ordering together (`Index Scan`, no sort, 24 buffers).
- Kafka runs in KRaft mode (no Zookeeper). External port `9094`; internal broker port `9092`.

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