-- =====================================================================================================
-- Establishment & Cadre spec v2.1 - Pay & Benefits Setup:
--   REQ 2.1/2.2  salary scales by salary code (initial step, increments, maximum, linked grades, effective date)
--   REQ 4.1-4.4  scheme master (Pension, W&OP, EPF, ETF, welfare, insurance, others), per-employee assignment with
--                reasoned rate overrides, approval (SCHEME_CHANGE) and change log; payroll reads this configuration
--   REQ 3.1-3.4  inherited commitments of seconded officers with a monthly deduction ledger and alerts
-- Every amount, rate, GL code and lead time below is a PLACEHOLDER pending official values (open items 3, 4).
-- =====================================================================================================

-- ------------------------------------------------------------------ salary scales / salary codes (REQ 2.2)
ALTER TABLE pay_salary_scale ADD COLUMN linked_grades VARCHAR(200);
-- Segmented increments, e.g. "10x1200;12x1500" (10 increments of 1,200 then 12 of 1,500). NULL = max_steps x increment.
ALTER TABLE pay_salary_scale ADD COLUMN increment_schedule VARCHAR(500);
ALTER TABLE pay_salary_scale ADD COLUMN to_be_confirmed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE pay_salary_scale ADD COLUMN authority_ref VARCHAR(200);

UPDATE pay_salary_scale SET linked_grades = grade WHERE linked_grades IS NULL AND grade IS NOT NULL;

-- Placeholder scale per salary code used by the 28 designations (open item 3: official scales to be supplied).
INSERT INTO pay_salary_scale (version, created_at, created_by, code, name, grade, linked_grades, initial_salary, increment_amount,
                              max_steps, effective_from, to_be_confirmed, notes)
SELECT 0, now(), 'system', v.code, v.name, v.grade, v.grade, v.initial, v.inc, v.steps, DATE '2020-01-01', TRUE,
       'Placeholder salary scale - official initial step, increments and maximum to be supplied (open item 3)'
FROM (VALUES
  ('SL-4', 'Senior Level 4', 'SL4', 120000.00, 3000.00, 15),
  ('SL-3', 'Senior Level 3', 'SL3', 95000.00, 2500.00, 18),
  ('SL-1', 'Senior Level 1', 'SL1', 72000.00, 1800.00, 20),
  ('MN-7', 'Middle Level 7', 'MN7', 60000.00, 1500.00, 20),
  ('MN-4', 'Middle Level 4', 'MN4', 48000.00, 1200.00, 22),
  ('MN-2', 'Middle Level 2', 'MN2', 40000.00, 1000.00, 25),
  ('MN-1', 'Middle Level 1', 'MN1', 35000.00, 800.00, 25),
  ('PL-1', 'Primary Level 1', 'PL1', 30000.00, 600.00, 25)
) AS v(code, name, grade, initial, inc, steps)
WHERE NOT EXISTS (SELECT 1 FROM pay_salary_scale s WHERE s.code = v.code);

-- Salary code of each designation mapped from its grade (REQ 14.2). PLACEHOLDER mapping pending open item 3.
UPDATE designation SET salary_code = CASE grade
        WHEN 'SL4' THEN 'SL-4' WHEN 'SL3' THEN 'SL-3' WHEN 'SL1' THEN 'SL-1' WHEN 'MN7' THEN 'MN-7'
        WHEN 'MN4' THEN 'MN-4' WHEN 'MN2' THEN 'MN-2' WHEN 'MN1' THEN 'MN-1' WHEN 'PL1' THEN 'PL-1' END
WHERE salary_code IS NULL AND grade IN ('SL4', 'SL3', 'SL1', 'MN7', 'MN4', 'MN2', 'MN1', 'PL1');

-- ------------------------------------------------------------------ scheme master (REQ 4.1)
ALTER TABLE pay_deduction_type ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'DEDUCTION'
    CONSTRAINT chk_pay_deduction_type_kind CHECK (kind IN ('DEDUCTION', 'CONTRIBUTION', 'BENEFIT'));
