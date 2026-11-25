-- Workflow engine v2 (spec v2.1 REQ 10.4-10.9): versioned definitions with routing rules, parallel / walk-up stages,
-- action types and SLAs; per-request resolved steps; richer approval audit trail.

-- ------------------------------------------------------------------ definitions and versions
ALTER TABLE workflow_definition ADD COLUMN legacy_fingerprint VARCHAR(64);

CREATE TABLE workflow_version (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    definition_id       BIGINT       NOT NULL REFERENCES workflow_definition (id) ON DELETE CASCADE,
    version_no          INTEGER      NOT NULL,
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    effective_from      TIMESTAMPTZ,
    content             TEXT         NOT NULL,
    change_note         VARCHAR(1000),
    source              VARCHAR(20)  NOT NULL DEFAULT 'DESIGNER',
    based_on_version_no INTEGER,
    published_at        TIMESTAMPTZ,
    published_by        VARCHAR(100),
    UNIQUE (definition_id, version_no),
    CHECK (status <> 'PUBLISHED' OR effective_from IS NOT NULL)
);
CREATE INDEX ix_wf_version_definition ON workflow_version (definition_id, status);

-- ------------------------------------------------------------------ instances and their resolved steps
ALTER TABLE workflow_instance ADD COLUMN version_id BIGINT REFERENCES workflow_version (id);
ALTER TABLE workflow_instance ADD COLUMN subject_employee_id BIGINT;
ALTER TABLE workflow_instance ADD COLUMN version_no INTEGER;
ALTER TABLE workflow_instance ADD COLUMN path_code VARCHAR(40);
ALTER TABLE workflow_instance ADD COLUMN path_name VARCHAR(150);
ALTER TABLE workflow_instance ADD COLUMN matched_rule VARCHAR(150);
ALTER TABLE workflow_instance ADD COLUMN routing_attributes TEXT;
ALTER TABLE workflow_instance ADD COLUMN routing_round INTEGER NOT NULL DEFAULT 1;
ALTER TABLE workflow_instance ADD COLUMN current_action_type VARCHAR(20);
ALTER TABLE workflow_instance ADD COLUMN due_at TIMESTAMPTZ;
CREATE INDEX ix_wf_instance_process_status ON workflow_instance (process_code, status);

CREATE TABLE workflow_instance_step (
    id                   BIGSERIAL PRIMARY KEY,
    instance_id          BIGINT       NOT NULL REFERENCES workflow_instance (id),
    round                INTEGER      NOT NULL,
    step_order           INTEGER      NOT NULL,
    name                 VARCHAR(150) NOT NULL,
    stage_name           VARCHAR(150),
    action_type          VARCHAR(20)  NOT NULL CHECK (action_type IN ('APPROVE', 'VERIFY', 'RECORD')),
    mode                 VARCHAR(10)  NOT NULL CHECK (mode IN ('ALL', 'ANY')),
    sla_hours            INTEGER,
    escalation_role_code VARCHAR(50),
    walk_level           INTEGER,
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('WAITING', 'ACTIVE', 'DONE', 'SUPERSEDED')),
    entered_at           TIMESTAMPTZ,
    due_at               TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    UNIQUE (instance_id, round, step_order)
);
CREATE INDEX ix_wf_step_instance ON workflow_instance_step (instance_id, round);

CREATE TABLE workflow_step_slot (
    id                   BIGSERIAL PRIMARY KEY,
    step_id              BIGINT       NOT NULL REFERENCES workflow_instance_step (id) ON DELETE CASCADE,
    slot_order           INTEGER      NOT NULL,
    approver_type        VARCHAR(30)  NOT NULL,
    approver_ref         VARCHAR(100),
    label                VARCHAR(250),
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'DONE', 'NOT_REQUIRED')),
    acted_by_user_id     BIGINT,
    acted_by_name        VARCHAR(200),
    on_behalf_of_user_id BIGINT,
    decision             VARCHAR(20),
    acted_at             TIMESTAMPTZ
);
CREATE INDEX ix_wf_slot_step ON workflow_step_slot (step_id);

-- ------------------------------------------------------------------ audit trail detail (REQ 10.8)
ALTER TABLE approval_action ADD COLUMN step_id BIGINT;
ALTER TABLE approval_action ADD COLUMN stage_action_type VARCHAR(20);
ALTER TABLE approval_action ADD COLUMN slot_label VARCHAR(250);
ALTER TABLE approval_action ADD COLUMN workflow_version_no INTEGER;

