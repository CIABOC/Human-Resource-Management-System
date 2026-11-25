-- =====================================================================================================
-- Spec v2.1 REQ 1 (official duty-time configuration) and REQ 13 (attendance and No-Pay).
--  * duty_policy               versioned work-time policies (FIXED / FLEXIBLE / SHIFT_ROSTER), approved through
--                              workflow DUTY_TIME_POLICY (Head of HR) and effective from a date (G-2, G-3, 1.5)
--  * duty_shift                shift patterns of a roster policy version (A/B/C, incl. shifts crossing midnight)
--  * duty_policy_assignment    effective-dated assignment to the organization, a division, a category or an employee
--  * nopay_rule                ordered, switchable No-Pay rules; first match wins per day (13.3)
--  * nopay_amount_config       effective-dated daily-rate basis, allowances and rounding consumed by payroll (13.4)
-- Existing working schedules (work_schedule) are migrated into approved policy versions and organization /
-- division / category assignments, then the old table is dropped. All values are configuration (G-1, open item 4).
-- =====================================================================================================

-- ------------------------------------------------------------------ duty-time policies (REQ 1.1, 1.4, 1.5)
CREATE TABLE duty_policy (
    id                      BIGSERIAL PRIMARY KEY,
    version                 BIGINT,
    created_at              TIMESTAMPTZ,
    created_by              VARCHAR(100),
    updated_at              TIMESTAMPTZ,
    updated_by              VARCHAR(100),
    code                    VARCHAR(40)   NOT NULL,
    version_no              INTEGER       NOT NULL,
    name                    VARCHAR(150)  NOT NULL,
    policy_type             VARCHAR(20)   NOT NULL CHECK (policy_type IN ('FIXED', 'FLEXIBLE', 'SHIFT_ROSTER')),
    in_time                 TIME,
    out_time                TIME,
    core_start              TIME,
    core_end                TIME,
    grace_minutes           INTEGER       NOT NULL DEFAULT 0 CHECK (grace_minutes >= 0),
    early_grace_minutes     INTEGER       NOT NULL DEFAULT 0 CHECK (early_grace_minutes >= 0),
    min_work_minutes        INTEGER       NOT NULL DEFAULT 0 CHECK (min_work_minutes >= 0),
    late_threshold_minutes  INTEGER       NOT NULL DEFAULT 0 CHECK (late_threshold_minutes >= 0),
    early_threshold_minutes INTEGER       NOT NULL DEFAULT 0 CHECK (early_threshold_minutes >= 0),
    half_day_minutes        INTEGER       NOT NULL DEFAULT 0 CHECK (half_day_minutes >= 0),
    lunch_start             TIME,
    lunch_minutes           INTEGER       NOT NULL DEFAULT 0 CHECK (lunch_minutes >= 0),
    working_days            VARCHAR(40),
    rotation_pattern        VARCHAR(500),
    rotation_start_date     DATE,
    effective_from          DATE          NOT NULL,
    effective_to            DATE,
    status                  VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT', 'PENDING', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    authority_ref           VARCHAR(200),
    reason                  VARCHAR(1000),
    workflow_instance_id    BIGINT,
    submitted_at            TIMESTAMPTZ,
    submitted_by            VARCHAR(100),
    approved_at             TIMESTAMPTZ,
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    UNIQUE (code, version_no)
);
CREATE INDEX ix_duty_policy_code ON duty_policy (code, status, effective_from);

CREATE TABLE duty_shift (
    policy_id     BIGINT       NOT NULL REFERENCES duty_policy (id) ON DELETE CASCADE,
    sort_order    INTEGER      NOT NULL,
    shift_code    VARCHAR(10)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    start_time    TIME         NOT NULL,
    end_time      TIME         NOT NULL,
    lunch_minutes INTEGER      NOT NULL DEFAULT 0 CHECK (lunch_minutes >= 0),
    PRIMARY KEY (policy_id, sort_order)
);

