-- =====================================================================================================
-- Establishment & Cadre spec v2.1: proposed CIABOC structure (section 2), 28 designations (2.3), the 971-post
-- cadre loaded from the reporting chains (2.2), new roles (2.5) and approval chains.
-- All of this is data: it can be changed through Cadre Change Requests and Master Data (G-1).
-- Grades, position categories and salary codes are placeholders pending official values (open item 3).
-- =====================================================================================================

-- ------------------------------------------------------------------ schema extensions
ALTER TABLE org_unit ADD COLUMN location VARCHAR(20);
ALTER TABLE org_unit ADD COLUMN head_title VARCHAR(200);
ALTER TABLE org_unit ADD COLUMN head_position_code VARCHAR(40);

ALTER TABLE designation ADD COLUMN level INTEGER;
ALTER TABLE designation ADD COLUMN salary_code VARCHAR(40);
ALTER TABLE designation ADD COLUMN dying_cadre BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE designation ADD COLUMN statutory BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE designation ADD COLUMN legal_basis VARCHAR(1000);

-- Post status: ACTIVE (vacant or filled), FROZEN (vacant post removed from cadre), TO_BE_ABOLISHED (filled excess).
ALTER TABLE org_position ADD COLUMN post_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
    CHECK (post_status IN ('ACTIVE', 'FROZEN', 'TO_BE_ABOLISHED', 'ABOLISHED'));
ALTER TABLE org_position ADD COLUMN change_id BIGINT;

-- Acting appointments count towards "Acting", confirmed ones towards "Filled" (spec 1.4).
ALTER TABLE employee_assignment ADD COLUMN acting BOOLEAN NOT NULL DEFAULT FALSE;

-- Cadre Change Request details (REQ 6.2, 6.4).
ALTER TABLE establishment_change ADD COLUMN approving_authority VARCHAR(200);
ALTER TABLE establishment_change ADD COLUMN reference_no VARCHAR(100);
ALTER TABLE establishment_change ADD COLUMN attachment_file_id BIGINT REFERENCES stored_file (id);
ALTER TABLE establishment_change ADD COLUMN exception_reason VARCHAR(1000);
ALTER TABLE establishment_change ADD COLUMN cadre_old_qty INTEGER;

-- Nested position categories (REQ 7.1).
ALTER TABLE position_category ADD COLUMN parent_id BIGINT REFERENCES position_category (id);

-- Control totals from the source cadre tables, used by the reconciliation view (REQ 6.11).
CREATE TABLE cadre_control_total (
    id             BIGSERIAL PRIMARY KEY,
    scope          VARCHAR(20)  NOT NULL CHECK (scope IN ('UNIT', 'DESIGNATION', 'ORGANIZATION')),
    ref_code       VARCHAR(40)  NOT NULL,
    label          VARCHAR(200) NOT NULL,
    approved_total INTEGER      NOT NULL,
    source         VARCHAR(200),
    UNIQUE (scope, ref_code)
);

INSERT INTO master_data_item (version, created_at, created_by, category, code, name_en, name_si, name_ta, sort_order, effective_from) VALUES
 (0, now(), 'system', 'LOCATION', 'HEAD_OFFICE', 'Head Office', 'ප්‍රධාන කාර්යාලය', 'தலைமை அலுவலகம்', 1, '2000-01-01'),
 (0, now(), 'system', 'LOCATION', 'REGIONAL', 'Regional', 'ප්‍රාදේශීය', 'பிராந்திய', 2, '2000-01-01')
ON CONFLICT (category, code) DO NOTHING;