-- ------------------------------------------------------------------ existing single-chain definitions become version 1
INSERT INTO workflow_version (version, created_at, created_by, definition_id, version_no, status, effective_from, content,
                              change_note, source, published_at, published_by)
SELECT 0, now(), 'system', d.id, 1, 'PUBLISHED', TIMESTAMPTZ '2000-01-01 00:00:00+00',
       jsonb_build_object(
           'defaultPath', 'STANDARD',
           'rules', '[]'::jsonb,
           'paths', jsonb_build_array(jsonb_build_object(
               'code', 'STANDARD',
               'name', 'Standard path',
               'stages', COALESCE((SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
                                              'name', s.name,
                                              'actionType', 'APPROVE',
                                              'mode', 'ANY',
                                              'approvers', jsonb_build_array(jsonb_strip_nulls(jsonb_build_object('type', s.approver_type, 'ref', s.approver_ref))),
                                              'slaHours', s.sla_hours,
                                              'escalationRoleCode', s.escalation_role_code))
                                          ORDER BY s.stage_order, s.id)
                                   FROM workflow_stage s WHERE s.definition_id = d.id), '[]'::jsonb))))::text,
       'Migrated from the single-chain configuration', 'MIGRATION', now(), 'system'
FROM workflow_definition d
WHERE NOT EXISTS (SELECT 1 FROM workflow_version v WHERE v.definition_id = d.id);

-- Existing requests run on version 1 (their steps are built on first use, keeping their progress).
UPDATE workflow_instance i SET version_id = v.id, version_no = 1, path_code = 'STANDARD', path_name = 'Standard path'
FROM workflow_version v JOIN workflow_definition d ON d.id = v.definition_id
WHERE d.process_code = i.process_code AND v.version_no = 1 AND i.version_id IS NULL;

-- ------------------------------------------------------------------ routing example from the spec (REQ 10.6), as a DRAFT
-- LEAVE: days <= 3 -> division head only; more days -> division head then HR; a Director (designation level <= 3)
-- -> supervising post, recorded by the Head of HR. Kept as a draft so current leave routing is unchanged until an
-- administrator reviews (Compare / Simulate) and publishes it.
INSERT INTO workflow_version (version, created_at, created_by, definition_id, version_no, status, content, change_note, source, based_on_version_no)
SELECT 0, now(), 'system', d.id, 2, 'DRAFT',
'{"defaultPath":"STANDARD","paths":[' ||
 '{"code":"SHORT","name":"Short leave (up to 3 days)","stages":[' ||
   '{"name":"Division head approval","actionType":"APPROVE","mode":"ANY","approvers":[{"type":"DIVISION_HEAD"}],"slaHours":48,"escalationRoleCode":"HR_ADMIN"}]},' ||
 '{"code":"STANDARD","name":"Standard leave (more than 3 days)","stages":[' ||
   '{"name":"Division head recommendation","actionType":"APPROVE","mode":"ANY","approvers":[{"type":"DIVISION_HEAD"}],"slaHours":48,"escalationRoleCode":"HR_ADMIN"},' ||
   '{"name":"HR approval","actionType":"APPROVE","mode":"ANY","approvers":[{"type":"ROLE","ref":"HR_ADMIN"}],"slaHours":48}]},' ||
 '{"code":"DIRECTOR","name":"Leave of a Director or above","stages":[' ||
   '{"name":"Supervising officer approval","actionType":"APPROVE","mode":"ANY","approvers":[{"type":"SUPERVISOR","ref":"INITIATOR"}],"slaHours":48,"escalationRoleCode":"HR_ADMIN"},' ||
   '{"name":"Head of HR record","actionType":"RECORD","mode":"ANY","approvers":[{"type":"ROLE","ref":"HEAD_OF_HR"}],"slaHours":72}]}' ||
'],"rules":[' ||
 '{"name":"Initiator is a Director or above","path":"DIRECTOR","when":{"join":"AND","clauses":[{"attribute":"initiator.designationLevel","operator":"LE","value":"3"}]}},' ||
 '{"name":"Up to 3 days","path":"SHORT","when":{"join":"AND","clauses":[{"attribute":"days","operator":"LE","value":"3"}]}}' ||
']}',
'Spec v2.1 REQ 10.6 example: route by number of days and by the initiator''s level', 'SEED', 1
FROM workflow_definition d WHERE d.process_code = 'LEAVE'
  AND NOT EXISTS (SELECT 1 FROM workflow_version v WHERE v.definition_id = d.id AND v.version_no = 2);
