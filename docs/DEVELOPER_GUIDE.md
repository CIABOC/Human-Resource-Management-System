# CIABOC HRMS — Developer Guide

Conventions every module follows. Read this before adding a module. The **Establishment** and **Employee** modules
(`backend/.../establishment`, `backend/.../employee`) are the reference implementations.

## Stack

| Layer | Technology | Responsibility |
|---|---|---|
| Presentation | React 18, TypeScript, Vite | Screens, forms, route protection, token handling |
| Application / API | Spring Boot 3.4, Java 21, Maven | Endpoints, validation, business rules, error handling |
| Security | Spring Security, JWT (+ e-mail/SMS OTP) | Login, token issue/expiry/refresh, roles & permissions |
| Data | PostgreSQL 16, Flyway | Schema, constraints, transactions, migrations |
| Deployment | Docker, Docker Compose, Nginx | Containers, reverse proxy, HTTPS, configuration |

## Backend

Package per module: `lk.gov.ciaboc.hrms.<module>`. Layering: entity → repository → service → controller.

### Entities and schema
- Business entities extend `common.BaseEntity` → the table **must** have
  `id BIGSERIAL PRIMARY KEY, version BIGINT, created_at TIMESTAMPTZ, created_by VARCHAR(100), updated_at TIMESTAMPTZ, updated_by VARCHAR(100)`.
- Hibernate runs with `ddl-auto: validate`; column types must match: `Instant`→`TIMESTAMPTZ`, `LocalDate`→`DATE`,
  `LocalTime`→`TIME`, `BigDecimal` money→`NUMERIC(14,2)`, `boolean`→`BOOLEAN`, `int/Integer`→`INTEGER`, `long/Long`→`BIGINT`,
  enums (`@Enumerated(STRING)`)→`VARCHAR`, long text→`TEXT` with `@Column(columnDefinition = "text")`.
- Avoid SQL keywords as table names (`position`, `user`, `order`...). Prefix instead (`org_position`).
- Reference other modules' records **by id** (`Long employeeId`), not `@ManyToOne` across modules.
- Things that change over time are **effective-dated** (`effective_from`, `effective_to` exclusive) and never overwritten
  (SYS-03/04, DR-01). Implement `common.EffectiveDated`.
- **Nothing hard-coded** (C-02, SYS-02, DR-03): rates, rules, working hours, entitlements, approval chains live in tables
  or `system_setting` rows seeded by migrations and editable in the UI.

### Migrations (Flyway, `src/main/resources/db/migration`)
Version ranges are reserved per module to avoid clashes:

| Range | Module |
|---|---|
| V1–V9 | Foundation (security, audit, workflow, notifications, master data, establishment, legal, employee) |
| V10–V19 | Leave and Attendance |
| V20–V29 | Payroll and Benefits |
| V30–V39 | Recruitment, Performance, Training |
| V40–V49 | Extended scope (transfers, promotions, retirement, certificates) |

Development-only sample data goes in `src/main/resources/db/demo/R__demo_<module>.sql` (repeatable, **idempotent**:
`ON CONFLICT DO NOTHING` / `WHERE NOT EXISTS`). Never change a migration that has been applied; add a new one.

