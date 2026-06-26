# Banking System

A Spring Boot 3.3 / Java 21 REST API for core banking operations — account management, fund transfers, and real-time notifications via Kafka.

## Features

- **JWT Authentication** — stateless two-token strategy (access + refresh) with Redis-backed blacklisting and token rotation
- **Account Management** — create and manage SAVINGS / CHECKING accounts
- **Payments** — fund transfers between accounts with a full transaction ledger
- **Notifications** — Kafka-driven inbox updated on every payment event

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot 3.3 |
| Security | Spring Security + JWT (HS256) |
| Database | PostgreSQL |
| Cache / Token store | Redis |
| Messaging | Apache Kafka (KRaft mode) |
| Build | Maven |
| Infrastructure | Docker Compose |

## Getting Started

### Prerequisites

- Docker & Docker Compose
- Java 21+
- Maven 3.9+

### Run

```bash
# 1. Configure environment
cp .env.example .env

# 2. Start infrastructure (PostgreSQL, Redis, Kafka)
docker compose up -d

# 3. Start the application
./mvnw spring-boot:run
```

The API is available at `http://localhost:8080`.

## API Reference

### Auth

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` | Register a new user, returns token pair |
| POST | `/api/auth/login` | Login, returns token pair |
| POST | `/api/auth/refresh` | Rotate refresh token |
| POST | `/api/auth/logout` | Blacklist access token + delete refresh token |

### Accounts

| Method | Path | Description |
|---|---|---|
| POST | `/api/accounts` | Create account (`{"type":"SAVINGS"` or `"CHECKING"}`) |
| GET | `/api/accounts` | List authenticated user's accounts |
| GET | `/api/accounts/{id}` | Get single account |
| GET | `/api/accounts/{id}/transactions` | Transactions for one account |

### Payments

| Method | Path | Description |
|---|---|---|
| POST | `/api/payments/transfer` | Transfer between accounts |
| GET | `/api/payments/transactions` | All transactions for current user |

### Notifications

| Method | Path | Description |
|---|---|---|
| GET | `/api/notifications` | Notification inbox |
| PATCH | `/api/notifications/{id}/read` | Mark one notification read |
| PATCH | `/api/notifications/read-all` | Mark all notifications read |

All endpoints except `/api/auth/**` and `/actuator/health` require a valid `Authorization: Bearer <token>` header.

## Infrastructure Ports

| Service | Host Port |
|---|---|
| Application | 8080 |
| PostgreSQL | 5432 |
| Redis | 6379 |
| Kafka | 9094 |

## Project Structure

```
src/main/java/com/banking/
├── auth/          # JWT auth, Spring Security config, token services
├── user/          # User entity (UserDetails), roles
├── account/       # Bank accounts, balances
├── payment/       # Transfers, transaction ledger, Kafka producer
├── notification/  # Kafka consumer, notification inbox
└── common/        # AppException, GlobalExceptionHandler
```

## Running Tests

```bash
./mvnw test
```
