-- =====================================================================================================
-- Extended scope (SRS 4.16, Release 5): transfers / promotions / confirmations (FR-EXT-001), retirement and
-- offboarding (FR-EXT-002), correspondence and certificates (FR-EXT-005) and personal-file digitization (FR-EXT-011).
-- =====================================================================================================

-- ------------------------------------------------------------------ FR-EXT-001 transfers, promotions, confirmations
CREATE TABLE ext_movement_request (
    id                    BIGSERIAL PRIMARY KEY,
    version               BIGINT,
    created_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    updated_at            TIMESTAMPTZ,
    updated_by            VARCHAR(100),
    request_type          VARCHAR(20)   NOT NULL CHECK (request_type IN ('TRANSFER', 'PROMOTION', 'CONFIRMATION')),
    employee_id           BIGINT        NOT NULL REFERENCES employee (id),
    from_org_unit_code    VARCHAR(40),
    from_designation_id   BIGINT,
    from_position_id      BIGINT,
    target_org_unit_code  VARCHAR(40),
    target_designation_id BIGINT,
    target_position_id    BIGINT,
    effective_date        DATE          NOT NULL,
    reason                VARCHAR(1000) NOT NULL,
    reference             VARCHAR(100),
    cadre_warning         VARCHAR(500),
    status                VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'RETURNED', 'APPLIED', 'REJECTED', 'CANCELLED')),
    workflow_instance_id  BIGINT,
    applied_at            TIMESTAMPTZ
);
CREATE INDEX ix_ext_movement_employee ON ext_movement_request (employee_id);
CREATE INDEX ix_ext_movement_status ON ext_movement_request (status);

-- ------------------------------------------------------------------ FR-EXT-002 retirement forecasting and offboarding
-- One row per (employee, lead time, retirement date) so each advance notice is sent exactly once.
CREATE TABLE ext_retirement_notice (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT      NOT NULL REFERENCES employee (id),
    retirement_date DATE        NOT NULL,
    lead_months     INTEGER     NOT NULL,
    sent_at         TIMESTAMPTZ NOT NULL,
    UNIQUE (employee_id, retirement_date, lead_months)
);

-- Separation types are configuration: the employee status and default separation reason they lead to.
CREATE TABLE ext_separation_type (
    id                       BIGSERIAL PRIMARY KEY,
    version                  BIGINT,
    created_at               TIMESTAMPTZ,
    created_by               VARCHAR(100),
    updated_at               TIMESTAMPTZ,
    updated_by               VARCHAR(100),
    code                     VARCHAR(40)  NOT NULL UNIQUE,
    name_en                  VARCHAR(200) NOT NULL,
    name_si                  VARCHAR(200),
    name_ta                  VARCHAR(200),
    employee_status          VARCHAR(40)  NOT NULL,
    default_separation_reason VARCHAR(40) NOT NULL,
    sort_order               INTEGER      NOT NULL DEFAULT 0,
    active                   BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Clearance checklist item templates (FR-EXT-002): data, not code.
CREATE TABLE ext_clearance_item_template (
    id               BIGSERIAL PRIMARY KEY,
    version          BIGINT,
    created_at       TIMESTAMPTZ,
    created_by       VARCHAR(100),
    updated_at       TIMESTAMPTZ,
    updated_by       VARCHAR(100),
    code             VARCHAR(40)  NOT NULL UNIQUE,
    name_en          VARCHAR(200) NOT NULL,
    name_si          VARCHAR(200),
    name_ta          VARCHAR(200),
    description      VARCHAR(500),
    responsible_role VARCHAR(50)  NOT NULL,
    -- comma-separated separation type codes; NULL = applies to every separation type
    applies_to       VARCHAR(300),
    sort_order       INTEGER      NOT NULL DEFAULT 0,
    active           BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE ext_separation_case (
    id                 BIGSERIAL PRIMARY KEY,
    version            BIGINT,
    created_at         TIMESTAMPTZ,
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    employee_id        BIGINT        NOT NULL REFERENCES employee (id),
    separation_type    VARCHAR(40)   NOT NULL,
    separation_reason  VARCHAR(40)   NOT NULL,
    target_status      VARCHAR(40)   NOT NULL,
    separation_date    DATE          NOT NULL,
    remarks            VARCHAR(1000),
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    settlement_amount  NUMERIC(14, 2),
    settlement_notes   TEXT,
    settlement_by      VARCHAR(100),
    settlement_at      TIMESTAMPTZ,
    completed_by       VARCHAR(100),
    completed_at       TIMESTAMPTZ,
    cancel_reason      VARCHAR(500)
);
CREATE UNIQUE INDEX ux_ext_separation_open ON ext_separation_case (employee_id) WHERE status = 'OPEN';

CREATE TABLE ext_separation_clearance (
    id               BIGSERIAL PRIMARY KEY,
    version          BIGINT,
    created_at       TIMESTAMPTZ,
    created_by       VARCHAR(100),
    updated_at       TIMESTAMPTZ,
    updated_by       VARCHAR(100),
    case_id          BIGINT       NOT NULL REFERENCES ext_separation_case (id) ON DELETE CASCADE,
    item_code        VARCHAR(40)  NOT NULL,
    item_name        VARCHAR(200) NOT NULL,
    responsible_role VARCHAR(50)  NOT NULL,
    sort_order       INTEGER      NOT NULL DEFAULT 0,
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'CLEARED', 'WAIVED')),
    remarks          VARCHAR(1000),
    cleared_by       VARCHAR(100),
    cleared_at       TIMESTAMPTZ,
    UNIQUE (case_id, item_code)
);
CREATE INDEX ix_ext_clearance_role ON ext_separation_clearance (responsible_role, status);