-- Resolution priority (REQ 1.3): EMPLOYEE -> CATEGORY -> DIVISION (nearest unit up the hierarchy) -> ORGANIZATION.
CREATE TABLE duty_policy_assignment (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    policy_code       VARCHAR(40)   NOT NULL,
    scope_type        VARCHAR(20)   NOT NULL CHECK (scope_type IN ('ORGANIZATION', 'DIVISION', 'CATEGORY', 'EMPLOYEE')),
    org_unit_code     VARCHAR(40),
    employee_category VARCHAR(40),
    employee_id       BIGINT REFERENCES employee (id),
    rotation_offset   INTEGER       NOT NULL DEFAULT 0,
    effective_from    DATE          NOT NULL,
    effective_to      DATE,
    authority_ref     VARCHAR(200),
    reason            VARCHAR(1000) NOT NULL,
    end_reason        VARCHAR(1000),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK ((scope_type = 'ORGANIZATION' AND org_unit_code IS NULL AND employee_category IS NULL AND employee_id IS NULL)
        OR (scope_type = 'DIVISION' AND org_unit_code IS NOT NULL AND employee_category IS NULL AND employee_id IS NULL)
        OR (scope_type = 'CATEGORY' AND employee_category IS NOT NULL AND org_unit_code IS NULL AND employee_id IS NULL)
        OR (scope_type = 'EMPLOYEE' AND employee_id IS NOT NULL AND org_unit_code IS NULL AND employee_category IS NULL))
);
CREATE INDEX ix_duty_assign_scope ON duty_policy_assignment (scope_type, effective_from);
CREATE INDEX ix_duty_assign_emp ON duty_policy_assignment (employee_id);

-- ------------------------------------------------------------------ migrate working schedules
INSERT INTO duty_policy (id, version, created_at, created_by, code, version_no, name, policy_type, in_time, out_time,
                         grace_minutes, early_grace_minutes, min_work_minutes, late_threshold_minutes, early_threshold_minutes,
                         half_day_minutes, lunch_minutes, working_days, effective_from, effective_to, status, authority_ref,
                         reason, approved_at)
SELECT s.id, 0, COALESCE(s.created_at, now()), COALESCE(s.created_by, 'system'), s.code,
       CAST(ROW_NUMBER() OVER (PARTITION BY s.code ORDER BY s.effective_from) AS integer), s.name, 'FIXED',
       s.start_time, s.end_time, s.late_grace_minutes, s.early_grace_minutes,
       CAST(EXTRACT(EPOCH FROM (s.end_time - s.start_time)) / 60 AS integer), 120, 120,
       CAST(EXTRACT(EPOCH FROM (s.end_time - s.start_time)) / 120 AS integer), 0, s.work_days, s.effective_from, s.effective_to,
       'APPROVED', 'Migrated working schedule (V60)',
       COALESCE(s.remarks, 'Migrated from the earlier working schedule') || ' - thresholds, lunch break and half-day to be confirmed by Administration',
       now()
FROM work_schedule s;
SELECT setval(pg_get_serial_sequence('duty_policy', 'id'), GREATEST((SELECT COALESCE(MAX(id), 0) FROM duty_policy), 1));

INSERT INTO duty_policy_assignment (version, created_at, created_by, policy_code, scope_type, org_unit_code, employee_category,
                                    effective_from, effective_to, authority_ref, reason)
SELECT 0, now(), 'system', x.code,
       CASE WHEN x.org_unit_code IS NOT NULL THEN 'DIVISION' WHEN x.employee_category IS NOT NULL THEN 'CATEGORY' ELSE 'ORGANIZATION' END,
       x.org_unit_code, CASE WHEN x.org_unit_code IS NULL THEN x.employee_category END,
       x.first_from, x.last_to, 'Migrated working schedule (V60)', 'Assignment carried over from the working schedule'