ALTER TABLE pay_deduction_type ADD COLUMN scheme_family VARCHAR(20) NOT NULL DEFAULT 'OTHER'
    CONSTRAINT chk_pay_deduction_type_family CHECK (scheme_family IN ('PENSION', 'WNOP', 'EPF', 'ETF', 'WELFARE', 'INSURANCE', 'OTHER'));
-- ALL_EMPLOYEES: applied to every in-service employee; PENSIONABLE / NON_PENSIONABLE: assigned with the employee's
-- pension package (Pension + W&OP or EPF + ETF); OPTIONAL: individual membership.
ALTER TABLE pay_deduction_type ADD COLUMN default_eligibility VARCHAR(20) NOT NULL DEFAULT 'OPTIONAL'
    CONSTRAINT chk_pay_deduction_type_elig CHECK (default_eligibility IN ('ALL_EMPLOYEES', 'PENSIONABLE', 'NON_PENSIONABLE', 'OPTIONAL'));
ALTER TABLE pay_deduction_type ADD COLUMN gl_code VARCHAR(40);
ALTER TABLE pay_deduction_type ADD COLUMN employer_gl_code VARCHAR(40);

UPDATE pay_deduction_type SET default_eligibility = 'ALL_EMPLOYEES' WHERE NOT membership_required;
UPDATE pay_deduction_type t SET kind = v.kind, scheme_family = v.fam, default_eligibility = v.elig, gl_code = v.gl, employer_gl_code = v.egl
FROM (VALUES
  ('EPF', 'CONTRIBUTION', 'EPF', 'NON_PENSIONABLE', 'GL-2101', 'GL-6101'),
  ('ETF', 'CONTRIBUTION', 'ETF', 'NON_PENSIONABLE', NULL, 'GL-6102'),
  ('WNOP', 'DEDUCTION', 'WNOP', 'PENSIONABLE', 'GL-2103', NULL),
  ('PENSION', 'CONTRIBUTION', 'PENSION', 'PENSIONABLE', 'GL-2104', 'GL-6104'),
  ('WELFARE', 'BENEFIT', 'WELFARE', 'OPTIONAL', 'GL-2105', NULL)
) AS v(code, kind, fam, elig, gl, egl)
WHERE t.code = v.code;

-- Versioned scheme terms (G-2/G-3): each pay_deduction_rate row is one approved version of the scheme's terms.
ALTER TABLE pay_deduction_rate DROP CONSTRAINT IF EXISTS pay_deduction_rate_employee_basis_check;
ALTER TABLE pay_deduction_rate DROP CONSTRAINT IF EXISTS pay_deduction_rate_employer_basis_check;
ALTER TABLE pay_deduction_rate ADD CONSTRAINT chk_pay_deduction_rate_emp_basis
    CHECK (employee_basis IN ('NONE', 'FIXED', 'PERCENT_OF_BASIC', 'PERCENT_OF_GROSS', 'PERCENT_OF_COMPONENTS'));
ALTER TABLE pay_deduction_rate ADD CONSTRAINT chk_pay_deduction_rate_er_basis
    CHECK (employer_basis IN ('NONE', 'FIXED', 'PERCENT_OF_BASIC', 'PERCENT_OF_GROSS', 'PERCENT_OF_COMPONENTS'));
-- Salary components included in the contribution base (BASIC and/or allowance codes) for PERCENT_OF_COMPONENTS.
ALTER TABLE pay_deduction_rate ADD COLUMN components_included VARCHAR(500);
ALTER TABLE pay_deduction_rate ADD COLUMN payer VARCHAR(10) NOT NULL DEFAULT 'EMPLOYEE'
    CONSTRAINT chk_pay_deduction_rate_payer CHECK (payer IN ('EMPLOYEE', 'EMPLOYER', 'SHARED'));
