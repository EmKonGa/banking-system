# DevOps Roadmap

Everything here is owned by the DevOps / platform engineer. Requires an AWS account and Terraform knowledge.

---

## What the developer hands you

Before starting any stage here, the developer should have delivered:

- [ ] Dockerfiles for all 6 services (api-gateway, auth, account, payment, notification, frontend/nginx)
- [ ] `docker-compose.yml` that starts the full stack locally with one command
- [ ] Health check endpoint on every service (`/actuator/health`)
- [ ] Env var list per service (see below)
- [ ] GitHub Actions CI workflow that builds and pushes Docker images to ECR on merge to main

### Env vars each service expects

| Var | Services | Notes |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD` | auth, account, payment, notification | Each service uses its own schema |
| `REDIS_HOST`, `REDIS_PASSWORD` | all | |
| `JWT_SECRET` | all | Rotate via Parameter Store |
| `INTERNAL_SECRET` | account, payment | For `/internal/**` service-to-service calls |
| `KAFKA_BOOTSTRAP_SERVERS` or SQS queue URLs | payment, notification | Depends on messaging choice |
| `PAYMENT_SERVICE_URL` | account | Feign client base URL |
| `ACCOUNT_SERVICE_URL` | payment | Feign client base URL |
| `OTEL_EXPORTER_ENDPOINT` | all | Tempo / X-Ray endpoint |

---

## Two-Repo Setup

| Repo | Owned by | Contains |
|---|---|---|
| `banking-system` | Developer | Spring Boot code, Dockerfiles, GitHub Actions CI/CD |
| `banking-infra` | DevOps | Terraform that provisions all AWS resources |

The `banking-infra` repo outputs values (RDS endpoint, Redis host, SQS URLs) that the developer's GitHub Actions reads and passes as env vars into ECS task definitions.

---

## Learning Principle

> **Always do something manually in the AWS console before writing Terraform for it.**
> Terraform is just automating what you already know how to do by hand.
> Learning Terraform before understanding the underlying AWS resource means copy-pasting config you don't understand.

---

## Stage 1 — EC2 + Docker Compose (Manual, No Terraform)

**Goal:** prove the app runs outside a developer's machine. Learn AWS fundamentals hands-on.

**Cost:** ~$0.02/hr while running. Stop the instance when not testing → ~$2–4/month.

### Prerequisites (app side)
- Full Docker Compose stack works locally
- Kafka replaced with SQS (MSK costs $200–400/month; SQS is free)

### Steps — all done in the AWS console

1. **Billing safety first**
   - Billing → Budgets → create a **$10 alert**
   - Save: `aws ec2 stop-instances --instance-ids <id>`

2. **Create a VPC**
   - CIDR: `10.0.0.0/16`
   - One public subnet: `10.0.1.0/24`
   - Attach an Internet Gateway
   - Add a route: `0.0.0.0/0 → IGW`

3. **Launch EC2**
   - AMI: Amazon Linux 2023
   - Instance type: **t3.small** (2 GB RAM — t2.micro is too small for 5 Spring Boot services)
   - Place in public subnet, auto-assign public IP
   - Create a key pair, download `.pem`
   - Security Group: allow SSH (22) from your IP, HTTP (8080) from anywhere

4. **Install Docker on EC2**
   ```bash
   sudo yum update -y
   sudo yum install docker -y
   sudo service docker start
   sudo usermod -aG docker ec2-user
   # Install Docker Compose
   sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
   sudo chmod +x /usr/local/bin/docker-compose
   ```

5. **Deploy**
   ```bash
   git clone <repo-url>
   cd banking-system
   cp .env.example .env  # fill in secrets
   docker compose up -d
   ```

6. **Open port 8080** in the Security Group → access via `http://<ec2-public-ip>:8080`

**What you learn:** EC2, VPC, Security Groups, SSH key pairs, Elastic IP, IAM basics.

---

## Stage 2 — Managed Services (Manual, One Per Week)

Keep the EC2 running Docker Compose. Replace one container per week with an AWS managed service.
Each swap is a single isolated change — if it breaks, you know exactly what caused it.

### Week 1 — RDS PostgreSQL (replaces the DB container)

**Free tier:** db.t3.micro, 20 GB storage, 750 hours/month for 12 months.

1. Create a **DB Subnet Group** using two private subnets (create private subnets `10.0.2.0/24`, `10.0.3.0/24`)
2. Create a **Security Group** for RDS — allow port 5432 from the EC2 security group only
3. Launch **RDS PostgreSQL 16** in the DB subnet group
4. Create schemas: connect via psql and run:
   ```sql
   CREATE SCHEMA banking_auth;
   CREATE SCHEMA banking_account;
   CREATE SCHEMA banking_payment;
   CREATE SCHEMA banking_notification;
   ```
5. Update `.env` on EC2: `DB_HOST=<rds-endpoint>`
6. Remove the `postgres` service from `docker-compose.yml`, `docker compose up -d`

**What you learn:** RDS, DB subnet groups, private subnets, security group rules between EC2 and RDS.

### Week 2 — ElastiCache Redis (replaces the Redis container)

**Free tier:** cache.t3.micro, 750 hours/month for 12 months.

1. Create a **Security Group** for ElastiCache — allow port 6379 from the EC2 security group only
2. Create an **ElastiCache Redis** cluster (single-node, cache.t3.micro) in the private subnets
3. Update `.env` on EC2: `REDIS_HOST=<elasticache-endpoint>`
4. Remove the `redis` service from `docker-compose.yml`, `docker compose up -d`

**What you learn:** ElastiCache, single-node vs cluster mode, VPC routing.

### Week 3 — Amazon SQS (replaces Kafka)

**Cost:** Always free up to 1M requests/month.

1. Create two SQS queues:
   - `payment-events` (standard queue)
   - `payment-events-dlq` (dead-letter queue, max receives: 3)
   - Configure `payment-events` to send to DLQ after 3 failures

2. Create an **IAM Role** for the EC2 instance:
   - Trust policy: `ec2.amazonaws.com`
   - Permissions: `sqs:SendMessage`, `sqs:ReceiveMessage`, `sqs:DeleteMessage` on both queues
   - Attach the role to the EC2 instance (Actions → Security → Modify IAM role)
   - No access keys needed — EC2 instance metadata provides credentials automatically

3. Update `.env` on EC2 with the SQS queue URLs
4. Remove the `kafka` service from `docker-compose.yml`, `docker compose up -d`

**What you learn:** SQS, IAM instance roles, DLQ, message visibility timeout, no-credentials auth.

### Week 4 — Parameter Store (replaces plain env vars)

**Cost:** Standard parameters are free.

1. Store each secret as a **SecureString** parameter:
   ```
   /banking/JWT_SECRET
   /banking/INTERNAL_SECRET
   /banking/DB_PASSWORD
   /banking/REDIS_PASSWORD
   ```
2. Add `ssm:GetParameters` and `ssm:GetParametersByPath` to the EC2 IAM role
3. Spring Cloud AWS pulls these at startup — no `.env` file needed for secrets

**What you learn:** SSM Parameter Store, IAM policies, least-privilege access.

### After Stage 2

```
Internet
    ↓
EC2 (Docker: api-gateway + 4 services + nginx frontend)
    ↓              ↓              ↓
RDS PostgreSQL  ElastiCache    SQS queues
(free tier)     Redis          (always free)
                (free tier)
```

**Cost:** ~$0 in free tier window (12 months), ~$25–30/month after.

---

## Stage 3 — Write Terraform for Everything You've Done Manually

Now that you understand every AWS resource from doing it by hand, codify it.
Create the `banking-infra` repo.

```
banking-infra/
├── main.tf
├── variables.tf
├── outputs.tf
├── vpc.tf           # VPC, subnets, internet gateway, route tables
├── security.tf      # Security groups
├── ec2.tf           # EC2 instance + IAM instance profile
├── rds.tf           # RDS PostgreSQL
├── elasticache.tf   # Redis cluster
├── sqs.tf           # SQS queues + DLQ
├── ssm.tf           # Parameter Store entries
└── ecr.tf           # ECR repositories (one per service image)
```

**Workflow:**
1. `terraform destroy` — tear down everything you built manually
2. `terraform apply` — recreate it from code
3. If the output is identical, your Terraform is correct

**What you learn:** Terraform state, HCL syntax, `plan` vs `apply`, resource dependencies, remote state (S3 backend).

---

## Stage 4 — ECS + ECR + ALB (~$18–25/month)

Move from Docker-on-EC2 to proper container orchestration. Add to `banking-infra` repo.

**Cost driver:** ALB costs ~$16–18/month regardless of traffic. This is the only unavoidable non-free cost in this stage. Skip until you're ready to pay.

Add to `banking-infra`:
```
ecs-cluster.tf    # ECS cluster (EC2 launch type reusing existing instance)
ecs-services.tf   # One ECS service + task definition per Spring Boot app
alb.tf            # ALB + target group + listener rules
iam-tasks.tf      # IAM task roles (ECS tasks read Parameter Store, write to SQS)
```

**Task definition key fields per service:**
- Image: `<account-id>.dkr.ecr.<region>.amazonaws.com/banking-<service>:<git-sha>`
- CPU / memory: 256 CPU units / 512 MB RAM per service (adjust based on load)
- Environment: pulled from Parameter Store via `valueFrom` (no plaintext secrets in task def)
- Health check: `curl -f http://localhost:<port>/actuator/health`
- Log driver: `awslogs` → CloudWatch Logs group `/banking/<service-name>`

**What you learn:** ECS task definitions, services, rolling deploys, ALB target groups, HTTPS via ACM, IAM task roles.

---

## Stage 5 — Full CI/CD with GitHub Actions

Wire GitHub Actions (developer's repo) to deploy automatically on merge to main.

GitHub Actions needs permission to push to ECR and update ECS services. Use **OIDC** — no long-lived AWS access keys stored in GitHub secrets.

```yaml
# .github/workflows/deploy.yml (in banking-system repo)

- name: Configure AWS credentials
  uses: aws-actions/configure-aws-credentials@v4
  with:
    role-to-assume: arn:aws:iam::<account-id>:role/github-actions-deploy
    aws-region: ap-southeast-1

- name: Push to ECR
  run: |
    docker build -t $ECR_REGISTRY/banking-auth:${{ github.sha }} ./auth-service
    docker push $ECR_REGISTRY/banking-auth:${{ github.sha }}

- name: Deploy to ECS
  run: |
    aws ecs update-service \
      --cluster banking-cluster \
      --service banking-auth \
      --force-new-deployment
```

**IAM role for GitHub Actions** (`github-actions-deploy`):
- Trust: `token.actions.githubusercontent.com` (OIDC provider)
- Permissions: `ecr:PutImage`, `ecs:UpdateService`, `ecs:RegisterTaskDefinition`

**What you learn:** GitHub Actions OIDC auth, ECR push, ECS rolling deploy, IAM OIDC trust policies.

---

## Stage 6 — Production Hardening (when ready to spend more)

| Item | What it does | Cost |
|---|---|---|
| HTTPS + ACM certificate | ALB terminates TLS, redirect 80 → 443 | Free (ACM cert is free) |
| Route 53 domain | `yourdomain.com → ALB` | ~$12/year for domain |
| Private subnets + NAT Gateway | Move ECS tasks off public subnets | ~$32/month |
| Multi-AZ RDS | Standby replica in second AZ | ~2× RDS cost |
| Multi-AZ ElastiCache | Read replica in second AZ | ~2× cache cost |
| AWS Secrets Manager | Auto-rotation of DB passwords | ~$0.40/secret/month |
| AWS WAF | Block malicious traffic at ALB | ~$5/month base |
| CloudWatch alarms | Alert on error rate, CPU, memory | Pay per alarm |
| AWS Cloud Map | Service discovery by name (replaces hardcoded URLs) | ~$0.10/namespace/month |
| **CloudFront CDN** | Serve Angular frontend static assets from edge locations — lower latency, reduces EC2/nginx load. Point CloudFront at the nginx origin, enable caching for `/assets/**` and `*.js`/`*.css`. | ~$0 (free tier: 1TB/month) |
| **DB sharding** | Horizontal partitioning when a single RDS instance can't handle write volume. Shard `transactions` by `user_id` range or hash. Not needed until you have millions of rows and measurable write latency. Evaluate before implementing. | Depends on RDS instance size |

---

## Cost Summary

| Stage | Monthly cost | What's running |
|---|---|---|
| Stage 1 — EC2 + Docker Compose | ~$2–4 (stop when idle) | Everything in Docker on one EC2 |
| Stage 2 — Managed services | ~$0 (free tier) | EC2 + RDS + ElastiCache + SQS |
| Stage 3 — Terraform | ~$0 | Same as Stage 2, now in code |
| Stage 4 — ECS + ALB | ~$18–25 | ALB is the main cost |
| Stage 5 — CI/CD added | ~$18–25 | GitHub Actions is free |
| Stage 6 — Production hardened | ~$60–80 | NAT Gateway + Multi-AZ adds cost |

---

## Timeline

| Stage | Estimate |
|---|---|
| Stage 1 — EC2 + Docker Compose (manual) | 1–2 days |
| Stage 2 — Managed services (manual, one per week) | ~4 weeks |
| Stage 3 — Terraform for Stages 1–2 | ~1–2 weeks |
| Stage 4 — ECS + ALB (Terraform) | ~1–2 weeks |
| Stage 5 — GitHub Actions CI/CD | ~3–5 days |
| Stage 6 — Production hardening | When ready |
