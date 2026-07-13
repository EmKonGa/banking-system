# Resilience4j Implementation Plan — Banking System

## Architecture Summary (Findings)

**External integration points that can fail:**

- **Redis** — used in 3 places: `RefreshTokenService` (create/getUserId/delete/rotate), `AuthService.logout()` (
  blacklist write), `JwtAuthenticationFilter` (blacklist read on every HTTP request).
- **Kafka** — `PaymentService.transfer()` sends a `PaymentEvent` inside a `TransactionSynchronization.afterCommit()`
  anonymous class callback.
- **WebSocket (SimpMessagingTemplate)** — called in `PaymentService.transfer()` afterCommit (4 calls) and in
  `NotificationService.create()`. In-process STOMP; lowest risk but can still throw.
- **Oracle DB** — JPA calls in all services; protected by Spring's transaction management and JDBC connection pooling.
  Resilience4j circuit breakers on synchronous JDBC calls are non-standard and not recommended here.

**Critical AOP constraint discovered:** The Kafka send in `PaymentService` lives inside a lambda (
`TransactionSynchronization.afterCommit()`). Resilience4j `@CircuitBreaker` and `@Retry` work only on Spring-proxied
bean method invocations — calling `this.method()` from inside a lambda bypasses the AOP proxy. Two new Spring beans must
be extracted for this reason.

**No AOP starter present:** `spring-boot-starter-aop` is absent from `pom.xml`, meaning all Resilience4j annotations
would be silently ignored without it.

---

## Step 1 — Add Dependencies to `pom.xml`

```xml
<!-- AOP support — required for @CircuitBreaker, @Retry annotations to take effect -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>

        <!-- Resilience4j — circuit breaker, retry, time-limiter for Spring Boot 3 -->
<dependency>
<groupId>io.github.resilience4j</groupId>
<artifactId>resilience4j-spring-boot3</artifactId>
<version>2.2.0</version>
</dependency>
```

`resilience4j-spring-boot3` 2.2.0 bundles `resilience4j-core`, `resilience4j-circuitbreaker`, `resilience4j-retry`,
`resilience4j-timelimiter`, `resilience4j-micrometer`, and the Actuator health/endpoint integration. No Spring Cloud BOM
is required.

---

## Step 2 — New Class: `KafkaEventPublisher`

**File:** `backend/src/main/java/com/banking/common/resilience/KafkaEventPublisher.java`

**Why needed:** The Kafka send in `PaymentService` is inside an anonymous `TransactionSynchronization` inner class.
Spring AOP cannot intercept `this.method()` calls. A dedicated `@Component` bean is required so the AOP proxy is
honoured.

**Responsibilities:**

- Single method: `publish(String topic, String key, PaymentEvent event)`
- Annotated with `@CircuitBreaker(name = "kafka-producer", fallbackMethod = "publishFallback")`
- Annotated with `@Retry(name = "kafka-producer")`
- `@Retry` is the outer decorator (default Resilience4j AOP order: CB outer, Retry inner — retries happen before the CB
  counts a failure)
- Injects `KafkaTemplate<String, PaymentEvent>`
- `publishFallback(String topic, String key, PaymentEvent event, Throwable t)` — logs a structured error including
  `event.transactionId()`. The transaction is already committed and money moved; do not rethrow.

---

## Step 3 — New Class: `TokenBlacklistService`

**File:** `backend/src/main/java/com/banking/auth/service/TokenBlacklistService.java`

**Why needed:** `JwtAuthenticationFilter` calls `redis.hasKey("blacklist:" + jti)` directly. Since
`JwtAuthenticationFilter` is itself a `@Component`, its own methods are proxied, but calling `StringRedisTemplate`
directly is not guarded. The check needs to live in a separate bean.

**Responsibilities:**

- `boolean isBlacklisted(String jti)` — `@CircuitBreaker(name = "redis", fallbackMethod = "isBlacklistedFallback")`
    - Fallback returns `false` (fail-open). Rationale: returning `true` would lock every user out during a Redis outage,
      which is worse than a blacklisted token briefly remaining usable.
- `void blacklistToken(String jti, Duration ttl)` —
  `@CircuitBreaker(name = "redis", fallbackMethod = "blacklistTokenFallback")` + `@Retry(name = "redis")`
    - Fallback logs the failure; the access token expires naturally at its 15-minute TTL.

---

## Step 4 — Annotate `RefreshTokenService`

**File:** `backend/src/main/java/com/banking/auth/service/RefreshTokenService.java`

