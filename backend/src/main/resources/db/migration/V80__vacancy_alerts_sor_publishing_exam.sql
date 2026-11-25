-- =====================================================================================================
-- Spec v2.1 REQ 5 (vacancy alerts), REQ 8 (SOR preparation and publishing) and REQ 9 (examination e-mails).
-- Triggers, lead times, recipients, channels, templates and selection methods are all data (spec 1.3, G-1).
-- Sinhala / Tamil wording is provisional and must be reviewed by CIABOC before go-live (G-7, N-3).
-- =====================================================================================================

-- ------------------------------------------------------------------ REQ 5.4 vacancy register
-- Status model: FORECAST / OPEN / ADVERTISED / FILLED / FROZEN (5.4) plus CLOSED (applications closed, selection in
-- progress) and CANCELLED. Earlier statuses map: DRAFT -> OPEN (vacant, not advertised), OPEN -> ADVERTISED.
DO $$
DECLARE c text;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'rec_vacancy'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%status%'
    LOOP
        EXECUTE 'ALTER TABLE rec_vacancy DROP CONSTRAINT ' || quote_ident(c);
    END LOOP;
END $$;
UPDATE rec_vacancy SET status = 'ADVERTISED' WHERE status = 'OPEN';
UPDATE rec_vacancy SET status = 'OPEN' WHERE status = 'DRAFT';
ALTER TABLE rec_vacancy ADD CONSTRAINT ck_rec_vacancy_status
    CHECK (status IN ('FORECAST', 'OPEN', 'ADVERTISED', 'CLOSED', 'FILLED', 'FROZEN', 'CANCELLED'));

ALTER TABLE rec_vacancy ADD COLUMN position_id BIGINT REFERENCES org_position (id);
ALTER TABLE rec_vacancy ADD COLUMN trigger_code VARCHAR(30) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE rec_vacancy ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('AUTO', 'MANUAL'));
ALTER TABLE rec_vacancy ADD COLUMN source_key VARCHAR(120) UNIQUE;
ALTER TABLE rec_vacancy ADD COLUMN expected_date DATE;
ALTER TABLE rec_vacancy ADD COLUMN vacated_by_employee_id BIGINT REFERENCES employee (id);
ALTER TABLE rec_vacancy ADD COLUMN cadre_change_id BIGINT;
ALTER TABLE rec_vacancy ADD COLUMN sor_id BIGINT;
ALTER TABLE rec_vacancy ADD COLUMN filled_posts INTEGER NOT NULL DEFAULT 0 CHECK (filled_posts >= 0);
ALTER TABLE rec_vacancy ADD COLUMN filled_on DATE;
ALTER TABLE rec_vacancy ADD COLUMN status_reason VARCHAR(1000);
UPDATE rec_vacancy SET expected_date = COALESCE(opening_date, created_at::date, CURRENT_DATE) WHERE expected_date IS NULL;
CREATE INDEX ix_rec_vacancy_status_date ON rec_vacancy (status, expected_date);
CREATE INDEX ix_rec_vacancy_vacated ON rec_vacancy (vacated_by_employee_id);
CREATE INDEX ix_rec_vacancy_position ON rec_vacancy (position_id);

-- ------------------------------------------------------------------ REQ 5.1 configurable triggers
CREATE TABLE rec_vacancy_trigger (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    code                VARCHAR(30)  NOT NULL UNIQUE,
    name                VARCHAR(150) NOT NULL,
    name_si             VARCHAR(150),
    name_ta             VARCHAR(150),
    description         VARCHAR(500),
    -- SEPARATION_REASON master-data codes that fire this trigger when an employee leaves service.
    separation_reasons  VARCHAR(300),
    -- Placement reasons (PROMOTION, TRANSFER ...) that vacate the officer's previous post.
    placement_reasons   VARCHAR(300),
    -- Employee date used to forecast the vacancy before it happens.
    forecast_date_field VARCHAR(30) CHECK (forecast_date_field IS NULL OR forecast_date_field IN ('RETIREMENT_DATE', 'SECONDMENT_END_DATE')),
    -- Limits the forecast to these EMPLOYMENT_TYPE codes (blank = all).
    employment_types    VARCHAR(300),
    cadre_effect        VARCHAR(10) CHECK (cadre_effect IS NULL OR cadre_effect IN ('INCREASE', 'REDUCTION')),
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order          INTEGER      NOT NULL DEFAULT 0
);

