-- =====================================================================================================
-- Performance appraisal (wireframes "Performance appraisal", screens 1-15).
--   agreement & KPIs, KPI progress with evidence and versioned submissions, self-assessment, supervisor KPI
--   verification, behavioural assessment, reviewing officer approval & moderation, acknowledgement/finalization,
--   appeals, check-ins and feedback, special cases & KPI exceptions, delegation, controlled reopening,
--   HR policy versions (weighting + rating bands) and HRMS module synchronization.
-- Appraisals reuse the evaluation periods (per_period). Sample policy values are to be confirmed by Administration.
-- =====================================================================================================

-- ------------------------------------------------------------------ HR policy (screen 6)
CREATE TABLE apr_policy_version (
    id               BIGSERIAL PRIMARY KEY,
    version          BIGINT,
    created_at       TIMESTAMPTZ,
    created_by       VARCHAR(100),
    updated_at       TIMESTAMPTZ,
    updated_by       VARCHAR(100),
    version_no       INTEGER      NOT NULL UNIQUE,
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SUPERSEDED')),
    kpi_weight       NUMERIC(5,2) NOT NULL,
    behaviour_weight NUMERIC(5,2) NOT NULL,
    changed_by       VARCHAR(200),
    changed_by_user_id BIGINT,
    submitted_at     TIMESTAMPTZ,
    approved_by      VARCHAR(200),
    approved_at      TIMESTAMPTZ,
    note             VARCHAR(1000)
);

CREATE TABLE apr_rating_band (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    policy_version_id BIGINT       NOT NULL REFERENCES apr_policy_version (id) ON DELETE CASCADE,
    label             VARCHAR(60)  NOT NULL,
    rating_value      INTEGER      NOT NULL,
    category          VARCHAR(80)  NOT NULL,
    min_score         NUMERIC(6,2) NOT NULL,
    max_score         NUMERIC(6,2) NOT NULL,
    sort_order        INTEGER      NOT NULL,
    CHECK (max_score >= min_score)
);

-- ------------------------------------------------------------------ behavioural criteria (screen 4)
CREATE TABLE apr_behaviour_criterion (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    code         VARCHAR(40)  NOT NULL UNIQUE,
    name         VARCHAR(150) NOT NULL,
    sort_order   INTEGER      NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    descriptor_1 VARCHAR(300),
    descriptor_2 VARCHAR(300),
    descriptor_3 VARCHAR(300),
    descriptor_4 VARCHAR(300),
    descriptor_5 VARCHAR(300)
);

-- ------------------------------------------------------------------ the appraisal of one employee for one period
CREATE TABLE apr_appraisal (
    id                       BIGSERIAL PRIMARY KEY,
    version                  BIGINT,
    created_at               TIMESTAMPTZ,
    created_by               VARCHAR(100),
    updated_at               TIMESTAMPTZ,
    updated_by               VARCHAR(100),
    period_id                BIGINT       NOT NULL REFERENCES per_period (id),
    employee_id              BIGINT       NOT NULL REFERENCES employee (id),
    policy_version_id        BIGINT       REFERENCES apr_policy_version (id),
    kpi_weight               NUMERIC(5,2) NOT NULL,
    behaviour_weight         NUMERIC(5,2) NOT NULL,
    supervisor_user_id       BIGINT,
    reviewer_user_id         BIGINT,
    status                   VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'VERIFIED', 'APPROVED', 'ACKNOWLEDGED', 'FINALIZED')),
    agreement_status         VARCHAR(30)  NOT NULL CHECK (agreement_status IN ('DRAFT', 'SUBMITTED', 'SUPERVISOR_SIGNED', 'FINAL')),
    agreement_version        INTEGER      NOT NULL DEFAULT 1,
    employee_signed_at       TIMESTAMPTZ,
    supervisor_signed_at     TIMESTAMPTZ,
    supervisor_signed_by     VARCHAR(200),
    reviewer_signed_at       TIMESTAMPTZ,
    reviewer_signed_by       VARCHAR(200),
    self_status              VARCHAR(20)  NOT NULL DEFAULT 'NOT_STARTED' CHECK (self_status IN ('NOT_STARTED', 'DRAFT', 'SUBMITTED')),
    self_achievements        TEXT,
    self_challenges          TEXT,
    self_comments            TEXT,
    self_submitted_at        TIMESTAMPTZ,
    behaviour_status         VARCHAR(20)  NOT NULL DEFAULT 'NOT_STARTED' CHECK (behaviour_status IN ('NOT_STARTED', 'DRAFT', 'SUBMITTED')),
    strengths                TEXT,
    improvements             TEXT,
    behaviour_submitted_at   TIMESTAMPTZ,
    verification_completed_at TIMESTAMPTZ,
    kpi_score                NUMERIC(6,2),
    behaviour_score          NUMERIC(6,2),
    overall_score            NUMERIC(6,2),
    adjustment               NUMERIC(6,2),
    adjustment_justification TEXT,
    final_score              NUMERIC(6,2),
    category                 VARCHAR(80),
    reviewer_comments        TEXT,
    approved_at              TIMESTAMPTZ,
    acknowledged_at          TIMESTAMPTZ,
    employee_ack_comments    TEXT,
    finalized_at             TIMESTAMPTZ,
    UNIQUE (period_id, employee_id)
);
CREATE INDEX idx_apr_appraisal_supervisor ON apr_appraisal (supervisor_user_id);
CREATE INDEX idx_apr_appraisal_reviewer ON apr_appraisal (reviewer_user_id);
CREATE INDEX idx_apr_appraisal_employee ON apr_appraisal (employee_id);

