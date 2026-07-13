# Plan: Migrate Banking Monolith to Microservices

## Context

The banking system is a single Spring Boot 3.3.4 / Java 21 monolith running all five domain modules (auth, user, account, payment, notification) in one JVM, sharing a single Oracle XE schema. The migration goal is to split this into independently deployable services with clear domain boundaries, each owning its own database schema.

Chosen approach:
- **Database**: Oracle XE with separate schemas per service (no new DB process)
- **Shared code**: `banking-common` + `banking-events` Maven libraries
- **Migration**: Strangler Fig — extract one service at a time, monolith stays live throughout

---

## Target Architecture

```
Browser / Angular ──► api-gateway :8080 (Spring Cloud Gateway)
                           │
         ┌─────────────────┼────────────────────┐
         ▼                 ▼                    ▼
   auth-service       account-service      payment-service
      :8081               :8082                :8083
   BANKING_AUTH       BANKING_ACCOUNT      BANKING_PAYMENT
   (users table)      (accounts table)     (transactions, outbox_events)
   Redis (JWTs)            │ Feign ▲              │ Kafka ▼
                           └───────┘         notification-service
                                                  :8084
                                            BANKING_NOTIFICATION
                                            (notifications table)
                                            WebSocket (/ws)
```

### Maven Project Layout

```
banking-system/
├── pom.xml                    ← parent BOM
├── banking-events/            ← PaymentEvent (extended), TransferExecutionRequest/Result
├── banking-common/            ← AppException, GlobalExceptionHandler,
│                                ObservabilityConfig, Resilience4jLoggingConfig,
│                                JwtPrincipal record, InternalSecretInterceptor
├── api-gateway/
├── auth-service/
├── account-service/
├── payment-service/
└── notification-service/
```

---

## Phase 0 — Foundation (refactor monolith, Week 1)

Goal: make the monolith safe to split without breaking existing behavior.

**1. Create `banking-events` and `banking-common` Maven modules**

`banking-events` contents:
- `PaymentEvent` — move from `com.banking.payment.event`; add two new fields:
  `BigDecimal fromAccountBalance` and `BigDecimal toAccountBalance` (populated from account balances after mutation, still within the same monolith DB transaction in `PaymentService.transfer()`)
- `TransferExecutionRequest` — new: `{ fromAccountId, toAccountNumber, amount, idempotencyKey }`
- `TransferExecutionResult` — new: `{ fromAccountNumber, toAccountNumber, fromBalance, toBalance, fromUserId, toUserId }`

`banking-common` contents:
- Move `AppException`, `GlobalExceptionHandler` from `common.exception`
- Move `ObservabilityConfig`, `Resilience4jLoggingConfig` from `common.config`
- Add `JwtPrincipal` record: `{ UUID id, String email, String role }` — used by all non-auth services to avoid `User` entity dependency in JwtAuthenticationFilter
- Add `InternalSecretInterceptor` — `HandlerInterceptor` that validates `X-Internal-Secret` header; blocks request with 403 if header is missing or wrong

**2. Replace `PaymentEventListener` with consolidated notification-service consumer**

In the monolith's `PaymentEventConsumer`, add the balance and transaction WebSocket pushes currently done by `PaymentEventListener` (`SimpMessagingTemplate.convertAndSendToUser` for `/queue/balance` and `/queue/transaction`). Use the new `fromAccountBalance`/`toAccountBalance` from `PaymentEvent` instead of calling back to account state. Delete `PaymentEventListener`.

**3. Prepare claim-based JWT filter**

Create a second variant of `JwtAuthenticationFilter` that does NOT call `userDetailsService.loadUserByUsername()`. Instead it builds a `UsernamePasswordAuthenticationToken` using `JwtPrincipal` from claims:
```java
String role   = claims.get("role", String.class);
UUID   userId = UUID.fromString(claims.getSubject());  // store userId in sub or custom claim
var principal = new JwtPrincipal(userId, email, role);
var auth = new UsernamePasswordAuthenticationToken(principal, null,
             List.of(new SimpleGrantedAuthority("ROLE_" + role)));
```
Move this to `banking-common`. Auth-service keeps its own DB-backed filter; all other services use this claims-only filter.