FROM (SELECT s.code,
             (ARRAY_AGG(s.org_unit_code ORDER BY s.effective_from DESC))[1] AS org_unit_code,
             (ARRAY_AGG(s.employee_category ORDER BY s.effective_from DESC))[1] AS employee_category,
             MIN(s.effective_from) AS first_from,
             CASE WHEN BOOL_OR(s.effective_to IS NULL) THEN NULL ELSE MAX(s.effective_to) END AS last_to
      FROM work_schedule s GROUP BY s.code) x;

-- ------------------------------------------------------------------ attendance daily: policy, flags and outcome (REQ 13.1, 13.2)
ALTER TABLE attendance_daily ADD COLUMN policy_id BIGINT REFERENCES duty_policy (id);
UPDATE attendance_daily SET policy_id = schedule_id;
ALTER TABLE attendance_daily DROP COLUMN schedule_id;
DROP TABLE work_schedule;

ALTER TABLE attendance_daily DROP CONSTRAINT IF EXISTS attendance_daily_status_check;
UPDATE attendance_daily SET status = 'NO_POLICY' WHERE status = 'NO_SCHEDULE';
ALTER TABLE attendance_daily ADD CONSTRAINT attendance_daily_status_check
    CHECK (status IN ('PRESENT', 'LATE', 'EARLY', 'ABSENT', 'ON_LEAVE', 'HOLIDAY', 'WEEKEND', 'NO_POLICY'));

ALTER TABLE attendance_daily ADD COLUMN policy_source VARCHAR(20);
ALTER TABLE attendance_daily ADD COLUMN shift_code VARCHAR(10);
ALTER TABLE attendance_daily ADD COLUMN scheduled_start TIMESTAMPTZ;
ALTER TABLE attendance_daily ADD COLUMN scheduled_end TIMESTAMPTZ;
ALTER TABLE attendance_daily ADD COLUMN flags VARCHAR(300);
ALTER TABLE attendance_daily ADD COLUMN short_leave BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE attendance_daily ADD COLUMN pending_leave_id BIGINT;
ALTER TABLE attendance_daily ADD COLUMN outcome VARCHAR(30)
    CHECK (outcome IS NULL OR outcome IN ('PAYABLE', 'NO_PAY', 'HALF_DAY_NO_PAY', 'PENDING_LEAVE_DECISION', 'NOT_WORKING_DAY', 'NO_POLICY'));
ALTER TABLE attendance_daily ADD COLUMN nopay_days NUMERIC(4, 2) NOT NULL DEFAULT 0;
ALTER TABLE attendance_daily ADD COLUMN outcome_rule VARCHAR(40);
ALTER TABLE attendance_daily ADD COLUMN outcome_note VARCHAR(500);
ALTER TABLE attendance_daily ADD COLUMN leave_deduction_days NUMERIC(4, 2) NOT NULL DEFAULT 0;
ALTER TABLE attendance_daily ADD COLUMN leave_deduction_type VARCHAR(40);
ALTER TABLE attendance_daily ADD COLUMN manual_outcome VARCHAR(30)
    CHECK (manual_outcome IS NULL OR manual_outcome IN ('PAYABLE', 'NO_PAY', 'HALF_DAY_NO_PAY', 'PENDING_LEAVE_DECISION'));
ALTER TABLE attendance_daily ADD COLUMN manual_reason VARCHAR(1000);
ALTER TABLE attendance_daily ADD COLUMN manual_by VARCHAR(100);
ALTER TABLE attendance_daily ADD COLUMN manual_at TIMESTAMPTZ;