| Method                                   | Annotations                  | Fallback behaviour                                           |
|------------------------------------------|------------------------------|--------------------------------------------------------------|
| `create(String userId)`                  | `@CircuitBreaker` + `@Retry` | Throws `AppException(503)`                                   |
| `getUserId(String token)`                | `@CircuitBreaker` + `@Retry` | Throws `AppException(503)`                                   |
| `delete(String token)`                   | `@CircuitBreaker` only       | Logs warn, returns void (fail-open)                          |
| `rotate(String oldToken, String userId)` | Not annotated                | Delegates to `delete` + `create` which are already annotated |

Do NOT annotate `rotate()` — it calls `delete()` and `create()`, both already annotated. Annotating `rotate()` too would
create double-interception.

---

## Step 5 — Refactor `AuthService`

**File:** `backend/src/main/java/com/banking/auth/service/AuthService.java`

- Inject `TokenBlacklistService` instead of using `StringRedisTemplate` directly for the blacklist write.
- In `logout()`, replace `redis.opsForValue().set(...)` with
  `tokenBlacklistService.blacklistToken(jti, Duration.ofMillis(ttlMs))`.
- Remove `StringRedisTemplate redis` field.

---

## Step 6 — Refactor `JwtAuthenticationFilter`

**File:** `backend/src/main/java/com/banking/auth/filter/JwtAuthenticationFilter.java`

- Add `TokenBlacklistService tokenBlacklistService` field.
- Replace `redis.hasKey("blacklist:" + jti)` with `tokenBlacklistService.isBlacklisted(jti)`.
- Remove `StringRedisTemplate redis` field.

This is the hottest Redis call path (every authenticated HTTP request). The circuit breaker protects the entire
application from cascading failures when Redis is slow or down.

---

## Step 7 — New Class: `NotificationPushService`

**File:** `backend/src/main/java/com/banking/notification/service/NotificationPushService.java`

**Why needed:** `NotificationService.create()` calls `messagingTemplate.convertAndSendToUser(...)` from inside a
`TransactionSynchronization` lambda — same AOP bypass issue as Kafka. A dedicated bean is needed.

- `push(UUID userId, NotificationResponse response)` —
  `@CircuitBreaker(name = "websocket", fallbackMethod = "pushFallback")`
- Fallback logs at `WARN`. Notification is already persisted in DB; user sees it on next page load.

---

## Step 8 — New Class: `KafkaConsumerConfig`

**File:** `backend/src/main/java/com/banking/common/config/KafkaConsumerConfig.java`

- `@Bean` of type `DefaultErrorHandler` with `ExponentialBackOff`: initial 500ms, multiplier 2.0, max 3 attempts, max
  interval 10s.
- `AppException` added as non-retryable (business validation errors should not be retried).
- Spring Kafka 3.x auto-configures a `DefaultErrorHandler` bean into the listener container factory.

---

## Step 9 — Refactor `PaymentService`

**File:** `backend/src/main/java/com/banking/payment/service/PaymentService.java`

- Replace `KafkaTemplate<String, PaymentEvent>` injection with `KafkaEventPublisher`.
- In `afterCommit()`, replace `kafkaTemplate.send(...)` with `kafkaEventPublisher.publish(...)`.
- Wrap the four `messagingTemplate.convertAndSendToUser(...)` calls in try-catch so a WebSocket failure doesn't suppress
  the already-executed Kafka publish.

---

## Step 10 — Refactor `NotificationService`

**File:** `backend/src/main/java/com/banking/notification/service/NotificationService.java`

- Inject `NotificationPushService`.
- Replace `messagingTemplate.convertAndSendToUser(...)` calls with `notificationPushService.push(userId, response)`.

---

## Step 11 — Update `GlobalExceptionHandler`

**File:** `backend/src/main/java/com/banking/common/exception/GlobalExceptionHandler.java`

Add handler for `CallNotPermittedException` (circuit breaker is OPEN, no fallback defined upstream):

- Returns `503 SERVICE_UNAVAILABLE` with
  `{"status": 503, "message": "Service temporarily unavailable. Please retry shortly."}`

---

## Step 12 — `application.yml` — Resilience4j Config

```yaml
resilience4j:

  circuitbreaker:
    instances:
      redis:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50          # open after 50% failures in last 10 calls
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 80
        record-exceptions:
          - org.springframework.data.redis.RedisConnectionFailureException
          - io.lettuce.core.RedisException
          - java.util.concurrent.TimeoutException
          - java.io.IOException
        ignore-exceptions:
          - com.banking.common.exception.AppException
      kafka-producer:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s    # Kafka often needs more time to recover
        permitted-number-of-calls-in-half-open-state: 2
        slow-call-duration-threshold: 5s
        slow-call-rate-threshold: 80
        record-exceptions:
          - org.apache.kafka.common.errors.TimeoutException
          - org.apache.kafka.common.errors.RetriableException
          - org.springframework.kafka.KafkaException
          - java.util.concurrent.TimeoutException
      websocket:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 60
        wait-duration-in-open-state: 15s
        permitted-number-of-calls-in-half-open-state: 5

  retry:
    instances:
      redis:
        max-attempts: 3
        wait-duration: 300ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2.0
        exponential-max-wait-duration: 3s
        retry-exceptions:
          - org.springframework.data.redis.RedisConnectionFailureException
          - io.lettuce.core.RedisException
          - java.util.concurrent.TimeoutException
        ignore-exceptions:
          - com.banking.common.exception.AppException
      kafka-producer:
        max-attempts: 3
        wait-duration: 500ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2.0
        exponential-max-wait-duration: 10s
        retry-exceptions:
          - org.apache.kafka.common.errors.TimeoutException
          - org.apache.kafka.common.errors.RetriableException
          - org.springframework.kafka.KafkaException
```