CREATE TABLE apr_kpi (
    id                 BIGSERIAL PRIMARY KEY,
    version            BIGINT,
    created_at         TIMESTAMPTZ,
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    appraisal_id       BIGINT       NOT NULL REFERENCES apr_appraisal (id) ON DELETE CASCADE,
    objective          VARCHAR(300) NOT NULL,
    title              VARCHAR(300) NOT NULL,
    measurement_method VARCHAR(20)  NOT NULL CHECK (measurement_method IN ('MILESTONE', 'TASK_COUNT', 'TIMELINESS', 'QUALITY', 'QUALITATIVE')),
    target             VARCHAR(300),
    weight             NUMERIC(5,2) NOT NULL CHECK (weight >= 0 AND weight <= 100),
    review_period      VARCHAR(100),
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    milestones         TEXT,
    progress_data      TEXT,
    self_progress      NUMERIC(5,2),
    self_note          VARCHAR(1000),
    delay_explanation  TEXT,
    progress_status    VARCHAR(20)  NOT NULL DEFAULT 'NOT_STARTED' CHECK (progress_status IN ('NOT_STARTED', 'IN_PROGRESS', 'SUBMITTED', 'RETURNED', 'VERIFIED')),
    decision           VARCHAR(20)  CHECK (decision IN ('VERIFIED', 'ADJUSTED', 'RETURNED')),
    verified_value     NUMERIC(5,2),
    verifier_comment   VARCHAR(1000),
    excepted           BOOLEAN      NOT NULL DEFAULT FALSE,
    submission_count   INTEGER      NOT NULL DEFAULT 0
);
CREATE INDEX idx_apr_kpi_appraisal ON apr_kpi (appraisal_id);

CREATE TABLE apr_kpi_submission (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    kpi_id            BIGINT       NOT NULL REFERENCES apr_kpi (id) ON DELETE CASCADE,
    version_no        INTEGER      NOT NULL,
    self_progress     NUMERIC(5,2),
    progress_data     TEXT,
    delay_explanation TEXT,
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('SUBMITTED', 'VERIFIED', 'ADJUSTED', 'RETURNED')),
    submitted_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (kpi_id, version_no)
);

CREATE TABLE apr_evidence (
    id         BIGSERIAL PRIMARY KEY,
    version    BIGINT,
    created_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_at TIMESTAMPTZ,
    updated_by VARCHAR(100),
    kpi_id     BIGINT       NOT NULL REFERENCES apr_kpi (id) ON DELETE CASCADE,
    file_id    BIGINT       NOT NULL,
    file_name  VARCHAR(255) NOT NULL,
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'RETURNED'))
);
CREATE INDEX idx_apr_evidence_kpi ON apr_evidence (kpi_id);

CREATE TABLE apr_behaviour_rating (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    appraisal_id   BIGINT        NOT NULL REFERENCES apr_appraisal (id) ON DELETE CASCADE,
    criterion_id   BIGINT        NOT NULL REFERENCES apr_behaviour_criterion (id),
    rating         INTEGER       CHECK (rating BETWEEN 1 AND 5),
    not_applicable BOOLEAN       NOT NULL DEFAULT FALSE,
    justification  VARCHAR(2000),
    UNIQUE (appraisal_id, criterion_id)
);