-- ------------------------------------------------------------------ No-Pay rules (REQ 13.3)
CREATE TABLE nopay_rule (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    code              VARCHAR(40)   NOT NULL UNIQUE,
    rule_type         VARCHAR(40)   NOT NULL CHECK (rule_type IN ('APPROVED_LEAVE', 'PENDING_LEAVE', 'ABSENT_WITHOUT_LEAVE',
                                    'BELOW_HALF_DAY', 'LATE_WITHOUT_SHORT_LEAVE', 'EARLY_WITHOUT_SHORT_LEAVE', 'EXCESS_LATE_ARRIVALS')),
    name              VARCHAR(200)  NOT NULL,
    description       VARCHAR(1000),
    rule_order        INTEGER       NOT NULL,
    enabled           BOOLEAN       NOT NULL DEFAULT TRUE,
    deduction_days    NUMERIC(4, 2) NOT NULL DEFAULT 0 CHECK (deduction_days >= 0 AND deduction_days <= 1),
    threshold_minutes INTEGER CHECK (threshold_minutes IS NULL OR threshold_minutes >= 0),
    free_count        INTEGER CHECK (free_count IS NULL OR free_count >= 0),
    leave_type_code   VARCHAR(40)
);

INSERT INTO nopay_rule (version, created_at, created_by, code, rule_type, name, description, rule_order, enabled, deduction_days,
                        threshold_minutes, free_count, leave_type_code) VALUES
 (0, now(), 'system', 'APPROVED_LEAVE', 'APPROVED_LEAVE', 'Approved leave covers the day',
  'Approved paid leave makes the day payable. Approved unpaid leave is charged at the deduction below (half for half-day leave).',
  10, TRUE, 1, NULL, NULL, NULL),
 (0, now(), 'system', 'PENDING_LEAVE', 'PENDING_LEAVE', 'Leave decision pending',
  'An absence or shortfall covered by a leave application still awaiting a decision is held (not released to payroll) until decided (13.5).',
  20, TRUE, 0, NULL, NULL, NULL),
 (0, now(), 'system', 'ABSENT', 'ABSENT_WITHOUT_LEAVE', 'Absent without leave',
  'A working day with no attendance and no approved leave.', 30, TRUE, 1, NULL, NULL, NULL),
 (0, now(), 'system', 'BELOW_HALF_DAY', 'BELOW_HALF_DAY', 'Hours below the half-day threshold',
  'Net hours worked below the half-day threshold of the duty-time policy.', 40, TRUE, 0.5, NULL, NULL, NULL),
 (0, now(), 'system', 'LATE_NO_SHORT_LEAVE', 'LATE_WITHOUT_SHORT_LEAVE', 'Late beyond grace over 2 hours without short leave',
  'Arrival later than the threshold after the grace period without approved short leave. Leave the threshold empty to use the duty-time policy''s late threshold.',
  50, TRUE, 0.5, 120, NULL, NULL),
 (0, now(), 'system', 'EXCESS_LATES', 'EXCESS_LATE_ARRIVALS', 'More than 3 late arrivals in a month',
  'Each late arrival beyond the allowed count deducts the given days of the leave type when available, otherwise the same No-Pay.',
  60, TRUE, 0.5, NULL, 3, 'CASUAL'),
 (0, now(), 'system', 'EARLY_NO_SHORT_LEAVE', 'EARLY_WITHOUT_SHORT_LEAVE', 'Early departure beyond threshold without short leave',
  'Optional rule (disabled by default): departure earlier than the policy''s early threshold without approved short leave.',
  70, FALSE, 0.5, NULL, NULL, NULL);

-- The earlier flat no-pay settings are replaced by the rules above.
DELETE FROM system_setting WHERE setting_key IN ('nopay.count-unauthorized-absence', 'nopay.absence-day-value',
                                                 'nopay.include-unpaid-leave', 'nopay.late-arrivals-per-half-day');

