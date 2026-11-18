-- Position categorization (org chart): designations are grouped into configurable position categories.
-- Assignments are effective-dated; changes are submitted in batches and applied only after approval.

CREATE TABLE position_category (
    id          BIGSERIAL PRIMARY KEY,
    version     BIGINT,
    created_at  TIMESTAMPTZ,
    created_by  VARCHAR(100),
    updated_at  TIMESTAMPTZ,
    updated_by  VARCHAR(100),
    code        VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(150) NOT NULL,
    name_si     VARCHAR(150),
    name_ta     VARCHAR(150),
    color       VARCHAR(7)   NOT NULL DEFAULT '#0B1F4B' CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    closed      BOOLEAN      NOT NULL DEFAULT FALSE,
    description VARCHAR(500)
);

CREATE TABLE categorization_batch (
    id                   BIGSERIAL PRIMARY KEY,
    version              BIGINT,
    created_at           TIMESTAMPTZ,
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    effective_date       DATE          NOT NULL,
    reason               VARCHAR(2000) NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    workflow_instance_id BIGINT,
    applied_at           TIMESTAMPTZ
);

CREATE TABLE categorization_line (
    id               BIGSERIAL PRIMARY KEY,
    batch_id         BIGINT NOT NULL REFERENCES categorization_batch (id) ON DELETE CASCADE,
    designation_id   BIGINT NOT NULL REFERENCES designation (id),
    from_category_id BIGINT REFERENCES position_category (id),
    to_category_id   BIGINT REFERENCES position_category (id)
);
CREATE INDEX ix_categorization_line_batch ON categorization_line (batch_id);

CREATE TABLE designation_category_assignment (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT,
    created_at     TIMESTAMPTZ,
    created_by     VARCHAR(100),
    updated_at     TIMESTAMPTZ,
    updated_by     VARCHAR(100),
    designation_id BIGINT NOT NULL REFERENCES designation (id),
    category_id    BIGINT NOT NULL REFERENCES position_category (id),
    effective_from DATE   NOT NULL,
    effective_to   DATE,
    batch_id       BIGINT REFERENCES categorization_batch (id),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX ix_dca_designation ON designation_category_assignment (designation_id);

INSERT INTO workflow_definition (version, created_at, created_by, process_code, name, description)
VALUES (0, now(), 'system', 'POSITION_CATEGORIZATION', 'Position categorization',
        'Moves of designations between position categories on the organization chart');
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours)
SELECT id, 1, 'Director General approval', 'ROLE', 'MANAGEMENT', 120 FROM workflow_definition WHERE process_code = 'POSITION_CATEGORIZATION';

INSERT INTO system_setting (setting_key, setting_value, category, value_type, description)
VALUES ('org.short-name', 'Commission', 'GENERAL', 'STRING', 'Short name shown at the root of the organization chart')
ON CONFLICT (setting_key) DO NOTHING;
