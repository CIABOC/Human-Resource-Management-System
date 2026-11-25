-- =====================================================================================================
-- Establishment & Cadre spec v2.1 - Employee insurance management (REQ 11.1-11.6):
-- policies with provider, type, coverage, premium split and expiry; expiry alerts at configurable lead times;
-- versioned eligibility rules stored as data (AND/OR); automatic enrolment tasks; claims routed through the
-- INSURANCE_CLAIM workflow; provider connectors as configuration + field mapping with secrets kept encrypted or
-- referenced (never stored in plain text). All values are placeholders pending official values (open items 4, 6).
-- =====================================================================================================

-- ------------------------------------------------------------------ provider connectors (REQ 11.6, N-6)
CREATE TABLE ben_provider_connector (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    code              VARCHAR(40)   NOT NULL UNIQUE,
    name              VARCHAR(200)  NOT NULL,
    provider_name     VARCHAR(300)  NOT NULL,
    connector_type    VARCHAR(20)   NOT NULL CHECK (connector_type IN ('REST_API', 'SFTP_CSV')),
    transport_mode    VARCHAR(20)   NOT NULL DEFAULT 'SIMULATED' CHECK (transport_mode IN ('LIVE', 'SIMULATED')),
    base_url          VARCHAR(500),
    auth_method       VARCHAR(30)   NOT NULL DEFAULT 'NONE'
        CHECK (auth_method IN ('NONE', 'API_KEY', 'BASIC', 'BEARER_TOKEN', 'SFTP_PASSWORD', 'SFTP_KEY')),
    username          VARCHAR(200),
    -- Where the credential lives: ENCRYPTED (AES-GCM ciphertext below), ENV (environment variable name) or
    -- VAULT (secrets-vault path). The plain secret is never stored or returned by the API (N-1).
    credential_store  VARCHAR(20)   NOT NULL DEFAULT 'NONE' CHECK (credential_store IN ('NONE', 'ENCRYPTED', 'ENV', 'VAULT')),
    credential_ref    VARCHAR(300),
    credential_cipher TEXT,
    sftp_host         VARCHAR(300),
    sftp_port         INTEGER CHECK (sftp_port IS NULL OR (sftp_port > 0 AND sftp_port < 65536)),
    sftp_directory    VARCHAR(300),
    sync_schedule     VARCHAR(100),
    data_exchanged    VARCHAR(300),
    field_mapping     TEXT,
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    last_sync_at      TIMESTAMPTZ,
    next_sync_at      TIMESTAMPTZ,
    last_test_at      TIMESTAMPTZ,
    last_test_status  VARCHAR(20),
    last_test_message VARCHAR(1000),
    CHECK (credential_store <> 'ENCRYPTED' OR credential_cipher IS NOT NULL),
    CHECK (credential_store NOT IN ('ENV', 'VAULT') OR credential_ref IS NOT NULL)
);

CREATE TABLE ben_connector_sync_log (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    connector_id BIGINT        NOT NULL REFERENCES ben_provider_connector (id),
    operation    VARCHAR(20)   NOT NULL CHECK (operation IN ('TEST', 'SYNC')),
    dataset      VARCHAR(30),
    status       VARCHAR(20)   NOT NULL CHECK (status IN ('SUCCESS', 'FAILED', 'SIMULATED')),
    started_at   TIMESTAMPTZ   NOT NULL,
    finished_at  TIMESTAMPTZ,
    records      INTEGER       NOT NULL DEFAULT 0,
    message      VARCHAR(2000),
    file_name    VARCHAR(200),
    payload      TEXT,
    triggered_by VARCHAR(100)
);
CREATE INDEX ix_ben_sync_log_connector ON ben_connector_sync_log (connector_id, id DESC);

-- ------------------------------------------------------------------ schemes and policies (REQ 11.1, 11.2)
ALTER TABLE ben_insurance_scheme DROP CONSTRAINT IF EXISTS ben_insurance_scheme_scheme_type_check;
ALTER TABLE ben_insurance_scheme ADD CONSTRAINT chk_ben_scheme_type
    CHECK (scheme_type IN ('GROUP_MEDICAL', 'GROUP_LIFE', 'PERSONAL_ACCIDENT', 'AGRAHARA', 'OTHER'));