INSERT INTO rec_vacancy_trigger (version, created_at, created_by, code, name, name_si, name_ta, description, separation_reasons,
                                 placement_reasons, forecast_date_field, employment_types, cadre_effect, enabled, sort_order) VALUES
 (0, now(), 'system', 'RETIREMENT', 'Retirement', 'විශ්‍රාම ගැනීම', 'ஓய்வு', 'Forecast from the retirement date; confirmed when the officer retires',
  'AGE_RETIREMENT,VOLUNTARY_RETIREMENT', NULL, 'RETIREMENT_DATE', NULL, NULL, TRUE, 1),
 (0, now(), 'system', 'RESIGNATION', 'Resignation', 'ඉල්ලා අස්වීම', 'பதவி விலகல்', 'Officer resigns', 'RESIGNATION', NULL, NULL, NULL, NULL, TRUE, 2),
 (0, now(), 'system', 'CONTRACT_END', 'End of contract', 'කොන්ත්‍රාත්තුව අවසන් වීම', 'ஒப்பந்த முடிவு',
  'Forecast from the end date on the employee record for contract / temporary staff', NULL, NULL, 'SECONDMENT_END_DATE', 'CONTRACT,TEMPORARY', NULL, TRUE, 3),
 (0, now(), 'system', 'SECONDMENT_END', 'End of secondment', 'අනුයුක්තිය අවසන් වීම', 'இணைப்பு முடிவு',
  'Forecast from the secondment end date; confirmed when the secondment ends', 'END_OF_SECONDMENT', NULL, 'SECONDMENT_END_DATE', 'SECONDMENT', NULL, TRUE, 4),
 (0, now(), 'system', 'SERVICE_PERIOD_END', 'End of service period', 'සේවා කාලය අවසන් වීම', 'சேவைக் காலம் முடிவு',
  'Fixed service period ended (e.g. termination at the end of a term)', 'DISMISSAL', NULL, NULL, NULL, NULL, TRUE, 5),
 (0, now(), 'system', 'DEATH', 'Death', 'මරණය', 'மரணம்', 'Death of the post holder', 'DEATH', NULL, NULL, NULL, NULL, TRUE, 6),
 (0, now(), 'system', 'PROMOTION_OUT', 'Promotion out of the post', 'උසස් වීම', 'பதவி உயர்வு', 'Holder promoted to another post', NULL, 'PROMOTION', NULL, NULL, NULL, TRUE, 7),
 (0, now(), 'system', 'TRANSFER_OUT', 'Transfer out', 'ස්ථාන මාරුව', 'இடமாற்றம்', 'Holder transferred to another unit or out of the Commission',
  'TRANSFER_OUT', 'TRANSFER', NULL, NULL, NULL, TRUE, 8),
 (0, now(), 'system', 'CADRE_INCREASE', 'Cadre increase', 'සංවර්ග වැඩිවීම', 'பணியாளர் தொகுதி அதிகரிப்பு',
  'Approved Cadre Change Request adds posts (Forecast if effective in the future, else Open)', NULL, NULL, NULL, NULL, 'INCREASE', TRUE, 9),
 (0, now(), 'system', 'CADRE_REDUCTION', 'Cadre reduction', 'සංවර්ග අඩුවීම', 'பணியாளர் தொகுதி குறைப்பு',
  'Approved Cadre Change Request removes vacant posts (recorded as Frozen)', NULL, NULL, NULL, NULL, 'REDUCTION', TRUE, 10),
 (0, now(), 'system', 'MANUAL', 'Manual entry', 'අතින් ඇතුළත් කිරීම', 'கைமுறை பதிவு', 'Vacancy recorded by HR', NULL, NULL, NULL, NULL, NULL, TRUE, 11);

-- ------------------------------------------------------------------ REQ 5.2 / 5.3 alert rules and delivery log
CREATE TABLE rec_vacancy_alert_rule (
    id           BIGSERIAL PRIMARY KEY,
    version      BIGINT,
    created_at   TIMESTAMPTZ,
    created_by   VARCHAR(100),
    updated_at   TIMESTAMPTZ,
    updated_by   VARCHAR(100),
    name         VARCHAR(150)  NOT NULL,
    lead_days    INTEGER       NOT NULL CHECK (lead_days BETWEEN 0 AND 730),
    trigger_code VARCHAR(30),
    -- Comma-separated recipients: ROLE:<code>, USER:<username>, EMPLOYEE:<employee no>, DIVISION_HEAD
    recipients   VARCHAR(2000) NOT NULL,
    in_app       BOOLEAN       NOT NULL DEFAULT TRUE,
    email        BOOLEAN       NOT NULL DEFAULT TRUE,
    sms          BOOLEAN       NOT NULL DEFAULT FALSE,
    active       BOOLEAN       NOT NULL DEFAULT TRUE
);