-- ------------------------------------------------------------------ FR-EXT-005 correspondence and certificates
CREATE TABLE ext_letter_template (
    id            BIGSERIAL PRIMARY KEY,
    version       BIGINT,
    created_at    TIMESTAMPTZ,
    created_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100),
    code          VARCHAR(40)  NOT NULL,
    language      VARCHAR(2)   NOT NULL CHECK (language IN ('en', 'si', 'ta')),
    name          VARCHAR(200) NOT NULL,
    document_kind VARCHAR(20)  NOT NULL CHECK (document_kind IN ('LETTER', 'CERTIFICATE')),
    title         VARCHAR(300) NOT NULL,
    body_html     TEXT         NOT NULL,
    self_service  BOOLEAN      NOT NULL DEFAULT TRUE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (code, language)
);

CREATE TABLE ext_letter_request (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    employee_id          BIGINT       NOT NULL REFERENCES employee (id),
    template_code        VARCHAR(40)  NOT NULL,
    language             VARCHAR(2)   NOT NULL,
    purpose              VARCHAR(500) NOT NULL,
    addressee            VARCHAR(300),
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'RETURNED', 'APPROVED', 'ISSUED', 'REJECTED', 'CANCELLED')),
    workflow_instance_id BIGINT,
    issued_document_id   BIGINT
);
CREATE INDEX ix_ext_letter_request_employee ON ext_letter_request (employee_id);

CREATE TABLE ext_issued_document (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    document_no       VARCHAR(40)  NOT NULL UNIQUE,
    verification_code VARCHAR(40)  NOT NULL UNIQUE,
    employee_id       BIGINT       NOT NULL REFERENCES employee (id),
    holder_name       VARCHAR(200) NOT NULL,
    template_code     VARCHAR(40)  NOT NULL,
    language          VARCHAR(2)   NOT NULL,
    document_kind     VARCHAR(20)  NOT NULL,
    document_type_name VARCHAR(200) NOT NULL,
    title             VARCHAR(300) NOT NULL,
    body_html         TEXT         NOT NULL,
    content_sha256    VARCHAR(64)  NOT NULL,
    request_id        BIGINT REFERENCES ext_letter_request (id),
    issued_at         TIMESTAMPTZ  NOT NULL,
    issued_by         VARCHAR(100) NOT NULL,
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('VALID', 'REVOKED')),
    revoked_at        TIMESTAMPTZ,
    revoked_by        VARCHAR(100),
    revoke_reason     VARCHAR(500)
);
CREATE INDEX ix_ext_issued_employee ON ext_issued_document (employee_id);
CREATE SEQUENCE ext_document_no_seq;

-- ------------------------------------------------------------------ FR-EXT-011 personal-file digitization
CREATE TABLE ext_digitization_batch (
    id              BIGSERIAL PRIMARY KEY,
    version         BIGINT,
    created_at      TIMESTAMPTZ,
    created_by      VARCHAR(100),
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(100),
    batch_no        VARCHAR(40)  NOT NULL UNIQUE,
    title           VARCHAR(300) NOT NULL,
    source_location VARCHAR(300),
    remarks         VARCHAR(1000),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'IN_QA', 'COMPLETED')),
    submitted_by    VARCHAR(100),
    submitted_at    TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);
CREATE SEQUENCE ext_batch_no_seq;

CREATE TABLE ext_digitization_item (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    batch_id             BIGINT       NOT NULL REFERENCES ext_digitization_batch (id),
    employee_id          BIGINT       NOT NULL REFERENCES employee (id),
    employee_no          VARCHAR(30)  NOT NULL,
    document_type        VARCHAR(40)  NOT NULL,
    title                VARCHAR(300) NOT NULL,
    document_date        DATE,
    classification       VARCHAR(30)  NOT NULL CHECK (classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')),
    file_id              BIGINT       NOT NULL REFERENCES stored_file (id),
    file_name            VARCHAR(255),
    file_sha256          VARCHAR(64),
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('UPLOADED', 'VERIFIED', 'REJECTED', 'FILED', 'WITHDRAWN')),
    uploaded_by          VARCHAR(100) NOT NULL,
    qa_by                VARCHAR(100),
    qa_at                TIMESTAMPTZ,
    qa_remarks           VARCHAR(1000),
    employee_document_id BIGINT,
    filed_by             VARCHAR(100),
    filed_at             TIMESTAMPTZ
);
CREATE INDEX ix_ext_digitization_item_batch ON ext_digitization_item (batch_id);