ALTER TABLE ben_insurance_scheme ADD COLUMN policy_no VARCHAR(100);
ALTER TABLE ben_insurance_scheme ADD COLUMN coverage_description VARCHAR(1000);
ALTER TABLE ben_insurance_scheme ADD COLUMN policy_start DATE;
ALTER TABLE ben_insurance_scheme ADD COLUMN policy_expiry DATE;
-- Lead times (days, comma separated) for renewal alerts; NULL = setting benefits.policy.expiry-lead-days.
ALTER TABLE ben_insurance_scheme ADD COLUMN alert_lead_days VARCHAR(50);
ALTER TABLE ben_insurance_scheme ADD COLUMN connector_id BIGINT REFERENCES ben_provider_connector (id);
ALTER TABLE ben_insurance_scheme ADD COLUMN gl_code VARCHAR(40);
ALTER TABLE ben_insurance_scheme ADD COLUMN employer_gl_code VARCHAR(40);
ALTER TABLE ben_insurance_scheme ADD CONSTRAINT chk_ben_scheme_policy_dates
    CHECK (policy_expiry IS NULL OR policy_start IS NULL OR policy_expiry >= policy_start);
UPDATE ben_insurance_scheme SET gl_code = 'GL-2201' WHERE code = 'AGRAHARA' AND gl_code IS NULL;

ALTER TABLE ben_insurance_coverage ADD COLUMN dependents_count INTEGER NOT NULL DEFAULT 0 CHECK (dependents_count >= 0);
ALTER TABLE ben_insurance_coverage ADD COLUMN enrolment_task_id BIGINT;

-- Renewal alerts already sent per policy expiry and lead time (no duplicates).
CREATE TABLE ben_policy_alert (
    id          BIGSERIAL PRIMARY KEY,
    version     BIGINT,
    created_at  TIMESTAMPTZ,
    created_by  VARCHAR(100),
    updated_at  TIMESTAMPTZ,
    updated_by  VARCHAR(100),
    scheme_id   BIGINT  NOT NULL REFERENCES ben_insurance_scheme (id),
    expiry_date DATE    NOT NULL,
    lead_days   INTEGER NOT NULL,
    UNIQUE (scheme_id, expiry_date, lead_days)
);

-- ------------------------------------------------------------------ eligibility rules (REQ 11.3)
-- rule_json: {"op":"AND|OR","children":[...]} groups and {"field":..,"operator":"IN|NOT_IN|GTE|LTE","values":[..],"number":n}
-- conditions. A new version is stored on every change; earlier versions are kept (G-2).
CREATE TABLE ben_eligibility_rule (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    scheme_id           BIGINT        NOT NULL REFERENCES ben_insurance_scheme (id),
    version_no          INTEGER       NOT NULL,
    rule_json           TEXT          NOT NULL,
    dependents_covered  BOOLEAN       NOT NULL DEFAULT FALSE,
    max_dependents      INTEGER       NOT NULL DEFAULT 0 CHECK (max_dependents >= 0),
    waiting_period_days INTEGER       NOT NULL DEFAULT 0 CHECK (waiting_period_days >= 0),
    auto_create_tasks   BOOLEAN       NOT NULL DEFAULT TRUE,
    description         VARCHAR(1000),
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    superseded_at       TIMESTAMPTZ,
    UNIQUE (scheme_id, version_no)
);
CREATE UNIQUE INDEX ux_ben_rule_active ON ben_eligibility_rule (scheme_id) WHERE active;

