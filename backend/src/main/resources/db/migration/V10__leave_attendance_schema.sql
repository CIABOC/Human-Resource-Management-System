-- =====================================================================================================
-- Leave Management (SRS 4.4) and Attendance Management (SRS 4.5) schema.
-- Rules (entitlements, schedules, holidays, no-pay rules) are data, never code (C-02, SYS-02, DR-03).
-- =====================================================================================================

-- ------------------------------------------------------------------ leave types (FR-LEV-001)
CREATE TABLE leave_type (
    id                       BIGSERIAL PRIMARY KEY,
    version                  BIGINT,
    created_at               TIMESTAMPTZ,
    created_by               VARCHAR(100),
    updated_at               TIMESTAMPTZ,
    updated_by               VARCHAR(100),
    code                     VARCHAR(40)  NOT NULL UNIQUE,
    name                     VARCHAR(150) NOT NULL,
    name_si                  VARCHAR(150),
    name_ta                  VARCHAR(150),
    description              VARCHAR(1000),
    paid                     BOOLEAN      NOT NULL DEFAULT TRUE,
    balance_tracked          BOOLEAN      NOT NULL DEFAULT TRUE,
    requires_reason          BOOLEAN      NOT NULL DEFAULT FALSE,
    requires_document        BOOLEAN      NOT NULL DEFAULT FALSE,
    document_after_days      INTEGER,
    min_notice_days          INTEGER      NOT NULL DEFAULT 0 CHECK (min_notice_days >= 0),
    max_consecutive_days     INTEGER CHECK (max_consecutive_days IS NULL OR max_consecutive_days > 0),
    allow_backdated          BOOLEAN      NOT NULL DEFAULT FALSE,
    half_day_allowed         BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order               INTEGER      NOT NULL DEFAULT 0,
    active                   BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Effective-dated entitlement (days per calendar year) per employee category (FR-LEV-002).
CREATE TABLE leave_entitlement (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    leave_type_id     BIGINT       NOT NULL REFERENCES leave_type (id),
    employee_category VARCHAR(40)  NOT NULL,
    days_per_year     NUMERIC(7, 2) NOT NULL CHECK (days_per_year >= 0),
    effective_from    DATE         NOT NULL,
    effective_to      DATE,
    remarks           VARCHAR(500),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX ix_leave_entitlement_type ON leave_entitlement (leave_type_id, employee_category, effective_from);

-- Balance per employee / type / calendar year (FR-LEV-006). available = entitled + adjusted - used - pending.
CREATE TABLE leave_balance (
    id            BIGSERIAL PRIMARY KEY,
    version       BIGINT,
    created_at    TIMESTAMPTZ,
    created_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100),
    employee_id   BIGINT        NOT NULL REFERENCES employee (id),
    leave_type_id BIGINT        NOT NULL REFERENCES leave_type (id),
    leave_year    INTEGER       NOT NULL,
    entitled      NUMERIC(7, 2) NOT NULL DEFAULT 0,
    adjusted      NUMERIC(7, 2) NOT NULL DEFAULT 0,
    used          NUMERIC(7, 2) NOT NULL DEFAULT 0,
    pending       NUMERIC(7, 2) NOT NULL DEFAULT 0,
    UNIQUE (employee_id, leave_type_id, leave_year)
);

-- History of HR balance initialisations and adjustments (audited as well).
CREATE TABLE leave_balance_adjustment (
    id            BIGSERIAL PRIMARY KEY,
    version       BIGINT,
    created_at    TIMESTAMPTZ,
    created_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100),
    balance_id    BIGINT        NOT NULL REFERENCES leave_balance (id),
    employee_id   BIGINT        NOT NULL,
    leave_type_id BIGINT        NOT NULL,
    leave_year    INTEGER       NOT NULL,
    kind          VARCHAR(20)   NOT NULL CHECK (kind IN ('INITIALISE', 'ADJUST')),
    days          NUMERIC(7, 2) NOT NULL,
    reason        VARCHAR(1000) NOT NULL
);
CREATE INDEX ix_leave_adj_emp ON leave_balance_adjustment (employee_id, leave_year);

