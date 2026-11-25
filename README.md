# CIABOC HRMS

End-to-end Human Resource Management Solution for the **Commission to Investigate Allegations of Bribery or
Corruption**, built from the CIABOC HRMS Concept Note (28 Aug 2026) and SRS v0.1 (16 Nov 2026).

| Layer | Technology |
|---|---|
| Presentation | React 18, TypeScript, Vite — navy & gold theme, English / සිංහල / தமிழ் |
| Application / API | Spring Boot 3.4, Java 21, Maven |
| Security | Spring Security, JWT access + rotating refresh tokens, e-mail/SMS one-time code |
| Data | PostgreSQL 16, Flyway migrations |
| Deployment | Docker, Docker Compose, Nginx (HTTPS, security headers, rate limits) |

## Quick start (development)

Prerequisites: Java 21, Maven 3.9, Node 20+, PostgreSQL 16 (or Docker).

```bash
# 1. Database
docker run -d --name hrms-db -e POSTGRES_DB=hrms -e POSTGRES_USER=hrms -e POSTGRES_PASSWORD=hrms -p 5432:5432 postgres:16-alpine

# 2. API (dev profile = sample structure, demo users, OTP shown on screen)
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 3. Web
cd frontend
npm install
npm run dev            # http://localhost:5173  (proxies /api to :8080)
```

Demo accounts (dev profile only), password **`Demo@12345`**:

| User | Role | Notes |
|---|---|---|
| `admin` / `Admin@12345` | System Administrator | configuration only, no HR content |
| `hr`, `hr2` | HR Administrator | two officers so maker-checker can be demonstrated |
| `establishment` | Establishment Officer | structure, cadre, legal documents |
| `adminofficer` | Administrative Officer | recruitment |
| `finance`, `finance2` | Finance Officer | payroll preparer / checker |
| `head` | Division Head (Investigation) | division data scope, first-level approvals |
| `employee` | Employee | self-service |
| `dg` | Management (Director General) | high-level approvals, dashboards |
| `auditor` | Auditor | audit history, access review |

## Production-style stack (Docker Compose)

```bash
cp .env.example .env          # set DB_PASSWORD, JWT_SECRET (openssl rand -base64 48), BOOTSTRAP_ADMIN_PASSWORD
sh nginx/generate-dev-cert.sh # self-signed cert for UAT; use an issued certificate in production
docker compose up -d --build  # https://localhost
docker compose --profile av up -d   # optional ClamAV upload scanning (set CLAMAV_ENABLED=true)
```

The database and API are on an internal network; only Nginx is published.

## Tests

```bash
cd backend && mvn -B test                       # embedded PostgreSQL — no Docker required
cd frontend && npm run typecheck && npm test
```

See [docs/TEST_APPROACH.md](docs/TEST_APPROACH.md).

## Documentation

- [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md) — releases, requirement status, open items, risks
- [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md) — conventions for every module
- [docs/TEST_APPROACH.md](docs/TEST_APPROACH.md)
- API documentation: `http://localhost:8080/api/docs` (development only)

## Key design decisions

- **Nothing hard-coded** (C-02): structure, cadre, working hours, leave rules, rates, approval chains, roles and
  templates are data, editable in the UI, with effective dates and history.
- **Approvals** run through one configurable workflow engine; requesters can never approve their own requests.
- **Audit** is append-only and hash-chained; a database trigger rejects UPDATE/DELETE/TRUNCATE.
- **Data scope** (whole institution / own division / self) and **restricted records** apply to every list, report
  and export.
- **Sign-in** uses password + one-time code now and is designed to be replaced by the CIABOC SSO/IAM platform (INT-01).