INSERT INTO rec_vacancy_alert_rule (version, created_at, created_by, name, lead_days, trigger_code, recipients, in_app, email, sms, active) VALUES
 (0, now(), 'system', '90-day early alert', 90, NULL, 'ROLE:ADMIN_OFFICER,ROLE:HEAD_OF_HR,DIVISION_HEAD', TRUE, TRUE, FALSE, TRUE),
 (0, now(), 'system', '60-day early alert', 60, NULL, 'ROLE:ADMIN_OFFICER,ROLE:HEAD_OF_HR,DIVISION_HEAD', TRUE, TRUE, FALSE, TRUE),
 (0, now(), 'system', '30-day early alert', 30, NULL, 'ROLE:ADMIN_OFFICER,ROLE:HEAD_OF_HR,DIVISION_HEAD', TRUE, TRUE, FALSE, TRUE),
 (0, now(), 'system', 'Vacancy open', 0, NULL, 'ROLE:ADMIN_OFFICER,ROLE:HEAD_OF_HR,DIVISION_HEAD', TRUE, TRUE, FALSE, TRUE);

CREATE TABLE rec_vacancy_alert (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    vacancy_id          BIGINT       NOT NULL REFERENCES rec_vacancy (id),
    rule_id             BIGINT       NOT NULL REFERENCES rec_vacancy_alert_rule (id),
    level               VARCHAR(10)  NOT NULL,
    lead_days           INTEGER      NOT NULL,
    recipient_user_id   BIGINT       NOT NULL,
    recipient_name      VARCHAR(200) NOT NULL,
    recipient_basis     VARCHAR(120) NOT NULL,
    channel             VARCHAR(10)  NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL', 'SMS')),
    address             VARCHAR(200),
    notification_id     BIGINT,
    outbound_message_id BIGINT,
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('SENT', 'QUEUED', 'SKIPPED')),
    detail              VARCHAR(500),
    sent_at             TIMESTAMPTZ  NOT NULL,
    UNIQUE (vacancy_id, rule_id, recipient_user_id, channel)
);
CREATE INDEX ix_rec_vacancy_alert_vacancy ON rec_vacancy_alert (vacancy_id);

-- ------------------------------------------------------------------ REQ 8 Scheme of Recruitment
CREATE TABLE rec_sor_selection_method (
    id         BIGSERIAL PRIMARY KEY,
    version    BIGINT,
    created_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_at TIMESTAMPTZ,
    updated_by VARCHAR(100),
    code       VARCHAR(40)  NOT NULL UNIQUE,
    name_en    VARCHAR(200) NOT NULL,
    name_si    VARCHAR(200),
    name_ta    VARCHAR(200),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order INTEGER      NOT NULL DEFAULT 0
);

INSERT INTO rec_sor_selection_method (version, created_at, created_by, code, name_en, name_si, name_ta, sort_order) VALUES
 (0, now(), 'system', 'OPEN_COMPETITIVE_EXAM', 'Open competitive examination followed by a structured interview',
  'විවෘත තරග විභාගය සහ ව්‍යුහගත සම්මුඛ පරීක්ෂණය', 'திறந்த போட்டிப் பரீட்சை மற்றும் கட்டமைக்கப்பட்ட நேர்முகத் தேர்வு', 1),
 (0, now(), 'system', 'LIMITED_COMPETITIVE_EXAM', 'Limited competitive examination followed by a structured interview',
  'සීමිත තරග විභාගය සහ ව්‍යුහගත සම්මුඛ පරීක්ෂණය', 'மட்டுப்படுத்தப்பட்ட போட்டிப் பரீட்சை மற்றும் நேர்முகத் தேர்வு', 2),
 (0, now(), 'system', 'MERIT', 'Merit basis (qualifications and experience) with a structured interview',
  'කුසලතා පදනම (සුදුසුකම් සහ පළපුරුද්ද) සහ සම්මුඛ පරීක්ෂණය', 'தகைமை அடிப்படை (தகைமைகள் மற்றும் அனுபவம்) மற்றும் நேர்முகத் தேர்வு', 3),
 (0, now(), 'system', 'INTERVIEW', 'Structured interview only', 'ව්‍යුහගත සම්මුඛ පරීක්ෂණය පමණි', 'கட்டமைக்கப்பட்ட நேர்முகத் தேர்வு மட்டும்', 4);