**4. Refactor `KafkaEventPublisher`** to use `KafkaTemplate<String, Object>` (generic). Remove the import of `PaymentEvent` from `common`.

**5. Write integration tests** (MockMvc) covering all existing endpoints — these serve as the regression safety net for all subsequent phases.

Critical files to modify in this phase:
- `backend/src/main/java/com/banking/payment/service/PaymentService.java` — populate balance fields in `PaymentEvent`
- `backend/src/main/java/com/banking/payment/event/PaymentEvent.java` — move to `banking-events`, add balance fields
- `backend/src/main/java/com/banking/notification/consumer/PaymentEventConsumer.java` — add balance/transaction WS push
- `backend/src/main/java/com/banking/payment/service/PaymentEventListener.java` — delete
- `backend/src/main/java/com/banking/common/resilience/KafkaEventPublisher.java` — genericize

---

## Phase 1 — Extract `auth-service` (Week 2)

**What moves**: `auth/*`, `user/*`, `DataInitializer` (seeds users only).

**Database**: `auth-service` connects to `BANKING_AUTH` Oracle schema. Data migration SQL:
```sql
CREATE USER BANKING_AUTH IDENTIFIED BY "auth_pass"; GRANT CONNECT, RESOURCE TO BANKING_AUTH;
INSERT INTO BANKING_AUTH.USERS SELECT * FROM BANKING_USER.USERS; COMMIT;
```

**What changes**:
- `auth-service` retains the full DB-backed `JwtAuthenticationFilter` (password verification still needs `UserRepository`)
- Remove the `DataInitializer` from the monolith (guard with `@ConditionalOnProperty(name="seeding.enabled", havingValue="false", matchIfMissing=true)`)
- Add `api-gateway` container. Gateway routes `/api/auth/**` to auth-service; all other paths still go to the monolith `http://banking-monolith:8080`

**Gateway JWT filter** (`JwtAuthGatewayFilter` — `GlobalFilter`):
- Validates Bearer token signature with `JwtService` (HS256, shared `JWT_SECRET`)
- Checks Redis blacklist (`blacklist:{jti}`)
- Routes marked `metadata.skip-jwt: true` (login, register, refresh, `/ws/**`) bypass this filter
- Passes original `Authorization` header downstream unchanged (defense-in-depth)
- Blocks any path starting with `/internal/` (`BlockInternalPathsFilter`, highest `@Order`)

Gateway dependencies: `spring-cloud-starter-gateway`, `jjwt-*`, `spring-boot-starter-data-redis-reactive`, `banking-common`, `banking-events`.
Spring Cloud BOM: `2023.0.3` (matches Spring Boot 3.3.x).

---

## Phase 2 — Extract `notification-service` (Week 3)

**What moves**: `notification/*`, `auth.config.WebSocketConfig`, `auth.config.WebSocketAuthChannelInterceptor`.

**Database**: `BANKING_NOTIFICATION` schema.

**Key changes**:
- `WebSocketAuthChannelInterceptor` no longer needs `CustomUserDetailsService` or `UserRepository`. Extracts `userId` from JWT claims only using `JwtPrincipal`.
- `PaymentEventConsumer` does everything: persist notifications + push `/queue/balance`, `/queue/transaction`, `/queue/notifications` via `SimpMessagingTemplate`.
- Use the balance fields from `PaymentEvent` (set in Phase 0) for balance pushes — no callback to account-service.
- Remove `WebSocketConfig` and all notification code from the monolith.

Gateway: add route `Path=/api/notifications/**` → notification-service and `Path=/ws/**` → `ws://notification-service:8084`.

---

## Phase 3 — Extract `payment-service` (Weeks 4–5) — most complex

**What moves**: `payment/*` minus `PaymentEventListener` (deleted in Phase 0).

