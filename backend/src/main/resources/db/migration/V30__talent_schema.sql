-- =====================================================================================================
-- Recruitment and Appointment (SRS 4.3), Performance Evaluation (SRS 4.8), Training and Development (SRS 4.9).
-- Models, criteria, weights, rating scales, letter templates and approval chains are DATA (C-02, DR-03).
-- =====================================================================================================

-- ================================================================== Recruitment
CREATE TABLE rec_vacancy (
    id               BIGSERIAL PRIMARY KEY,
    version          BIGINT,
    created_at       TIMESTAMPTZ,
    created_by       VARCHAR(100),
    updated_at       TIMESTAMPTZ,
    updated_by       VARCHAR(100),
    reference_no     VARCHAR(40)  NOT NULL UNIQUE,
    title            VARCHAR(200) NOT NULL,
    designation_id   BIGINT       NOT NULL REFERENCES designation (id),
    org_unit_code    VARCHAR(40)  NOT NULL,
    num_posts        INTEGER      NOT NULL CHECK (num_posts > 0),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED', 'FILLED', 'CANCELLED')),
    opening_date     DATE,
    closing_date     DATE,
    description      TEXT,
    requirements     TEXT,
    exam_weight      NUMERIC(5, 2) NOT NULL CHECK (exam_weight >= 0),
    interview_weight NUMERIC(5, 2) NOT NULL CHECK (interview_weight >= 0),
    CHECK (closing_date IS NULL OR opening_date IS NULL OR closing_date >= opening_date)
);
CREATE INDEX ix_rec_vacancy_des_unit ON rec_vacancy (designation_id, org_unit_code);

CREATE TABLE rec_announcement (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    vacancy_id   BIGINT REFERENCES rec_vacancy (id),
    title        VARCHAR(300) NOT NULL,
    content      TEXT         NOT NULL,
    publish_date DATE         NOT NULL,
    closing_date DATE,
    published    BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ,
    published_by VARCHAR(100),
    CHECK (closing_date IS NULL OR closing_date >= publish_date)
);

CREATE TABLE rec_applicant (
    id                 BIGSERIAL PRIMARY KEY,
    version            BIGINT,
    created_at         TIMESTAMPTZ,
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    vacancy_id         BIGINT       NOT NULL REFERENCES rec_vacancy (id),
    application_no     VARCHAR(40)  NOT NULL UNIQUE,
    title              VARCHAR(20),
    full_name          VARCHAR(300) NOT NULL,
    name_with_initials VARCHAR(200) NOT NULL,
    nic                VARCHAR(12)  NOT NULL,
    date_of_birth      DATE         NOT NULL,
    gender             VARCHAR(20),
    civil_status       VARCHAR(20),
    email              VARCHAR(150),
    mobile             VARCHAR(20),
    address            VARCHAR(500),
    qualifications     TEXT,
    received_date      DATE         NOT NULL,
    source             VARCHAR(20)  NOT NULL DEFAULT 'HR_ENTERED' CHECK (source IN ('HR_ENTERED', 'ONLINE')),
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('RECEIVED', 'SHORTLISTED', 'REJECTED', 'WITHDRAWN',
                                                              'RECOMMENDED', 'SELECTED', 'NOT_SELECTED', 'APPOINTED')),
    remarks            VARCHAR(1000),
    employee_id        BIGINT REFERENCES employee (id),
    UNIQUE (vacancy_id, nic)
);
CREATE INDEX ix_rec_applicant_vacancy ON rec_applicant (vacancy_id, status);

CREATE TABLE rec_applicant_status (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    applicant_id BIGINT      NOT NULL REFERENCES rec_applicant (id),
    from_status  VARCHAR(20),
    to_status    VARCHAR(20) NOT NULL,
    changed_at   TIMESTAMPTZ NOT NULL,
    changed_by   VARCHAR(100) NOT NULL,
    remarks      VARCHAR(1000)
);
CREATE INDEX ix_rec_applicant_status ON rec_applicant_status (applicant_id);