CREATE TABLE rec_sor (
    id                         BIGSERIAL PRIMARY KEY,
    version                    BIGINT,
    created_at                 TIMESTAMPTZ,
    created_by                 VARCHAR(100),
    updated_at                 TIMESTAMPTZ,
    updated_by                 VARCHAR(100),
    series_id                  BIGINT,
    sor_no                     VARCHAR(40)  NOT NULL,
    version_no                 INTEGER      NOT NULL CHECK (version_no >= 1),
    designation_id             BIGINT       NOT NULL REFERENCES designation (id),
    grade                      VARCHAR(40),
    salary_code                VARCHAR(40),
    title                      VARCHAR(300) NOT NULL,
    service_reference          VARCHAR(500),
    educational_qualifications TEXT,
    experience                 TEXT,
    age_min                    INTEGER CHECK (age_min IS NULL OR age_min BETWEEN 16 AND 70),
    age_max                    INTEGER CHECK (age_max IS NULL OR age_max BETWEEN 16 AND 70),
    age_reference              VARCHAR(200),
    competencies               TEXT,
    selection_method           VARCHAR(40),
    selection_details          TEXT,
    other_conditions           TEXT,
    translations               TEXT,
    status                     VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'PENDING', 'RETURNED', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    locked                     BOOLEAN      NOT NULL DEFAULT FALSE,
    change_reason              VARCHAR(1000),
    workflow_instance_id       BIGINT,
    submitted_at               TIMESTAMPTZ,
    approved_at                TIMESTAMPTZ,
    approved_by                VARCHAR(100),
    UNIQUE (sor_no, version_no),
    CHECK (age_min IS NULL OR age_max IS NULL OR age_max >= age_min)
);
CREATE INDEX ix_rec_sor_designation ON rec_sor (designation_id, status);
CREATE INDEX ix_rec_sor_series ON rec_sor (series_id);
ALTER TABLE rec_vacancy ADD CONSTRAINT fk_rec_vacancy_sor FOREIGN KEY (sor_id) REFERENCES rec_sor (id);

-- Official SOR template per language (REQ 8.2). Lines starting with "# " are headings; {{placeholders}} are filled
-- from the SOR. Every edit is audited with previous and new text.
CREATE TABLE rec_sor_template (
    id         BIGSERIAL PRIMARY KEY,
    version    BIGINT,
    created_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_at TIMESTAMPTZ,
    updated_by VARCHAR(100),
    language   VARCHAR(2)   NOT NULL UNIQUE CHECK (language IN ('en', 'si', 'ta')),
    name       VARCHAR(200) NOT NULL,
    body       TEXT         NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE
);