-- ------------------------------------------------------------------ organizational units (section 2.1)
INSERT INTO org_unit (version, created_at, created_by, code, name, unit_type, parent_code, location, head_title, sort_order, effective_from) VALUES
 (0, now(), 'system', 'DG_OFFICE', 'DG & Communications', 'DIVISION', NULL, NULL, 'Director General', 1, '2020-01-01'),
 (0, now(), 'system', 'ADDL_DG', 'Office of the Additional DG', 'DIVISION', 'DG_OFFICE', NULL, 'Additional Director General', 2, '2020-01-01'),
 (0, now(), 'system', 'LEGAL', 'Legal (Law Enforcement)', 'DIVISION', 'ADDL_DG', NULL, 'Senior DDG (Law Enforcement)', 1, '2020-01-01'),
 (0, now(), 'system', 'LEGAL-HO', 'Legal – Head Office', 'DEPARTMENT', 'LEGAL', 'HEAD_OFFICE', 'Deputy Director General', 1, '2020-01-01'),
 (0, now(), 'system', 'LEGAL-REG', 'Legal – Regional', 'DEPARTMENT', 'LEGAL', 'REGIONAL', 'Deputy Director General', 2, '2020-01-01'),
 (0, now(), 'system', 'INV', 'Investigation', 'DIVISION', 'DG_OFFICE', NULL, 'Director (Investigation)', 3, '2020-01-01'),
 (0, now(), 'system', 'INV-HO', 'Investigation – Head Office', 'DEPARTMENT', 'INV', 'HEAD_OFFICE', 'Deputy Director / Assistant Director', 1, '2020-01-01'),
 (0, now(), 'system', 'INV-REG', 'Investigation – Regional', 'DEPARTMENT', 'INV', 'REGIONAL', 'Deputy Director / Assistant Director', 2, '2020-01-01'),
 (0, now(), 'system', 'CMD', 'Complaint Management', 'DIVISION', 'DG_OFFICE', NULL, 'Director (Complaint Management)', 4, '2020-01-01'),
 (0, now(), 'system', 'PREV_AL', 'Prevention and Assets & Liabilities', 'DIVISION', 'DG_OFFICE', NULL, 'Senior Director (Prevention and A&L)', 5, '2020-01-01'),
 (0, now(), 'system', 'PREV', 'Prevention', 'DEPARTMENT', 'PREV_AL', NULL, 'Director (Prevention)', 1, '2020-01-01'),
 (0, now(), 'system', 'ALD', 'Assets and Liabilities', 'DEPARTMENT', 'PREV_AL', NULL, 'Director (A&L)', 2, '2020-01-01'),
 (0, now(), 'system', 'CORP', 'Corporate Affairs', 'DIVISION', 'DG_OFFICE', NULL, 'Senior Director (Corporate Affairs)', 6, '2020-01-01'),
 (0, now(), 'system', 'ADMIN', 'Administration', 'DEPARTMENT', 'CORP', NULL, 'Director (Administration)', 1, '2020-01-01'),
 (0, now(), 'system', 'FIN', 'Finance', 'DEPARTMENT', 'CORP', NULL, 'Director (Finance)', 2, '2020-01-01'),
 (0, now(), 'system', 'PT', 'Planning & Training', 'DEPARTMENT', 'CORP', NULL, 'Director (Planning & Training)', 3, '2020-01-01'),
 (0, now(), 'system', 'IT', 'Information Technology', 'DEPARTMENT', 'CORP', NULL, 'Director (IT)', 4, '2020-01-01'),
 (0, now(), 'system', 'IRR', 'International Relations & Research', 'DEPARTMENT', 'CORP', NULL, 'Director (IR&R)', 5, '2020-01-01'),
 (0, now(), 'system', 'SPECIAL', 'Special Unit', 'DIVISION', 'DG_OFFICE', NULL, 'DDG (Special)', 7, '2020-01-01'),
 (0, now(), 'system', 'SPECIAL-CASES', 'Special Cases', 'DEPARTMENT', 'SPECIAL', NULL, 'Senior Assistant Director General', 1, '2020-01-01'),
 (0, now(), 'system', 'SPECIAL-EXPERTS', 'Experts', 'DEPARTMENT', 'SPECIAL', NULL, 'Assistant Director General', 2, '2020-01-01'),
 (0, now(), 'system', 'IA', 'Internal Audit', 'DIVISION', 'DG_OFFICE', NULL, 'Chief Internal Auditor', 8, '2020-01-01'),
 (0, now(), 'system', 'INTEL', 'Intelligence', 'DIVISION', 'DG_OFFICE', NULL, 'DD/AD (Intelligence)', 9, '2020-01-01')