-- Legacy employment-type / minimum-service eligibility converted into rule version 1.
INSERT INTO ben_eligibility_rule (version, created_at, created_by, scheme_id, version_no, rule_json, waiting_period_days, auto_create_tasks, description, active)
SELECT 0, now(), 'system', s.id, 1,
       '{"op":"AND","children":[' ||
       CONCAT_WS(',',
           CASE WHEN s.eligible_employment_types IS NOT NULL AND s.eligible_employment_types <> ''
                THEN '{"field":"EMPLOYMENT_TYPE","operator":"IN","values":["' || replace(s.eligible_employment_types, ',', '","') || '"]}' END,
           CASE WHEN s.min_service_months > 0
                THEN '{"field":"SERVICE_MONTHS","operator":"GTE","number":' || s.min_service_months || '}' END) ||
       ']}', 0, TRUE, 'Converted from the earlier scheme eligibility settings', TRUE
FROM ben_insurance_scheme s
WHERE NOT EXISTS (SELECT 1 FROM ben_eligibility_rule r WHERE r.scheme_id = s.id);

-- Automatic enrolment tasks (REQ 11.3) raised on joining, transfer, grade change or rule change.
CREATE TABLE ben_enrolment_task (
    id            BIGSERIAL PRIMARY KEY,
    version       BIGINT,
    created_at    TIMESTAMPTZ,
    created_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100),
    scheme_id     BIGINT        NOT NULL REFERENCES ben_insurance_scheme (id),
    employee_id   BIGINT        NOT NULL REFERENCES employee (id),
    rule_id       BIGINT REFERENCES ben_eligibility_rule (id),
    trigger_type  VARCHAR(20)   NOT NULL CHECK (trigger_type IN ('JOINING', 'TRANSFER', 'GRADE_CHANGE', 'RULE_CHANGE', 'PERIODIC', 'MANUAL')),
    trigger_date  DATE          NOT NULL,
    eligible_from DATE          NOT NULL,
    status        VARCHAR(20)   NOT NULL CHECK (status IN ('OPEN', 'ENROLLED', 'DISMISSED')),
    coverage_id   BIGINT REFERENCES ben_insurance_coverage (id),
    resolution    VARCHAR(1000),
    resolved_at   TIMESTAMPTZ,
    resolved_by   VARCHAR(100)
);
CREATE UNIQUE INDEX ux_ben_task_open ON ben_enrolment_task (scheme_id, employee_id) WHERE status = 'OPEN';
CREATE INDEX ix_ben_task_status ON ben_enrolment_task (status, id DESC);

-- ------------------------------------------------------------------ claims (REQ 11.5)
ALTER TABLE ben_insurance_claim DROP CONSTRAINT IF EXISTS ben_insurance_claim_status_check;
UPDATE ben_insurance_claim SET status = 'SUBMITTED' WHERE status = 'IN_REVIEW';
UPDATE ben_insurance_claim SET status = 'HR_VERIFIED' WHERE status = 'APPROVED';
UPDATE ben_insurance_claim SET status = 'SETTLED' WHERE status = 'PAID';
ALTER TABLE ben_insurance_claim ADD CONSTRAINT chk_ben_claim_status
    CHECK (status IN ('SUBMITTED', 'HR_VERIFIED', 'SENT_TO_INSURER', 'SETTLED', 'REJECTED'));
ALTER TABLE ben_insurance_claim ADD COLUMN workflow_instance_id BIGINT;
ALTER TABLE ben_insurance_claim ADD COLUMN rejection_reason VARCHAR(1000);
ALTER TABLE ben_insurance_claim ADD COLUMN verified_at TIMESTAMPTZ;
ALTER TABLE ben_insurance_claim ADD COLUMN sent_at TIMESTAMPTZ;
ALTER TABLE ben_insurance_claim ADD COLUMN settled_at TIMESTAMPTZ;
ALTER TABLE ben_insurance_claim ADD COLUMN settlement_date DATE;
ALTER TABLE ben_insurance_claim ADD CONSTRAINT chk_ben_claim_rejection
    CHECK (status <> 'REJECTED' OR rejection_reason IS NOT NULL OR remarks IS NOT NULL);