ALTER TABLE pay_deduction_rate ADD COLUMN authority_ref VARCHAR(200);
ALTER TABLE pay_deduction_rate ADD COLUMN change_id BIGINT;
UPDATE pay_deduction_rate SET payer = CASE
        WHEN employee_basis <> 'NONE' AND employer_basis <> 'NONE' THEN 'SHARED'
        WHEN employee_basis = 'NONE' AND employer_basis <> 'NONE' THEN 'EMPLOYER'
        ELSE 'EMPLOYEE' END;
UPDATE pay_deduction_rate SET components_included = 'BASIC' WHERE components_included IS NULL;

-- Change log and approval of every scheme edit (REQ 4.3). proposed / previous hold the full JSON snapshots.
CREATE TABLE pay_scheme_change (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    deduction_type_id    BIGINT REFERENCES pay_deduction_type (id),
    scheme_code          VARCHAR(40)   NOT NULL,
    change_type          VARCHAR(20)   NOT NULL CHECK (change_type IN ('CREATE', 'UPDATE', 'RATE')),
    status               VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'RETURNED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    reason               VARCHAR(1000) NOT NULL,
    effective_from       DATE,
    proposed             TEXT          NOT NULL,
    previous             TEXT,
    workflow_instance_id BIGINT,
    decided_at           TIMESTAMPTZ,
    decided_by           VARCHAR(100),
    applied_rate_id      BIGINT REFERENCES pay_deduction_rate (id)
);
CREATE INDEX ix_pay_scheme_change_code ON pay_scheme_change (scheme_code, id);
ALTER TABLE pay_deduction_rate ADD CONSTRAINT fk_pay_deduction_rate_change FOREIGN KEY (change_id) REFERENCES pay_scheme_change (id);

-- Per-employee assignment: a rate override is allowed only with a recorded reason (REQ 4.2).
ALTER TABLE pay_deduction_membership ADD COLUMN override_reason VARCHAR(1000);
UPDATE pay_deduction_membership SET override_reason = 'Override recorded before REQ 4.2 (reason not captured)'
WHERE (employee_value_override IS NOT NULL OR employer_value_override IS NOT NULL) AND override_reason IS NULL;
ALTER TABLE pay_deduction_membership ADD CONSTRAINT chk_pay_membership_override_reason
    CHECK ((employee_value_override IS NULL AND employer_value_override IS NULL) OR override_reason IS NOT NULL);

-- Payroll posts each component to the scheme's GL code (REQ 4.1 "Payroll GL code").
ALTER TABLE pay_payroll_line_item ADD COLUMN gl_code VARCHAR(40);