-- ------------------------------------------------------------------ No-Pay amount configuration (REQ 13.4)
CREATE TABLE nopay_amount_config (
    id                 BIGSERIAL PRIMARY KEY,
    version            BIGINT,
    created_at         TIMESTAMPTZ,
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    effective_from     DATE          NOT NULL UNIQUE,
    effective_to       DATE,
    rate_basis         VARCHAR(30)   NOT NULL CHECK (rate_basis IN ('CALENDAR_DAYS', 'WORKING_DAYS', 'FIXED_DIVISOR')),
    divisor            NUMERIC(6, 2) CHECK (divisor IS NULL OR divisor > 0),
    include_allowances BOOLEAN       NOT NULL DEFAULT TRUE,
    allowance_codes    VARCHAR(500),
    rounding_mode      VARCHAR(10)   NOT NULL CHECK (rounding_mode IN ('HALF_UP', 'UP', 'DOWN')),
    rounding_scale     INTEGER       NOT NULL DEFAULT 2 CHECK (rounding_scale BETWEEN 0 AND 4),
    approval_required  BOOLEAN       NOT NULL DEFAULT TRUE,
    authority_ref      VARCHAR(200),
    reason             VARCHAR(1000),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (rate_basis <> 'FIXED_DIVISOR' OR divisor IS NOT NULL)
);
INSERT INTO nopay_amount_config (version, created_at, created_by, effective_from, rate_basis, divisor, include_allowances,
                                 allowance_codes, rounding_mode, rounding_scale, approval_required, authority_ref, reason)
VALUES (0, now(), 'system', DATE '2000-01-01', 'FIXED_DIVISOR', 30, TRUE, NULL, 'HALF_UP', 2, TRUE, 'Placeholder (open item 4)',
        'Daily rate = (basic salary + allowances) / 30, rounded half-up to cents. Placeholder pending official values.');

-- ------------------------------------------------------------------ No-Pay run / line: outcomes, holds and release (REQ 13.5)
ALTER TABLE nopay_run ALTER COLUMN rules_snapshot TYPE TEXT;
ALTER TABLE nopay_run ADD COLUMN held_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE nopay_run ADD COLUMN payable_total NUMERIC(9, 2) NOT NULL DEFAULT 0;
ALTER TABLE nopay_run ADD COLUMN amount_config_id BIGINT REFERENCES nopay_amount_config (id);
ALTER TABLE nopay_run ADD COLUMN release_round INTEGER NOT NULL DEFAULT 0;

ALTER TABLE nopay_line ADD COLUMN payable_days NUMERIC(7, 2) NOT NULL DEFAULT 0;
ALTER TABLE nopay_line ADD COLUMN half_day_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE nopay_line ADD COLUMN pending_days NUMERIC(7, 2) NOT NULL DEFAULT 0;
ALTER TABLE nopay_line ADD COLUMN leave_deduction_days NUMERIC(7, 2) NOT NULL DEFAULT 0;
ALTER TABLE nopay_line ADD COLUMN leave_deduction_type VARCHAR(40);
ALTER TABLE nopay_line ADD COLUMN leave_deducted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE nopay_line ADD COLUMN release_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
    CHECK (release_status IN ('DRAFT', 'HELD', 'PENDING_RELEASE', 'RELEASED'));
ALTER TABLE nopay_line ADD COLUMN released_at TIMESTAMPTZ;
ALTER TABLE nopay_line ADD COLUMN hold_reason VARCHAR(500);
UPDATE nopay_line l SET release_status = 'RELEASED', released_at = r.approved_at FROM nopay_run r WHERE r.id = l.run_id AND r.status = 'APPROVED';

-- ------------------------------------------------------------------ short leave (REQ 13.3 "without short leave")
ALTER TABLE leave_type ADD COLUMN short_leave BOOLEAN NOT NULL DEFAULT FALSE;
INSERT INTO leave_type (version, created_at, created_by, code, name, name_si, name_ta, description, paid, balance_tracked,
                        requires_reason, requires_document, document_after_days, min_notice_days, max_consecutive_days,
                        allow_backdated, half_day_allowed, sort_order, active, short_leave)
VALUES (0, now(), 'system', 'SHORT', 'Short leave', 'කෙටි නිවාඩු', 'குறுகிய விடுமுறை',
        'A few hours'' absence (late arrival or early departure) on one day. Not charged against a balance; the monthly limit is a setting. To be confirmed by Administration.',
        TRUE, FALSE, TRUE, FALSE, NULL, 0, 1, TRUE, FALSE, 7, TRUE, TRUE)
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------------ approval chains (G-4, 2.5): Head of HR
INSERT INTO workflow_definition (version, created_at, created_by, process_code, name, description) VALUES
 (0, now(), 'system', 'DUTY_TIME_POLICY', 'Duty time policy', 'New version of an official duty-time policy (REQ 1.5)');
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours)
SELECT id, 1, 'Head of HR approval', 'ROLE', 'HEAD_OF_HR', 72 FROM workflow_definition WHERE process_code = 'DUTY_TIME_POLICY';