INSERT INTO rec_sor_template (version, created_at, created_by, language, name, body) VALUES
 (0, now(), 'system', 'en', 'Scheme of Recruitment (English)', E'# {{orgName}}\n# SCHEME OF RECRUITMENT – {{title}}\nSOR No. {{sorNo}} (version {{versionNo}}) · Status: {{status}}\n\n# 1. Post\nDesignation: {{designation}}\nGrade: {{grade}}\nSalary code: {{salaryCode}}\nService / circular reference: {{serviceReference}}\n\n# 2. Educational qualifications\n{{qualifications}}\n\n# 3. Experience\n{{experience}}\n\n# 4. Age limits\nNot less than {{ageMin}} years and not more than {{ageMax}} years as at {{ageReference}}.\n\n# 5. Competencies\n{{competencies}}\n\n# 6. Method of selection\n{{selectionMethod}}\n{{selectionDetails}}\n\n# 7. Other conditions\n{{otherConditions}}\n\nApproved on {{approvedOn}}'),
 (0, now(), 'system', 'si', 'බඳවා ගැනීමේ පටිපාටිය (සිංහල)', E'# {{orgName}}\n# බඳවා ගැනීමේ පටිපාටිය – {{title}}\nබ.ප. අංකය {{sorNo}} (සංස්කරණය {{versionNo}}) · තත්ත්වය: {{status}}\n\n# 1. තනතුර\nතනතුර: {{designation}}\nශ්‍රේණිය: {{grade}}\nවැටුප් කේතය: {{salaryCode}}\nසේවා / චක්‍රලේඛ යොමුව: {{serviceReference}}\n\n# 2. අධ්‍යාපන සුදුසුකම්\n{{qualifications}}\n\n# 3. පළපුරුද්ද\n{{experience}}\n\n# 4. වයස් සීමාව\n{{ageReference}} දිනට වයස අවුරුදු {{ageMin}} ට නොඅඩු සහ අවුරුදු {{ageMax}} ට නොවැඩි විය යුතුය.\n\n# 5. නිපුණතා\n{{competencies}}\n\n# 6. තෝරා ගැනීමේ ක්‍රමය\n{{selectionMethod}}\n{{selectionDetails}}\n\n# 7. වෙනත් කොන්දේසි\n{{otherConditions}}\n\nඅනුමත කළ දිනය {{approvedOn}}'),
 (0, now(), 'system', 'ta', 'ஆட்சேர்ப்புத் திட்டம் (தமிழ்)', E'# {{orgName}}\n# ஆட்சேர்ப்புத் திட்டம் – {{title}}\nஆ.தி. இல. {{sorNo}} (பதிப்பு {{versionNo}}) · நிலை: {{status}}\n\n# 1. பதவி\nபதவி: {{designation}}\nதரம்: {{grade}}\nசம்பளக் குறியீடு: {{salaryCode}}\nசேவை / சுற்றறிக்கை குறிப்பு: {{serviceReference}}\n\n# 2. கல்வித் தகைமைகள்\n{{qualifications}}\n\n# 3. அனுபவம்\n{{experience}}\n\n# 4. வயதெல்லை\n{{ageReference}} அன்று {{ageMin}} வயதுக்குக் குறையாமலும் {{ageMax}} வயதுக்கு மேற்படாமலும் இருத்தல் வேண்டும்.\n\n# 5. தேர்ச்சிகள்\n{{competencies}}\n\n# 6. தெரிவு முறை\n{{selectionMethod}}\n{{selectionDetails}}\n\n# 7. ஏனைய நிபந்தனைகள்\n{{otherConditions}}\n\nஅங்கீகரிக்கப்பட்ட திகதி {{approvedOn}}');

INSERT INTO workflow_definition (version, created_at, created_by, process_code, name, description) VALUES
 (0, now(), 'system', 'SOR_APPROVAL', 'Scheme of Recruitment approval', 'Approval of a Scheme of Recruitment version before it can be used to publish a vacancy (REQ 8.3)')
ON CONFLICT (process_code) DO NOTHING;
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours)
SELECT id, 1, 'Head of HR approval', 'ROLE', 'HEAD_OF_HR', 72 FROM workflow_definition
WHERE process_code = 'SOR_APPROVAL' AND NOT EXISTS (SELECT 1 FROM workflow_stage s WHERE s.definition_id = workflow_definition.id);

-- ------------------------------------------------------------------ REQ 8.4-8.7 publishing
CREATE TABLE rec_publication (
    id              BIGSERIAL PRIMARY KEY,
    version         BIGINT,
    created_at      TIMESTAMPTZ,
    created_by      VARCHAR(100),
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(100),
    vacancy_id      BIGINT      NOT NULL REFERENCES rec_vacancy (id),
    sor_id          BIGINT      NOT NULL REFERENCES rec_sor (id),
    opening_date    DATE        NOT NULL,
    closing_date    DATE        NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('PUBLISHED', 'PARTIAL', 'FAILED', 'WITHDRAWN')),
    confirmed_at    TIMESTAMPTZ NOT NULL,
    confirmed_by    VARCHAR(100) NOT NULL,
    withdrawn_at    TIMESTAMPTZ,
    withdrawn_by    VARCHAR(100),
    withdraw_reason VARCHAR(1000),
    CHECK (closing_date >= opening_date)
);
CREATE INDEX ix_rec_publication_vacancy ON rec_publication (vacancy_id);

CREATE TABLE rec_publication_post (
    id              BIGSERIAL PRIMARY KEY,
    version         BIGINT,
    created_at      TIMESTAMPTZ,
    created_by      VARCHAR(100),
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(100),
    publication_id  BIGINT       NOT NULL REFERENCES rec_publication (id),
    channel         VARCHAR(30)  NOT NULL,
    title           VARCHAR(300) NOT NULL,
    content         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'POSTED', 'SIMULATED', 'FAILED', 'WITHDRAWN', 'WITHDRAW_MANUAL')),
    external_id     VARCHAR(200),
    post_url        VARCHAR(500),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000),
    posted_at       TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    UNIQUE (publication_id, channel)
);

