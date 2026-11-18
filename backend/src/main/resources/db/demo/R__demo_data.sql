-- =====================================================================================================
-- DEMO / DEVELOPMENT DATA ONLY (loaded with the 'dev' profile). Idempotent.
-- The structure, designations and cadre below are ILLUSTRATIVE. The approved CIABOC structure, cadre and
-- legal mappings must be entered by the Establishment Officer (SRS 11.1 items 1-4).
-- =====================================================================================================

INSERT INTO org_unit (version, created_at, created_by, code, name, name_si, name_ta, unit_type, parent_code, sort_order, effective_from) VALUES
 (0, now(), 'demo', 'DG_OFFICE', 'Office of the Director General', 'අධ්‍යක්ෂ ජනරාල් කාර්යාලය', 'பணிப்பாளர் நாயகம் அலுவலகம்', 'DIVISION', NULL, 1, '2020-01-01'),
 (0, now(), 'demo', 'ADMIN', 'Administration Division', 'පරිපාලන අංශය', 'நிர்வாகப் பிரிவு', 'DIVISION', 'DG_OFFICE', 2, '2020-01-01'),
 (0, now(), 'demo', 'ADMIN-HR', 'Human Resources Section', 'මානව සම්පත් අංශය', 'மனித வளப் பகுதி', 'SECTION', 'ADMIN', 1, '2020-01-01'),
 (0, now(), 'demo', 'INV', 'Investigation Division', 'විමර්ශන අංශය', 'விசாரணைப் பிரிவு', 'DIVISION', 'DG_OFFICE', 3, '2020-01-01'),
 (0, now(), 'demo', 'INV-OPS', 'Investigation Operations Unit', 'විමර්ශන මෙහෙයුම් ඒකකය', 'விசாரணை செயற்பாட்டு அலகு', 'DEPARTMENT', 'INV', 1, '2020-01-01'),
 (0, now(), 'demo', 'INV-INT', 'Intelligence Unit', 'බුද්ධි ඒකකය', 'புலனாய்வு அலகு', 'DEPARTMENT', 'INV', 2, '2020-01-01'),
 (0, now(), 'demo', 'LEGAL', 'Legal Division', 'නීති අංශය', 'சட்டப் பிரிவு', 'DIVISION', 'DG_OFFICE', 4, '2020-01-01'),
 (0, now(), 'demo', 'PREV', 'Prevention Division', 'වැළැක්වීමේ අංශය', 'தடுப்புப் பிரிவு', 'DIVISION', 'DG_OFFICE', 5, '2020-01-01'),
 (0, now(), 'demo', 'ALD', 'Asset and Liability Declarations Division', 'වත්කම් හා බැරකම් ප්‍රකාශන අංශය', 'சொத்து பொறுப்பு அறிவிப்புப் பிரிவு', 'DIVISION', 'DG_OFFICE', 6, '2020-01-01'),
 (0, now(), 'demo', 'ICT', 'ICT Division', 'තොරතුරු තාක්ෂණ අංශය', 'தகவல் தொழில்நுட்பப் பிரிவு', 'DIVISION', 'DG_OFFICE', 7, '2020-01-01'),
 (0, now(), 'demo', 'FIN', 'Finance Division', 'මුදල් අංශය', 'நிதிப் பிரிவு', 'DIVISION', 'DG_OFFICE', 8, '2020-01-01')
ON CONFLICT (code, effective_from) DO NOTHING;