DELETE FROM workflow_stage WHERE definition_id IN (SELECT id FROM workflow_definition WHERE process_code = 'NOPAY_APPROVAL');
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours)
SELECT id, 1, 'Head of HR approval', 'ROLE', 'HEAD_OF_HR', 72 FROM workflow_definition WHERE process_code = 'NOPAY_APPROVAL';
UPDATE workflow_definition SET description = 'Head of HR approval of payable and No-Pay days before release to payroll (REQ 13.5)'
WHERE process_code = 'NOPAY_APPROVAL';

-- ------------------------------------------------------------------ settings
INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('duty-policy.max-backdate-days', '0', 'ATTENDANCE', 'INT',
  'How many days in the past a new duty-time policy version may take effect (0 = from today only; processed days are never recalculated)'),
 ('attendance.shift-window-minutes', '240', 'ATTENDANCE', 'INT',
  'Roster shifts: events up to this many minutes before the shift start and after the shift end belong to the shift (shifts crossing midnight)'),
 ('leave.short-leave.max-per-month', '2', 'LEAVE', 'INT', 'Maximum short-leave applications per employee per month'),
 ('dashboard.due-soon.policy-renewal-days', '30', 'DASHBOARD', 'INT',
  'Expiring & due soon: show duty-time policies and No-Pay amount configurations ending within this many days'),
 ('dashboard.due-soon.appraisal-days', '14', 'DASHBOARD', 'INT',
  'Expiring & due soon: show appraisal stage deadlines within this many days')
ON CONFLICT (setting_key) DO NOTHING;

-- ------------------------------------------------------------------ notification templates
INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'DUTY_POLICY_DECIDED', 'Sent to the submitter when a duty-time policy version is decided',
  'Duty-time policy {{code}} v{{versionNo}}: {{decision}}', 'රාජකාරි කාල ප්‍රතිපත්තිය {{code}} v{{versionNo}}: {{decision}}',
  'கடமை நேரக் கொள்கை {{code}} v{{versionNo}}: {{decision}}',
  'Duty-time policy {{code}} version {{versionNo}} (effective {{effectiveFrom}}) was {{decision}}.',
  'රාජකාරි කාල ප්‍රතිපත්තිය {{code}} අනුවාදය {{versionNo}} ({{effectiveFrom}} සිට) {{decision}}.',
  'கடமை நேரக் கொள்கை {{code}} பதிப்பு {{versionNo}} ({{effectiveFrom}} முதல்) {{decision}}.', FALSE),
 (0, now(), 'system', 'NOPAY_HELD', 'Sent to the Head of HR when No-Pay lines are held for pending leave decisions',
  'No-Pay {{period}}: {{count}} employee(s) held', 'වැටුප් රහිත {{period}}: සේවකයින් {{count}} රඳවා ඇත', 'சம்பளமற்ற {{period}}: {{count}} ஊழியர்(கள்) நிறுத்தி வைக்கப்பட்டனர்',
  '{{count}} employee(s) were held from the {{period}} release because leave decisions are pending.',
  'නිවාඩු තීරණ අපේක්ෂිත බැවින් සේවකයින් {{count}} {{period}} නිකුතුවෙන් රඳවා ඇත.',
  'விடுமுறை முடிவுகள் நிலுவையில் உள்ளதால் {{count}} ஊழியர்(கள்) {{period}} வெளியீட்டிலிருந்து நிறுத்தி வைக்கப்பட்டனர்.', FALSE)
ON CONFLICT (code) DO NOTHING;
