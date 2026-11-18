# CIABOC HRMS — Test Approach

Every requirement in the SRS carries an ID (FR-…, NFR-…, SYS-…). Each must be traceable to at least one test
(SRS §10.2). This document describes how each layer of the stack is tested.

| Layer | What is verified | How | Tooling |
|---|---|---|---|
| **Presentation** (React 18, TypeScript, Vite) | Screens render per role; forms validate; route guards block unauthorized pages; token refresh and idle sign-out | Static type checking on every build; component tests for shared UI; manual role walk-throughs in UAT | `tsc --noEmit`, Vitest + Testing Library |
| **Application / API** (Spring Boot, Java 21) | Endpoints, validation, business rules (leave balance, payroll calculation, cadre, workflow), error envelope | Unit tests for pure calculation classes; integration tests through HTTP (MockMvc) against a real database | JUnit 5, AssertJ, MockMvc |
| **Security** (Spring Security, JWT, OTP) | Password + OTP sign-in, wrong password / wrong OTP rejection, lockout, token expiry, refresh-token rotation and reuse detection, RBAC per endpoint, data scope (division / self), restricted records, segregation of duties | Integration tests using each demo role; negative tests expecting 401/403/422 | `FoundationIntegrationTest` and module tests |
| **Data** (PostgreSQL, Flyway) | Migrations apply cleanly from empty; Hibernate schema validation; constraints (NIC format, unique codes, effective dates); audit-log immutability trigger; transactions roll back on rule violations | Every integration test boots an embedded PostgreSQL and runs all migrations + demo data | zonky embedded-postgres |
| **Deployment** (Docker, Compose, Nginx) | Containers start in order with health checks; Nginx serves the SPA, proxies `/api`, redirects HTTP→HTTPS, applies security headers and rate limits; configuration via environment | `docker compose config`; smoke test after `docker compose up` (sign-in, dashboard, one approval); header check with `curl -I` | Docker Compose |

## Test levels

1. **Unit** — pure logic without Spring (e.g. payroll calculator, weighted appraisal score, working-day counting).
2. **Integration** — `IntegrationTestBase` boots the full application on an embedded PostgreSQL with demo data and
   demo users, then drives the HTTP API exactly as the browser does.
3. **System / smoke** — the Docker stack on a UAT host with the `prod` profile.
4. **UAT** — business process owners execute scenarios per module and sign off (SRS §10.2).
5. **Security assurance** — OWASP Top 10 review, dependency scanning, independent penetration test, DPIA and threat
   model before go-live (NFR-SEC-06, -09; C-07).
6. **Performance** — standard screens < 3 s, standard reports < 30 s, payroll within the agreed window, at the
   confirmed number of concurrent users (NFR-PER-01..04).

## Running

```bash
cd backend  && mvn -B test          # unit + integration (embedded PostgreSQL, no Docker needed)
cd frontend && npm run typecheck && npm test
```

## Foundation coverage (Release 1)

`FoundationIntegrationTest` covers: unauthenticated rejection; wrong password and wrong OTP; refresh-token rotation and
reuse rejection; RBAC (employee cannot list employees); division data scope; restricted records hidden; cadre change
applied only after approval; requester cannot approve own request; non-approver refused; effective-dated
reorganization with history and point-in-time view; structural cycle prevention; employee validation (NIC format and
uniqueness) and service history; maker-checker on sensitive fields; every establishment report runs; CSV/Excel/PDF
exports; audit chain verification and database-level immutability; dashboard; master data in-use protection; System
Administrator has no HR-content access by default.