CREATE TABLE apr_trail (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    appraisal_id BIGINT       NOT NULL REFERENCES apr_appraisal (id) ON DELETE CASCADE,
    level        VARCHAR(40)  NOT NULL,
    officer_name VARCHAR(200),
    on_behalf_of VARCHAR(200),
    decision     VARCHAR(60)  NOT NULL,
    comment      TEXT,
    acted_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_apr_trail_appraisal ON apr_trail (appraisal_id);

CREATE TABLE apr_agreement_history (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    appraisal_id BIGINT       NOT NULL REFERENCES apr_appraisal (id) ON DELETE CASCADE,
    version_no   INTEGER      NOT NULL,
    changed_by   VARCHAR(200),
    changed_at   TIMESTAMPTZ  NOT NULL,
    note         VARCHAR(300)
);

-- ------------------------------------------------------------------ appeals (screen 7)
CREATE TABLE apr_appeal (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    appraisal_id BIGINT       NOT NULL REFERENCES apr_appraisal (id),
    reference    VARCHAR(30)  NOT NULL UNIQUE,
    grounds      TEXT         NOT NULL,
    file_ids     VARCHAR(500),
    status       VARCHAR(20)  NOT NULL CHECK (status IN ('SUBMITTED', 'UNDER_REVIEW', 'DECIDED')),
    decision     TEXT,
    decided_by   VARCHAR(200),
    decided_at   TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ  NOT NULL
);

-- ------------------------------------------------------------------ check-ins and feedback (screen 13)
CREATE TABLE apr_checkin (
    id                 BIGSERIAL PRIMARY KEY,
    version            BIGINT,
    created_at         TIMESTAMPTZ,
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    employee_id        BIGINT      NOT NULL REFERENCES employee (id),
    supervisor_user_id BIGINT      NOT NULL,
    supervisor_name    VARCHAR(200),
    checkin_date       DATE        NOT NULL,
    progress_discussed TEXT,
    agreed_actions     TEXT,
    status             VARCHAR(20) NOT NULL CHECK (status IN ('SCHEDULED', 'DONE', 'CANCELLED'))
);

CREATE TABLE apr_feedback (
    id            BIGSERIAL PRIMARY KEY,
    version       BIGINT,
    created_at    TIMESTAMPTZ,
    created_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100),
    from_user_id  BIGINT       NOT NULL,
    from_name     VARCHAR(200),
    to_user_id    BIGINT,
    to_employee_id BIGINT,
    to_name       VARCHAR(200),
    feedback_type VARCHAR(20)  NOT NULL CHECK (feedback_type IN ('RECOGNITION', 'IMPROVEMENT', 'REQUEST')),
    body          TEXT         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'SENT' CHECK (status IN ('SENT', 'OPEN', 'ANSWERED'))
);

-- ------------------------------------------------------------------ special cases and KPI exceptions (screen 14)
CREATE TABLE apr_special_case (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    employee_id          BIGINT       NOT NULL REFERENCES employee (id),
    appraisal_id         BIGINT       REFERENCES apr_appraisal (id),
    case_type            VARCHAR(30)  NOT NULL CHECK (case_type IN ('PARTIAL_PERIOD', 'TRANSFER', 'PROBATION', 'EXTENDED_LEAVE',
                                                     'ACTING_APPOINTMENT', 'REPORTING_LINE_CHANGE', 'KPI_EXCEPTION')),
    kpi_id               BIGINT       REFERENCES apr_kpi (id),
    effective_from       DATE,
    effective_to         DATE,
    new_position         VARCHAR(200),
    previous_supervisor  VARCHAR(200),
    target_adjustment    VARCHAR(30),
    reason               VARCHAR(60),
    justification        TEXT         NOT NULL,
    original_value       NUMERIC(6,2),
    adjusted_value       NUMERIC(6,2),
    file_id              BIGINT,
    file_name            VARCHAR(255),
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by_user_id BIGINT       NOT NULL,
    requested_by_name    VARCHAR(200),
    decided_by_name      VARCHAR(200),
    decided_at           TIMESTAMPTZ,
    decision_comment     VARCHAR(1000),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);

