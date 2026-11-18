-- =====================================================================================================
-- CIABOC HRMS - Release 1 (Foundation) schema
-- Security, audit, master data, notifications, workflow, files, establishment, legal, employee.
-- Convention: business tables carry id, version, created_at/by, updated_at/by (BaseEntity).
-- =====================================================================================================

-- ------------------------------------------------------------------ security / access management
CREATE TABLE role (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    code         VARCHAR(50)  NOT NULL UNIQUE,
    name         VARCHAR(150) NOT NULL,
    description  VARCHAR(500),
    system_role  BOOLEAN      NOT NULL DEFAULT FALSE,
    active       BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE role_permission (
    role_id    BIGINT      NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    permission VARCHAR(50) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE app_user (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    username            VARCHAR(100) NOT NULL,
    password_hash       VARCHAR(100),
    full_name           VARCHAR(200) NOT NULL,
    email               VARCHAR(200),
    mobile              VARCHAR(30),
    employee_id         BIGINT UNIQUE,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    data_scope          VARCHAR(20)  NOT NULL DEFAULT 'SELF' CHECK (data_scope IN ('ALL', 'DIVISION', 'SELF')),
    preferred_language  VARCHAR(5)   NOT NULL DEFAULT 'en' CHECK (preferred_language IN ('en', 'si', 'ta')),
    otp_channel         VARCHAR(10)  NOT NULL DEFAULT 'EMAIL' CHECK (otp_channel IN ('EMAIL', 'SMS')),
    failed_attempts     INTEGER      NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_app_user_username ON app_user (LOWER(username));

CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role (id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE otp_challenge (
    id         VARCHAR(36) PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash  VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL,
    attempts   INTEGER     NOT NULL DEFAULT 0,
    consumed   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE refresh_token (
    id         BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);

-- ------------------------------------------------------------------ audit (append-only, hash-chained)
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL,
    user_id     BIGINT,
    username    VARCHAR(100) NOT NULL,
    action      VARCHAR(30)  NOT NULL,
    entity_type VARCHAR(60),
    entity_id   VARCHAR(60),
    summary     VARCHAR(1000),
    old_value   TEXT,
    new_value   TEXT,
    ip_address  VARCHAR(64),
    prev_hash   VARCHAR(64),
    hash        VARCHAR(64)  NOT NULL
);
CREATE INDEX ix_audit_occurred ON audit_log (occurred_at);
CREATE INDEX ix_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_user ON audit_log (user_id);

CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (operation % rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable();

-- ------------------------------------------------------------------ configuration
CREATE TABLE master_data_item (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    category       VARCHAR(40)  NOT NULL,
    code           VARCHAR(40)  NOT NULL,
    name_en        VARCHAR(200) NOT NULL,
    name_si        VARCHAR(200),
    name_ta        VARCHAR(200),
    description    VARCHAR(500),
    sort_order     INTEGER      NOT NULL DEFAULT 0,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    effective_from DATE         NOT NULL DEFAULT CURRENT_DATE,
    effective_to   DATE,
    UNIQUE (category, code)
);

CREATE TABLE system_setting (
    setting_key   VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(1000) NOT NULL,
    category      VARCHAR(40),
    value_type    VARCHAR(20)   NOT NULL DEFAULT 'STRING',
    description   VARCHAR(500),
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100)
);

CREATE TABLE integration_config (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    code         VARCHAR(40)  NOT NULL UNIQUE,
    ref_id       VARCHAR(10),
    name         VARCHAR(150) NOT NULL,
    purpose      VARCHAR(500),
    priority     VARCHAR(1),
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    endpoint_url VARCHAR(500),
    notes        VARCHAR(1000)
);

-- ------------------------------------------------------------------ notifications
CREATE TABLE notification_template (
    id         BIGSERIAL PRIMARY KEY,
    version    BIGINT,
    created_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_at TIMESTAMPTZ,
    updated_by VARCHAR(100),
    code       VARCHAR(60)   NOT NULL UNIQUE,
    description VARCHAR(300),
    title_en   VARCHAR(300)  NOT NULL,
    title_si   VARCHAR(300),
    title_ta   VARCHAR(300),
    body_en    VARCHAR(2000) NOT NULL,
    body_si    VARCHAR(2000),
    body_ta    VARCHAR(2000),
    send_email BOOLEAN       NOT NULL DEFAULT FALSE,
    send_sms   BOOLEAN       NOT NULL DEFAULT FALSE,
    active     BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE notification (
    id                BIGSERIAL PRIMARY KEY,
    recipient_user_id BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    template_code     VARCHAR(60),
    title             VARCHAR(300) NOT NULL,
    body              VARCHAR(2000),
    link              VARCHAR(300),
    is_read           BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_notification_recipient ON notification (recipient_user_id, is_read);

CREATE TABLE outbound_message (
    id              BIGSERIAL PRIMARY KEY,
    channel         VARCHAR(10)  NOT NULL,
    recipient       VARCHAR(200) NOT NULL,
    subject         VARCHAR(300),
    body            TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000),
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    sensitive       BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_outbound_pending ON outbound_message (status, next_attempt_at);

-- ------------------------------------------------------------------ workflow engine
CREATE TABLE workflow_definition (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    process_code VARCHAR(50)  NOT NULL UNIQUE,
    name         VARCHAR(150) NOT NULL,
    description  VARCHAR(500),
    active       BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE workflow_stage (
    id                   BIGSERIAL PRIMARY KEY,
    definition_id        BIGINT       NOT NULL REFERENCES workflow_definition (id) ON DELETE CASCADE,
    stage_order          INTEGER      NOT NULL,
    name                 VARCHAR(150) NOT NULL,
    approver_type        VARCHAR(20)  NOT NULL CHECK (approver_type IN ('ROLE', 'POSITION', 'USER', 'DIVISION_HEAD')),
    approver_ref         VARCHAR(100),
    sla_hours            INTEGER,
    escalation_role_code VARCHAR(50)
);

CREATE TABLE workflow_instance (
    id                    BIGSERIAL PRIMARY KEY,
    version               BIGINT,
    created_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    updated_at            TIMESTAMPTZ,
    updated_by            VARCHAR(100),
    process_code          VARCHAR(50)  NOT NULL,
    entity_type           VARCHAR(60)  NOT NULL,
    entity_id             BIGINT       NOT NULL,
    title                 VARCHAR(300) NOT NULL,
    link                  VARCHAR(300),
    requester_user_id     BIGINT       NOT NULL REFERENCES app_user (id),
    requester_name        VARCHAR(200) NOT NULL,
    requester_employee_id BIGINT,
    subject_org_unit_code VARCHAR(40),
    current_stage         INTEGER      NOT NULL,
    total_stages          INTEGER      NOT NULL,
    current_stage_name    VARCHAR(150),
    status                VARCHAR(20)  NOT NULL,
    submitted_at          TIMESTAMPTZ  NOT NULL,
    stage_entered_at      TIMESTAMPTZ  NOT NULL,
    completed_at          TIMESTAMPTZ,
    escalated             BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_wf_instance_status ON workflow_instance (status);
CREATE INDEX ix_wf_instance_entity ON workflow_instance (entity_type, entity_id);
CREATE INDEX ix_wf_instance_requester ON workflow_instance (requester_user_id);

CREATE TABLE approval_action (
    id                   BIGSERIAL PRIMARY KEY,
    instance_id          BIGINT       NOT NULL REFERENCES workflow_instance (id),
    stage_order          INTEGER      NOT NULL,
    stage_name           VARCHAR(150),
    actor_user_id        BIGINT,
    actor_name           VARCHAR(200) NOT NULL,
    on_behalf_of_user_id BIGINT,
    on_behalf_of_name    VARCHAR(200),
    action               VARCHAR(20)  NOT NULL,
    comment_text         VARCHAR(2000),
    acted_at             TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_approval_action_instance ON approval_action (instance_id);
CREATE INDEX ix_approval_action_actor ON approval_action (actor_user_id);

CREATE TABLE delegation (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    from_user_id BIGINT       NOT NULL REFERENCES app_user (id),
    from_name    VARCHAR(200) NOT NULL,
    to_user_id   BIGINT       NOT NULL REFERENCES app_user (id),
    to_name      VARCHAR(200) NOT NULL,
    process_code VARCHAR(50),
    start_date   DATE         NOT NULL,
    end_date     DATE         NOT NULL,
    reason       VARCHAR(500),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    CHECK (end_date >= start_date)
);

-- ------------------------------------------------------------------ files
CREATE TABLE stored_file (
    id            BIGSERIAL PRIMARY KEY,
    original_name VARCHAR(255) NOT NULL,
    content_type  VARCHAR(120),
    size_bytes    BIGINT       NOT NULL,
    storage_key   VARCHAR(300) NOT NULL UNIQUE,
    sha256        VARCHAR(64)  NOT NULL,
    module        VARCHAR(40)  NOT NULL,
    scan_status   VARCHAR(20)  NOT NULL,
    uploaded_by   VARCHAR(100) NOT NULL,
    uploaded_at   TIMESTAMPTZ  NOT NULL
);

-- ------------------------------------------------------------------ establishment
CREATE TABLE org_unit (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    code           VARCHAR(40)  NOT NULL,
    name           VARCHAR(200) NOT NULL,
    name_si        VARCHAR(200),
    name_ta        VARCHAR(200),
    unit_type      VARCHAR(20)  NOT NULL CHECK (unit_type IN ('DIVISION', 'DEPARTMENT', 'SECTION', 'SUB_SECTION')),
    parent_code    VARCHAR(40),
    sort_order     INTEGER      NOT NULL DEFAULT 0,
    effective_from DATE         NOT NULL,
    effective_to   DATE,
    change_id      BIGINT,
    UNIQUE (code, effective_from),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX ix_org_unit_code ON org_unit (code);

CREATE TABLE designation (
    id               BIGSERIAL PRIMARY KEY,
    version          BIGINT,
    created_at       TIMESTAMPTZ,
    created_by       VARCHAR(100),
    updated_at       TIMESTAMPTZ,
    updated_by       VARCHAR(100),
    code             VARCHAR(40)  NOT NULL UNIQUE,
    name             VARCHAR(200) NOT NULL,
    name_si          VARCHAR(200),
    name_ta          VARCHAR(200),
    grade            VARCHAR(40),
    service_category VARCHAR(40),
    description      VARCHAR(1000),
    active           BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE org_position (
    id                       BIGSERIAL PRIMARY KEY,
    version                  BIGINT,
    created_at               TIMESTAMPTZ,
    created_by               VARCHAR(100),
    updated_at               TIMESTAMPTZ,
    updated_by               VARCHAR(100),
    code                     VARCHAR(40)  NOT NULL UNIQUE,
    title                    VARCHAR(200) NOT NULL,
    designation_id           BIGINT       NOT NULL REFERENCES designation (id),
    org_unit_code            VARCHAR(40)  NOT NULL,
    reports_to_position_code VARCHAR(40),
    active                   BOOLEAN      NOT NULL DEFAULT TRUE,
    activated_on             DATE,
    deactivated_on           DATE
);

CREATE TABLE cadre_allocation (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    designation_id BIGINT      NOT NULL REFERENCES designation (id),
    org_unit_code  VARCHAR(40) NOT NULL,
    approved_qty   INTEGER     NOT NULL CHECK (approved_qty >= 0),
    effective_from DATE        NOT NULL,
    effective_to   DATE,
    change_id      BIGINT,
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX ix_cadre_designation_unit ON cadre_allocation (designation_id, org_unit_code);

CREATE TABLE establishment_change (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    change_type          VARCHAR(30)   NOT NULL,
    target_ref           VARCHAR(40),
    payload              TEXT          NOT NULL,
    summary              VARCHAR(500)  NOT NULL,
    effective_date       DATE          NOT NULL,
    reason               VARCHAR(2000) NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    workflow_instance_id BIGINT,
    applied_at           TIMESTAMPTZ,
    cadre_designation_id BIGINT,
    cadre_org_unit_code  VARCHAR(40),
    cadre_proposed_qty   INTEGER
);

-- ------------------------------------------------------------------ legal documents
CREATE TABLE legal_document (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    doc_type            VARCHAR(20)  NOT NULL CHECK (doc_type IN ('ACT', 'GAZETTE', 'REGULATION', 'CIRCULAR')),
    doc_number          VARCHAR(100) NOT NULL,
    title               VARCHAR(500) NOT NULL,
    issuing_authority   VARCHAR(200),
    publication_date    DATE,
    effective_date      DATE,
    status              VARCHAR(20)  NOT NULL,
    verification_status VARCHAR(20)  NOT NULL,
    verified_by         VARCHAR(100),
    verified_at         TIMESTAMPTZ,
    series_id           BIGINT,
    version_no          INTEGER      NOT NULL DEFAULT 1,
    previous_version_id BIGINT REFERENCES legal_document (id),
    file_id             BIGINT REFERENCES stored_file (id),
    file_name           VARCHAR(255),
    description         VARCHAR(2000)
);

CREATE TABLE legal_section (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    document_id         BIGINT      NOT NULL REFERENCES legal_document (id),
    parent_key          VARCHAR(36),
    section_key         VARCHAR(36) NOT NULL,
    level               VARCHAR(20) NOT NULL,
    number              VARCHAR(40) NOT NULL,
    heading             VARCHAR(500),
    content             TEXT,
    sort_order          INTEGER     NOT NULL DEFAULT 0,
    effective_from      DATE        NOT NULL,
    effective_to        DATE,
    verification_status VARCHAR(20) NOT NULL,
    version_no          INTEGER     NOT NULL DEFAULT 1,
    previous_version_id BIGINT REFERENCES legal_section (id)
);
CREATE INDEX ix_legal_section_doc ON legal_section (document_id);
CREATE INDEX ix_legal_section_key ON legal_section (section_key);

CREATE TABLE legal_section_tag (
    section_id BIGINT      NOT NULL REFERENCES legal_section (id) ON DELETE CASCADE,
    tag        VARCHAR(40) NOT NULL,
    PRIMARY KEY (section_id, tag)
);

CREATE TABLE legal_section_designation (
    section_id     BIGINT NOT NULL REFERENCES legal_section (id) ON DELETE CASCADE,
    designation_id BIGINT NOT NULL REFERENCES designation (id),
    PRIMARY KEY (section_id, designation_id)
);

CREATE TABLE legal_amendment (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    original_document_id BIGINT        NOT NULL REFERENCES legal_document (id),
    section_id           BIGINT REFERENCES legal_section (id),
    amending_document_id BIGINT REFERENCES legal_document (id),
    amendment_type       VARCHAR(20)   NOT NULL CHECK (amendment_type IN ('INSERTED', 'SUBSTITUTED', 'MODIFIED', 'REPEALED')),
    description          VARCHAR(2000) NOT NULL,
    effective_date       DATE          NOT NULL,
    resulting_section_id BIGINT REFERENCES legal_section (id)
);

-- ------------------------------------------------------------------ employees
CREATE TABLE employee (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    employee_no         VARCHAR(30)  NOT NULL UNIQUE,
    title               VARCHAR(20),
    full_name           VARCHAR(300) NOT NULL,
    name_with_initials  VARCHAR(200) NOT NULL,
    name_si             VARCHAR(300),
    name_ta             VARCHAR(300),
    nic                 VARCHAR(12)  NOT NULL UNIQUE CHECK (nic ~ '^([0-9]{9}[VX]|[0-9]{12})$'),
    date_of_birth       DATE         NOT NULL,
    gender              VARCHAR(20),
    civil_status        VARCHAR(20),
    email               VARCHAR(200),
    phone               VARCHAR(30),
    mobile              VARCHAR(30),
    permanent_address   VARCHAR(500),
    residential_address VARCHAR(500),
    status              VARCHAR(40)  NOT NULL,
    employment_type     VARCHAR(40)  NOT NULL,
    employee_category   VARCHAR(40)  NOT NULL,
    service_category    VARCHAR(40),
    appointment_date    DATE         NOT NULL,
    confirmation_date   DATE,
    retirement_date     DATE,
    increment_month     INTEGER CHECK (increment_month BETWEEN 1 AND 12),
    separation_date     DATE,
    separation_reason   VARCHAR(40),
    separation_remarks  VARCHAR(1000),
    seconded_from       VARCHAR(300),
    secondment_end_date DATE,
    org_unit_code       VARCHAR(40),
    designation_id      BIGINT REFERENCES designation (id),
    position_id         BIGINT REFERENCES org_position (id),
    bank_code           VARCHAR(40),
    bank_branch         VARCHAR(100),
    bank_account_no     VARCHAR(40),
    restricted          BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_employee_unit ON employee (org_unit_code);
CREATE INDEX ix_employee_status ON employee (status);

ALTER TABLE app_user ADD CONSTRAINT fk_app_user_employee FOREIGN KEY (employee_id) REFERENCES employee (id);

CREATE TABLE employee_assignment (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    employee_id    BIGINT      NOT NULL REFERENCES employee (id),
    org_unit_code  VARCHAR(40) NOT NULL,
    designation_id BIGINT      NOT NULL REFERENCES designation (id),
    position_id    BIGINT REFERENCES org_position (id),
    effective_from DATE        NOT NULL,
    effective_to   DATE,
    reason         VARCHAR(30) NOT NULL,
    reference      VARCHAR(100),
    remarks        VARCHAR(1000),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX ix_assignment_employee ON employee_assignment (employee_id);
CREATE INDEX ix_assignment_designation_unit ON employee_assignment (designation_id, org_unit_code);

CREATE TABLE employee_qualification (
    id                 BIGSERIAL PRIMARY KEY,
    version            BIGINT,
    created_at         TIMESTAMPTZ,
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    employee_id        BIGINT       NOT NULL REFERENCES employee (id),
    qualification_type VARCHAR(40)  NOT NULL,
    title              VARCHAR(300) NOT NULL,
    institution        VARCHAR(300),
    year_obtained      INTEGER,
    result             VARCHAR(100),
    remarks            VARCHAR(500)
);

CREATE TABLE employee_previous_employment (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    employee_id  BIGINT       NOT NULL REFERENCES employee (id),
    organization VARCHAR(300) NOT NULL,
    designation  VARCHAR(200),
    from_date    DATE,
    to_date      DATE,
    remarks      VARCHAR(500)
);

CREATE TABLE employee_emergency_contact (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    employee_id  BIGINT       NOT NULL REFERENCES employee (id),
    name         VARCHAR(200) NOT NULL,
    relationship VARCHAR(40),
    phone        VARCHAR(30)  NOT NULL,
    address      VARCHAR(500)
);

CREATE TABLE employee_document (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    employee_id    BIGINT       NOT NULL REFERENCES employee (id),
    document_type  VARCHAR(40)  NOT NULL,
    title          VARCHAR(300) NOT NULL,
    document_date  DATE,
    classification VARCHAR(30)  NOT NULL CHECK (classification IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')),
    file_id        BIGINT       NOT NULL REFERENCES stored_file (id),
    file_name      VARCHAR(255),
    remarks        VARCHAR(500)
);

CREATE TABLE service_event (
    id          BIGSERIAL PRIMARY KEY,
    version     BIGINT,
    created_at  TIMESTAMPTZ,
    created_by  VARCHAR(100),
    updated_at  TIMESTAMPTZ,
    updated_by  VARCHAR(100),
    employee_id BIGINT        NOT NULL REFERENCES employee (id),
    event_type  VARCHAR(30)   NOT NULL,
    event_date  DATE          NOT NULL,
    description VARCHAR(1000) NOT NULL,
    reference   VARCHAR(100)
);
CREATE INDEX ix_service_event_employee ON service_event (employee_id);

CREATE TABLE profile_change_request (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    employee_id          BIGINT      NOT NULL REFERENCES employee (id),
    changes              TEXT        NOT NULL,
    reason               VARCHAR(1000),
    source               VARCHAR(10) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    workflow_instance_id BIGINT
);