ON CONFLICT (code, effective_from) DO NOTHING;

-- ------------------------------------------------------------------ designations (section 2.3) + statutory DG (2.4)
INSERT INTO designation (version, created_at, created_by, code, name, level, grade, service_category, dying_cadre, statutory, legal_basis, description, active) VALUES
 (0, now(), 'system', 'DG', 'Director General', 0, 'SL4', 'COMMISSION_SERVICE', FALSE, TRUE,
  'Anti-Corruption Act, No. 9 of 2023, s.17(1): appointed by the President on the recommendation of the Constitutional Council after consulting the members of the Commission; Chief Executive Officer and Chief Accounting Officer; term of 5 years; when vacant the Additional Director General acts under s.25(2); reports to the Commission.',
  'Statutory post; not counted in the 971-post cadre', TRUE),
 (0, now(), 'system', 'ADDL_DG', 'Additional Director General', 1, 'SL4', 'COMMISSION_SERVICE', FALSE, FALSE, 'Acts as Director General when the post is vacant (Anti-Corruption Act No. 9 of 2023, s.25(2))', NULL, TRUE),
 (0, now(), 'system', 'SDDG', 'Senior Deputy Director General', 2, 'SL4', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'SD', 'Senior Director', 2, 'SL4', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'DDG', 'Deputy Director General', 3, 'SL4', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'DIR', 'Director', 3, 'SL3', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'CIA', 'Chief Internal Auditor', 3, 'SL3', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'SADG', 'Senior Assistant Director General', 4, 'SL3', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'ADG', 'Assistant Director General', 4, 'SL3', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'DDAD', 'Deputy Director / Assistant Director', 5, 'SL1', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'RO', 'Research Officer', 5, 'SL1', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'IA', 'Internal Auditor', 5, 'SL1', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'SIO', 'Senior Investigation Officer', 6, 'MN7', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'SPO', 'Senior Prevention Officer', 6, 'MN7', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'AO', 'Administrative Officer', 6, 'MN7', 'COMBINED_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'ITO', 'IT Officer', 7, 'MN4', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'TO', 'Transport Officer / Technical Officer', 7, 'MN4', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'TRANS', 'Translator cum Interpreter', 7, 'MN4', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'LIB', 'Librarian', 7, 'MN4', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'LA', 'Legal Assistant', 8, 'MN4', 'COMMISSION_SERVICE', TRUE, FALSE, NULL, 'Dying cadre: no fresh recruitment; posts lapse as holders leave', TRUE),
 (0, now(), 'system', 'IO', 'Investigation Officer', 9, 'MN4', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'PO', 'Prevention Officer', 9, 'MN4', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'ICTA', 'ICT Assistant', 10, 'MN2', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'ALIB', 'Assistant Librarian', 10, 'MN2', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'ACMO', 'Anti-Corruption Management Officer', 10, 'MN2', 'COMBINED_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'TYP', 'Typist cum Stenographer', 11, 'MN1', 'COMBINED_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'COMM', 'Communication Assistant', 11, 'MN1', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'DRV', 'Driver', 12, 'PL1', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE),
 (0, now(), 'system', 'OA', 'Office Assistant', 13, 'PL1', 'COMMISSION_SERVICE', FALSE, FALSE, NULL, NULL, TRUE)
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, level = EXCLUDED.level, grade = EXCLUDED.grade,
    dying_cadre = EXCLUDED.dying_cadre, statutory = EXCLUDED.statutory, legal_basis = EXCLUDED.legal_basis,
    description = EXCLUDED.description, active = TRUE;

-- ------------------------------------------------------------------ cadre matrix (unit x designation)
-- Built from the reporting chains in section 2.2. Where the chains and the cadre tables differ, the
-- reconciliation view shows the variance for verification against the source table (open item 1).
CREATE TEMP TABLE tmp_cadre (unit VARCHAR(40), des VARCHAR(40), qty INT) ON COMMIT DROP;
INSERT INTO tmp_cadre VALUES
 ('DG_OFFICE','DG',1), ('DG_OFFICE','OA',12),
 ('ADDL_DG','ADDL_DG',1),
 ('LEGAL','SDDG',1),
 ('LEGAL-HO','DDG',4), ('LEGAL-HO','SADG',5), ('LEGAL-HO','ADG',11), ('LEGAL-HO','AO',2), ('LEGAL-HO','LA',7),
 ('LEGAL-HO','ACMO',12), ('LEGAL-HO','TYP',6), ('LEGAL-HO','COMM',2), ('LEGAL-HO','OA',20),
 ('LEGAL-REG','DDG',1), ('LEGAL-REG','SADG',6), ('LEGAL-REG','ADG',31), ('LEGAL-REG','ACMO',12), ('LEGAL-REG','TYP',4),
 ('LEGAL-REG','COMM',2), ('LEGAL-REG','OA',15),
 ('INV','DIR',1),
 ('INV-HO','DDAD',8), ('INV-HO','SIO',15), ('INV-HO','IO',57), ('INV-HO','ACMO',6), ('INV-HO','TYP',7), ('INV-HO','COMM',2), ('INV-HO','OA',6),
 ('INV-REG','DDAD',8), ('INV-REG','SIO',24), ('INV-REG','IO',240), ('INV-REG','ACMO',20), ('INV-REG','TYP',22), ('INV-REG','DRV',24), ('INV-REG','OA',30),
 ('CMD','DIR',1), ('CMD','DDAD',3), ('CMD','AO',1), ('CMD','ACMO',10), ('CMD','COMM',2), ('CMD','OA',4),
 ('PREV_AL','SD',1),
 ('PREV','DIR',1), ('PREV','DDAD',4), ('PREV','SPO',12), ('PREV','ITO',1), ('PREV','PO',50), ('PREV','ACMO',4), ('PREV','ICTA',1), ('PREV','OA',5),
 ('ALD','DIR',1), ('ALD','DDAD',2), ('ALD','ITO',1), ('ALD','ACMO',4), ('ALD','ICTA',1), ('ALD','OA',3),
 ('CORP','SD',1),
 ('ADMIN','DIR',1), ('ADMIN','DDAD',4), ('ADMIN','AO',1), ('ADMIN','TO',2), ('ADMIN','TRANS',6), ('ADMIN','LIB',1), ('ADMIN','ALIB',1),
 ('ADMIN','ACMO',21), ('ADMIN','TYP',2), ('ADMIN','COMM',2), ('ADMIN','DRV',45),
 ('FIN','DIR',1), ('FIN','DDAD',3), ('FIN','ACMO',14), ('FIN','OA',4),
 ('PT','DIR',1), ('PT','DDAD',2), ('PT','ACMO',4), ('PT','OA',4),
 ('IT','DIR',1), ('IT','DDAD',1), ('IT','ITO',2), ('IT','ICTA',4), ('IT','OA',2),
 ('IRR','DIR',1), ('IRR','DDAD',2), ('IRR','RO',3), ('IRR','ACMO',4), ('IRR','OA',4),
 ('SPECIAL','DDG',1),
 ('SPECIAL-CASES','SADG',5), ('SPECIAL-CASES','ADG',11), ('SPECIAL-CASES','DDAD',7), ('SPECIAL-CASES','SIO',6), ('SPECIAL-CASES','IO',30),
 ('SPECIAL-CASES','TYP',3), ('SPECIAL-CASES','OA',3),
 ('SPECIAL-EXPERTS','ADG',5),
 ('IA','CIA',1), ('IA','IA',1), ('IA','ACMO',3), ('IA','OA',3),
 ('INTEL','DDAD',1), ('INTEL','SIO',2), ('INTEL','IO',10), ('INTEL','TYP',1), ('INTEL','OA',2);

INSERT INTO cadre_allocation (version, created_at, created_by, designation_id, org_unit_code, approved_qty, effective_from)
SELECT 0, now(), 'system', d.id, t.unit, t.qty, '2020-01-01'
FROM tmp_cadre t JOIN designation d ON d.code = t.des
WHERE NOT EXISTS (SELECT 1 FROM cadre_allocation c WHERE c.designation_id = d.id AND c.org_unit_code = t.unit
                  AND c.effective_from <= CURRENT_DATE AND (c.effective_to IS NULL OR c.effective_to > CURRENT_DATE));

-- One post record (position ID) per approved post (REQ 6.5): <unit>/<designation>/<nnn>.
INSERT INTO org_position (version, created_at, created_by, code, title, designation_id, org_unit_code, active, activated_on, post_status)
SELECT 0, now(), 'system', t.unit || '/' || t.des || '/' || lpad(g::text, 3, '0'), d.name, d.id, t.unit, TRUE, '2020-01-01', 'ACTIVE'
FROM tmp_cadre t JOIN designation d ON d.code = t.des, generate_series(1, t.qty) g
ON CONFLICT (code) DO NOTHING;

-- Head of each unit = first post of the head designation; heads report to the parent unit's head (REQ 10.3).
CREATE TEMP TABLE tmp_head (unit VARCHAR(40), des VARCHAR(40)) ON COMMIT DROP;
INSERT INTO tmp_head VALUES ('DG_OFFICE','DG'), ('ADDL_DG','ADDL_DG'), ('LEGAL','SDDG'), ('LEGAL-HO','DDG'), ('LEGAL-REG','DDG'),
 ('INV','DIR'), ('INV-HO','DDAD'), ('INV-REG','DDAD'), ('CMD','DIR'), ('PREV_AL','SD'), ('PREV','DIR'), ('ALD','DIR'), ('CORP','SD'),
 ('ADMIN','DIR'), ('FIN','DIR'), ('PT','DIR'), ('IT','DIR'), ('IRR','DIR'), ('SPECIAL','DDG'), ('SPECIAL-CASES','SADG'),
 ('SPECIAL-EXPERTS','ADG'), ('IA','CIA'), ('INTEL','DDAD');
UPDATE org_unit u SET head_position_code = h.unit || '/' || h.des || '/001'
FROM tmp_head h WHERE u.code = h.unit AND u.effective_to IS NULL;
-- Every post reports to its unit head; unit heads report to the parent unit's head.
UPDATE org_position p SET reports_to_position_code = u.head_position_code
FROM org_unit u WHERE u.code = p.org_unit_code AND u.effective_to IS NULL AND p.code <> u.head_position_code
  AND p.reports_to_position_code IS NULL AND u.head_position_code IS NOT NULL;
UPDATE org_position p SET reports_to_position_code = parent.head_position_code
FROM org_unit u JOIN org_unit parent ON parent.code = u.parent_code AND parent.effective_to IS NULL
WHERE u.effective_to IS NULL AND p.code = u.head_position_code AND p.reports_to_position_code IS NULL;

-- ------------------------------------------------------------------ control totals (sections 2.1 and 2.3)
INSERT INTO cadre_control_total (scope, ref_code, label, approved_total, source) VALUES
 ('ORGANIZATION', 'CIABOC', 'Total proposed cadre (excluding the Director General)', 971, 'Spec v2.1 §2.1 / §2.3'),
 ('UNIT', 'LEGAL-HO', 'Legal (Law Enforcement) – Head Office', 69, '§2.1'), ('UNIT', 'LEGAL-REG', 'Legal (Law Enforcement) – Regional', 71, '§2.1'),
 ('UNIT', 'INV-HO', 'Investigation – Head Office', 102, '§2.1'), ('UNIT', 'INV-REG', 'Investigation – Regional', 368, '§2.1'),
 ('UNIT', 'CMD', 'Complaint Management', 21, '§2.1'), ('UNIT', 'PREV', 'Prevention', 78, '§2.1'), ('UNIT', 'ALD', 'Assets and Liabilities', 12, '§2.1'),
 ('UNIT', 'ADMIN', 'Corporate Affairs – Administration', 86, '§2.1'), ('UNIT', 'FIN', 'Corporate Affairs – Finance', 22, '§2.1'),
 ('UNIT', 'PT', 'Corporate Affairs – Planning & Training', 11, '§2.1'), ('UNIT', 'IT', 'Corporate Affairs – IT', 10, '§2.1'),
 ('UNIT', 'IRR', 'Corporate Affairs – International Relations & Research', 14, '§2.1'),
 ('UNIT', 'SPECIAL-CASES', 'Special Unit – Special Cases', 66, '§2.1'), ('UNIT', 'SPECIAL-EXPERTS', 'Special Unit – Experts', 5, '§2.1'),
 ('UNIT', 'IA', 'Internal Audit', 8, '§2.1'), ('UNIT', 'INTEL', 'Intelligence', 16, '§2.1'), ('UNIT', 'DG_OFFICE', 'DG & Communications', 12, '§2.1'),
 ('DESIGNATION', 'ADDL_DG', 'Additional DG', 1, '§2.3'), ('DESIGNATION', 'SDDG', 'Senior DDG', 1, '§2.3'), ('DESIGNATION', 'SD', 'Senior Director', 2, '§2.3'),
 ('DESIGNATION', 'DDG', 'Deputy Director General', 6, '§2.3'), ('DESIGNATION', 'DIR', 'Director', 9, '§2.3'), ('DESIGNATION', 'CIA', 'Chief Internal Auditor', 1, '§2.3'),
 ('DESIGNATION', 'SADG', 'Senior Assistant Director General', 16, '§2.3'), ('DESIGNATION', 'ADG', 'Assistant Director General', 58, '§2.3'),
 ('DESIGNATION', 'DDAD', 'Deputy Director / Assistant Director', 45, '§2.3'), ('DESIGNATION', 'RO', 'Research Officer', 3, '§2.3'),
 ('DESIGNATION', 'IA', 'Internal Auditor', 1, '§2.3'), ('DESIGNATION', 'SIO', 'Senior Investigation Officer', 47, '§2.3'),
 ('DESIGNATION', 'SPO', 'Senior Prevention Officer', 12, '§2.3'), ('DESIGNATION', 'AO', 'Administrative Officer', 4, '§2.3'),
 ('DESIGNATION', 'ITO', 'IT Officer', 4, '§2.3'), ('DESIGNATION', 'TO', 'Transport Officer / Technical Officer', 2, '§2.3'),
 ('DESIGNATION', 'TRANS', 'Translator cum Interpreter', 6, '§2.3'), ('DESIGNATION', 'LIB', 'Librarian', 1, '§2.3'),
 ('DESIGNATION', 'LA', 'Legal Assistant (dying cadre)', 7, '§2.3'), ('DESIGNATION', 'IO', 'Investigation Officer', 337, '§2.3'),
 ('DESIGNATION', 'PO', 'Prevention Officer', 50, '§2.3'), ('DESIGNATION', 'ICTA', 'ICT Assistant', 6, '§2.3'),
 ('DESIGNATION', 'ALIB', 'Assistant Librarian', 1, '§2.3'), ('DESIGNATION', 'ACMO', 'Anti-Corruption Management Officer', 113, '§2.3'),
 ('DESIGNATION', 'TYP', 'Typist cum Stenographer', 45, '§2.3'), ('DESIGNATION', 'COMM', 'Communication Assistant', 7, '§2.3'),
 ('DESIGNATION', 'DRV', 'Driver', 69, '§2.3'), ('DESIGNATION', 'OA', 'Office Assistant', 117, '§2.3')
ON CONFLICT (scope, ref_code) DO NOTHING;

-- ------------------------------------------------------------------ position categories (REQ 7.1; membership to be confirmed)
INSERT INTO position_category (version, created_at, created_by, code, name, name_si, name_ta, color, sort_order, closed) VALUES
 (0, now(), 'system', 'EXECUTIVE', 'Executive', 'විධායක', 'நிறைவேற்று', '#0B1F4B', 1, FALSE),
 (0, now(), 'system', 'LEGAL', 'Legal', 'නීති', 'சட்டம்', '#1F8A70', 2, FALSE),
 (0, now(), 'system', 'INVESTIGATION', 'Investigation', 'විමර්ශන', 'விசாரணை', '#3B5BDB', 3, FALSE),
 (0, now(), 'system', 'MGMT_ASSISTANT', 'Management Assistant', 'කළමනාකරණ සහකාර', 'முகாமைத்துவ உதவியாளர்', '#7B3FE4', 4, FALSE),
 (0, now(), 'system', 'TECHNICAL', 'Technical & Library', 'තාක්ෂණික සහ පුස්තකාල', 'தொழில்நுட்பம் மற்றும் நூலகம்', '#0E7490', 5, FALSE),
 (0, now(), 'system', 'SUPPORT', 'Support Staff', 'සහායක කාර්ය මණ්ඩලය', 'உதவி ஊழியர்கள்', '#E8590C', 6, FALSE),
 (0, now(), 'system', 'LEGACY', 'Legacy Grades', 'පැරණි ශ්‍රේණි', 'பழைய தரங்கள்', '#6B7A99', 7, TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO designation_category_assignment (version, created_at, created_by, designation_id, category_id, effective_from)
SELECT 0, now(), 'system', d.id, c.id, '2020-01-01'
FROM (VALUES ('DG','EXECUTIVE'), ('ADDL_DG','EXECUTIVE'), ('SDDG','EXECUTIVE'), ('SD','EXECUTIVE'), ('DDG','EXECUTIVE'), ('DIR','EXECUTIVE'),
             ('CIA','EXECUTIVE'), ('DDAD','EXECUTIVE'), ('RO','EXECUTIVE'), ('IA','EXECUTIVE'),
             ('SADG','LEGAL'), ('ADG','LEGAL'),
             ('SIO','INVESTIGATION'), ('IO','INVESTIGATION'), ('SPO','INVESTIGATION'), ('PO','INVESTIGATION'),
             ('AO','MGMT_ASSISTANT'), ('ACMO','MGMT_ASSISTANT'), ('TYP','MGMT_ASSISTANT'), ('COMM','MGMT_ASSISTANT'), ('TRANS','MGMT_ASSISTANT'),
             ('ITO','TECHNICAL'), ('ICTA','TECHNICAL'), ('LIB','TECHNICAL'), ('ALIB','TECHNICAL'),
             ('DRV','SUPPORT'), ('OA','SUPPORT'), ('LA','LEGACY')) AS v(des, cat)
JOIN designation d ON d.code = v.des JOIN position_category c ON c.code = v.cat
WHERE NOT EXISTS (SELECT 1 FROM designation_category_assignment a WHERE a.designation_id = d.id);
-- 'TO' (Transport / Technical Officer) is intentionally left unassigned for categorization.

-- ------------------------------------------------------------------ organization settings (2.4)
INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('org.governing-act', 'Anti-Corruption Act, No. 9 of 2023', 'GENERAL', 'STRING', 'Statute establishing the Commission and the Director General post (shown on statutory post records)'),
 ('cadre.excess-alert', 'true', 'ESTABLISHMENT', 'BOOLEAN', 'Highlight units and designations where filled + acting exceeds approved')
ON CONFLICT (setting_key) DO NOTHING;

-- ------------------------------------------------------------------ roles (section 2.5)
INSERT INTO role (version, created_at, created_by, code, name, description, system_role) VALUES
 (0, now(), 'system', 'HEAD_OF_HR', 'Head of HR', 'Approves cadre, categorization, policy and No-Pay changes; receives alerts', TRUE),
 (0, now(), 'system', 'PUBLISHER', 'Publisher', 'Only role allowed to publish approved SORs and vacancies', TRUE),
 (0, now(), 'system', 'COMMISSION', 'The Commission', 'Final approving authority for cadre changes', TRUE),
 (0, now(), 'system', 'PAYROLL_ACCOUNTANT', 'Payroll Accountant', 'Officer in charge of inherited liability deductions', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','EST_VIEW','LEGAL_VIEW','EMP_VIEW','REC_VIEW','LEAVE_VIEW','ATT_VIEW','ATT_MANAGE',
  'BEN_VIEW','PER_VIEW','TRN_VIEW','WFL_APPROVE','RPT_VIEW','EXT_VIEW','EST_CADRE_EXCEPTION']) p
WHERE r.code = 'HEAD_OF_HR' ON CONFLICT DO NOTHING;
INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','REC_VIEW','SOR_PUBLISH']) p WHERE r.code = 'PUBLISHER' ON CONFLICT DO NOTHING;
INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','EST_VIEW','LEGAL_VIEW','RPT_VIEW','WFL_APPROVE']) p WHERE r.code = 'COMMISSION' ON CONFLICT DO NOTHING;
INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','PAY_VIEW','PAY_MANAGE','RPT_VIEW','RPT_SENSITIVE','WFL_APPROVE']) p
WHERE r.code = 'PAYROLL_ACCOUNTANT' ON CONFLICT DO NOTHING;
-- The Administration Officer maintains master data, cadre requests, categorization drafts, SOR drafts, exam e-mails,
-- duty-time policies, schemes and insurance configuration (2.5, REQ 1, 4, 11, 12).
INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['EST_MANAGE','LEGAL_VIEW','MDM_MANAGE','SOR_MANAGE','ATT_CONFIG','PAY_CONFIG','BEN_VIEW','BEN_MANAGE','PER_MANAGE']) p
WHERE r.code = 'ADMIN_OFFICER' ON CONFLICT DO NOTHING;
INSERT INTO role_permission (role_id, permission)
SELECT r.id, 'SOR_MANAGE' FROM role r WHERE r.code = 'HR_ADMIN' ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------------ approval chains (G-4)
DELETE FROM workflow_stage WHERE definition_id IN (SELECT id FROM workflow_definition WHERE process_code IN ('ESTABLISHMENT_CHANGE', 'POSITION_CATEGORIZATION'));
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours)
SELECT id, 1, 'Head of HR review', 'ROLE', 'HEAD_OF_HR', 72 FROM workflow_definition WHERE process_code = 'ESTABLISHMENT_CHANGE';
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours)
SELECT id, 2, 'Commission approval', 'ROLE', 'COMMISSION', 240 FROM workflow_definition WHERE process_code = 'ESTABLISHMENT_CHANGE';
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours)
SELECT id, 1, 'Head of HR approval', 'ROLE', 'HEAD_OF_HR', 72 FROM workflow_definition WHERE process_code = 'POSITION_CATEGORIZATION';
UPDATE workflow_definition SET name = 'Establishment / Cadre Change Request' WHERE process_code = 'ESTABLISHMENT_CHANGE';