-- ------------------------------------------------------------------ inherited commitments (REQ 3.1-3.4)
ALTER TABLE pay_employee_loan ALTER COLUMN loan_type DROP NOT NULL;
ALTER TABLE pay_employee_loan ADD COLUMN loan_name VARCHAR(200);
ALTER TABLE pay_employee_loan ADD COLUMN first_deduction_month VARCHAR(7)
    CONSTRAINT chk_pay_loan_first_month CHECK (first_deduction_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$');
ALTER TABLE pay_employee_loan ADD COLUMN end_date DATE;
ALTER TABLE pay_employee_loan ADD COLUMN arrangement_ref VARCHAR(200);
ALTER TABLE pay_employee_loan ADD COLUMN remittance_target VARCHAR(300);
ALTER TABLE pay_employee_loan ADD COLUMN arrangement_file_id BIGINT REFERENCES stored_file (id);
ALTER TABLE pay_employee_loan ADD COLUMN status_reason VARCHAR(1000);
ALTER TABLE pay_employee_loan ADD COLUMN end_alert_sent_on DATE;

UPDATE pay_employee_loan SET loan_name = COALESCE(loan_name, lender),
                             first_deduction_month = COALESCE(first_deduction_month, to_char(start_date, 'YYYY-MM')),
                             remittance_target = COALESCE(remittance_target, lender);
ALTER TABLE pay_employee_loan ALTER COLUMN loan_name SET NOT NULL;
ALTER TABLE pay_employee_loan ALTER COLUMN first_deduction_month SET NOT NULL;

ALTER TABLE pay_employee_loan DROP CONSTRAINT IF EXISTS pay_employee_loan_status_check;
UPDATE pay_employee_loan SET status = 'COMPLETED' WHERE status = 'SETTLED';
ALTER TABLE pay_employee_loan ADD CONSTRAINT chk_pay_employee_loan_status
    CHECK (status IN ('ACTIVE', 'COMPLETING', 'COMPLETED', 'SUSPENDED', 'DEDUCTION_FAILED', 'TRANSFERRED_OUT'));
ALTER TABLE pay_employee_loan ADD CONSTRAINT chk_pay_employee_loan_end CHECK (end_date IS NULL OR end_date >= start_date);

ALTER TABLE pay_loan_transaction DROP CONSTRAINT IF EXISTS pay_loan_transaction_txn_type_check;
ALTER TABLE pay_loan_transaction ADD CONSTRAINT chk_pay_loan_txn_type
    CHECK (txn_type IN ('OPENING', 'DEDUCTION', 'SETTLEMENT', 'INSTALMENT_CHANGE', 'TRANSFER_OUT', 'REVERSAL'));

-- Monthly deduction ledger (REQ 3.2): one row per commitment and payroll month.
CREATE TABLE pay_commitment_ledger (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    loan_id        BIGINT        NOT NULL REFERENCES pay_employee_loan (id),
    period         VARCHAR(7)    NOT NULL CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    amount         NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
    status         VARCHAR(20)   NOT NULL CHECK (status IN ('QUEUED', 'POSTED', 'FAILED')),
    payroll_run_id BIGINT REFERENCES pay_payroll_run (id),
    balance_after  NUMERIC(14,2),
    failure_reason VARCHAR(1000),
    queued_at      TIMESTAMPTZ,
    posted_at      TIMESTAMPTZ,
    failed_at      TIMESTAMPTZ,
    UNIQUE (loan_id, period)
);
CREATE INDEX ix_pay_commitment_ledger_status ON pay_commitment_ledger (status, period);

INSERT INTO pay_commitment_ledger (version, created_at, created_by, loan_id, period, amount, status, payroll_run_id, balance_after, posted_at)
SELECT 0, now(), 'system', t.loan_id, t.period, SUM(t.amount), 'POSTED', MAX(t.payroll_run_id), MIN(t.balance_after), now()
FROM pay_loan_transaction t
WHERE t.txn_type = 'DEDUCTION' AND t.period IS NOT NULL
GROUP BY t.loan_id, t.period
ON CONFLICT (loan_id, period) DO NOTHING;

-- ------------------------------------------------------------------ approval chain (G-4, REQ 4.3)
INSERT INTO workflow_definition (version, created_at, created_by, process_code, name, description)
VALUES (0, now(), 'system', 'SCHEME_CHANGE', 'Salary scheme change',
        'Creation of, or changes to, a deduction / contribution / benefit scheme or its rates (REQ 4.3)')
ON CONFLICT (process_code) DO NOTHING;
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours)
SELECT d.id, 1, 'Head of HR approval', 'ROLE', 'HEAD_OF_HR', 72 FROM workflow_definition d
WHERE d.process_code = 'SCHEME_CHANGE' AND NOT EXISTS (SELECT 1 FROM workflow_stage s WHERE s.definition_id = d.id);

-- ------------------------------------------------------------------ settings (G-1: editable in the UI)
INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('payroll.liability.end-alert-days', '30', 'PAYROLL', 'INT', 'Days before an inherited commitment''s arrangement ends when the officer in charge is alerted (REQ 3.3)'),
 ('payroll.liability.officer-role', 'PAYROLL_ACCOUNTANT', 'PAYROLL', 'STRING', 'Role of the officer in charge of inherited liability deductions (REQ 3.3)'),
 ('payroll.salary-codes.status', 'PLACEHOLDER', 'PAYROLL', 'STRING', 'Salary scales and designation salary codes are placeholders until official values are supplied (open item 3)')
ON CONFLICT (setting_key) DO NOTHING;

