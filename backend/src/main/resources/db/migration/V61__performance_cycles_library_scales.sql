-- =====================================================================================================
-- Spec v2.1 REQ 12 (performance evaluation):
--  * per_cycle_stage          configurable stages of an evaluation cycle with dates (12.1)
--  * per_kpi_library          KPI / objective / competency library with measure, weight and applicability (12.2)
--  * per_rating_scale(_level) editable rating scales: value, label, score band, descriptor (12.3)
--  * evaluation lines         target, achieved, self rating, supervisor rating, training need (12.4)
--  * evaluation               stage, self score, rating label, mid-year review, training needs, increment link (12.5, 12.6)
-- All sample content is configuration to be confirmed by Administration (SRS 11.1 #12, open item 4).
-- =====================================================================================================

-- ------------------------------------------------------------------ cycle stages (12.1)
CREATE TABLE per_cycle_stage (
    id         BIGSERIAL PRIMARY KEY,
    version    BIGINT,
    created_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_at TIMESTAMPTZ,
    updated_by VARCHAR(100),
    period_id  BIGINT       NOT NULL REFERENCES per_period (id),
    stage_code VARCHAR(30)  NOT NULL CHECK (stage_code IN ('OBJECTIVE_SETTING', 'MID_YEAR_REVIEW', 'SELF_ASSESSMENT',
                                            'SUPERVISOR_RATING', 'MODERATION', 'FINALIZATION')),
    name       VARCHAR(150) NOT NULL,
    sort_order INTEGER      NOT NULL,
    start_date DATE,
    end_date   DATE,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date),
    UNIQUE (period_id, stage_code)
);

-- Stages for periods that already exist (new cycles get them when created).
INSERT INTO per_cycle_stage (version, created_at, created_by, period_id, stage_code, name, sort_order, start_date, end_date, enabled)
SELECT 0, now(), 'system', p.id, s.code, s.name, s.ord,
       CASE s.code
           WHEN 'OBJECTIVE_SETTING' THEN p.start_date
           WHEN 'MID_YEAR_REVIEW' THEN p.start_date + (p.end_date - p.start_date) / 2 - 14
           WHEN 'SELF_ASSESSMENT' THEN GREATEST(p.start_date, p.end_date - 30)
           WHEN 'SUPERVISOR_RATING' THEN GREATEST(p.start_date, p.end_date - 14)
           WHEN 'MODERATION' THEN p.end_date
           ELSE p.end_date END,
       CASE s.code
           WHEN 'OBJECTIVE_SETTING' THEN LEAST(p.end_date, p.start_date + 30)
           WHEN 'MID_YEAR_REVIEW' THEN LEAST(p.end_date, p.start_date + (p.end_date - p.start_date) / 2 + 14)
           WHEN 'SELF_ASSESSMENT' THEN p.end_date
           WHEN 'SUPERVISOR_RATING' THEN GREATEST(p.end_date, COALESCE(p.due_date, p.end_date) - 14)
           WHEN 'MODERATION' THEN GREATEST(p.end_date, COALESCE(p.due_date, p.end_date) - 7)
           ELSE GREATEST(p.end_date, COALESCE(p.due_date, p.end_date)) END,
       TRUE
FROM per_period p
CROSS JOIN (VALUES ('OBJECTIVE_SETTING', 'Objective setting', 1), ('MID_YEAR_REVIEW', 'Mid-year review', 2),
                   ('SELF_ASSESSMENT', 'Self-assessment', 3), ('SUPERVISOR_RATING', 'Supervisor rating', 4),
                   ('MODERATION', 'Moderation', 5), ('FINALIZATION', 'Finalization', 6)) AS s(code, name, ord);

