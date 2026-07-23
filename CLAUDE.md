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
./mvnw test
```

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

- **Sync (Feign):** `account-service` → `payment-service` (fetch transactions); `payment-service` → `account-service` (balance checks/updates)
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
- **Transactional Outbox**: payment-service writes `OutboxEvent` rows in the same DB transaction as the transfer; `OutboxPoller` publishes them to Kafka asynchronously, guaranteeing at-least-once delivery.
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