INSERT INTO designation (version, created_at, created_by, code, name, grade, service_category, active) VALUES
 (0, now(), 'demo', 'DG', 'Director General', 'SL4', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'DDG', 'Deputy Director General', 'SL4', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'SADG', 'Senior Assistant Director General', 'SL3', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'ADG', 'Assistant Director General', 'SL3', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'DIR', 'Director', 'SL3', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'AD', 'Assistant Director', 'SL1', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'AO', 'Administrative Officer', 'MN7', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'IO', 'Investigating Officer', 'MN4', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'LO', 'Legal Officer', 'SL1', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'ICTO', 'ICT Officer', 'MN4', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'ACC', 'Accountant', 'SL1', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'MA', 'Management Service Officer', 'MN2', 'COMBINED_SERVICE', TRUE),
 (0, now(), 'demo', 'DRV', 'Driver', 'PL1', 'COMMISSION_SERVICE', TRUE),
 (0, now(), 'demo', 'OA', 'Office Assistant', 'PL1', 'COMMISSION_SERVICE', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO org_position (version, created_at, created_by, code, title, designation_id, org_unit_code, reports_to_position_code, active, activated_on)
SELECT 0, now(), 'demo', v.code, v.title, d.id, v.unit, v.reports_to, TRUE, '2020-01-01'
FROM (VALUES
  ('P-DG', 'Director General', 'DG', 'DG_OFFICE', NULL),
  ('P-DDG', 'Deputy Director General', 'DDG', 'DG_OFFICE', 'P-DG'),
  ('P-DIR-ADMIN', 'Director (Administration)', 'DIR', 'ADMIN', 'P-DDG'),
  ('P-DIR-INV', 'Director (Investigation)', 'DIR', 'INV', 'P-DDG'),
  ('P-DIR-LEGAL', 'Director (Legal)', 'DIR', 'LEGAL', 'P-DDG'),
  ('P-DIR-FIN', 'Director (Finance)', 'DIR', 'FIN', 'P-DDG'),
  ('P-AO-HR', 'Administrative Officer (HR)', 'AO', 'ADMIN-HR', 'P-DIR-ADMIN')
) AS v(code, title, des, unit, reports_to)
JOIN designation d ON d.code = v.des
ON CONFLICT (code) DO NOTHING;

INSERT INTO cadre_allocation (version, created_at, created_by, designation_id, org_unit_code, approved_qty, effective_from)
SELECT 0, now(), 'demo', d.id, v.unit, v.qty, '2020-01-01'
FROM (VALUES
  ('DG', 'DG_OFFICE', 1), ('DDG', 'DG_OFFICE', 2), ('MA', 'DG_OFFICE', 3),
  ('DIR', 'ADMIN', 1), ('AO', 'ADMIN', 3), ('AO', 'ADMIN-HR', 2), ('MA', 'ADMIN-HR', 4), ('DRV', 'ADMIN', 6), ('OA', 'ADMIN', 8),
  ('DIR', 'INV', 1), ('ADG', 'INV', 2), ('IO', 'INV-OPS', 25), ('MA', 'INV-OPS', 5), ('IO', 'INV-INT', 10),
  ('DIR', 'LEGAL', 1), ('LO', 'LEGAL', 12),
  ('DIR', 'PREV', 1), ('AD', 'PREV', 4),
  ('DIR', 'ALD', 1), ('AD', 'ALD', 3), ('MA', 'ALD', 6),
  ('ICTO', 'ICT', 4),
  ('DIR', 'FIN', 1), ('ACC', 'FIN', 3), ('MA', 'FIN', 4)
) AS v(des, unit, qty)
JOIN designation d ON d.code = v.des
WHERE NOT EXISTS (SELECT 1 FROM cadre_allocation c WHERE c.designation_id = d.id AND c.org_unit_code = v.unit);

INSERT INTO employee (version, created_at, created_by, employee_no, title, full_name, name_with_initials, nic, date_of_birth, gender,
                      civil_status, email, mobile, status, employment_type, employee_category, service_category, appointment_date,
                      retirement_date, increment_month, org_unit_code, designation_id, position_id, bank_code, bank_branch, bank_account_no, restricted)
SELECT 0, now(), 'demo', v.no, v.title, v.full_name, v.initials, v.nic, v.dob::date, v.gender, 'MARRIED', v.email, v.mobile, 'ACTIVE',
       v.etype, v.cat, 'COMMISSION_SERVICE', v.appointed::date, (v.dob::date + INTERVAL '60 years')::date, EXTRACT(MONTH FROM v.appointed::date)::int,
       v.unit, d.id, p.id, 'BOC', 'Colombo', v.acct, v.restricted
FROM (VALUES
  ('CIA-0001', 'MR', 'Demo Director General', 'D. D. General', '196805501234', '1968-02-19', 'MALE', 'dg@demo.ciaboc.lk', '0770000001', 'PERMANENT', 'SENIOR_MANAGEMENT', '2021-03-01', 'DG_OFFICE', 'DG', 'P-DG', '8000000001', FALSE),
  ('CIA-0002', 'MRS', 'Demo Deputy Director General', 'D. D. Deputy', '197256701234', '1972-03-07', 'FEMALE', 'ddg@demo.ciaboc.lk', '0770000002', 'PERMANENT', 'SENIOR_MANAGEMENT', '2019-06-15', 'DG_OFFICE', 'DDG', 'P-DDG', '8000000002', FALSE),
  ('CIA-0003', 'MS', 'Demo Human Resources Officer', 'D. H. Resources', '198560901234', '1985-04-10', 'FEMALE', 'hr@demo.ciaboc.lk', '0770000003', 'PERMANENT', 'EXECUTIVE', '2015-01-05', 'ADMIN-HR', 'AO', 'P-AO-HR', '8000000003', FALSE),
  ('CIA-0004', 'MR', 'Demo Establishment Officer', 'D. E. Officer', '198012301234', '1980-05-02', 'MALE', 'est@demo.ciaboc.lk', '0770000004', 'PERMANENT', 'EXECUTIVE', '2012-08-20', 'ADMIN', 'AO', NULL, '8000000004', FALSE),
  ('CIA-0005', 'MR', 'Demo Administrative Officer', 'D. A. Officer', '198717401234', '1987-06-22', 'MALE', 'ao@demo.ciaboc.lk', '0770000005', 'PERMANENT', 'EXECUTIVE', '2017-02-01', 'ADMIN', 'AO', NULL, '8000000005', FALSE),
  ('CIA-0006', 'MRS', 'Demo Finance Accountant', 'D. F. Accountant', '198266201234', '1982-06-11', 'FEMALE', 'finance@demo.ciaboc.lk', '0770000006', 'PERMANENT', 'EXECUTIVE', '2014-11-03', 'FIN', 'ACC', NULL, '8000000006', FALSE),
  ('CIA-0007', 'MR', 'Demo Director Investigation', 'D. I. Director', '197509801234', '1975-04-08', 'MALE', 'head@demo.ciaboc.lk', '0770000007', 'PERMANENT', 'SENIOR_MANAGEMENT', '2016-04-01', 'INV', 'DIR', 'P-DIR-INV', '8000000007', FALSE),
  ('CIA-0008', 'MR', 'Demo Investigating Officer', 'D. I. Officer', '199011201234', '1990-04-22', 'MALE', 'employee@demo.ciaboc.lk', '0770000008', 'PERMANENT', 'INVESTIGATION', '2018-09-10', 'INV-OPS', 'IO', NULL, '8000000008', FALSE),
  ('CIA-0009', 'MS', 'Demo Intelligence Officer', 'D. Intelligence', '199265501234', '1992-06-03', 'FEMALE', 'intel@demo.ciaboc.lk', '0770000009', 'PERMANENT', 'INVESTIGATION', '2020-01-15', 'INV-INT', 'IO', NULL, '8000000009', TRUE),
  ('CIA-0010', 'MR', 'Demo Internal Auditor', 'D. I. Auditor', '197833301234', '1978-11-28', 'MALE', 'auditor@demo.ciaboc.lk', '0770000010', 'PERMANENT', 'SECONDARY', '2011-07-01', 'DG_OFFICE', 'MA', NULL, '8000000010', FALSE),
  ('CIA-0011', 'MRS', 'Demo Senior Accountant', 'D. S. Accountant', '197971201234', '1979-07-31', 'FEMALE', 'finance2@demo.ciaboc.lk', '0770000011', 'PERMANENT', 'EXECUTIVE', '2013-03-18', 'FIN', 'ACC', NULL, '8000000011', FALSE),
  ('CIA-0012', 'MS', 'Demo HR Assistant', 'D. H. Assistant', '199470101234', '1994-07-19', 'FEMALE', 'hr2@demo.ciaboc.lk', '0770000012', 'PERMANENT', 'SECONDARY', '2019-10-01', 'ADMIN-HR', 'MA', NULL, '8000000012', FALSE),
  ('CIA-0013', 'MR', 'Demo Legal Officer', 'D. L. Officer', '198801501234', '1988-01-15', 'MALE', 'legal@demo.ciaboc.lk', '0770000013', 'PERMANENT', 'EXECUTIVE', '2016-12-01', 'LEGAL', 'LO', NULL, '8000000013', FALSE),
  ('CIA-0014', 'MR', 'Demo ICT Officer', 'D. ICT Officer', '199303601234', '1993-02-05', 'MALE', 'ict@demo.ciaboc.lk', '0770000014', 'SECONDMENT', 'TERTIARY', '2022-05-02', 'ICT', 'ICTO', NULL, '8000000014', FALSE),
  ('CIA-0015', 'MRS', 'Demo Management Officer', 'D. M. Officer', '196721001234', '1967-07-29', 'FEMALE', 'mso@demo.ciaboc.lk', '0770000015', 'PERMANENT', 'SECONDARY', '1995-03-01', 'INV-OPS', 'MA', NULL, '8000000015', FALSE)
) AS v(no, title, full_name, initials, nic, dob, gender, email, mobile, etype, cat, appointed, unit, des, pos, acct, restricted)
JOIN designation d ON d.code = v.des
LEFT JOIN org_position p ON p.code = v.pos
ON CONFLICT (employee_no) DO NOTHING;

INSERT INTO employee_assignment (version, created_at, created_by, employee_id, org_unit_code, designation_id, position_id, effective_from, reason)
SELECT 0, now(), 'demo', e.id, e.org_unit_code, e.designation_id, e.position_id, e.appointment_date, 'APPOINTMENT'
FROM employee e
WHERE e.created_by = 'demo' AND NOT EXISTS (SELECT 1 FROM employee_assignment a WHERE a.employee_id = e.id);

INSERT INTO service_event (version, created_at, created_by, employee_id, event_type, event_date, description)
SELECT 0, now(), 'demo', e.id, 'APPOINTMENT', e.appointment_date, 'Appointed (demo data)'
FROM employee e
WHERE e.created_by = 'demo' AND NOT EXISTS (SELECT 1 FROM service_event s WHERE s.employee_id = e.id);

-- Initial legal reference (FR-EST-030). Provision text is deliberately NOT reproduced here: it must be entered from
-- the official Gazette and marked Verified (FR-EST-044).
INSERT INTO legal_document (version, created_at, created_by, doc_type, doc_number, title, issuing_authority, publication_date,
                            effective_date, status, verification_status, version_no, description)
SELECT 0, now(), 'demo', 'ACT', 'No. 9 of 2023', 'Anti-Corruption Act, No. 9 of 2023', 'Parliament of the Democratic Socialist Republic of Sri Lanka',
       '2023-08-08', '2023-09-15', 'ACTIVE', 'DRAFT', 1, 'Initial legal reference for CIABOC establishment. Dates and text to be verified against the official Gazette.'
WHERE NOT EXISTS (SELECT 1 FROM legal_document WHERE doc_number = 'No. 9 of 2023');
UPDATE legal_document SET series_id = id WHERE series_id IS NULL;

INSERT INTO legal_section (version, created_at, created_by, document_id, parent_key, section_key, level, number, heading, content,
                           sort_order, effective_from, verification_status, version_no)
SELECT 0, now(), 'demo', d.id, NULL, gen_random_uuid()::text, v.level, v.num, v.heading,
       '[Provision text to be entered from the official Gazette and verified.]', v.ord, d.effective_date, 'DRAFT', 1
FROM legal_document d,
     (VALUES ('PART', 'I', 'Preliminary (placeholder heading)', 1),
             ('PART', 'II', 'The Commission (placeholder heading)', 2),
             ('PART', 'III', 'Staff of the Commission (placeholder heading)', 3)) AS v(level, num, heading, ord)
WHERE d.doc_number = 'No. 9 of 2023'
  AND NOT EXISTS (SELECT 1 FROM legal_section s WHERE s.document_id = d.id AND s.number = v.num);