-- ------------------------------------------------------------------ rating scales (12.3)
CREATE TABLE per_rating_scale (
    id          BIGSERIAL PRIMARY KEY,
    version     BIGINT,
    created_at  TIMESTAMPTZ,
    created_by  VARCHAR(100),
    updated_at  TIMESTAMPTZ,
    updated_by  VARCHAR(100),
    code        VARCHAR(40)   NOT NULL UNIQUE,
    name        VARCHAR(200)  NOT NULL,
    description VARCHAR(1000),
    active      BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE per_rating_scale_level (
    scale_id   BIGINT        NOT NULL REFERENCES per_rating_scale (id) ON DELETE CASCADE,
    sort_order INTEGER       NOT NULL,
    value      NUMERIC(5, 2) NOT NULL,
    label      VARCHAR(100)  NOT NULL,
    band_min   NUMERIC(6, 2) NOT NULL,
    band_max   NUMERIC(6, 2) NOT NULL,
    descriptor VARCHAR(1000),
    PRIMARY KEY (scale_id, sort_order),
    CHECK (band_max >= band_min)
);

INSERT INTO per_rating_scale (version, created_at, created_by, code, name, description) VALUES
 (0, now(), 'system', 'FIVE_POINT', 'Five-point scale', 'SAMPLE scale to be confirmed with Administration. Bands apply to the weighted overall score.'),
 (0, now(), 'system', 'FOUR_POINT', 'Four-point scale', 'SAMPLE scale to be confirmed with Administration. Bands apply to the weighted overall score.');

INSERT INTO per_rating_scale_level (scale_id, sort_order, value, label, band_min, band_max, descriptor)
SELECT s.id, v.ord, v.val, v.label, v.bmin, v.bmax, v.descr
FROM per_rating_scale s
JOIN (VALUES
  ('FIVE_POINT', 1, 1, 'Unsatisfactory', 1.00, 1.49, 'Performance falls well short of the required standard'),
  ('FIVE_POINT', 2, 2, 'Needs improvement', 1.50, 2.49, 'Meets some but not all of the required standard'),
  ('FIVE_POINT', 3, 3, 'Meets expectations', 2.50, 3.49, 'Fully meets the required standard'),
  ('FIVE_POINT', 4, 4, 'Exceeds expectations', 3.50, 4.49, 'Frequently exceeds the required standard'),
  ('FIVE_POINT', 5, 5, 'Outstanding', 4.50, 5.00, 'Consistently and substantially exceeds the required standard'),
  ('FOUR_POINT', 1, 1, 'Unsatisfactory', 1.00, 1.49, 'Below the required standard'),
  ('FOUR_POINT', 2, 2, 'Fair', 1.50, 2.49, 'Partly meets the required standard'),
  ('FOUR_POINT', 3, 3, 'Good', 2.50, 3.49, 'Meets the required standard'),
  ('FOUR_POINT', 4, 4, 'Very good', 3.50, 4.00, 'Exceeds the required standard')
) AS v(scale, ord, val, label, bmin, bmax, descr) ON v.scale = s.code;

ALTER TABLE per_model ADD COLUMN rating_scale_id BIGINT REFERENCES per_rating_scale (id);
UPDATE per_model SET rating_scale_id = (SELECT id FROM per_rating_scale WHERE code = 'FIVE_POINT') WHERE rating_min = 1 AND rating_max = 5;
UPDATE per_model SET rating_scale_id = (SELECT id FROM per_rating_scale WHERE code = 'FOUR_POINT') WHERE rating_min = 1 AND rating_max = 4;

-- ------------------------------------------------------------------ KPI / objective / competency library (12.2)
CREATE TABLE per_kpi_library (
    id                      BIGSERIAL PRIMARY KEY,
    version                 BIGINT,
    created_at              TIMESTAMPTZ,
    created_by              VARCHAR(100),
    updated_at              TIMESTAMPTZ,
    updated_by              VARCHAR(100),
    code                    VARCHAR(40)   NOT NULL UNIQUE,
    kind                    VARCHAR(20)   NOT NULL CHECK (kind IN ('KPI', 'OBJECTIVE', 'COMPETENCY')),
    title                   VARCHAR(300)  NOT NULL,
    description             VARCHAR(1000),
    measure                 VARCHAR(500),
    default_weight          NUMERIC(6, 2) CHECK (default_weight IS NULL OR (default_weight > 0 AND default_weight <= 100)),
    applicable_categories   VARCHAR(500),
    applicable_designations VARCHAR(500),
    active                  BOOLEAN       NOT NULL DEFAULT TRUE
);

INSERT INTO per_kpi_library (version, created_at, created_by, code, kind, title, description, measure, default_weight,
                             applicable_categories, applicable_designations) VALUES
 (0, now(), 'system', 'KPI-INV-TIMELY', 'KPI', 'Investigations completed within target time',
  'Proportion of assigned investigations completed within the target time', '% of cases closed within target', 30, 'INVESTIGATION', NULL),
 (0, now(), 'system', 'KPI-INV-QUALITY', 'KPI', 'Quality of investigation reports',
  'Completeness and accuracy of reports and evidence handling', 'Reports accepted without return by legal review (%)', 25, 'INVESTIGATION', NULL),
 (0, now(), 'system', 'KPI-LEGAL-FILES', 'KPI', 'Legal opinions delivered on time',
  'Opinions and indictments delivered within the agreed time', 'Files completed within target (%)', 30, 'EXECUTIVE', 'ADG,SADG,DDG,DIR'),
 (0, now(), 'system', 'OBJ-AGREED', 'OBJECTIVE', 'Achievement of agreed objectives',
  'Delivery against objectives agreed at the start of the cycle', 'Objectives achieved / agreed', 30, NULL, NULL),
 (0, now(), 'system', 'COMP-INTEGRITY', 'COMPETENCY', 'Integrity and confidentiality',
  'Protection of confidential information, compliance with the Code of Conduct', 'Behavioural rating', 20, NULL, NULL),
 (0, now(), 'system', 'COMP-LEADERSHIP', 'COMPETENCY', 'Leadership and staff development',
  'Direction, delegation and development of subordinates', 'Behavioural rating', 20, 'SENIOR_MANAGEMENT,EXECUTIVE', NULL),
 (0, now(), 'system', 'COMP-TEAMWORK', 'COMPETENCY', 'Teamwork and initiative', 'Cooperation and initiative', 'Behavioural rating', 10, NULL, NULL),
 (0, now(), 'system', 'COMP-PUNCTUAL', 'COMPETENCY', 'Punctuality and attendance', 'Attendance record of the cycle',
  'Late arrivals and unauthorised absences', 20, 'TERTIARY,SECONDARY,PRIMARY', NULL);

-- ------------------------------------------------------------------ templates (models) and their criteria
ALTER TABLE per_criterion DROP CONSTRAINT IF EXISTS per_criterion_criterion_type_check;
ALTER TABLE per_criterion ADD CONSTRAINT per_criterion_criterion_type_check
    CHECK (criterion_type IN ('CRITERION', 'KPI', 'OBJECTIVE', 'COMPETENCY'));
ALTER TABLE per_criterion ADD COLUMN measure VARCHAR(500);
ALTER TABLE per_criterion ADD COLUMN library_item_id BIGINT REFERENCES per_kpi_library (id);
UPDATE per_criterion c SET library_item_id = l.id, measure = l.measure
FROM per_kpi_library l WHERE l.title = c.title;

-- ------------------------------------------------------------------ evaluation lines (12.4)
ALTER TABLE per_rating ALTER COLUMN rating DROP NOT NULL;
ALTER TABLE per_rating ADD COLUMN target VARCHAR(500);
ALTER TABLE per_rating ADD COLUMN achieved VARCHAR(500);
ALTER TABLE per_rating ADD COLUMN self_rating NUMERIC(5, 2);
ALTER TABLE per_rating ADD COLUMN self_comment VARCHAR(2000);
ALTER TABLE per_rating ADD COLUMN training_need BOOLEAN NOT NULL DEFAULT FALSE;

-- ------------------------------------------------------------------ evaluations (12.4 - 12.6)
ALTER TABLE per_evaluation ADD COLUMN stage VARCHAR(30) NOT NULL DEFAULT 'SUPERVISOR_RATING'
    CHECK (stage IN ('OBJECTIVE_SETTING', 'SELF_ASSESSMENT', 'SUPERVISOR_RATING', 'MODERATION', 'FINALIZED'));
UPDATE per_evaluation SET stage = 'FINALIZED' WHERE status = 'FINALIZED';
UPDATE per_evaluation SET stage = 'MODERATION' WHERE status = 'SUBMITTED';
ALTER TABLE per_evaluation ADD COLUMN stage_entered_at TIMESTAMPTZ;
ALTER TABLE per_evaluation ADD COLUMN objectives_set_at TIMESTAMPTZ;
ALTER TABLE per_evaluation ADD COLUMN mid_year_comments TEXT;
ALTER TABLE per_evaluation ADD COLUMN mid_year_at TIMESTAMPTZ;
ALTER TABLE per_evaluation ADD COLUMN self_submitted_at TIMESTAMPTZ;
ALTER TABLE per_evaluation ADD COLUMN self_score NUMERIC(6, 2);
ALTER TABLE per_evaluation ADD COLUMN self_percent NUMERIC(6, 2);
ALTER TABLE per_evaluation ADD COLUMN overall_rating_label VARCHAR(100);
ALTER TABLE per_evaluation ADD COLUMN return_comment VARCHAR(2000);
ALTER TABLE per_evaluation ADD COLUMN training_needs TEXT;
ALTER TABLE per_evaluation ADD COLUMN increment_recommendation VARCHAR(20)
    CHECK (increment_recommendation IS NULL OR increment_recommendation IN ('RECOMMENDED', 'DEFERRED', 'WITHHELD', 'NOT_APPLICABLE'));
ALTER TABLE per_evaluation ADD COLUMN increment_note VARCHAR(1000);
ALTER TABLE per_evaluation ADD COLUMN increment_decided_by VARCHAR(100);
ALTER TABLE per_evaluation ADD COLUMN increment_decided_at TIMESTAMPTZ;
CREATE INDEX ix_per_evaluation_stage ON per_evaluation (period_id, stage);

-- Read-only link for payroll increments (12.6): one row per finalized evaluation. Payroll reads this view; it never
-- writes to performance tables.
CREATE VIEW v_per_increment_recommendation AS
SELECT v.id AS evaluation_id, v.employee_id, p.code AS cycle_code, p.name AS cycle_name, p.end_date AS cycle_end_date,
       v.overall_score, v.overall_percent, v.overall_rating_label, v.increment_recommendation, v.increment_note,
       v.increment_decided_by, v.increment_decided_at, v.finalized_at
FROM per_evaluation v JOIN per_period p ON p.id = v.period_id
WHERE v.status = 'FINALIZED';

-- ------------------------------------------------------------------ workflow, settings, notifications
UPDATE workflow_definition SET name = 'Performance evaluation moderation',
       description = 'Moderation of a supervisor-rated evaluation before it is finalized (REQ 12.4)'
WHERE process_code = 'PERFORMANCE_EVALUATION';
UPDATE workflow_stage SET name = 'Moderation (HR)'
WHERE definition_id IN (SELECT id FROM workflow_definition WHERE process_code = 'PERFORMANCE_EVALUATION') AND stage_order = 1;

INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('performance.increment.min-percent', '50', 'PERFORMANCE', 'DECIMAL',
  'Finalized evaluations scoring at least this percentage are linked to the next increment as RECOMMENDED, otherwise DEFERRED (HR may override with a reason)'),
 ('performance.stage-sla-days', '14', 'PERFORMANCE', 'INT',
  'Days an evaluation may stay in one stage before it is reported as overdue when the stage has no end date')
ON CONFLICT (setting_key) DO NOTHING;

INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'PER_SELF_ASSESSMENT', 'Sent to an employee when the self-assessment of an evaluation is open',
  'Self-assessment open: {{period}}', 'ස්වයං ඇගයීම විවෘතයි: {{period}}', 'சுய மதிப்பீடு திறக்கப்பட்டது: {{period}}',
  'Your objectives for {{period}} are set. Please record what you achieved and your self rating. {{comment}}',
  '{{period}} සඳහා ඔබගේ අරමුණු සකසා ඇත. ඔබ ළඟා කරගත් දේ සහ ඔබගේ ස්වයං ඇගයීම සටහන් කරන්න. {{comment}}',
  '{{period}} க்கான உங்கள் இலக்குகள் அமைக்கப்பட்டுள்ளன. நீங்கள் அடைந்தவற்றையும் சுய மதிப்பீட்டையும் பதிவு செய்யவும். {{comment}}', FALSE),
 (0, now(), 'system', 'PER_SELF_SUBMITTED', 'Sent to the evaluator when an employee submits the self-assessment',
  'Self-assessment submitted: {{employee}}', 'ස්වයං ඇගයීම ඉදිරිපත් කෙරිණි: {{employee}}', 'சுய மதிப்பீடு சமர்ப்பிக்கப்பட்டது: {{employee}}',
  '{{employee}} submitted the self-assessment for {{period}}. Please complete the supervisor rating.',
  '{{employee}} {{period}} සඳහා ස්වයං ඇගයීම ඉදිරිපත් කළේය. කරුණාකර අධීක්ෂක ඇගයීම සම්පූර්ණ කරන්න.',
  '{{employee}} {{period}} க்கான சுய மதிப்பீட்டைச் சமர்ப்பித்தார். மேற்பார்வையாளர் மதிப்பீட்டை நிறைவு செய்யவும்.', FALSE)
ON CONFLICT (code) DO NOTHING;
