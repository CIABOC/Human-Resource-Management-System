-- =====================================================================================================
-- Payroll and Salary Administration (SRS 4.6) and Employee Benefits and Insurance (SRS 4.7) - schema.
-- Rates, scales and rules are data (C-02, SYS-02); everything that changes over time is effective-dated (SYS-03/04).
-- =====================================================================================================

-- ------------------------------------------------------------------ salary scales (FR-PAY-001)
CREATE TABLE pay_salary_scale (
    id               BIGSERIAL PRIMARY KEY,
    version          BIGINT,
    created_at       TIMESTAMPTZ,
    created_by       VARCHAR(100),
    updated_at       TIMESTAMPTZ,
    updated_by       VARCHAR(100),
    code             VARCHAR(40)   NOT NULL,
    name             VARCHAR(200)  NOT NULL,
    grade            VARCHAR(40),
    initial_salary   NUMERIC(14,2) NOT NULL CHECK (initial_salary >= 0),
    increment_amount NUMERIC(14,2) NOT NULL CHECK (increment_amount >= 0),
    max_steps        INTEGER       NOT NULL CHECK (max_steps >= 0),
    effective_from   DATE          NOT NULL,
    effective_to     DATE,
    notes            VARCHAR(1000),
    UNIQUE (code, effective_from),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- ------------------------------------------------------------------ allowances (FR-PAY-002)
CREATE TABLE pay_allowance_type (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    code           VARCHAR(40)  NOT NULL UNIQUE,
    name           VARCHAR(200) NOT NULL,
    name_si        VARCHAR(200),
    name_ta        VARCHAR(200),
    applies_to_all BOOLEAN      NOT NULL DEFAULT FALSE,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    description    VARCHAR(500)
);

CREATE TABLE pay_allowance_rate (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    allowance_type_id BIGINT        NOT NULL REFERENCES pay_allowance_type (id),
    basis             VARCHAR(20)   NOT NULL CHECK (basis IN ('FIXED', 'PERCENT_OF_BASIC')),
    rate_value        NUMERIC(14,4) NOT NULL CHECK (rate_value >= 0),
    effective_from    DATE          NOT NULL,
    effective_to      DATE,
    notes             VARCHAR(500),
    UNIQUE (allowance_type_id, effective_from),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- ------------------------------------------------------------------ employee salary placement (FR-PAY-003)
CREATE TABLE pay_employee_salary (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    employee_id    BIGINT        NOT NULL REFERENCES employee (id),
    scale_code     VARCHAR(40)   NOT NULL,
    step           INTEGER       NOT NULL CHECK (step >= 0),
    basic_salary   NUMERIC(14,2) NOT NULL CHECK (basic_salary >= 0),
    effective_from DATE          NOT NULL,
    effective_to   DATE,
    reason         VARCHAR(30)   NOT NULL,
    reference      VARCHAR(100),
    remarks        VARCHAR(1000),
    UNIQUE (employee_id, effective_from),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX ix_pay_employee_salary_emp ON pay_employee_salary (employee_id, effective_from);

CREATE TABLE pay_employee_allowance (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    employee_id       BIGINT        NOT NULL REFERENCES employee (id),
    allowance_type_id BIGINT        NOT NULL REFERENCES pay_allowance_type (id),
    amount_override   NUMERIC(14,2) CHECK (amount_override >= 0),
    effective_from    DATE          NOT NULL,
    effective_to      DATE,
    remarks           VARCHAR(500),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX ix_pay_employee_allowance_emp ON pay_employee_allowance (employee_id);

-- ------------------------------------------------------------------ deductions and schemes (FR-PAY-010..012)
CREATE TABLE pay_deduction_type (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    code                VARCHAR(40)  NOT NULL UNIQUE,
    name                VARCHAR(200) NOT NULL,
    name_si             VARCHAR(200),
    name_ta             VARCHAR(200),
    category            VARCHAR(20)  NOT NULL CHECK (category IN ('STATUTORY', 'SCHEME', 'OTHER')),
    membership_required BOOLEAN      NOT NULL DEFAULT TRUE,
    pension_related     BOOLEAN      NOT NULL DEFAULT FALSE,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    description         VARCHAR(500)
);

CREATE TABLE pay_deduction_rate (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    deduction_type_id BIGINT        NOT NULL REFERENCES pay_deduction_type (id),
    employee_basis    VARCHAR(20)   NOT NULL CHECK (employee_basis IN ('NONE', 'FIXED', 'PERCENT_OF_BASIC', 'PERCENT_OF_GROSS')),
    employee_value    NUMERIC(14,4) NOT NULL DEFAULT 0 CHECK (employee_value >= 0),
    employer_basis    VARCHAR(20)   NOT NULL CHECK (employer_basis IN ('NONE', 'FIXED', 'PERCENT_OF_BASIC', 'PERCENT_OF_GROSS')),
    employer_value    NUMERIC(14,4) NOT NULL DEFAULT 0 CHECK (employer_value >= 0),
    effective_from    DATE          NOT NULL,
    effective_to      DATE,
    to_be_confirmed   BOOLEAN       NOT NULL DEFAULT FALSE,
    notes             VARCHAR(500),
    UNIQUE (deduction_type_id, effective_from),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE TABLE pay_deduction_membership (
    id                      BIGSERIAL PRIMARY KEY,
    version                 BIGINT,
    created_at              TIMESTAMPTZ,
    created_by              VARCHAR(100),
    updated_at              TIMESTAMPTZ,
    updated_by              VARCHAR(100),
    employee_id             BIGINT        NOT NULL REFERENCES employee (id),
    deduction_type_id       BIGINT        NOT NULL REFERENCES pay_deduction_type (id),
    member_no               VARCHAR(60),
    employee_value_override NUMERIC(14,4) CHECK (employee_value_override >= 0),
    employer_value_override NUMERIC(14,4) CHECK (employer_value_override >= 0),
    effective_from          DATE          NOT NULL,
    effective_to            DATE,
    remarks                 VARCHAR(500),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX ix_pay_deduction_membership_emp ON pay_deduction_membership (employee_id);

-- ------------------------------------------------------------------ seconded-employee loans (FR-PAY-020..023)
CREATE TABLE pay_employee_loan (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    employee_id         BIGINT        NOT NULL REFERENCES employee (id),
    loan_type           VARCHAR(40)   NOT NULL,
    lender              VARCHAR(300)  NOT NULL,
    previous_workplace  VARCHAR(300),
    reference_no        VARCHAR(100),
    original_amount     NUMERIC(14,2) NOT NULL CHECK (original_amount > 0),
    outstanding_balance NUMERIC(14,2) NOT NULL CHECK (outstanding_balance >= 0),
    monthly_instalment  NUMERIC(14,2) NOT NULL CHECK (monthly_instalment >= 0),
    start_date          DATE          NOT NULL,
    status              VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE', 'SETTLED', 'TRANSFERRED_OUT')),
    closed_on           DATE,
    remarks             VARCHAR(1000),
    CHECK (outstanding_balance <= original_amount)
);
CREATE INDEX ix_pay_employee_loan_emp ON pay_employee_loan (employee_id);

CREATE TABLE pay_loan_transaction (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    loan_id        BIGINT        NOT NULL REFERENCES pay_employee_loan (id),
    txn_date       DATE          NOT NULL,
    txn_type       VARCHAR(30)   NOT NULL CHECK (txn_type IN ('OPENING', 'DEDUCTION', 'SETTLEMENT', 'INSTALMENT_CHANGE', 'TRANSFER_OUT')),
    amount         NUMERIC(14,2) NOT NULL,
    balance_after  NUMERIC(14,2) NOT NULL,
    payroll_run_id BIGINT,
    period         VARCHAR(7),
    remarks        VARCHAR(500)
);
CREATE INDEX ix_pay_loan_transaction_loan ON pay_loan_transaction (loan_id);

CREATE TABLE pay_loan_adjustment (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    loan_id              BIGINT        NOT NULL REFERENCES pay_employee_loan (id),
    adjustment_type      VARCHAR(30)   NOT NULL CHECK (adjustment_type IN ('EARLY_SETTLEMENT', 'INSTALMENT_CHANGE', 'TRANSFER_OUT')),
    new_instalment       NUMERIC(14,2) CHECK (new_instalment >= 0),
    settlement_amount    NUMERIC(14,2) CHECK (settlement_amount >= 0),
    effective_date       DATE          NOT NULL,
    reason               VARCHAR(1000) NOT NULL,
    status               VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'RETURNED', 'APPLIED', 'REJECTED', 'CANCELLED')),
    workflow_instance_id BIGINT,
    applied_at           TIMESTAMPTZ
);

-- ------------------------------------------------------------------ payroll runs (FR-PAY-030..035)
CREATE TABLE pay_payroll_run (
    id                     BIGSERIAL PRIMARY KEY,
    version                BIGINT,
    created_at             TIMESTAMPTZ,
    created_by             VARCHAR(100),
    updated_at             TIMESTAMPTZ,
    updated_by             VARCHAR(100),
    period                 VARCHAR(7)    NOT NULL UNIQUE CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    pay_date               DATE,
    status                 VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'RETURNED', 'FINALIZED')),
    locked                 BOOLEAN       NOT NULL DEFAULT FALSE,
    calculated_at          TIMESTAMPTZ,
    calculated_by          VARCHAR(100),
    calculated_by_user_id  BIGINT,
    submitted_at           TIMESTAMPTZ,
    submitted_by           VARCHAR(100),
    finalized_at           TIMESTAMPTZ,
    workflow_instance_id   BIGINT,
    employee_count         INTEGER       NOT NULL DEFAULT 0,
    total_gross            NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_deductions       NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_net              NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_employer         NUMERIC(14,2) NOT NULL DEFAULT 0,
    warning_count          INTEGER       NOT NULL DEFAULT 0,
    notes                  VARCHAR(1000)
);

CREATE TABLE pay_payroll_line (
    id                     BIGSERIAL PRIMARY KEY,
    version                BIGINT,
    created_at             TIMESTAMPTZ,
    created_by             VARCHAR(100),
    updated_at             TIMESTAMPTZ,
    updated_by             VARCHAR(100),
    run_id                 BIGINT        NOT NULL REFERENCES pay_payroll_run (id),
    employee_id            BIGINT        NOT NULL REFERENCES employee (id),
    employee_no            VARCHAR(30)   NOT NULL,
    employee_name          VARCHAR(300)  NOT NULL,
    designation            VARCHAR(200),
    org_unit_code          VARCHAR(40),
    bank_code              VARCHAR(40),
    bank_branch            VARCHAR(100),
    bank_account_no        VARCHAR(40),
    scale_code             VARCHAR(40),
    step                   INTEGER,
    basic_salary           NUMERIC(14,2) NOT NULL,
    gross_pay              NUMERIC(14,2) NOT NULL,
    total_deductions       NUMERIC(14,2) NOT NULL,
    net_pay                NUMERIC(14,2) NOT NULL,
    employer_contributions NUMERIC(14,2) NOT NULL,
    no_pay_days            NUMERIC(6,2)  NOT NULL DEFAULT 0,
    warnings               VARCHAR(1000),
    UNIQUE (run_id, employee_id)
);
CREATE INDEX ix_pay_payroll_line_emp ON pay_payroll_line (employee_id);

CREATE TABLE pay_payroll_line_item (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    line_id        BIGINT        NOT NULL REFERENCES pay_payroll_line (id) ON DELETE CASCADE,
    component_type VARCHAR(20)   NOT NULL CHECK (component_type IN ('EARNING', 'DEDUCTION', 'EMPLOYER')),
    source_type    VARCHAR(20)   NOT NULL,
    source_id      BIGINT,
    code           VARCHAR(40)   NOT NULL,
    description    VARCHAR(300)  NOT NULL,
    amount         NUMERIC(14,2) NOT NULL,
    sort_order     INTEGER       NOT NULL DEFAULT 0
);
CREATE INDEX ix_pay_payroll_line_item_line ON pay_payroll_line_item (line_id);

-- Controlled corrections: the only way to change pay after a period is finalized (FR-PAY-033).
CREATE TABLE pay_payroll_adjustment (
    id              BIGSERIAL PRIMARY KEY,
    version         BIGINT,
    created_at      TIMESTAMPTZ,
    created_by      VARCHAR(100),
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(100),
    employee_id     BIGINT        NOT NULL REFERENCES employee (id),
    adjustment_type VARCHAR(20)   NOT NULL CHECK (adjustment_type IN ('EARNING', 'DEDUCTION')),
    code            VARCHAR(40)   NOT NULL,
    description     VARCHAR(300)  NOT NULL,
    amount          NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    target_period   VARCHAR(7)    NOT NULL CHECK (target_period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    related_run_id  BIGINT REFERENCES pay_payroll_run (id),
    reason          VARCHAR(1000) NOT NULL,
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('OPEN', 'APPLIED', 'CANCELLED')),
    applied_run_id  BIGINT REFERENCES pay_payroll_run (id)
);

-- Finalized (locked) payroll periods are immutable, also at database level (FR-PAY-033, defence in depth).
CREATE OR REPLACE FUNCTION pay_guard_locked_run() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.locked THEN RAISE EXCEPTION 'Payroll run % is finalized and locked', OLD.period; END IF;
        RETURN OLD;
    END IF;
    IF OLD.locked THEN RAISE EXCEPTION 'Payroll run % is finalized and locked', OLD.period; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pay_run_locked BEFORE UPDATE OR DELETE ON pay_payroll_run
    FOR EACH ROW EXECUTE FUNCTION pay_guard_locked_run();

CREATE OR REPLACE FUNCTION pay_guard_locked_line() RETURNS trigger AS $$
DECLARE
    rid BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN rid := OLD.run_id; ELSE rid := NEW.run_id; END IF;
    IF EXISTS (SELECT 1 FROM pay_payroll_run r WHERE r.id = rid AND r.locked) THEN
        RAISE EXCEPTION 'Payroll lines of a finalized run cannot be changed';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pay_line_locked BEFORE INSERT OR UPDATE OR DELETE ON pay_payroll_line
    FOR EACH ROW EXECUTE FUNCTION pay_guard_locked_line();

CREATE OR REPLACE FUNCTION pay_guard_locked_item() RETURNS trigger AS $$
DECLARE
    lid BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN lid := OLD.line_id; ELSE lid := NEW.line_id; END IF;
    IF EXISTS (SELECT 1 FROM pay_payroll_line l JOIN pay_payroll_run r ON r.id = l.run_id WHERE l.id = lid AND r.locked) THEN
        RAISE EXCEPTION 'Payroll line items of a finalized run cannot be changed';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pay_item_locked BEFORE INSERT OR UPDATE OR DELETE ON pay_payroll_line_item
    FOR EACH ROW EXECUTE FUNCTION pay_guard_locked_item();

-- ------------------------------------------------------------------ insurance (FR-BEN-001..006)
CREATE TABLE ben_insurance_scheme (
    id                        BIGSERIAL PRIMARY KEY,
    version                   BIGINT,
    created_at                TIMESTAMPTZ,
    created_by                VARCHAR(100),
    updated_at                TIMESTAMPTZ,
    updated_by                VARCHAR(100),
    code                      VARCHAR(40)   NOT NULL UNIQUE,
    name                      VARCHAR(200)  NOT NULL,
    name_si                   VARCHAR(200),
    name_ta                   VARCHAR(200),
    scheme_type               VARCHAR(20)   NOT NULL CHECK (scheme_type IN ('AGRAHARA', 'OTHER')),
    category                  VARCHAR(40),
    provider                  VARCHAR(300),
    employee_premium          NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (employee_premium >= 0),
    employer_premium          NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (employer_premium >= 0),
    coverage_amount           NUMERIC(14,2) CHECK (coverage_amount >= 0),
    payroll_deduction         BOOLEAN       NOT NULL DEFAULT TRUE,
    claims_enabled            BOOLEAN       NOT NULL DEFAULT TRUE,
    eligible_employment_types VARCHAR(500),
    min_service_months        INTEGER       NOT NULL DEFAULT 0 CHECK (min_service_months >= 0),
    eligibility_notes         TEXT,
    active                    BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE ben_insurance_coverage (
    id                 BIGSERIAL PRIMARY KEY,
    version            BIGINT,
    created_at         TIMESTAMPTZ,
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    employee_id        BIGINT        NOT NULL REFERENCES employee (id),
    scheme_id          BIGINT        NOT NULL REFERENCES ben_insurance_scheme (id),
    policy_no          VARCHAR(100),
    start_date         DATE          NOT NULL,
    expiry_date        DATE,
    employee_premium   NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (employee_premium >= 0),
    employer_premium   NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (employer_premium >= 0),
    coverage_amount    NUMERIC(14,2) CHECK (coverage_amount >= 0),
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    expiry_notified_on DATE,
    remarks            VARCHAR(1000),
    CHECK (expiry_date IS NULL OR expiry_date >= start_date)
);
CREATE INDEX ix_ben_coverage_emp ON ben_insurance_coverage (employee_id);
CREATE INDEX ix_ben_coverage_expiry ON ben_insurance_coverage (status, expiry_date);

CREATE TABLE ben_insurance_claim (
    id              BIGSERIAL PRIMARY KEY,
    version         BIGINT,
    created_at      TIMESTAMPTZ,
    created_by      VARCHAR(100),
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(100),
    coverage_id     BIGINT        NOT NULL REFERENCES ben_insurance_coverage (id),
    employee_id     BIGINT        NOT NULL REFERENCES employee (id),
    claim_date      DATE          NOT NULL,
    incident_date   DATE,
    claim_type      VARCHAR(100)  NOT NULL,
    amount_claimed  NUMERIC(14,2) NOT NULL CHECK (amount_claimed > 0),
    amount_approved NUMERIC(14,2) CHECK (amount_approved >= 0),
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('SUBMITTED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'PAID')),
    provider_ref    VARCHAR(100),
    description     VARCHAR(2000),
    remarks         VARCHAR(1000)
);
CREATE INDEX ix_ben_claim_emp ON ben_insurance_claim (employee_id);
