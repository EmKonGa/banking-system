# Roadmap

## Who owns what

| File | Owner | Scope |
|---|---|---|
| [`developer_roadmap.md`](developer_roadmap.md) | Developer | App code, tests, CI/CD, Docker |
| [`devops_roadmap.md`](devops_roadmap.md) | DevOps / Platform engineer | AWS infrastructure, Terraform |

---

## Quick summary

### Developer (do this first)

1. **Phase 1 — Fix money bugs:** atomic balance update (race condition), OutboxPoller distributed lock, idempotency hardening, Saga pattern (compensating transactions)
2. **Phase 2 — Data layer:** DB schema separation, Flyway, database indexes, HikariCP tuning, pagination
3. **Phase 3 — Resilience & observability:** Kafka DLQ, Loki log aggregation, rate limiting
4. **Phase 4 — CI/CD:** GitHub Actions + Testcontainers integration tests covering the money path
5. **Phase 5 — Advanced architecture:** Reconciliation Service, WebSocket Redis relay, RS256 JWT
6. **Phase 6 — New services:** Loan Service, Fraud Detection, Audit Service
7. **Phase 7 — Learning & polish:** graceful shutdown, API versioning, OpenAPI docs, frontend containerization, caching, Scheduled Payments, Analytics/Reporting, gRPC

### DevOps (starts after Phase 1 is done)

1. **Stage 1 — EC2 + Docker Compose:** manual, ~$2–4/month (stop when idle)
2. **Stage 2 — Managed services:** RDS, ElastiCache, SQS, Parameter Store — manual, one per week
3. **Stage 3 — Terraform:** codify everything from Stages 1–2 into `banking-infra` repo
4. **Stage 4 — ECS + ALB:** proper container orchestration (~$18–25/month)
5. **Stage 5 — CI/CD wired to AWS:** OIDC auth, ECR push, ECS rolling deploy
6. **Stage 6 — Production hardening:** HTTPS, private subnets, Multi-AZ, WAF

---

## Cost at each stage

| Stage | Monthly cost |
|---|---|
| Local development | $0 |
| EC2 + Docker Compose (stop when idle) | ~$2–4 |
| EC2 + managed services (free tier) | ~$0 |
| ECS + ALB | ~$18–25 |
| Production hardened | ~$60–80 |
