# HRMS – Organization, Establishment and Cadre Management — Requirements v2.1 (draft)

Transcribed from the CIABOC specification (based on requirements gathering of 2026.10.07, approved UI screens, the
proposed cadre/structure and the `hrms.html` prototype). Requirement IDs are cited in code and tests.

## 1.3 Design principle
Nothing is hard-coded. Every policy, rate, threshold, lead time, approval path, template and master list is
maintained by authorised administrators through the UI. Every change is versioned, effective-dated and audited.

## 1.4 Definitions
Cadre = approved posts for a designation in a division. Approved / Filled / Acting / Vacant = approved posts; posts
held by confirmed officers; posts held by acting officers; approved posts with no holder. Excess/Shortage =
(Filled + Acting) − Approved. CCR = Cadre Change Request. SOR = Scheme of Recruitment. Head Office / Regional =
location split (Legal and Investigation). Dying cadre = no fresh recruitment; posts lapse as holders leave.

## 2. Organizational structure
Loaded by migration `V52__proposed_structure_and_cadre.sql`: 971 posts (excluding the statutory Director General),
units with Head Office/Regional locations, 28 designations with levels, reporting chains, one post record per
approved post, control totals for reconciliation, position categories. Statutory basis of the DG post
(Anti-Corruption Act No. 9 of 2023, s.17(1); Additional DG acts under s.25(2)) recorded once and shown on records
about the post.

## 2.5 User roles
| Role | Responsibilities |
|---|---|
| Administration Officer (`ADMIN_OFFICER`) | Master data, cadre requests, categorization drafts, SOR drafts, exam emails |
| Head of HR (`HEAD_OF_HR`) | Approves cadre, categorization, policy and No-Pay changes; receives alerts |
| Publisher (`PUBLISHER`) | Only role allowed to publish approved SORs and vacancies |
| Approver (per workflow) | Acts on requests routed by the workflow engine |
| Division Head (`DIVISION_HEAD`) | Receives vacancy alerts; supervisor in workflows |
| The Commission (`COMMISSION`) | Final approving authority for cadre changes |
| Payroll Accountant (`PAYROLL_ACCOUNTANT`) | Officer in charge for inherited liability deductions |

## 3. General requirements
- G-1 All configuration through the UI; no code change/redeployment.
- G-2 Policies, rates, structures are versioned; earlier versions retained, never overwritten.
- G-3 Every change carries an effective date; past records not recalculated unless explicitly required.
- G-4 Approval-sensitive changes follow the configured approval workflow (module 10).
- G-5 All significant actions audited: who, when, from, to, remarks.
- G-6 RBAC limits who can create, approve and publish.
- G-7 Notices, letters and SOR content in Sinhala, Tamil and English.
- G-8 Desktop and tablet; navigation: Overview, Establishment, Pay & Benefits Setup, Recruitment, People.

## 4.1 Establishment dashboard
- D-1 Approved cadre, filled (incl. acting), vacant and forecast posts, pending approvals (with overdue SLA count).
- D-2 Totals from live cadre data across all active divisions.
- D-3 Upcoming vacancy alerts: post, division, trigger, vacancy date, alert level (90/60/30-day or Open).
- D-4 "Awaiting my approval": items routed by the workflow engine (cadre change, SOR, categorization, policy, insurance claim).
- D-5 Configuration Center linking every configurable master and rule set: duty time policies, salary schemes, vacancy alert rules, approval workflows, No-Pay rules, KPIs and rating scales, insurance providers, templates.
- D-6 Cadre health per division: filled vs approved.
- D-7 "Expiring & due soon": policy renewals, secondment ends, appraisal deadlines, contract ends; configurable lead times.

## 4.2 Official duty time configuration (REQ 1)
- 1.1 Work-time policies: fixed, flexible (core hours) and shift roster types; in/out times, grace period, minimum working hours, late and early thresholds, half-day threshold, lunch break, working days.
- 1.2 Assigned to divisions, categories or individual employees with effective-from/optional effective-to, authority reference and reason.
- 1.3 Resolution priority: employee override → category → division → organization default. Effective Policy Lookup shows which policy applies and why.
- 1.4 Shift patterns (e.g. A/B/C) incl. shifts crossing midnight and a repeating rotation cycle.
- 1.5 Saving creates a new version, goes through approval, applies from the effective date only; past attendance not recalculated.
- 1.6 Attendance and No-Pay read these values at run time.

