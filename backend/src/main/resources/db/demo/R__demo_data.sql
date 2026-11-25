-- =====================================================================================================
-- DEMO / DEVELOPMENT DATA ONLY (loaded with the 'dev' profile). Idempotent.
-- The proposed CIABOC structure, designations, cadre and posts are loaded by migration V52 (all profiles).
-- This file only adds sample employees placed into that structure.
-- =====================================================================================================

INSERT INTO employee (version, created_at, created_by, employee_no, title, full_name, name_with_initials, nic, date_of_birth, gender,
                      civil_status, email, mobile, status, employment_type, employee_category, service_category, appointment_date,
                      retirement_date, increment_month, org_unit_code, designation_id, position_id, bank_code, bank_branch, bank_account_no, restricted)
SELECT 0, now(), 'demo', v.no, v.title, v.full_name, v.initials, v.nic, v.dob::date, v.gender, 'MARRIED', v.email, v.mobile, 'ACTIVE',
       v.etype, v.cat, 'COMMISSION_SERVICE', v.appointed::date, (v.dob::date + INTERVAL '60 years')::date, EXTRACT(MONTH FROM v.appointed::date)::int,
       v.unit, d.id, p.id, 'BOC', 'Colombo', v.acct, v.restricted
FROM (VALUES
  ('CIA-0001', 'MR', 'Demo Director General', 'D. D. General', '196805501234', '1968-02-19', 'MALE', 'dg@demo.ciaboc.lk', '0770000001', 'PERMANENT', 'SENIOR_MANAGEMENT', '2021-03-01', 'DG_OFFICE', 'DG', 'DG_OFFICE/DG/001', '8000000001', FALSE),
  ('CIA-0002', 'MRS', 'Demo Additional Director General', 'D. A. General', '197256701234', '1972-03-07', 'FEMALE', 'addldg@demo.ciaboc.lk', '0770000002', 'PERMANENT', 'SENIOR_MANAGEMENT', '2019-06-15', 'ADDL_DG', 'ADDL_DG', 'ADDL_DG/ADDL_DG/001', '8000000002', FALSE),
  ('CIA-0003', 'MS', 'Demo Human Resources Officer', 'D. H. Resources', '198560901234', '1985-04-10', 'FEMALE', 'hr@demo.ciaboc.lk', '0770000003', 'PERMANENT', 'EXECUTIVE', '2015-01-05', 'ADMIN', 'AO', 'ADMIN/AO/001', '8000000003', FALSE),
  ('CIA-0004', 'MR', 'Demo Establishment Officer', 'D. E. Officer', '198012301234', '1980-05-02', 'MALE', 'est@demo.ciaboc.lk', '0770000004', 'PERMANENT', 'EXECUTIVE', '2012-08-20', 'ADMIN', 'DDAD', NULL, '8000000004', FALSE),
  ('CIA-0005', 'MR', 'Demo Administration Officer', 'D. A. Officer', '198717401234', '1987-06-22', 'MALE', 'ao@demo.ciaboc.lk', '0770000005', 'PERMANENT', 'EXECUTIVE', '2017-02-01', 'ADMIN', 'DDAD', NULL, '8000000005', FALSE),
  ('CIA-0006', 'MRS', 'Demo Finance Officer', 'D. F. Officer', '198266201234', '1982-06-11', 'FEMALE', 'finance@demo.ciaboc.lk', '0770000006', 'PERMANENT', 'EXECUTIVE', '2014-11-03', 'FIN', 'DDAD', NULL, '8000000006', FALSE),
  ('CIA-0007', 'MR', 'Demo Director Investigation', 'D. I. Director', '197509801234', '1975-04-08', 'MALE', 'head@demo.ciaboc.lk', '0770000007', 'PERMANENT', 'SENIOR_MANAGEMENT', '2016-04-01', 'INV', 'DIR', 'INV/DIR/001', '8000000007', FALSE),
  ('CIA-0008', 'MR', 'Demo Investigation Officer', 'D. I. Officer', '199011201234', '1990-04-22', 'MALE', 'employee@demo.ciaboc.lk', '0770000008', 'PERMANENT', 'INVESTIGATION', '2018-09-10', 'INV-HO', 'IO', NULL, '8000000008', FALSE),
  ('CIA-0009', 'MS', 'Demo Intelligence Officer', 'D. Intelligence', '199265501234', '1992-06-03', 'FEMALE', 'intel@demo.ciaboc.lk', '0770000009', 'PERMANENT', 'INVESTIGATION', '2020-01-15', 'INTEL', 'IO', NULL, '8000000009', TRUE),
  ('CIA-0010', 'MR', 'Demo Internal Auditor', 'D. I. Auditor', '197833301234', '1978-11-28', 'MALE', 'auditor@demo.ciaboc.lk', '0770000010', 'PERMANENT', 'EXECUTIVE', '2011-07-01', 'IA', 'IA', 'IA/IA/001', '8000000010', FALSE),
  ('CIA-0011', 'MRS', 'Demo Senior Finance Officer', 'D. S. Finance', '197971201234', '1979-07-31', 'FEMALE', 'finance2@demo.ciaboc.lk', '0770000011', 'PERMANENT', 'SECONDARY', '2013-03-18', 'FIN', 'ACMO', NULL, '8000000011', FALSE),
  ('CIA-0012', 'MS', 'Demo HR Assistant', 'D. H. Assistant', '199470101234', '1994-07-19', 'FEMALE', 'hr2@demo.ciaboc.lk', '0770000012', 'PERMANENT', 'SECONDARY', '2019-10-01', 'ADMIN', 'ACMO', NULL, '8000000012', FALSE),
  ('CIA-0013', 'MR', 'Demo Assistant Director General', 'D. L. Officer', '198801501234', '1988-01-15', 'MALE', 'legal@demo.ciaboc.lk', '0770000013', 'PERMANENT', 'EXECUTIVE', '2016-12-01', 'LEGAL-HO', 'ADG', NULL, '8000000013', FALSE),
  ('CIA-0014', 'MR', 'Demo IT Officer', 'D. IT Officer', '199303601234', '1993-02-05', 'MALE', 'ict@demo.ciaboc.lk', '0770000014', 'SECONDMENT', 'TERTIARY', '2022-05-02', 'IT', 'ITO', NULL, '8000000014', FALSE),
  ('CIA-0015', 'MRS', 'Demo Management Officer', 'D. M. Officer', '196721001234', '1967-07-29', 'FEMALE', 'mso@demo.ciaboc.lk', '0770000015', 'PERMANENT', 'SECONDARY', '1995-03-01', 'INV-HO', 'ACMO', NULL, '8000000015', FALSE),
  ('CIA-0016', 'MR', 'Demo Head of HR', 'D. H. Head', '197702201234', '1977-01-20', 'MALE', 'headhr@demo.ciaboc.lk', '0770000016', 'PERMANENT', 'SENIOR_MANAGEMENT', '2014-02-03', 'ADMIN', 'DIR', 'ADMIN/DIR/001', '8000000016', FALSE)
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