-- ------------------------------------------------------------------ delegation and controlled reopening (screen 15)
CREATE TABLE apr_delegation (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    delegator_user_id BIGINT       NOT NULL,
    delegator_name    VARCHAR(200),
    delegate_user_id  BIGINT       NOT NULL,
    delegate_name     VARCHAR(200),
    date_from         DATE         NOT NULL,
    date_to           DATE         NOT NULL,
    scope             VARCHAR(30)  NOT NULL CHECK (scope IN ('VERIFICATION_AND_REVIEW', 'APPROVAL', 'ALL')),
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    revoked_at        TIMESTAMPTZ,
    CHECK (date_to >= date_from),
    CHECK (delegator_user_id <> delegate_user_id)
);

CREATE TABLE apr_reopen (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    appraisal_id         BIGINT       NOT NULL REFERENCES apr_appraisal (id),
    reference            VARCHAR(30)  NOT NULL UNIQUE,
    reason               VARCHAR(60)  NOT NULL,
    justification        TEXT         NOT NULL,
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'REOPENED', 'REJECTED', 'FINALIZED_AGAIN')),
    requested_by_user_id BIGINT       NOT NULL,
    requested_by_name    VARCHAR(200),
    approved_by_name     VARCHAR(200),
    decided_at           TIMESTAMPTZ,
    snapshot             TEXT
);

-- ------------------------------------------------------------------ HRMS synchronization (screen 8)
CREATE TABLE apr_sync_module (
    id              BIGSERIAL PRIMARY KEY,
    version         BIGINT,
    created_at      TIMESTAMPTZ,
    created_by      VARCHAR(100),
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(100),
    module_code     VARCHAR(20)  NOT NULL UNIQUE,
    module_label    VARCHAR(60)  NOT NULL,
    data_label      VARCHAR(100) NOT NULL,
    sort_order      INTEGER      NOT NULL,
    last_sync_at    TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    status          VARCHAR(20)  NOT NULL DEFAULT 'NEVER' CHECK (status IN ('NEVER', 'SUCCESS', 'FAILED')),
    last_message    VARCHAR(1000)
);

CREATE TABLE apr_sync_failure (
    id          BIGSERIAL PRIMARY KEY,
    version     BIGINT,
    created_at  TIMESTAMPTZ,
    created_by  VARCHAR(100),
    updated_at  TIMESTAMPTZ,
    updated_by  VARCHAR(100),
    module_code VARCHAR(20)   NOT NULL,
    failed_at   TIMESTAMPTZ   NOT NULL,
    reason      VARCHAR(1000) NOT NULL
);

-- =====================================================================================================
-- Reference data
-- =====================================================================================================

-- Policy v1 (sample weighting and bands, to be confirmed by Administration).
INSERT INTO apr_policy_version (version, created_at, created_by, version_no, status, kpi_weight, behaviour_weight, changed_by, approved_by, approved_at, note)
VALUES (0, now(), 'system', 1, 'APPROVED', 70, 30, 'System (initial sample)', 'System (initial sample)', now(),
        'Initial sample policy - to be confirmed by Administration');

INSERT INTO apr_rating_band (version, created_at, created_by, policy_version_id, label, rating_value, category, min_score, max_score, sort_order)
SELECT 0, now(), 'system', p.id, b.label, b.val, b.cat, b.mn, b.mx, b.ord
FROM apr_policy_version p
CROSS JOIN (VALUES ('Very Good', 5, 'Outstanding', 90, 100, 1),
                   ('Good', 4, 'Very good', 75, 89.99, 2),
                   ('Average', 3, 'Satisfactory', 60, 74.99, 3),
                   ('Bad', 2, 'Needs improvement', 40, 59.99, 4),
                   ('Very Bad', 1, 'Unsatisfactory', 0, 39.99, 5)) AS b(label, val, cat, mn, mx, ord)
WHERE p.version_no = 1;