-- Channel connectors (N-6). Disabled = posts are simulated and recorded as SIMULATED. Access tokens are never stored
-- here: they are supplied by the secrets vault / environment as app.publishing.credentials.<CODE>.
INSERT INTO integration_config (version, created_at, created_by, code, ref_id, name, purpose, priority, enabled, notes) VALUES
 (0, now(), 'system', 'WEBSITE', 'PUB-01', 'Official website', 'Publishes approved vacancies to the CIABOC website (REQ 8.4)', 'S', FALSE,
  'HTTPS endpoint; token from the secrets vault (app.publishing.credentials.WEBSITE)'),
 (0, now(), 'system', 'APPLICANT_PORTAL', 'PUB-02', 'Applicant portal', 'Publishes vacancies to the online applicant portal (REQ 5.5, 5.6)', 'S', FALSE,
  'The portal also reads /api/public/vacancies; token from the secrets vault (app.publishing.credentials.APPLICANT_PORTAL)'),
 (0, now(), 'system', 'FACEBOOK', 'PUB-03', 'Facebook page', 'Vacancy posts on the official Facebook page (REQ 8.4)', 'C', FALSE,
  'Page access token from the secrets vault (app.publishing.credentials.FACEBOOK)'),
 (0, now(), 'system', 'LINKEDIN', 'PUB-04', 'LinkedIn page', 'Vacancy posts on the official LinkedIn page (REQ 8.4)', 'C', FALSE,
  'Organization token from the secrets vault (app.publishing.credentials.LINKEDIN)'),
 (0, now(), 'system', 'X', 'PUB-05', 'X (Twitter)', 'Vacancy posts on the official X account (REQ 8.4)', 'C', FALSE,
  'Token from the secrets vault (app.publishing.credentials.X)')
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------------ REQ 9 examination coordination
CREATE TABLE rec_exam_org_contact (
    id                 BIGSERIAL PRIMARY KEY,
    version            BIGINT,
    created_at         TIMESTAMPTZ,
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    institution        VARCHAR(200) NOT NULL,
    department         VARCHAR(200),
    contact_person     VARCHAR(200) NOT NULL,
    contact_title      VARCHAR(150),
    email              VARCHAR(200) NOT NULL,
    phone              VARCHAR(30),
    address            VARCHAR(500),
    preferred_language VARCHAR(3)   NOT NULL CHECK (preferred_language IN ('en', 'si', 'ta', 'all')),
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    last_used_at       TIMESTAMPTZ
);

CREATE TABLE rec_exam_email_template (
    id         BIGSERIAL PRIMARY KEY,
    version    BIGINT,
    created_at TIMESTAMPTZ,
    created_by VARCHAR(100),
    updated_at TIMESTAMPTZ,
    updated_by VARCHAR(100),
    code       VARCHAR(40)  NOT NULL,
    language   VARCHAR(2)   NOT NULL CHECK (language IN ('en', 'si', 'ta')),
    name       VARCHAR(200) NOT NULL,
    subject    VARCHAR(300) NOT NULL,
    body       TEXT         NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (code, language)
);

INSERT INTO rec_exam_email_template (version, created_at, created_by, code, language, name, subject, body) VALUES
 (0, now(), 'system', 'EXAM_COORDINATION', 'en', 'Request for examination support',
  'Request for support – {{examName}} on {{examDate}}',
  E'Dear {{contactName}},\n\n{{institution}}{{departmentLine}}\n\nThe {{orgName}} will conduct the {{examName}} on {{examDate}} from {{examTime}}.\n\nVenue: {{venue}}\nNumber of candidates: {{candidateCount}}\n\nWe kindly request your support as follows:\n{{supportRequested}}\n\nWe would be grateful for your confirmation at your earliest convenience.\n\nYours faithfully,\n\n{{signatory}}\n{{signatoryTitle}}'),
 (0, now(), 'system', 'EXAM_COORDINATION', 'si', 'විභාග සහාය ඉල්ලීම',
  '{{examName}} – {{examDate}} සඳහා සහාය ඉල්ලීම',
  E'හිතවත් {{contactName}} මහත්මයාණෙනි / මහත්මියනි,\n\n{{institution}}{{departmentLine}}\n\n{{orgName}} විසින් {{examName}} {{examDate}} දින {{examTime}} පැවැත්වීමට නියමිතය.\n\nවිභාග ස්ථානය: {{venue}}\nඅපේක්ෂකයින් සංඛ්‍යාව: {{candidateCount}}\n\nපහත සඳහන් සහාය ලබා දෙන මෙන් කාරුණිකව ඉල්ලා සිටිමු:\n{{supportRequested}}\n\nඔබගේ තහවුරු කිරීම ඉක්මනින් ලබා දෙන්නේ නම් කෘතඥ වෙමු.\n\nමෙයට විශ්වාසී,\n\n{{signatory}}\n{{signatoryTitle}}'),
 (0, now(), 'system', 'EXAM_COORDINATION', 'ta', 'பரீட்சை ஒத்துழைப்புக் கோரிக்கை',
  '{{examName}} – {{examDate}} க்கான ஒத்துழைப்புக் கோரிக்கை',
  E'அன்புள்ள {{contactName}},\n\n{{institution}}{{departmentLine}}\n\n{{orgName}} {{examName}} பரீட்சையை {{examDate}} அன்று {{examTime}} நடத்தவுள்ளது.\n\nபரீட்சை நிலையம்: {{venue}}\nபரீட்சார்த்திகளின் எண்ணிக்கை: {{candidateCount}}\n\nபின்வரும் ஒத்துழைப்பை வழங்குமாறு தயவுடன் கேட்டுக்கொள்கிறோம்:\n{{supportRequested}}\n\nதங்களின் உறுதிப்படுத்தலை விரைவில் எதிர்பார்க்கிறோம்.\n\nஇங்ஙனம்,\n\n{{signatory}}\n{{signatoryTitle}}');

