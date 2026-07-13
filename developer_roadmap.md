# Developer Roadmap

Focus: problems that cause real production incidents in payment systems. Config-level trivia removed.

---

## Current State

### What's built well

- Proper microservice boundaries — services communicate only via API or Kafka, no cross-schema JPA joins
- API Gateway with JWT validation + Redis blacklist at the edge
- Transactional Outbox pattern on payment events (at-least-once Kafka delivery)
- Resilience4j circuit breakers + retries on all cross-service Feign calls and Redis/Kafka
- Idempotency keys on transfers (`AccountTransferLog`)
- `X-Internal-Secret` header protecting `/internal/**` endpoints
- Full observability stack: Prometheus + Grafana + Tempo distributed tracing
- WebSocket push for real-time balance/transaction updates
- Shared libraries (`banking-common`, `banking-events`) keeping cross-cutting code DRY
- Angular frontend with auth, accounts, transfer, notifications
- Kafka consumer exponential backoff + error handler in notification-service

### Critical bugs (will cause real money loss)

| Bug | Consequence |
|---|---|
| Race condition on concurrent transfers | Two concurrent transfers can both pass the balance check → overdraft |
| OutboxPoller has no distributed lock | 2+ payment-service instances double-publish every payment event |
| No saga / compensating transaction | Debit succeeds, credit fails → money lost with no recovery |
| Idempotency check is not atomic with the debit | Retry after network timeout = double charge |
| `ddl-auto: update` everywhere | Schema drift or data loss on redeploy |

### Structural gaps

| Gap | Risk |
|---|---|
| All services share one PostgreSQL database | One bad migration breaks all services |
| No pagination on list endpoints | Unbounded queries OOM/timeout at scale |
| No database indexes | Every transaction list query does a full table scan |
| HikariCP on defaults | Connection pool exhaustion under load |
| No rate limiting at gateway | Open to abuse |
| WebSocket uses in-memory STOMP broker | Notifications drop silently if notification-service scales to 2+ replicas |
| No Kafka Dead Letter Queue | Failed messages dropped silently after retries |
| No centralized log aggregation | Can't correlate logs to traces in production |
| No reconciliation | Silent discrepancies accumulate undetected |
| Zero tests | Can't safely change anything |

---

## What to hand off to DevOps

When Phases 1–3 are done, hand the DevOps engineer:
- Dockerfiles (one per service)
- List of env vars each service needs
- Port and health check endpoint (`/actuator/health`) per service
- GitHub Actions CI workflow that builds and pushes Docker images to ECR

See `devops_roadmap.md` for the AWS deployment path.

---

## Phase 1 — Fix the Money Bugs

> These are real bugs that lose or duplicate money. Fix before anything else.

### 1a. Fix the race condition — atomic balance update

**Current bug:** `PaymentService` calls account-service via Feign to check balance, then calls again to debit. Between those two calls another transfer can drain the account.

**Fix:** replace the check-then-deduct Feign calls with a single atomic SQL update in account-service:

```sql
UPDATE accounts
SET balance = balance - :amount
WHERE id = :id
  AND balance >= :amount
  AND status = 'ACTIVE'
```

Check affected rows — if 0, reject with insufficient funds. No separate balance read needed. The database row lock makes this race-condition-proof.

Apply the same pattern for the credit side. Remove the separate balance-check Feign call entirely.

### 1b. Fix the OutboxPoller — distributed lock

**Current bug:** `OutboxPoller` calls `findTop10ByStatusOrderByCreatedAtAsc` with no locking. Two payment-service instances pick up the same rows and double-publish the same payment event to Kafka.