**Database**: `BANKING_PAYMENT` schema.

### Critical entity refactor — `Transaction`

Remove `@ManyToOne` to `Account`. Replace with plain columns:
```java
// File: payment-service/src/.../payment/entity/Transaction.java
@Column private UUID fromAccountId;
@Column(nullable=false) private UUID toAccountId;
@Column private String fromAccountNumber;
@Column(nullable=false) private String toAccountNumber;
@Column private UUID fromUserId;
@Column private UUID toUserId;
```
`TransactionResponse.from(t)` uses the stored string fields directly — no lazy JPA traversal.

Data migration SQL (denormalize account numbers and user IDs):
```sql
INSERT INTO BANKING_PAYMENT.TRANSACTIONS (
  id, from_account_id, to_account_id,
  from_account_number, to_account_number,
  from_user_id, to_user_id,
  amount, type, status, description, created_at
)
SELECT t.id, t.from_account_id, t.to_account_id,
       fa.account_number, ta.account_number,
       fa.user_id, ta.user_id,
       t.amount, t.type, t.status, t.description, t.created_at
FROM BANKING_USER.TRANSACTIONS t
LEFT JOIN BANKING_USER.ACCOUNTS fa ON fa.id = t.from_account_id
JOIN BANKING_USER.ACCOUNTS ta ON ta.id = t.to_account_id;

INSERT INTO BANKING_PAYMENT.OUTBOX_EVENTS SELECT * FROM BANKING_USER.OUTBOX_EVENTS;
```

### Transfer flow (replacing the direct `AccountRepository` calls)

`PaymentService.transfer()` now:
1. Generates `UUID idempotencyKey`
2. Calls `AccountServiceClient.executeTransfer(TransferExecutionRequest)` via Feign (to the **monolith** during Phase 3, to account-service after Phase 4)
3. Monolith adds `InternalAccountController` at `/internal/accounts/execute-transfer` (protected by `X-Internal-Secret`): validates accounts, deducts/credits balance, saves `account_transfer_log` row (idempotency), returns `TransferExecutionResult`
4. payment-service saves `Transaction` row + `OutboxEvent` in one DB transaction using values from `TransferExecutionResult`

Feign client in payment-service:
```java
// com.banking.payment.client.AccountServiceClient
@FeignClient(name = "account-service", url = "${services.account-service.url}")
interface AccountServiceClient {
    @PostMapping("/internal/accounts/execute-transfer")
    TransferExecutionResult executeTransfer(@RequestBody TransferExecutionRequest req,
                                            @RequestHeader("X-Internal-Secret") String secret);
    @GetMapping("/internal/accounts/{id}")
    AccountDetail getAccount(@PathVariable UUID id,
                             @RequestHeader("X-Internal-Secret") String secret);
}
```

New Resilience4j config in payment-service:
```yaml
resilience4j:
  circuitbreaker.instances.account-service:
    sliding-window-size: 10
    failure-rate-threshold: 50
    wait-duration-in-open-state: 30s
    record-exceptions: [FeignException, ConnectException, TimeoutException]
  retry.instances.account-service:
    max-attempts: 3
    wait-duration: 500ms
    enable-exponential-backoff: true
    exponential-backoff-multiplier: 2.0
```

Add `InternalPaymentController` at `/internal/payments/transactions/by-account/{accountId}` — used by account-service in Phase 4 to serve `GET /api/accounts/{id}/transactions`.

Gateway: add route `Path=/api/payments/**` → payment-service.

---

## Phase 4 — Extract `account-service` (Week 6)

**What moves**: `account/*`.

**Database**: `BANKING_ACCOUNT` schema.

**Key changes**:
- `AccountController` no longer injects `PaymentService`. The `GET /api/accounts/{id}/transactions` endpoint calls `PaymentServiceClient.getTransactionsByAccount(id)` via Feign instead.
  - Fallback: return empty list with `X-Partial-Response: true` header when payment-service circuit is open.