CREATE TABLE rec_exam_schedule (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100),
    reference_no      VARCHAR(40)  NOT NULL UNIQUE,
    exam_name         VARCHAR(300) NOT NULL,
    vacancy_id        BIGINT REFERENCES rec_vacancy (id),
    exam_id           BIGINT REFERENCES rec_exam (id),
    exam_date         DATE         NOT NULL,
    start_time        TIME,
    end_time          TIME,
    venues            TEXT         NOT NULL,
    candidate_count   INTEGER      NOT NULL CHECK (candidate_count > 0),
    support_requested TEXT         NOT NULL,
    signatory_name    VARCHAR(200) NOT NULL,
    signatory_title   VARCHAR(200),
    template_code     VARCHAR(40)  NOT NULL,
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'PARTLY_SENT', 'SENT', 'CANCELLED'))
);

CREATE TABLE rec_exam_email (
    id                  BIGSERIAL PRIMARY KEY,
    version             BIGINT,
    created_at          TIMESTAMPTZ,
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    schedule_id         BIGINT       NOT NULL REFERENCES rec_exam_schedule (id),
    contact_id          BIGINT       NOT NULL REFERENCES rec_exam_org_contact (id),
    venue               VARCHAR(300),
    language            VARCHAR(3)   NOT NULL,
    recipient_email     VARCHAR(200) NOT NULL,
    subject             VARCHAR(300) NOT NULL,
    body                TEXT         NOT NULL,
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'QUEUED', 'CANCELLED')),
    approved_by         VARCHAR(100),
    approved_at         TIMESTAMPTZ,
    outbound_message_id BIGINT,
    queued_at           TIMESTAMPTZ,
    UNIQUE (schedule_id, contact_id)
);