INSERT INTO workflow_definition (version, created_at, created_by, process_code, name, description)
VALUES (0, now(), 'system', 'INSURANCE_CLAIM', 'Insurance claim', 'HR verification of an employee insurance claim before it is sent to the insurer (REQ 11.5)')
ON CONFLICT (process_code) DO NOTHING;
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours)
SELECT d.id, 1, 'HR verification', 'ROLE', 'HR_ADMIN', 72 FROM workflow_definition d
WHERE d.process_code = 'INSURANCE_CLAIM' AND NOT EXISTS (SELECT 1 FROM workflow_stage s WHERE s.definition_id = d.id);

-- ------------------------------------------------------------------ settings
INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('benefits.policy.expiry-lead-days', '60,30,7', 'BENEFITS', 'STRING', 'Default lead times (days) for insurance policy renewal alerts (REQ 11.2)'),
 ('benefits.policy.alert-role', 'HR_ADMIN', 'BENEFITS', 'STRING', 'Role alerted about policy renewals and new enrolment tasks'),
 ('benefits.eligibility.joining-window-days', '90', 'BENEFITS', 'INT', 'Employees appointed within this many days are treated as joiners when eligibility is evaluated (REQ 11.3)'),
 ('benefits.connectors.allow-http', 'false', 'BENEFITS', 'BOOLEAN', 'Allow plain http:// base URLs for LIVE insurer connectors (https is required otherwise)'),
 ('benefits.due-soon.days', '60', 'BENEFITS', 'INT', 'Look-ahead (days) for insurance items in the Expiring & due soon dashboard feed (D-7)')
ON CONFLICT (setting_key) DO NOTHING;

INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'BEN_POLICY_RENEWAL', 'Group policy renewal alert to HR (REQ 11.2)',
  'Policy renewal due in {{days}} days: {{scheme}}', 'ඔප්පුව දින {{days}} කින් අලුත් කළ යුතුය: {{scheme}}', 'கொள்கை {{days}} நாட்களில் புதுப்பிக்கப்பட வேண்டும்: {{scheme}}',
  'The {{scheme}} policy {{policyNo}} with {{provider}} expires on {{expiryDate}} ({{enrolled}} members enrolled).',
  '{{provider}} සමඟ ඇති {{scheme}} ඔප්පුව {{policyNo}} {{expiryDate}} දින කල් ඉකුත් වේ (සාමාජිකයින් {{enrolled}}).',
  '{{provider}} உடனான {{scheme}} கொள்கை {{policyNo}} {{expiryDate}} அன்று காலாவதியாகிறது ({{enrolled}} உறுப்பினர்கள்).', TRUE),
 (0, now(), 'system', 'BEN_ENROLMENT_TASK', 'New insurance enrolment task (REQ 11.3)',
  'Enrolment task: {{employeeNo}} {{scheme}}', 'ලියාපදිංචි කිරීමේ කාර්යය: {{employeeNo}} {{scheme}}', 'பதிவுப் பணி: {{employeeNo}} {{scheme}}',
  '{{employeeNo}} became eligible for {{scheme}} ({{trigger}}); enrol from {{eligibleFrom}}.',
  '{{employeeNo}} {{scheme}} සඳහා සුදුසුකම් ලැබීය ({{trigger}}); {{eligibleFrom}} සිට ලියාපදිංචි කරන්න.',
  '{{employeeNo}} {{scheme}} க்குத் தகுதி பெற்றார் ({{trigger}}); {{eligibleFrom}} முதல் பதிவு செய்யவும்.', FALSE),
 (0, now(), 'system', 'BEN_CLAIM_STATUS', 'Insurance claim status change sent to the employee (REQ 11.5)',
  'Insurance claim {{claimId}}: {{status}}', 'රක්ෂණ හිමිකම {{claimId}}: {{status}}', 'காப்புறுதிக் கோரிக்கை {{claimId}}: {{status}}',
  'Your {{scheme}} claim {{claimId}} is now {{status}}. {{note}}',
  'ඔබගේ {{scheme}} හිමිකම {{claimId}} දැන් {{status}}. {{note}}',
  'உங்கள் {{scheme}} கோரிக்கை {{claimId}} இப்போது {{status}}. {{note}}', TRUE)
ON CONFLICT (code) DO NOTHING;