- `InternalAccountController` at `/internal/accounts/**` — production-ready now (was on monolith during Phase 3).
- `AccountService.executeTransfer()` performs the debit/credit + saves to `account_transfer_log` (idempotency key check).

Data migration:
```sql
CREATE USER BANKING_ACCOUNT IDENTIFIED BY "account_pass"; GRANT CONNECT, RESOURCE TO BANKING_ACCOUNT;
INSERT INTO BANKING_ACCOUNT.ACCOUNTS SELECT * FROM BANKING_USER.ACCOUNTS; COMMIT;
```

Update payment-service env var: `ACCOUNT_SERVICE_URL=http://account-service:8082`.

Gateway: route `Path=/api/accounts/**` → account-service.

**Decommission monolith**: remove its container from docker-compose. All traffic now flows through the gateway.

---

## New `docker-compose.yml` (additions)

New containers to add alongside the existing infrastructure (oracle, redis, kafka, observability):

```yaml
api-gateway:          # port 8080
auth-service:         # port 8081, depends_on: oracle, redis
account-service:      # port 8082, depends_on: oracle
payment-service:      # port 8083, depends_on: oracle, kafka, account-service
notification-service: # port 8084, depends_on: oracle, kafka
```

Shared env vars block (`x-common-env`):
```yaml
REDIS_HOST: redis
KAFKA_BOOTSTRAP_SERVERS: kafka:9092
JWT_SECRET: ${JWT_SECRET}
INTERNAL_SECRET: ${INTERNAL_SECRET}
OTEL_EXPORTER_ENDPOINT: http://tempo:4318/v1/traces
```

Oracle init script (`db/init/01-create-schemas.sql`) mounted at `/container-entrypoint-initdb.d/` creates the four Oracle users and grants.

Update `observability/prometheus.yml`: replace the `host.docker.internal:8080` scrape job with five jobs targeting `api-gateway:8080`, `auth-service:8081`, `account-service:8082`, `payment-service:8083`, `notification-service:8084`.

Each service `application.yml` adds:
```yaml
management.metrics.tags.application: ${spring.application.name}
management.otlp.tracing.endpoint: ${OTEL_EXPORTER_ENDPOINT}
management.tracing.sampling.probability: 1.0
```

---

## Distributed Tracing

Feign clients automatically propagate `traceparent` headers via Micrometer's `ObservationRegistry` — no extra config. Spring Kafka 3.x propagates trace headers in Kafka message headers when `micrometer-tracing` is on the classpath. Full end-to-end traces (gateway → payment-service → Feign → account-service → Kafka → notification-service) are visible in Tempo.

---

## Verification

For each phase, verify end-to-end before moving to the next:

1. **Phase 0**: Run `./mvnw test` — all existing integration tests must pass. Manually trigger a transfer and confirm WebSocket pushes for balance, transaction, and notification all arrive (now all from `PaymentEventConsumer`, not `PaymentEventListener`).

2. **Phase 1**: `POST /api/auth/login` through gateway returns tokens. Blacklist check works: logout then call a protected endpoint → 401. Check Redis for `blacklist:{jti}` key.

3. **Phase 2**: WebSocket connection through `ws://localhost:8080/ws`. After transfer, all three push destinations arrive: `/queue/balance`, `/queue/transaction`, `/queue/notifications`. Check notification persisted in `BANKING_NOTIFICATION.NOTIFICATIONS`.

4. **Phase 3**: `POST /api/payments/transfer` → completes, returns `TransactionResponse` with account numbers. OutboxPoller publishes to Kafka (check Kafka UI at `:9002`). Transaction row in `BANKING_PAYMENT.TRANSACTIONS` has denormalized `from_account_number` populated.

5. **Phase 4**: `GET /api/accounts/{id}/transactions` returns transaction history served from payment-service. `GET /api/accounts` works. Decommission monolith: bring down that container and confirm all endpoints still respond through the gateway. Check Grafana (`:3000`) — all 5 services emit metrics and traces.