**Fix:** `SELECT ... FOR UPDATE SKIP LOCKED` so each instance claims exclusive rows:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
@Query("SELECT e FROM OutboxEvent e WHERE e.status = 'PENDING' ORDER BY e.createdAt ASC LIMIT 10")
List<OutboxEvent> findPendingWithLock();
```

### 1c. Harden idempotency — make the check atomic

**Current gap:** `AccountTransferLog` (idempotency key) exists, but the check and the debit are separate steps. A retry that arrives between check and debit bypasses the protection.

**Fix:** wrap the idempotency check and balance deduction in the same DB transaction with a unique constraint on `idempotency_key`. Let the DB reject the duplicate — catch the constraint violation and return the original result.

### 1d. Saga — architectural evolution (not a current bug)

After reading the code, both debit and credit happen inside a single `@Transactional` method in `InternalAccountController.doTransfer()`. If the credit fails, the entire transaction rolls back — the debit never commits. There is no partial failure risk in the current architecture.

The Saga pattern becomes necessary when the transfer flow spans multiple independent services or databases. Moved to Phase 5 as an architectural choice, not a bug fix.

---

## Phase 2 — Stabilise the Data Layer

### 2a. Separate DB schemas

Each service gets its own PostgreSQL schema. Same instance is fine for now.

| Service | Schema |
|---|---|
| auth-service | `banking_auth` |
| account-service | `banking_account` |
| payment-service | `banking_payment` |
| notification-service | `banking_notification` |

Prevents one service's migration from corrupting another's tables.

### 2b. Replace `ddl-auto: update` with Flyway

Add `flyway-core` to each service. Write `V1__init.sql` from the current schema. Required before any deployment where containers are replaced rather than updated in place.

All subsequent schema changes (indexes, new columns, saga state tables) go in versioned migration files.

### 2c. Add database indexes

No explicit indexes defined anywhere. These are required for production query performance:

| Table | Index columns | Reason |
|---|---|---|
| `transactions` | `user_id`, `account_id`, `created_at` | Every transaction list query |
| `accounts` | `user_id` | Account list per user |
| `notifications` | `user_id`, `is_read` | Inbox + unread count |
| `outbox_events` | `status`, `created_at` | OutboxPoller scans every 15s |
| `account_transfer_logs` | `idempotency_key` | Checked on every transfer |

Add via `V2__add_indexes.sql` Flyway migration.

### 2d. Tune HikariCP connection pool

Spring Boot defaults are conservative and collapse under load. Add to every service:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

Rule of thumb for pool size: `(vCPU × 2) + 1`. A t3.small (2 vCPU) → 5.

### 2e. Add pagination to list endpoints

`GET /api/payments/transactions`, `GET /api/notifications`, `GET /api/accounts` return unbounded result sets. Apply `Pageable` with default page size 20, max 100.

---

## Phase 3 — Resilience & Observability

### 3a. Add Kafka Dead Letter Queue

Messages that exhaust retries are currently dropped silently. Wire `DeadLetterPublishingRecoverer` so they go to `payment-events.DLT` instead:

```java
@Bean
public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
    ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
    backOff.setMaxAttempts(3);
    return new DefaultErrorHandler(recoverer, backOff);
}
```

Alert on any message landing in the DLT — it means a payment notification was lost.

### 3b. Add Loki for centralized log aggregation

You have traces (Tempo) and metrics (Prometheus/Grafana) but logs are per-container. Add Loki + Promtail to `docker-compose.yml`. Completes the observability triad — logs, metrics, and traces all in one Grafana UI, all correlatable by trace ID.

### 3c. Add rate limiting at the gateway

Wire `RequestRateLimiterGatewayFilter` with Redis `RedisRateLimiter`. Infrastructure already exists. Config-only change in `api-gateway/application.yml`. Reasonable start: 20 req/s per user, burst 40.

---

## Phase 4 — CI/CD Pipeline

```
PR:   build → test (Testcontainers) → docker build
Main: same  → push images to ECR   → trigger ECS rolling deploy
```

Integration tests must cover the money path before this goes live:
- Transfer happy path
- Insufficient funds (race condition fix verified)
- Duplicate idempotency key (exactly-once verified)
- Saga rollback (debit reversal verified)

Use Testcontainers for PostgreSQL + Redis + Kafka.

---

## Phase 5 — Advanced Architecture

### 5a. Reconciliation Service

Nightly job that verifies the books balance:

```
sum(all transaction debits) = sum(all transaction credits) = sum(all account balance changes)
```

Any mismatch means money was created or destroyed by a bug — alerts immediately. This catches issues that idempotency and saga miss (e.g. silent DB corruption, missed events). Simple to build, critical for correctness. Most payment startups skip this and discover discrepancies months later.

### 5b. WebSocket horizontal scaling

In-memory STOMP broker means notification-service can only run as 1 replica. Switch to Redis Pub/Sub relay:

```java
config.enableStompBrokerRelay("/topic", "/queue")
      .setRelayHost(redisHost)
      .setRelayPort(6379);