INSERT INTO apr_behaviour_criterion (version, created_at, created_by, code, name, sort_order, descriptor_1, descriptor_2, descriptor_3, descriptor_4, descriptor_5) VALUES
 (0, now(), 'system', 'DISCIPLINE', 'Discipline', 1,
  'Frequently disregards rules and instructions', 'Sometimes disregards rules', 'Generally follows rules and procedures', 'Consistently follows rules and procedures', 'Exemplary conduct; a model for others'),
 (0, now(), 'system', 'PUNCTUALITY', 'Punctuality and attendance', 2,
  'Frequently late or absent without notice', 'Often late or absent', 'Usually punctual; absences notified', 'Punctual and reliable attendance', 'Always punctual; excellent attendance record'),
 (0, now(), 'system', 'TEAMWORK', 'Teamwork and cooperation', 3,
  'Does not cooperate with colleagues', 'Cooperates only when asked', 'Works adequately with the team', 'Actively supports colleagues', 'Builds team spirit and helps others succeed'),
 (0, now(), 'system', 'COMMUNICATION', 'Communication skills', 4,
  'Communication is unclear or inappropriate', 'Communication often needs correction', 'Communicates clearly in routine matters', 'Communicates clearly and effectively', 'Outstanding written and oral communication'),
 (0, now(), 'system', 'RESPONSIBILITY', 'Responsibility and accountability', 5,
  'Avoids responsibility for own work', 'Accepts responsibility reluctantly', 'Accepts responsibility for own work', 'Takes ownership of results', 'Takes full ownership, including for the team'),
 (0, now(), 'system', 'LEADERSHIP', 'Leadership qualities', 6,
  'Shows no initiative', 'Rarely shows initiative', 'Shows initiative when required', 'Guides and motivates others', 'Inspires others and leads by example'),
 (0, now(), 'system', 'PROFESSIONALISM', 'Professionalism', 7,
  'Conduct falls below expected standards', 'Conduct occasionally below standard', 'Maintains professional conduct', 'Highly professional in all dealings', 'Exemplary integrity and professionalism'),
 (0, now(), 'system', 'ATTITUDE', 'Attitude towards work', 8,
  'Negative attitude affects work', 'Indifferent attitude to work', 'Positive attitude to assigned work', 'Enthusiastic and committed', 'Exceptionally committed and self-motivated'),
 (0, now(), 'system', 'INSTRUCTIONS', 'Ability to follow instructions', 9,
  'Frequently fails to follow instructions', 'Needs repeated instructions', 'Follows instructions adequately', 'Follows instructions accurately', 'Follows instructions precisely and anticipates needs'),
 (0, now(), 'system', 'PROBLEM_SOLVING', 'Problem-solving ability', 10,
  'Unable to resolve routine problems', 'Resolves problems only with help', 'Resolves routine problems', 'Resolves complex problems effectively', 'Finds creative solutions to difficult problems');

INSERT INTO apr_sync_module (version, created_at, created_by, module_code, module_label, data_label, sort_order) VALUES
 (0, now(), 'system', '01', 'Module 01', 'Organization and cadre', 1),
 (0, now(), 'system', '02', 'Module 02', 'Employee master data', 2),
 (0, now(), 'system', '05_06', 'Modules 05, 06', 'Attendance and leave', 3),
 (0, now(), 'system', '09', 'Module 09', 'Learning and career', 4),
 (0, now(), 'system', '12', 'Module 12', 'Transfers and promotions', 5);

INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('appraisal.appeal-window-days', '14', 'PERFORMANCE', 'INT', 'Days after finalization during which an employee may appeal an appraisal'),
 ('appraisal.self-assessment-days', '30', 'PERFORMANCE', 'INT', 'Self-assessment closes this many days after the end of the appraisal period (when the period has no due date)')
ON CONFLICT (setting_key) DO NOTHING;

INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'APR_ACTION', 'Performance appraisal: an action is waiting for the recipient',
  'Performance appraisal: {{title}}', 'කාර්ය සාධන ඇගයීම: {{title}}', 'செயல்திறன் மதிப்பீடு: {{title}}',
  '{{message}}', '{{message}}', '{{message}}', FALSE)
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------------ permissions
INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['APR_MANAGE','APR_OVERVIEW','APR_REPORT']) p
WHERE r.code IN ('HR_ADMIN', 'HEAD_OF_HR')
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['APR_POLICY_APPROVE']) p
WHERE r.code IN ('HEAD_OF_HR', 'MANAGEMENT')
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['APR_OVERVIEW','APR_REPORT']) p
WHERE r.code IN ('MANAGEMENT', 'COMMISSION')
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission)
SELECT r.id, 'APR_ADMIN' FROM role r WHERE r.code = 'SYSTEM_ADMIN'
ON CONFLICT DO NOTHING;
