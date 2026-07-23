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
| `banking-common` | `com.banking.common` | Shared `AppException` + `GlobalExceptionHandler` |
| `banking-events` | `com.banking.events` | Shared Kafka event DTOs (e.g. `PaymentEvent`) used by producer and consumer |

### Service Communication

- **Sync (Feign):** `account-service` → `payment-service` (fetch transactions); `payment-service` → `account-service` (execute a transfer, and `GET /internal/accounts/transfers/{idempotencyKey}` to ask whether one committed — used by the saga's recovery poller)
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
| GET | `/api/payments/transactions` | JWT | All transactions for current user |
| GET | `/api/notifications` | JWT | Notification inbox |
| PATCH | `/api/notifications/{id}/read` | JWT | Mark one notification read |
| PATCH | `/api/notifications/read-all` | JWT | Mark all notifications read |
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
- **`User` implements `UserDetails`** directly — no separate adapter wrapper.
- **`GlobalExceptionHandler`** centralizes error responses: `AppException` → typed HTTP status, `BadCredentialsException` → 401, `MethodArgumentNotValidException` → field-keyed map.
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