---

## Step 13 — `application.yml` — Client-Level Timeouts

Resilience4j's `@TimeLimiter` requires `CompletableFuture` return types and is not suitable for synchronous blocking
calls. Configure timeouts at the infrastructure client level instead.

**Redis (under `spring.data.redis`):**

```yaml
connect-timeout: 3s      # TCP connection establishment limit
timeout: 2s              # command execution timeout
lettuce:
  pool:
    max-active: 16
    max-idle: 8
    min-idle: 2
    max-wait: 1s
```

**Kafka producer (under `spring.kafka.producer`):**

```yaml
properties:
  request.timeout.ms: 10000    # per-request broker response timeout
  delivery.timeout.ms: 30000   # total delivery (including retries) timeout
  max.block.ms: 5000           # max time kafkaTemplate.send() blocks on metadata fetch
```

---

## Step 14 — Actuator and Prometheus Exposure

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,circuitbreakers,circuitbreakerevents,retries,retryevents
  health:
    circuitbreakers:
      enabled: true
    retries:
      enabled: true
```

**Prometheus metrics emitted automatically:**

- `resilience4j_circuitbreaker_state{name="redis"}` — 0=CLOSED, 1=OPEN, 2=HALF_OPEN
- `resilience4j_circuitbreaker_calls_total{name,kind}` — successful/failed/not_permitted/ignored
- `resilience4j_circuitbreaker_failure_rate{name}` — gauge in percent
- `resilience4j_circuitbreaker_slow_call_rate{name}` — gauge
- `resilience4j_retry_calls_total{name,kind}` — successful_without_retry / successful_with_retry / failed_with_retry /
  failed_without_retry

**Suggested Prometheus alert rules:**

- Alert when `resilience4j_circuitbreaker_state{name="redis"} == 1` for > 60s
- Alert when `resilience4j_circuitbreaker_state{name="kafka-producer"} == 1` for > 120s
- Alert when `resilience4j_retry_calls_total{kind="failed_with_retry"}` rate exceeds threshold

---

## Implementation Order

| #  | Action                                          | File                      |
|----|-------------------------------------------------|---------------------------|
| 1  | Add dependencies                                | `pom.xml`                 |
| 2  | Add Resilience4j config + timeouts + management | `application.yml`         |
| 3  | Create `KafkaEventPublisher`                    | `common/resilience/`      |
| 4  | Create `TokenBlacklistService`                  | `auth/service/`           |
| 5  | Create `NotificationPushService`                | `notification/service/`   |
| 6  | Create `KafkaConsumerConfig`                    | `common/config/`          |
| 7  | Add exception handlers                          | `GlobalExceptionHandler`  |
| 8  | Annotate methods + fallbacks                    | `RefreshTokenService`     |
| 9  | Delegate blacklist write                        | `AuthService`             |
| 10 | Delegate blacklist read                         | `JwtAuthenticationFilter` |
| 11 | Delegate Kafka send + wrap WS pushes            | `PaymentService`          |
| 12 | Delegate WS push                                | `NotificationService`     |

---

## Fallback Method Signatures

| Class                     | Method                                  | Return             | Behaviour                  |
|---------------------------|-----------------------------------------|--------------------|----------------------------|
| `KafkaEventPublisher`     | `publishFallback(topic, key, event, t)` | `void`             | Log error, return          |
| `TokenBlacklistService`   | `isBlacklistedFallback(jti, t)`         | `boolean`          | Return `false` (fail-open) |
| `TokenBlacklistService`   | `blacklistTokenFallback(jti, ttl, t)`   | `void`             | Log warn, return           |
| `RefreshTokenService`     | `createFallback(userId, t)`             | `String`           | Throw `AppException(503)`  |
| `RefreshTokenService`     | `getUserIdFallback(token, t)`           | `Optional<String>` | Throw `AppException(503)`  |
| `RefreshTokenService`     | `deleteFallback(token, t)`              | `void`             | Return (fail-open)         |
| `NotificationPushService` | `pushFallback(userId, response, t)`     | `void`             | Log warn, return           |