CREATE TABLE rec_applicant_document (
    id            BIGSERIAL PRIMARY KEY,
    version       BIGINT,
    created_at    TIMESTAMPTZ,
    created_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100),
    applicant_id  BIGINT       NOT NULL REFERENCES rec_applicant (id),
    document_type VARCHAR(60)  NOT NULL,
    title         VARCHAR(200) NOT NULL,
    file_id       BIGINT       NOT NULL REFERENCES stored_file (id),
    file_name     VARCHAR(255) NOT NULL
);
CREATE INDEX ix_rec_applicant_document ON rec_applicant_document (applicant_id);

CREATE TABLE rec_exam (
    id         BIGSERIAL PRIMARY KEY,
    version    BIGINT,
    created_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_at TIMESTAMPTZ,
    updated_by VARCHAR(100),
    vacancy_id BIGINT        NOT NULL REFERENCES rec_vacancy (id),
    title      VARCHAR(200)  NOT NULL,
    exam_type  VARCHAR(20)   NOT NULL CHECK (exam_type IN ('WRITTEN', 'PRACTICAL')),
    exam_date  DATE          NOT NULL,
    start_time TIME,
    venue      VARCHAR(300),
    max_marks  NUMERIC(7, 2) NOT NULL CHECK (max_marks > 0),
    pass_marks NUMERIC(7, 2),
    status     VARCHAR(20)   NOT NULL CHECK (status IN ('SCHEDULED', 'COMPLETED', 'CANCELLED'))
);
CREATE INDEX ix_rec_exam_vacancy ON rec_exam (vacancy_id);

CREATE TABLE rec_exam_result (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    exam_id      BIGINT  NOT NULL REFERENCES rec_exam (id),
    applicant_id BIGINT  NOT NULL REFERENCES rec_applicant (id),
    marks        NUMERIC(7, 2),
    absent       BOOLEAN NOT NULL DEFAULT FALSE,
    remarks      VARCHAR(1000),
    UNIQUE (exam_id, applicant_id)
);

CREATE TABLE rec_interview (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    vacancy_id     BIGINT        NOT NULL REFERENCES rec_vacancy (id),
    applicant_id   BIGINT        NOT NULL REFERENCES rec_applicant (id),
    interview_date DATE          NOT NULL,
    interview_time TIME,
    venue          VARCHAR(300),
    panel          VARCHAR(1000) NOT NULL,
    max_marks      NUMERIC(7, 2) NOT NULL CHECK (max_marks > 0),
    marks          NUMERIC(7, 2),
    remarks        VARCHAR(2000),
    status         VARCHAR(20)   NOT NULL CHECK (status IN ('SCHEDULED', 'COMPLETED', 'ABSENT', 'CANCELLED'))
);
CREATE INDEX ix_rec_interview_applicant ON rec_interview (applicant_id);

CREATE TABLE rec_selection (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    vacancy_id           BIGINT      NOT NULL REFERENCES rec_vacancy (id),
    status               VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'PENDING', 'RETURNED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    remarks              VARCHAR(2000),
    workflow_instance_id BIGINT,
    submitted_at         TIMESTAMPTZ,
    decided_at           TIMESTAMPTZ
);
CREATE INDEX ix_rec_selection_vacancy ON rec_selection (vacancy_id);

CREATE TABLE rec_selection_item (
    id              BIGSERIAL PRIMARY KEY,
    version         BIGINT,
    created_at      TIMESTAMPTZ,
    created_by      VARCHAR(100),
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(100),
    selection_id    BIGINT  NOT NULL REFERENCES rec_selection (id) ON DELETE CASCADE,
    applicant_id    BIGINT  NOT NULL REFERENCES rec_applicant (id),
    rank_no         INTEGER NOT NULL,
    exam_score      NUMERIC(7, 2),
    interview_score NUMERIC(7, 2),
    total_score     NUMERIC(7, 2) NOT NULL,
    recommended     BOOLEAN NOT NULL DEFAULT FALSE,
    remarks         VARCHAR(1000),
    UNIQUE (selection_id, applicant_id)
);

