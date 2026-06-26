# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

**Start infrastructure (required before running the app):**
```bash
docker compose up -d
```

**Run the application:**
```bash
./mvnw spring-boot:run
```

**Build (skip tests):**
```bash
./mvnw package -DskipTests
```

**Run all tests:**
```bash
./mvnw test
```

**Run a single test class:**
```bash
./mvnw test -Dtest=ClassName
```

**Environment setup:** Copy `.env.example` to `.env` before running Docker Compose. The app reads env vars with defaults baked into `application.yml`, so the `.env` file is only needed to override Docker Compose values.

## Architecture

This is a Spring Boot 3.3 / Java 21 monolith organized into feature packages under `com.banking`. Each module is self-contained with its own `controller`, `service`, `dto`, `entity`, and `repository` sub-packages.

| Package | Responsibility |
|---|---|
| `auth` | JWT login/register/refresh/logout, Spring Security config |
| `user` | `User` entity (implements `UserDetails`), role management |
| `account` | Bank accounts, balances, per-account transaction history |
| `payment` | Transfers, transaction ledger, Kafka producer |
| `notification` | Kafka consumer, per-user notification inbox |
| `common` | Shared `AppException` + `GlobalExceptionHandler` |

## API Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` | Register, returns token pair |
| POST | `/api/auth/login` | Login, returns token pair |
| POST | `/api/auth/refresh` | Rotate refresh token |
| POST | `/api/auth/logout` | Blacklist access + delete refresh token |
| POST | `/api/accounts` | Create account (`{"type":"SAVINGS"\|"CHECKING"}`) |
| GET | `/api/accounts` | List authenticated user's accounts |
| GET | `/api/accounts/{id}` | Get single account |
| GET | `/api/accounts/{id}/transactions` | Transactions for one account |
| POST | `/api/payments/transfer` | Transfer between accounts |
| GET | `/api/payments/transactions` | All transactions for current user |
| GET | `/api/notifications` | Notification inbox |
| PATCH | `/api/notifications/{id}/read` | Mark one notification read |
| PATCH | `/api/notifications/read-all` | Mark all notifications read |

## Auth Flow

Stateless JWT-based auth with two-token strategy:

- **Access token** (15 min): signed HS256 JWT; carries `jti` (UUID) for blacklisting and `sub` (email).
- **Refresh token** (7 days): opaque UUID stored in Redis under `refresh:<token>` → `userId`.

**Logout** blacklists the access token's `jti` in Redis (`blacklist:<jti>`) for its remaining TTL, and deletes the refresh token key. `JwtAuthenticationFilter` checks this blacklist on every request before authenticating.

**Refresh rotation**: old refresh token is deleted and a new one is issued atomically in `RefreshTokenService.rotate()`.

`AppProperties` (`@ConfigurationProperties(prefix = "app.jwt")`) is the single source of truth for JWT secret and expiration values.

## Key Design Decisions

- `User` implements `UserDetails` directly — no separate `UserDetailsAdapter` wrapper.
- `GlobalExceptionHandler` centralizes error responses: `AppException` → typed HTTP status, `BadCredentialsException` → 401, `MethodArgumentNotValidException` → field-keyed map.
- Public endpoints: `/api/auth/**` and `/actuator/health`. Everything else requires a valid JWT.
- Kafka runs in KRaft mode (no Zookeeper). External port is `9094`; internal broker-to-broker uses `9092`.
- `spring.jpa.hibernate.ddl-auto=update` — schema is managed by Hibernate auto-DDL during development.

## Infrastructure Ports

| Service | Host port |
|---|---|
| PostgreSQL | 5432 |
| Redis | 6379 |
| Kafka (external) | 9094 |
| App | 8080 (default) |
