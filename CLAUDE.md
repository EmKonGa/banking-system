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

> ⚠️ `record-exceptions` on a Resilience4j breaker is a **whitelist** — anything unlisted counts as a *success*. Spring Data translates Lettuce command timeouts into `org.springframework.dao.QueryTimeoutException`, which was missing from the `redis` instance, so the breaker never opened on the most common Redis failure. Fixed in `account-service`; **the other four services still have this gap.**
- **`User` implements `UserDetails`** directly — no separate adapter wrapper.
- **`GlobalExceptionHandler`** centralizes error responses: `AppException` → typed HTTP status, `BadCredentialsException` → 401, `MethodArgumentNotValidException` → field-keyed map.
- **Flyway migrations** — schema is managed by Flyway per service (`db/migration`); `spring.jpa.hibernate.ddl-auto=validate` so Hibernate only checks the schema against the entities, it does not mutate it.
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