CREATE TABLE rec_letter_template (
    id         BIGSERIAL PRIMARY KEY,
    version    BIGINT,
    created_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_at TIMESTAMPTZ,
    updated_by VARCHAR(100),
    code       VARCHAR(40)  NOT NULL,
    language   VARCHAR(2)   NOT NULL CHECK (language IN ('en', 'si', 'ta')),
    name       VARCHAR(200) NOT NULL,
    category   VARCHAR(30)  NOT NULL CHECK (category IN ('APPOINTMENT', 'EXAM_CALL', 'INTERVIEW_CALL', 'REGRET', 'OTHER')),
    subject    VARCHAR(300) NOT NULL,
    body       TEXT         NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (code, language)
);

CREATE TABLE rec_correspondence (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    applicant_id        BIGINT       NOT NULL REFERENCES rec_applicant (id),
    vacancy_id          BIGINT       NOT NULL REFERENCES rec_vacancy (id),
    template_id         BIGINT REFERENCES rec_letter_template (id),
    template_code       VARCHAR(40)  NOT NULL,
    category            VARCHAR(30)  NOT NULL,
    language            VARCHAR(2)   NOT NULL,
    subject             VARCHAR(300) NOT NULL,
    body                TEXT         NOT NULL,
    channel             VARCHAR(10)  NOT NULL CHECK (channel IN ('PRINT', 'EMAIL')),
    recipient           VARCHAR(200),
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('PREPARED', 'QUEUED', 'FAILED')),
    outbound_message_id BIGINT,
    generated_at        TIMESTAMPTZ  NOT NULL,
    generated_by        VARCHAR(100) NOT NULL
);
CREATE INDEX ix_rec_correspondence_applicant ON rec_correspondence (applicant_id);

-- ================================================================== Performance
CREATE TABLE per_period (
    id         BIGSERIAL PRIMARY KEY,
    version    BIGINT,
    created_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_at TIMESTAMPTZ,
    updated_by VARCHAR(100),
    code       VARCHAR(40)  NOT NULL UNIQUE,
    name       VARCHAR(200) NOT NULL,
    start_date DATE         NOT NULL,
    end_date   DATE         NOT NULL,
    due_date   DATE,
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    CHECK (end_date >= start_date)
);

CREATE TABLE per_model (
    id          BIGSERIAL PRIMARY KEY,
    version     BIGINT,
    created_at  TIMESTAMPTZ,
    created_by  VARCHAR(100),
    updated_at  TIMESTAMPTZ,
    updated_by  VARCHAR(100),
    code        VARCHAR(40)   NOT NULL UNIQUE,
    name        VARCHAR(200)  NOT NULL,
    description VARCHAR(1000),
    rating_min  NUMERIC(5, 2) NOT NULL,
    rating_max  NUMERIC(5, 2) NOT NULL,
    sample      BOOLEAN       NOT NULL DEFAULT FALSE,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    CHECK (rating_max > rating_min)
);

CREATE TABLE per_criterion (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    model_id       BIGINT        NOT NULL REFERENCES per_model (id),
    criterion_type VARCHAR(20)   NOT NULL CHECK (criterion_type IN ('CRITERION', 'KPI', 'OBJECTIVE')),
    title          VARCHAR(300)  NOT NULL,
    description    VARCHAR(1000),
    weight         NUMERIC(6, 2) NOT NULL CHECK (weight > 0),
    sort_order     INTEGER       NOT NULL DEFAULT 0
);
CREATE INDEX ix_per_criterion_model ON per_criterion (model_id);

CREATE TABLE per_category_model (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    employee_category VARCHAR(40) NOT NULL UNIQUE,
    model_id          BIGINT      NOT NULL REFERENCES per_model (id)
);