## 4.3 Salary scales and schemes (REQ 2 and 4)
- 2.1 Payroll calculates; this module supplies configuration only.
- 2.2 Salary scales by salary code: initial step, increments, maximum, linked grades, effective date.
- 4.1 Scheme master (Pension, WNOP, EPF, ETF, welfare, insurance, others): type, calculation basis, salary components included, employee and employer rates, payer, default eligibility, Payroll GL code, authority reference, effective date.
- 4.2 Per-employee assignment (pensionable: Pension + WNOP; non-pensionable: EPF/ETF). Rate override per employee only with a recorded reason.
- 4.3 Scheme changes go through approval; change log records every edit.
- 4.4 Payroll receives scheme, basis, rate, payer and dates; keeps no separate copy of rates.

## 4.4 Liability transfer for seconded officers (REQ 3)
- 3.1 Record seconded status and each inherited commitment: loan name, original organization, monthly instalment, outstanding balance, first deduction month, end date, authority/arrangement reference, remittance target, arrangement letter (PDF).
- 3.2 Deductions passed to Payroll monthly until balance zero or arrangement ends; balance reduces; ledger per month (queued, posted, failed).
- 3.3 Alerts to officer in charge: balance reaches zero; 30 days (configurable) before arrangement ends; monthly deduction fails in Payroll.
- 3.4 Statuses Active, Completing, Completed, Suspended, Deduction failed; exportable.

## 4.5 Vacancy alerts and online applications (REQ 5)
- 5.1 Vacancy created automatically when a post becomes unfilled: retirement, resignation, end of contract/secondment/service period, death, promotion or transfer out, or cadre increase. Triggers configurable. Manual vacancy too.
- 5.2 Early alerts before the vacancy date; lead times (e.g. 90/60/30) configurable.
- 5.3 Alerts to configurable recipients (Administration Officer, Head of HR, Division Head or any role/person) in-app, official email, optional SMS; delivery status (delivered, read, unread, bounced).
- 5.4 Vacancy record: ID, post, division, grade, trigger, expected date, linked SOR, applicants, status (Forecast, Open, Advertised, Filled, Frozen).
- 5.5 Publish to applicant portal only with a linked approved SOR (and approval where policy requires); opening/closing dates; portal preview.
- 5.6 Applications via applicant portal (owned by Recruitment) linked to vacancy ID and counted.
- 5.7 When filled, post status updates automatically and alerts stop.
- 5.8 CCR increases create vacancies automatically (Forecast if effective date in future, else Open). Reductions create Frozen entries.

## 4.6 Dynamic cadre management (REQ 6)
- 6.1 Increase/decrease approved cadre for any designation in any division via UI.
- 6.2 Each change is a CCR: division, designation, old number, new number, effective date, approving authority, reference number (circular or Commission decision), attached approval document, justification.
- 6.3 CCR: Draft → Pending approval → Approved/Rejected. Applies from effective date; old value retained; future-dated changes don't alter current figures.
- 6.4 Block reducing below filled + acting unless authorised exception with reason; message states filled count and allowed minimum.
- 6.5 Increase creates new vacant positions with position IDs and shows an impact preview before submission. Decrease marks filled excess "to be abolished on vacation" and freezes vacant removed posts.
- 6.6 Live Cadre vs Actual: approved, filled, acting, vacant, excess/shortage with utilisation bar; grouped by designation, division or grade; filters division, grade, position category; drill-down division/grade → designation; totals.
- 6.7 Cadre History report: effective date, division, designation, old → new, reference, status; filter by division; "view cadre as at" date; exportable.
- 6.8 Designation can be added to a division with no establishment line (approved 0).
- 6.9 Cadre per designation, unit and location (HO/Regional), rolling up to unit, group and organization; initial load from the 971-post table.
- 6.10 Dying cadre: posts lapse as holders leave; no vacancy, no recruitment or increase.
- 6.11 Reconciliation view: sum by designation vs sum by unit; flags differences against approved total.