### Shared services (use these, do not re-implement)
| Need | Use |
|---|---|
| Current user, employee id | `security.CurrentUser.get()`, `CurrentUser.employeeId()` |
| Errors | `throw ApiException.badRequest/rule(code,msg)/notFound/conflict/forbidden(...)` → JSON envelope |
| Audit trail | `AuditService.log(AuditAction, entityType, id, summary[, oldValue, newValue])` for every create/update/approve/view of sensitive data/export |
| Approvals | `WorkflowService.start(processCode, entityType, entityId, title, frontEndLink, subjectEmployeeId)`; implement `workflow.WorkflowHandler` on your service (callbacks `onApproved/onRejected/onReturned/onResubmitted/onCancelled`, and `summary()` for the approver screen). Seed `workflow_definition` + `workflow_stage` rows in your migration. Requesters can never approve their own requests. |
| Notifications | `NotificationService.notifyEmployee/notifyUser/notifyRole(templateCode, vars, link)`; seed trilingual rows in `notification_template`. Only include data the recipient may see. |
| Employee data | `employee.EmployeeLookup`: `brief(s)`, `inService()`, `requireAccess(id)` (scope + restricted records), `scopeUnits()`, `visibleEmployeeIds()`. Never return the `Employee` entity from another module. |
| Placement / status changes | `employee.EmployeeService.assign(...)`, `changeStatus(...)`, `create(...)` |
| Master data | `MasterDataService.requireActive(category, code, label)`; add a `MasterDataUsageChecker` bean if you store codes |
| Settings | `SettingsService.getInt/getDecimal/getBool/get(key, default)` |
| Files | `FileStorageService.store(file, module)` / `download(fileId)` — authorize first, then download |
| Reports | `@Component` implementing `reports.ReportProvider` returning `ReportDefinition.builder(...)` definitions. SQL must include `/*SCOPE:e*/` when it lists employees (alias `e`). Exports, scope and audit are automatic. |
| Dashboard | `@Component` implementing `dashboard.DashboardContributor` (tiles/charts filtered by the user's permissions) |
| Cross-module contracts | `common.contracts.*` (e.g. `NoPaySource`) — obtain via `ObjectProvider` |

### Security
- Every endpoint is authenticated. Guard with `@PreAuthorize("hasAuthority('PERM')")` using `security.Permission` names.
- Self-service endpoints act on `CurrentUser.employeeId()` only. Viewing another employee's data needs the module
  permission **and** `EmployeeLookup.requireAccess(employeeId)`.
- Proposed roles → permissions are seeded in `V2__reference_data.sql` (SRS §9) and editable in *Users & Roles*.

### Tests
- Integration tests extend `support.IntegrationTestBase` (embedded PostgreSQL, real migrations, demo data). Demo users
  (password `Demo@12345`): `hr`, `hr2`, `establishment`, `adminofficer`, `finance`, `finance2`, `head` (Investigation
  division head), `employee` (Investigating Officer CIA-0008), `dg` (Management), `auditor`; `admin`/`Admin@12345`.
- Run: `cd backend && mvn -B -o test -Dtest=YourTest`.

## Frontend

- One folder per module: `src/modules/<key>/` with `module.tsx` exporting a `ModuleDef` (`nav`, `routes`, `i18n`).
  `registry.ts` already lists every module; you never edit shared files to add pages.
- Pages are lazy-loaded (`lazy(() => import('./MyPage'))`). Route guards: `anyOf: ['PERM', ...]`.
- Data: `api` (axios, base `/api`, auto token refresh), `useApi<T>(url, params)`, `download(url, {method, body})`,
  `errorMessage(e)`.
- UI kit (`@/components/ui`, `DataTable`, `pickers`, `WorkflowTrail`, `Toast`): `PageHeader`, `Card`, `Modal`,
  `ConfirmDialog`, `TextField`, `SelectField`, `TextArea`, `Checkbox`, `StatusBadge`, `Badge`, `Tabs`, `DL`, `KpiCard`,
  `Alert`, `Loading`, `Empty`, `DataTable`, `fmtDate`, `fmtDateTime`, `fmtMoney`, `MasterDataSelect`, `OrgUnitSelect`,
  `DesignationSelect`, `EmployeeSelect`, `useMasterDataLabels`, `WorkflowTrail`, `useToast`, `useAuth().can(...)`.
- Theme: classes in `src/styles/theme.css` (`btn btn-primary|btn-gold|btn-outline|btn-ghost`, `grid grid-2..4`,
  `form-grid`, `filters`, `card`, `badge-*`). Navy for primary actions, gold for the single most important action.
- Text: `t('module.key', 'English default')`; supply Sinhala (`si`) and Tamil (`ta`) at least for navigation labels and
  page titles in the module's `i18n` (translations to be reviewed by CIABOC before go-live).
- Approvals need no module UI: the shared Approvals screen shows `WorkflowHandler.summary()`. Link requests to
  `/approvals/{instanceId}` and show `<WorkflowTrail entityType=... entityId=... />` on detail screens.
- Check: `cd frontend && npx tsc --noEmit` (strict, no unused locals).