CREATE TABLE per_evaluation (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    period_id            BIGINT      NOT NULL REFERENCES per_period (id),
    employee_id          BIGINT      NOT NULL REFERENCES employee (id),
    model_id             BIGINT      NOT NULL REFERENCES per_model (id),
    evaluator_user_id    BIGINT REFERENCES app_user (id),
    evaluator_name       VARCHAR(200),
    status               VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'RETURNED', 'REJECTED', 'FINALIZED')),
    overall_score        NUMERIC(6, 2),
    overall_percent      NUMERIC(6, 2),
    evaluator_comments   TEXT,
    employee_comments    TEXT,
    submitted_at         TIMESTAMPTZ,
    finalized_at         TIMESTAMPTZ,
    acknowledged_at      TIMESTAMPTZ,
    workflow_instance_id BIGINT,
    last_reminder_at     TIMESTAMPTZ,
    UNIQUE (period_id, employee_id)
);
CREATE INDEX ix_per_evaluation_evaluator ON per_evaluation (evaluator_user_id, status);
CREATE INDEX ix_per_evaluation_employee ON per_evaluation (employee_id);

CREATE TABLE per_rating (
    id            BIGSERIAL PRIMARY KEY,
    version       BIGINT,
    created_at    TIMESTAMPTZ,
    created_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100),
    evaluation_id BIGINT        NOT NULL REFERENCES per_evaluation (id),
    criterion_id  BIGINT        NOT NULL REFERENCES per_criterion (id),
    rating        NUMERIC(5, 2) NOT NULL,
    comment       VARCHAR(2000),
    UNIQUE (evaluation_id, criterion_id)
);

-- ================================================================== Training
CREATE TABLE trn_programme (
    id                      BIGSERIAL PRIMARY KEY,
    version                 BIGINT,
    created_at              TIMESTAMPTZ,
    created_by              VARCHAR(100),
    updated_at              TIMESTAMPTZ,
    updated_by              VARCHAR(100),
    code                    VARCHAR(40)  NOT NULL UNIQUE,
    title                   VARCHAR(300) NOT NULL,
    description             TEXT,
    provider                VARCHAR(200),
    start_date              DATE         NOT NULL,
    end_date                DATE         NOT NULL,
    venue                   VARCHAR(300),
    capacity                INTEGER      NOT NULL CHECK (capacity > 0),
    cost                    NUMERIC(14, 2),
    nomination_closing_date DATE,
    status                  VARCHAR(20)  NOT NULL CHECK (status IN ('PLANNED', 'OPEN', 'CLOSED', 'COMPLETED', 'CANCELLED')),
    CHECK (end_date >= start_date),
    CHECK (cost IS NULL OR cost >= 0)
);

CREATE TABLE trn_nomination (
    id                     BIGSERIAL PRIMARY KEY,
    version                BIGINT,
    created_at             TIMESTAMPTZ,
    created_by             VARCHAR(100),
    updated_at             TIMESTAMPTZ,
    updated_by             VARCHAR(100),
    programme_id           BIGINT      NOT NULL REFERENCES trn_programme (id),
    employee_id            BIGINT      NOT NULL REFERENCES employee (id),
    source                 VARCHAR(10) NOT NULL CHECK (source IN ('HR', 'SELF')),
    reason                 VARCHAR(1000),
    status                 VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'RETURNED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    workflow_instance_id   BIGINT,
    completion_status      VARCHAR(20) NOT NULL DEFAULT 'NOT_RECORDED'
        CHECK (completion_status IN ('NOT_RECORDED', 'COMPLETED', 'NOT_COMPLETED', 'ABSENT')),
    attended               BOOLEAN     NOT NULL DEFAULT FALSE,
    result                 VARCHAR(200),
    completion_remarks     VARCHAR(1000),
    completion_recorded_at TIMESTAMPTZ,
    certificate_file_id    BIGINT REFERENCES stored_file (id),
    certificate_file_name  VARCHAR(255)
);
CREATE INDEX ix_trn_nomination_programme ON trn_nomination (programme_id, status);
CREATE INDEX ix_trn_nomination_employee ON trn_nomination (employee_id);
-- One live nomination per employee per programme.
CREATE UNIQUE INDEX ux_trn_nomination_live ON trn_nomination (programme_id, employee_id)
    WHERE status IN ('PENDING', 'RETURNED', 'APPROVED');