## 4.7 Position categories and organization chart (REQ 7)
- 7.1 Position categories (Executive, Legal, Investigation, Management Assistant, Support Staff, Legacy Grades …), nested if needed.
- 7.2 Drag-and-drop moves a designation into a category or unit; unassigned shown separately, can be dragged in or dropped back.
- 7.3 Drop saved only as a draft with visible banner and Save / Submit for approval / Discard; never changed by accidental drag.
- 7.4 Validate drops with reasons: closed category rejected; circular category hierarchy rejected.
- 7.5 Every categorization change audited (who, when, designation, from, to, effective date, status).
- 7.6 Org Chart shows the Commission, the DG and every unit (incl. HO and Regional) with head and staff following reporting lines; users can drag people between divisions, add a division and export the chart. Divisions from Master Data.

## 4.8 SOR preparation and publishing (REQ 8)
- 8.1 Guided six-step form (post details, qualifications, selection, format preview, approval, publish): designation, grade, salary code, educational qualifications, experience, age limits, competencies, selection method, service/circular reference.
- 8.2 Organized automatically into the official SOR template in Sinhala, Tamil and English with live preview and PDF download.
- 8.3 Drafts versioned and approved; approved SOR locked; changes need a new version with a reason; earlier versions kept.
- 8.4 Publish action: choose channels (official website, applicant portal, Facebook, LinkedIn, X); posts built from the SOR without retyping; editable before confirming.
- 8.5 Preview of all channels + confirmation; only Publisher role can publish.
- 8.6 Per-channel results (status, time, link); failed posts retried without duplicating successful ones.
- 8.7 Published posts can be withdrawn or updated where the platform allows, with audit trail.

## 4.9 Automated examination emails (REQ 9)
- 9.1 On scheduling an exam (name, date, time, venues, candidate count, support requested) generate one email/letter per coordinating organization.
- 9.2 Organization contact directory (institution, department, contact person, email, preferred language, last used) maintained by Administration.
- 9.3 Configurable templates with placeholders (exam name, date, time, venue, candidate count, requested support, contact name, signatory) in Sinhala, Tamil, English or all three.
- 9.4 Preview and approve each email, then send all in one action; optional auto-send when approved where policy allows.

## 4.10 Authority delegation and workflow engine (REQ 10)
- 10.1 Define and maintain all organizational positions incl. statutory posts and legal basis.
- 10.2 Any number of levels and positions.
- 10.3 Define who reports to whom for each position.
- 10.4 Approval/forwarding path determined automatically from initiator's position, division and hierarchy.
- 10.5 Variable-length paths (e.g. a stage that walks up the hierarchy until a given position is reached).
- 10.6 Different processes (leave, cadre change, SOR approval, categorization, insurance claim, performance appraisal, document approval, API access approval) have own paths selected by configurable routing rules (e.g. days ≤ 3, days > 3, initiator is a Director).
- 10.7 Add, remove, reorder, modify stages; parallel steps and rules; each stage has action type (approve/return, verify, record) and SLA; no code changes.
- 10.8 Complete audit trail: actor, stage, action, date/time, remarks.
- 10.9 Workflow changes versioned with effective date; in-flight requests finish on the version they started unless the administrator chooses otherwise; Compare-with-previous-version view and path simulation tool.

## 4.11 Employee insurance management (REQ 11)
- 11.1 Schemes and policies (Group Medical, Group Life, Personal Accident, Agrahara, others): provider, type, coverage, premium split, enrolment count, expiry.
- 11.2 Policy expiry alerts configurable (e.g. 60/30/7 days).
- 11.3 Eligibility rule builder (employment type, service period, category, employee lists; AND/OR), dependents covered, waiting period; evaluated on joining, transfer and grade change; preview of matched and newly eligible employees; enrolment tasks created automatically.
- 11.4 Enrolment, premiums and deductions/contributions pushed to Payroll.
- 11.5 Claims: Submitted → HR verified → Sent to insurer → Settled (or Rejected with reason).
- 11.6 Provider API connectors = configuration + field mapping: base URL, auth method, credentials in a secrets vault, sync schedule, data exchanged, test connection, sync log; SFTP CSV providers too; new insurers without code changes.