-- Leave applications (FR-LEV-003..009).
CREATE TABLE leave_application (
    id                          BIGSERIAL PRIMARY KEY,
    version                     BIGINT,
    created_at                  TIMESTAMPTZ,
    created_by                  VARCHAR(100),
    updated_at                  TIMESTAMPTZ,
    updated_by                  VARCHAR(100),
    employee_id                 BIGINT        NOT NULL REFERENCES employee (id),
    leave_type_id               BIGINT        NOT NULL REFERENCES leave_type (id),
    leave_year                  INTEGER       NOT NULL,
    start_date                  DATE          NOT NULL,
    end_date                    DATE          NOT NULL,
    half_day                    BOOLEAN       NOT NULL DEFAULT FALSE,
    half_day_session            VARCHAR(2) CHECK (half_day_session IS NULL OR half_day_session IN ('AM', 'PM')),
    days                        NUMERIC(7, 2) NOT NULL,
    reason                      VARCHAR(1000),
    contact_during_leave        VARCHAR(300),
    document_file_id            BIGINT REFERENCES stored_file (id),
    status                      VARCHAR(20)   NOT NULL
        CHECK (status IN ('PENDING', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED')),
    workflow_instance_id        BIGINT,
    decided_at                  TIMESTAMPTZ,
    cancellation_status         VARCHAR(20) CHECK (cancellation_status IS NULL OR cancellation_status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    cancellation_reason         VARCHAR(1000),
    cancel_workflow_instance_id BIGINT,
    CHECK (end_date >= start_date)
);
CREATE INDEX ix_leave_app_emp ON leave_application (employee_id, start_date);
CREATE INDEX ix_leave_app_status ON leave_application (status, start_date);

-- ------------------------------------------------------------------ attendance configuration
CREATE TABLE public_holiday (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    holiday_date DATE         NOT NULL UNIQUE,
    name         VARCHAR(200) NOT NULL,
    name_si      VARCHAR(200),
    name_ta      VARCHAR(200),
    holiday_type VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC' CHECK (holiday_type IN ('PUBLIC', 'BANK', 'MERCANTILE', 'SPECIAL')),
    remarks      VARCHAR(500)
);

-- Effective-dated working schedules (FR-ATT-010..013). Versions of one schedule share a code.
CREATE TABLE work_schedule (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    code                VARCHAR(40)  NOT NULL,
    name                VARCHAR(150) NOT NULL,
    start_time          TIME         NOT NULL,
    end_time            TIME         NOT NULL,
    late_grace_minutes  INTEGER      NOT NULL DEFAULT 0 CHECK (late_grace_minutes >= 0),
    early_grace_minutes INTEGER      NOT NULL DEFAULT 0 CHECK (early_grace_minutes >= 0),
    work_days           VARCHAR(40)  NOT NULL,
    org_unit_code       VARCHAR(40),
    employee_category   VARCHAR(40),
    effective_from      DATE         NOT NULL,
    effective_to        DATE,
    remarks             VARCHAR(500),
    CHECK (end_time > start_time),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    UNIQUE (code, effective_from)
);

-- ------------------------------------------------------------------ attendance data
-- Raw check-in / check-out events from devices, imports and approved manual corrections (FR-ATT-001..005, INT-04).
CREATE TABLE attendance_event (
    id              BIGSERIAL PRIMARY KEY,
    version         BIGINT,
    created_at      TIMESTAMPTZ,
    created_by      VARCHAR(100),
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(100),
    employee_id     BIGINT REFERENCES employee (id),
    employee_no     VARCHAR(30)  NOT NULL,
    event_time      TIMESTAMPTZ  NOT NULL,
    work_date       DATE         NOT NULL,
    direction       VARCHAR(3)   NOT NULL CHECK (direction IN ('IN', 'OUT')),
    source          VARCHAR(10)  NOT NULL CHECK (source IN ('DEVICE', 'MANUAL', 'IMPORT')),
    device_id       VARCHAR(60),
    batch_ref       VARCHAR(60),
    flagged         BOOLEAN      NOT NULL DEFAULT FALSE,
    flag_reason     VARCHAR(500),
    review_status   VARCHAR(20) CHECK (review_status IS NULL OR review_status IN ('ACCEPTED', 'REJECTED', 'SUPERSEDED')),
    review_comment  VARCHAR(500),
    reviewed_by     VARCHAR(100),
    reviewed_at     TIMESTAMPTZ,
    correction_id   BIGINT
);
CREATE INDEX ix_att_event_emp_date ON attendance_event (employee_id, work_date);
CREATE INDEX ix_att_event_date ON attendance_event (work_date);
CREATE INDEX ix_att_event_flagged ON attendance_event (flagged, review_status);

-- Processed daily attendance (FR-ATT-006..009, -014..018).
CREATE TABLE attendance_daily (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    employee_id          BIGINT       NOT NULL REFERENCES employee (id),
    work_date            DATE         NOT NULL,
    schedule_id          BIGINT REFERENCES work_schedule (id),
    check_in             TIMESTAMPTZ,
    check_out            TIMESTAMPTZ,
    worked_minutes       INTEGER      NOT NULL DEFAULT 0,
    late_minutes         INTEGER      NOT NULL DEFAULT 0,
    early_minutes        INTEGER      NOT NULL DEFAULT 0,
    status               VARCHAR(20)  NOT NULL
        CHECK (status IN ('PRESENT', 'LATE', 'EARLY', 'ABSENT', 'ON_LEAVE', 'HOLIDAY', 'WEEKEND', 'NO_SCHEDULE')),
    leave_application_id BIGINT,
    leave_type_code      VARCHAR(40),
    leave_half_day       BOOLEAN      NOT NULL DEFAULT FALSE,
    unpaid_leave         BOOLEAN      NOT NULL DEFAULT FALSE,
    corrected            BOOLEAN      NOT NULL DEFAULT FALSE,
    exception_note       VARCHAR(500),
    processed_at         TIMESTAMPTZ  NOT NULL,
    UNIQUE (employee_id, work_date)
);
CREATE INDEX ix_att_daily_date ON attendance_daily (work_date, status);

-- Manual attendance corrections, approved through workflow ATTENDANCE_CORRECTION (FR-ATT-019..020).
CREATE TABLE attendance_correction (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    employee_id          BIGINT        NOT NULL REFERENCES employee (id),
    work_date            DATE          NOT NULL,
    check_in             TIME,
    check_out            TIME,
    reason               VARCHAR(1000) NOT NULL,
    status               VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'RETURNED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    workflow_instance_id BIGINT,
    CHECK (check_in IS NOT NULL OR check_out IS NOT NULL)
);
CREATE INDEX ix_att_corr_emp ON attendance_correction (employee_id, work_date);

-- Monthly no-pay calculation, reviewed and approved before release to payroll (FR-ATT-021..023, FR-LEV-010).
CREATE TABLE nopay_run (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    period               VARCHAR(7)    NOT NULL UNIQUE,
    status               VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT', 'PENDING', 'RETURNED', 'APPROVED', 'REJECTED')),
    calculated_at        TIMESTAMPTZ   NOT NULL,
    calculated_by        VARCHAR(100),
    rules_snapshot       VARCHAR(1000),
    unprocessed_days     INTEGER       NOT NULL DEFAULT 0,
    total_days           NUMERIC(9, 2) NOT NULL DEFAULT 0,
    employee_count       INTEGER       NOT NULL DEFAULT 0,
    workflow_instance_id BIGINT,
    approved_at          TIMESTAMPTZ
);

CREATE TABLE nopay_line (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    run_id            BIGINT        NOT NULL REFERENCES nopay_run (id) ON DELETE CASCADE,
    employee_id       BIGINT        NOT NULL REFERENCES employee (id),
    absence_days      NUMERIC(7, 2) NOT NULL DEFAULT 0,
    unpaid_leave_days NUMERIC(7, 2) NOT NULL DEFAULT 0,
    late_count        INTEGER       NOT NULL DEFAULT 0,
    late_days         NUMERIC(7, 2) NOT NULL DEFAULT 0,
    calculated_days   NUMERIC(7, 2) NOT NULL DEFAULT 0,
    adjusted_days     NUMERIC(7, 2),
    adjustment_reason VARCHAR(1000),
    UNIQUE (run_id, employee_id)
);