-- ------------------------------------------------------------------ notification template + settings
INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'VACANCY_ALERT', 'Early / open vacancy alert (REQ 5.2, 5.3); e-mail and SMS are queued by the vacancy module',
  'Vacancy alert ({{level}}): {{post}}', 'පුරප්පාඩු දැනුම්දීම ({{level}}): {{post}}', 'வெற்றிட எச்சரிக்கை ({{level}}): {{post}}',
  'Vacancy {{vacancyRef}} – {{designation}}, {{unit}} ({{trigger}}) is expected on {{expectedDate}}. Status: {{status}}.',
  'පුරප්පාඩුව {{vacancyRef}} – {{designation}}, {{unit}} ({{trigger}}) {{expectedDate}} දින අපේක්ෂිතය. තත්ත්වය: {{status}}.',
  'வெற்றிடம் {{vacancyRef}} – {{designation}}, {{unit}} ({{trigger}}) {{expectedDate}} அன்று எதிர்பார்க்கப்படுகிறது. நிலை: {{status}}.', FALSE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('vacancy.alerts.enabled', 'true', 'RECRUITMENT', 'BOOLEAN', 'Send early vacancy alerts at the configured lead times (REQ 5.2)'),
 ('vacancy.forecast-horizon-days', '365', 'RECRUITMENT', 'INT', 'Forecast vacancies from retirement / secondment / contract end dates this many days ahead (REQ 5.1)'),
 ('vacancy.fill-on-acting', 'false', 'RECRUITMENT', 'BOOLEAN', 'Treat an acting placement into the post as filling the vacancy (REQ 5.7)'),
 ('vacancy.auto-link-sor', 'true', 'RECRUITMENT', 'BOOLEAN', 'Link the current approved SOR of the designation to new vacancies automatically'),
 ('publish.channels', 'WEBSITE,APPLICANT_PORTAL,FACEBOOK,LINKEDIN,X', 'RECRUITMENT', 'STRING', 'Publishing channels offered to the Publisher (integration codes, REQ 8.4)'),
 ('publish.can-update', 'WEBSITE,APPLICANT_PORTAL,FACEBOOK,LINKEDIN', 'RECRUITMENT', 'STRING', 'Channels whose published posts may be edited (REQ 8.7)'),
 ('publish.can-withdraw', 'WEBSITE,APPLICANT_PORTAL,FACEBOOK,LINKEDIN,X', 'RECRUITMENT', 'STRING', 'Channels whose published posts may be withdrawn by the system (REQ 8.7)'),
 ('publish.max-length.X', '280', 'RECRUITMENT', 'INT', 'Maximum characters of an X post'),
 ('publish.max-length.FACEBOOK', '5000', 'RECRUITMENT', 'INT', 'Maximum characters of a Facebook post'),
 ('publish.max-length.LINKEDIN', '3000', 'RECRUITMENT', 'INT', 'Maximum characters of a LinkedIn post'),
 ('publish.public-base-url', 'https://www.ciaboc.gov.lk', 'RECRUITMENT', 'STRING', 'Public site used for vacancy links in posts'),
 ('publish.template.WEBSITE', E'{{title}} – {{posts}} post(s)\nDivision: {{unit}} · Grade: {{grade}} · Salary code: {{salaryCode}}\n\nEducational qualifications:\n{{qualifications}}\n\nExperience:\n{{experience}}\n\nAge: {{ageLimits}}\nMethod of selection: {{selectionMethod}}\n\nApplications are accepted from {{openingDate}} to {{closingDate}}. Reference {{vacancyRef}} (SOR {{sorNo}} v{{sorVersion}}).\n{{link}}',
  'RECRUITMENT', 'STRING', 'Website post built from the SOR (placeholders in {{ }})'),
 ('publish.template.APPLICANT_PORTAL', E'{{title}} – {{posts}} post(s)\nDivision: {{unit}} · Grade: {{grade}} · Salary code: {{salaryCode}}\n\nEducational qualifications:\n{{qualifications}}\n\nExperience:\n{{experience}}\n\nAge: {{ageLimits}}\nMethod of selection: {{selectionMethod}}\n\nApply online from {{openingDate}} to {{closingDate}} quoting {{vacancyRef}}.\n{{link}}',
  'RECRUITMENT', 'STRING', 'Applicant portal post built from the SOR'),
 ('publish.template.FACEBOOK', E'Vacancy: {{title}} ({{posts}} post(s)) – {{unit}}\nQualifications: {{qualificationsShort}}\nAge: {{ageLimits}}\nClosing date: {{closingDate}}. Reference {{vacancyRef}}.\nDetails and applications: {{link}}',
  'RECRUITMENT', 'STRING', 'Facebook post built from the SOR'),
 ('publish.template.LINKEDIN', E'{{orgName}} is recruiting: {{title}} ({{posts}} post(s)), {{unit}}.\nQualifications: {{qualificationsShort}}\nClosing date: {{closingDate}}. Reference {{vacancyRef}}.\n{{link}}',
  'RECRUITMENT', 'STRING', 'LinkedIn post built from the SOR'),
 ('publish.template.X', 'Vacancy: {{title}} ({{posts}}) – apply by {{closingDate}}. Ref {{vacancyRef}} {{link}}', 'RECRUITMENT', 'STRING', 'X post built from the SOR'),
 ('exam.email.auto-send', 'false', 'RECRUITMENT', 'BOOLEAN', 'Queue an examination e-mail as soon as it is approved (REQ 9.4)'),
 ('exam.email.default-template', 'EXAM_COORDINATION', 'RECRUITMENT', 'STRING', 'Template code used for new examination schedules')
ON CONFLICT (setting_key) DO NOTHING;

-- The Publisher sees the SOR and publication screens (REC_VIEW already granted in V52); the Administration Officer
-- prepares SORs and exam e-mails (SOR_MANAGE granted in V52).