```

Prerequisite before scaling notification-service on ECS.

### 5c. Migrate JWT to RS256 (asymmetric keys)

Auth-service holds the RSA private key. All other services verify with the public key only — no shared secret. Public key distributed via JWKS endpoint (`/api/auth/.well-known/jwks.json`). Eliminates the shared-secret risk where compromising one service exposes the signing key to all.

---

## Phase 6 — New Business Services

Each service is a standalone module: own DB schema, Flyway migrations, Resilience4j, Kafka events, REST API via gateway.

> Phase 1 must be complete before any new service. Atomic transfers and Flyway are prerequisites.

### 6a. Loan Service (P1)

Loan lifecycle: `PENDING → APPROVED → ACTIVE → CLOSED / REJECTED`

- EMI calculation: principal + interest rate + tenure → monthly instalment
- Scheduled job deducts EMI monthly via payment-service transfer
- Missed EMI → `OVERDUE` → notification alert
- Saga: what if EMI deduction fails? (insufficient funds → retry next day → mark overdue after N failures)

Hard parts: state machine, distributed scheduling without double-execution, saga for EMI deduction.

### 6b. Fraud Detection Service (P1)

Pure event-driven — consumes payment events from Kafka, never called directly.

Rules (start simple):
- Transfer > $10,000
- More than N transfers in M minutes from same account
- Transfer to new account within 24h of its creation

On fraud detected → publishes `FraudAlertDetected` → notification-service alerts user, account-service freezes account.

Hard parts: stateful rule evaluation across events (need to track recent transfer history per user), idempotent fraud alerts (same event must not trigger two freezes).

### 6c. Audit Service (P2)

Immutable append-only log of every significant action across all services.

- All services publish `AuditEvent` to a dedicated Kafka topic
- audit-service persists — **no updates, no deletes, ever**
- Separate DB schema with an insert-only DB user (no `UPDATE`/`DELETE` grants at DB level)
- Required for PCI-DSS and SOC2 compliance

Hard parts: guaranteed delivery (missed audit events are a compliance violation), preventing any code path from mutating the log.

---

---

## Phase 7 — Learning & Polish

> Not critical path. Do these after Phase 1–4 when you want to round out your knowledge.
> Each item is low risk, low complexity — good for learning a concept without pressure.

### 7a. Graceful shutdown

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

One config change per service. Prevents in-flight requests from being cut off during rolling restarts. Good to understand how Spring handles SIGTERM.

### 7b. API versioning

Add `/v1/` prefix to all routes in the gateway and controllers. Learn why versioning matters before you have external consumers locked to your current contract.

### 7c. OpenAPI / Swagger docs

Add `springdoc-openapi-starter-webmvc-ui` to each service. Annotate controllers with `@Tag` and `@Operation`. Good for learning how to document APIs and useful for manual testing via the Swagger UI.

### 7d. Containerize the frontend

Add an `nginx` Dockerfile for the Angular app. Wire it into `docker-compose.yml` so `docker compose up` starts the complete product including the frontend. Teaches multi-stage Docker builds for a Node.js/Angular app.

### 7e. Application-level caching

Add `@Cacheable` on read-heavy endpoints using the existing Redis connection:
- Account list per user (invalidate on account create/close)
- Notification count per user (invalidate on new notification)

Teaches cache invalidation patterns and when caching helps vs. when it causes stale data bugs.

### 7f. Scheduled Payments Service

Standing orders and recurring transfers — "transfer $500 to savings every 1st of month."

Teaches distributed cron scheduling and how to prevent double-execution when multiple instances run. Shares patterns with the Loan Service EMI scheduler (do Loan Service first).

### 7g. Analytics / Reporting Service

Read-only service: spending by category, monthly statement, balance history.

Consumes payment events from Kafka and builds its own denormalized read model in a separate DB schema. Teaches CQRS — a separate read model optimised for queries rather than writes.

### 7h. gRPC for internal service calls

Migrate one internal call (e.g., `payment-service → account-service` balance check) to gRPC. Write a `.proto` file, generate stubs, benchmark before/after. Teaches protobuf, HTTP/2, and when binary protocols are worth the added complexity.

---

## Timeline

| Phase | Estimate |
|---|---|
| Phase 1 — Fix the money bugs (race condition, OutboxPoller, idempotency, saga) | ~3–4 weeks |
| Phase 2 — Data layer (schemas, Flyway, indexes, pool tuning, pagination) | ~2 weeks |
| Phase 3 — Resilience & observability (DLQ, Loki, rate limiting) | ~1–2 weeks |
| Phase 4 — CI/CD + integration tests | ~1–2 weeks |
| Phase 5 — Advanced architecture (reconciliation, WebSocket scaling, RS256) | ~2–3 weeks |
| Phase 6a — Loan Service | ~2–3 weeks |
| Phase 6b — Fraud Detection Service | ~1–2 weeks |
| Phase 6c — Audit Service | ~1 week |
| Phase 7 — Learning & polish (any order, any time after Phase 4) | ~2–3 weeks total |