-- ------------------------------------------------------------------ notifications (trilingual; translations to be reviewed)
INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'PAY_LIABILITY_ZERO', 'Inherited commitment fully recovered (REQ 3.3)',
  'Commitment recovered: {{employeeNo}} {{loanName}}', 'බැඳීම අය කර අවසන්: {{employeeNo}} {{loanName}}', 'கடப்பாடு முழுமையாக அறவிடப்பட்டது: {{employeeNo}} {{loanName}}',
  'The outstanding balance of {{loanName}} ({{organization}}) for {{employeeNo}} reached zero in {{period}}. Stop remittances to {{remittanceTarget}}.',
  '{{employeeNo}} හි {{loanName}} ({{organization}}) ශේෂය {{period}} දී ශුන්‍ය විය. {{remittanceTarget}} වෙත ප්‍රේෂණ නවත්වන්න.',
  '{{employeeNo}} இன் {{loanName}} ({{organization}}) நிலுவை {{period}} இல் பூச்சியமானது. {{remittanceTarget}} க்கான அனுப்புதல்களை நிறுத்தவும்.', TRUE),
 (0, now(), 'system', 'PAY_LIABILITY_ENDING', 'Inherited commitment arrangement about to end (REQ 3.3)',
  'Arrangement ending: {{employeeNo}} {{loanName}}', 'එකඟතාව අවසන් වේ: {{employeeNo}} {{loanName}}', 'ஏற்பாடு முடிவடைகிறது: {{employeeNo}} {{loanName}}',
  'The deduction arrangement {{arrangementRef}} for {{loanName}} of {{employeeNo}} ends on {{endDate}}. Outstanding balance: {{balance}}.',
  '{{employeeNo}} හි {{loanName}} සඳහා අඩු කිරීමේ එකඟතාව {{arrangementRef}} {{endDate}} දින අවසන් වේ. හිඟ ශේෂය: {{balance}}.',
  '{{employeeNo}} இன் {{loanName}} க்கான கழிவு ஏற்பாடு {{arrangementRef}} {{endDate}} அன்று முடிவடைகிறது. நிலுவை: {{balance}}.', TRUE),
 (0, now(), 'system', 'PAY_LIABILITY_ENDED', 'Inherited commitment arrangement ended with a balance (REQ 3.2)',
  'Arrangement ended: {{employeeNo}} {{loanName}}', 'එකඟතාව අවසන් විය: {{employeeNo}} {{loanName}}', 'ஏற்பாடு முடிந்தது: {{employeeNo}} {{loanName}}',
  'The deduction arrangement for {{loanName}} of {{employeeNo}} ended on {{endDate}}; deductions have stopped. Remaining balance: {{balance}}.',
  '{{employeeNo}} හි {{loanName}} සඳහා එකඟතාව {{endDate}} දින අවසන් විය; අඩු කිරීම් නවතා ඇත. ඉතිරි ශේෂය: {{balance}}.',
  '{{employeeNo}} இன் {{loanName}} க்கான ஏற்பாடு {{endDate}} அன்று முடிந்தது; கழிவுகள் நிறுத்தப்பட்டன. மீதி: {{balance}}.', TRUE),
 (0, now(), 'system', 'PAY_LIABILITY_FAILED', 'Monthly inherited commitment deduction failed in payroll (REQ 3.3)',
  'Deduction failed: {{employeeNo}} {{loanName}} {{period}}', 'අඩු කිරීම අසාර්ථකයි: {{employeeNo}} {{loanName}} {{period}}', 'கழிவு தோல்வியடைந்தது: {{employeeNo}} {{loanName}} {{period}}',
  'The {{period}} deduction of {{amount}} for {{loanName}} of {{employeeNo}} failed: {{reason}}.',
  '{{employeeNo}} හි {{loanName}} සඳහා {{period}} අඩු කිරීම ({{amount}}) අසාර්ථක විය: {{reason}}.',
  '{{employeeNo}} இன் {{loanName}} க்கான {{period}} கழிவு ({{amount}}) தோல்வியடைந்தது: {{reason}}.', TRUE)
ON CONFLICT (code) DO NOTHING;