## 4.12 Performance evaluation (REQ 12)
- 12.1 Cycles (e.g. Annual Appraisal 2026) with configurable stages: objective setting, mid-year review, self-assessment, supervisor rating, moderation, finalization; dates and progress.
- 12.2 KPI and criteria library: KPIs, objectives, competencies with measure, weight, applicability; weights total 100% per template.
- 12.3 Rating scales (label, band, descriptor) editable.
- 12.4 Evaluations show weight, target, achieved, self rating, supervisor rating, weighted score; overall score/rating recalculates; supervisor comments; return to employee, save, or submit to moderation.
- 12.5 History across years; reports: rating distribution by unit, pending by stage and evaluator with SLA, employee history, training needs summary.
- 12.6 Finalized results can be linked to increments.

## 4.13 Attendance and No-Pay (REQ 13)
- 13.1 Check-in/out, hours, absences, late, early from device logs, evaluated against the resolved duty-time policy and approved leave.
- 13.2 Daily register and monthly summary with flags and outcome (Payable, No-Pay, Half-day No-Pay, Pending leave decision); manual adjustment where authorised.
- 13.3 No-Pay rules configurable, ordered, switchable; first match wins each day. Defaults: approved leave covers the day (payable); absent without leave (1 day No-Pay); hours below half-day threshold (0.5); late beyond grace over 2 hours without short leave (0.5); more than 3 late arrivals in a month (each extra deducts 0.5 casual leave, otherwise 0.5 No-Pay).
- 13.4 No-Pay amount = daily rate × days (half = 0.5); daily rate basis, allowances included, rounding and approval before Payroll configurable.
- 13.5 At period close payable and No-Pay days sent to Payroll after approval (Head of HR); employees with pending leave decisions held.

## 4.14 Master data (REQ 14)
- 14.1 Create/update/deactivate divisions, units, locations (Head Office, Regional), designations, employee categories, service grades, employment types and other reference data.
- 14.2 Each designation has a grade, position category and salary code.
- 14.3 Divisions show approved, filled and vacant totals; deactivating removes from selection lists but keeps history.
- 14.4 Master data shared by all modules.
- 14.5 Changes audited and, where policy requires, approved.

## 5. Business rules
- Approved cadre never below filled + acting without a recorded exception.
- Vacant = Approved − Filled − Acting (≥ 0). Excess/Shortage = Filled + Acting − Approved.
- A CCR applies from its effective date; history append-only; point-in-time queries use history.
- A vacancy can be published only with a linked approved SOR; a filled vacancy stops all alerts.
- Approved SORs locked; edits create a new version with a reason.
- Categorization drops are drafts until submitted and approved; closed categories reject drops; circular nesting rejected.
- Rate overrides need a reason; Payroll never stores its own copy of rates.

## 6. Non-functional
N-1 security (RBAC, secrets in a vault, approval-controlled changes, immutable audit); N-2 auditability (user, time,
previous/new values, reason); N-3 trilingual official documents; N-4 cadre views for whole establishment < 3 s;
N-5 retention, no deletion of approved records; N-6 configurable connectors (Payroll, applicant portal, leave,
email, social media, insurers); N-7 clear validation messages, confirmation before irreversible actions.

## 9. Open items
1. 971-post baseline approved; filled/acting per unit to be supplied; unit-by-designation matrix to be verified.
2. Structure follows the proposed structure (no Court Affairs Division) — to be confirmed.
3. Grades, position categories and salary codes for the 28 designations to be supplied.
4. Placeholder rates/amounts/limits pending official values.
5. Approval authority for cadre-reduction exceptions to be defined.
6. Integration contracts (Payroll, insurer APIs, social media tokens) to be finalised.
7. Retention periods to be confirmed.
