# CIABOC HRMS — Project Plan and Status

Sources: CIABOC HRMS Concept Note (28 Aug 2026) and SRS v0.1 (16 Nov 2026). Status as of 18 Nov 2026.

**Build status:** 41 backend tests pass on a clean build (embedded PostgreSQL), 6 frontend tests pass, and the
TypeScript typecheck and production build are clean. All requirements below are *built*, not yet *accepted*: they
still need the CIABOC confirmations listed in §4 and UAT sign-off by the business process owners (SRS §10.2).

## 1. Releases (SRS §10.1)

| Release | Content | Status |
|---|---|---|
| 1 Foundation | Master data, users/roles/RBAC, OTP + JWT sign-in, workflow engine, audit history, establishment and cadre, legal documents, employee management | Built and tested |
| 2 Core services | Leave, attendance, notifications, employee self-service, core reports, dashboards | Built and tested |
| 3 Compensation | Payroll and salary, deductions, seconded-employee loans, benefits and insurance | Built and tested |
| 4 Talent | Recruitment and appointments, performance evaluation, training | Built and tested |
| 5 Extended | Transfers and promotions, retirement and offboarding, letters and certificates, digitization | Built and tested (Should-have items only) |

## 2. Requirement status by module

| SRS area | Requirements | Status | Notes |
|---|---|---|---|
| 4.1 Establishment | FR-EST-001…061 | Built | All changes go through approval and are effective-dated; org chart viewable as at any past date; cadre shows approved / proposed / occupied / vacant |
| 4.1.4–4.1.6 Legal | FR-EST-030…052 | Built | Acts, Gazettes, regulations and circulars with versions, provisions, designation links, topics, verification status and amendments. Provision text must be entered from the official Gazette |
| 4.2 Employee | FR-EMP-001…014 | Built | NIC validation, digital personal file, service history, maker-checker on sensitive fields, self-service change requests, CSV import with reconciliation |
| 4.3 Recruitment | FR-REC-001…010 | Built | FR-REC-004 online applications (Could-have) deferred; HR enters applications |
| 4.4 Leave | FR-LEV-001…010 | Built | Leave crossing a calendar year must be split; amending approved leave = cancel and re-apply; no pro-rating for new joiners |
| 4.5 Attendance | FR-ATT-001…023, HW-01, INT-04 | Built | Device ingestion via CSV and JSON API (switchable); processing uses the employee's current unit |
| 4.6 Payroll | FR-PAY-001…035 | Built | Maker-checker, locked periods, controlled adjustments, payslips, bank advice. Rates as at the last day of the period (no mid-month pro-rating) |
| 4.7 Benefits | FR-BEN-001…006 | Built | Agrahara and other schemes, expiry notifications; provider API is a disabled stub until approved |
| 4.8 Performance | FR-PER-001…009 | Built | Models per employee category; 3 sample models seeded pending confirmation |
| 4.9 Training | FR-TRN-001…004 | Built | Nominations approved by division head and HR, with a capacity check |
| 4.10 Workflow | FR-WFL-001…010 | Built | Requests never stall without an approver: a head's own request goes to the parent unit's head, and an empty stage falls back to `workflow.fallback-role` |
| 4.11 Master data | FR-MDM-001…003 | Built | Values in use cannot be deactivated |
| 4.12 Reports | FR-RPT-001…006, §8 | Built | 57 catalogue reports, exportable to PDF/Excel/CSV, filtered by role and data scope; exports are audit-logged |
| 4.13 Notifications | FR-NOT-001…005 | Built | In-app, plus e-mail/SMS queued with retry; simulated until a mail/SMS relay is configured |
| 4.14 User and access | FR-UAM-001…008 | Built | Local sign-in (password + one-time code) until the CIABOC SSO/IAM is connected (INT-01) |
| 4.15 Audit | FR-AUD-001…006 | Built | Hash-chained log; the database rejects UPDATE, DELETE and TRUNCATE |
| 4.16 Extended | FR-EXT-001…005, 011 | Built | See §3 for items not built |
| §5 System-wide | SYS-01…14 | Built | ClamAV upload scanning is optional (Compose profile `av`) |
| §6 Non-functional | NFR-* | Partly | Security controls are in place. Performance targets, DR objectives, penetration test, DPIA and threat model are go-live activities (§5) |

## 3. Not built in this release (PM decision)

These are Could-have items (SRS 4.16, priority C), to be confirmed by the Steering Committee (SRS 11.1 #17):
FR-EXT-006 travel, claims and welfare loans · FR-EXT-007 grievance and disciplinary cases · FR-EXT-008 health,
safety and wellbeing · FR-EXT-009 workforce planning and succession · FR-EXT-010 HR helpdesk · FR-REC-004 online
applications.

## 4. Items requiring CIABOC confirmation (SRS §11.1)

Everything below is **configurable data** seeded with sample values. Nothing needs a code change once confirmed.

| # | Area | Seeded placeholder | Owner |
|---|---|---|---|
| 1–3 | Structure, cadre, designations | Sample divisions, designations and cadre (dev profile only) | Administration / Establishment |
| 4 | Legal | Anti-Corruption Act No. 9 of 2023 record with placeholder provisions, marked Draft | Legal Division |
| 5 | Employee fields / statuses | Status list in master data | Administration |
| 6 | Recruitment procedure | Selection approval by Management | Administration |
| 7 | Leave | Six leave types with entitlements per category | Administration |
| 8 | Attendance | 08:30–16:15 schedule, grace minutes, no-pay rules | Administration / ICT |
| 9–10 | Payroll, loans | EPF 8%/12%, ETF 3%, W&OP and pension rates; loan procedures | Finance |
| 11 | Insurance | Agrahara scheme | Administration / Finance |
| 12 | Performance | 3 sample models | Administration |
| 13–14 | Training, approval chains | Seeded workflows (editable in *Approval workflows*) | Administration / DG's office |
| 15–19 | Self-service, integrations, hosting, retention | See §5 | ICT / DPO / Records |

Sinhala and Tamil texts (navigation, templates, master data) should be reviewed by CIABOC's official translators.

## 5. Before go-live (Concept Note §9, SRS C-07)

- Connect the CIABOC SSO/IAM (INT-01), approved SMTP and SMS relays, and attendance devices (INT-04).
- Install the issued TLS certificate; set `JWT_SECRET`, database and admin secrets; keep the
  `prod` profile.
- Run the DPIA, threat model, independent penetration test and performance tests at the confirmed user numbers.
- Encrypt the database and file volumes at rest; set up backups, test restores and the DR plan with agreed RTO/RPO.
- Set retention periods per record class (NFR-RET-01).
- Migrate legacy data with cleansing, trial loads and reconciliation sign-off (DR-06), then UAT and go-live authorization.
- Embed a Unicode font (e.g. Noto Sans Sinhala) at `backend/src/main/resources/fonts/report-font.ttf` so Sinhala and
  Tamil print correctly in PDF exports, then verify in UAT.

## 6. Key risks (SRS §11.3)

| Risk | Mitigation in the build |
|---|---|
| Rules change after build | Rules, rates, chains, schedules and templates are effective-dated data |
| Automating unconfirmed processes | All seeded rules are marked "to be confirmed" in their descriptions |
| Privacy incident | RBAC, division/self data scope, restricted records, audited views and exports, no confidential text in notifications |
| Integration dependency | Each integration is switchable and messages are queued; the core works without them |
| Vendor lock-in | Open-source stack, documented APIs (`/api/docs` in dev), Flyway schema in source control |
