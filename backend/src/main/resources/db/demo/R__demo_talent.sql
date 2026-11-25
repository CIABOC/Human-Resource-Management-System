-- =====================================================================================================
-- DEMO / DEVELOPMENT DATA ONLY: recruitment, performance and training samples. Idempotent.
-- =====================================================================================================

INSERT INTO rec_vacancy (version, created_at, created_by, reference_no, title, designation_id, org_unit_code, num_posts, status,
                         opening_date, closing_date, description, requirements, exam_weight, interview_weight)
SELECT 0, now(), 'demo', 'VAC-DEMO-001', 'Anti-Corruption Management Officer (Administration)', d.id, 'ADMIN', 1, 'OPEN',
       CURRENT_DATE - 10, CURRENT_DATE + 20, 'Clerical support to the Human Resources Section.',
       'G.C.E. (A/L) with three passes; proficiency in Sinhala/Tamil and English; computer literacy.', 60, 40
FROM designation d WHERE d.code = 'ACMO'
ON CONFLICT (reference_no) DO NOTHING;

INSERT INTO rec_announcement (version, created_at, created_by, vacancy_id, title, content, publish_date, closing_date, published, published_at, published_by)
SELECT 0, now(), 'demo', v.id, 'Vacancy: Management Service Officer', E'Applications are invited for one post of Management Service Officer in the Human Resources Section.\n\nSee the vacancy notice for requirements.',
       CURRENT_DATE - 10, CURRENT_DATE + 20, TRUE, now(), 'demo'
FROM rec_vacancy v WHERE v.reference_no = 'VAC-DEMO-001'
  AND NOT EXISTS (SELECT 1 FROM rec_announcement a WHERE a.vacancy_id = v.id);

INSERT INTO rec_applicant (version, created_at, created_by, vacancy_id, application_no, title, full_name, name_with_initials, nic,
                           date_of_birth, gender, email, mobile, address, qualifications, received_date, source, status)
SELECT 0, now(), 'demo', v.id, x.no, x.title, x.full_name, x.initials, x.nic, x.dob::date, x.gender, x.email, '0770001111',
       x.address, 'G.C.E. (A/L) 3 passes', CURRENT_DATE - 5, 'HR_ENTERED', x.status
FROM rec_vacancy v,
     (VALUES ('VAC-DEMO-001-A0001', 'MS', 'Demo Applicant Kumari Fernando', 'K. Fernando', '199876500001', '1998-05-12', 'FEMALE', 'kumari@example.lk', '15 Lake Road, Kandy', 'SHORTLISTED'),
             ('VAC-DEMO-001-A0002', 'MR', 'Demo Applicant Nimal Jayasinghe', 'N. Jayasinghe', '199712300002', '1997-02-03', 'MALE', 'nimal@example.lk', '8 Temple Lane, Galle', 'RECEIVED'))
         AS x(no, title, full_name, initials, nic, dob, gender, email, address, status)
WHERE v.reference_no = 'VAC-DEMO-001'
ON CONFLICT (application_no) DO NOTHING;

INSERT INTO rec_applicant_status (version, created_at, created_by, applicant_id, from_status, to_status, changed_at, changed_by, remarks)
SELECT 0, now(), 'demo', a.id, NULL, a.status, now(), 'demo', 'Demo data'
FROM rec_applicant a WHERE a.created_by = 'demo'
  AND NOT EXISTS (SELECT 1 FROM rec_applicant_status s WHERE s.applicant_id = a.id);

INSERT INTO per_period (version, created_at, created_by, code, name, start_date, end_date, due_date, status)
VALUES (0, now(), 'demo', 'DEMO-ANNUAL', 'Demo annual evaluation period', date_trunc('year', CURRENT_DATE)::date,
        (date_trunc('year', CURRENT_DATE) + INTERVAL '1 year - 1 day')::date,
        (date_trunc('year', CURRENT_DATE) + INTERVAL '1 year + 30 days')::date, 'OPEN')
ON CONFLICT (code) DO NOTHING;

INSERT INTO trn_programme (version, created_at, created_by, code, title, description, provider, start_date, end_date, venue, capacity,
                           cost, nomination_closing_date, status)
VALUES (0, now(), 'demo', 'TRN-DEMO-001', 'Digital evidence handling', 'Introductory programme on collecting and preserving digital evidence.',
        'Sri Lanka Institute of Development Administration', CURRENT_DATE + 30, CURRENT_DATE + 32, 'SLIDA, Colombo 07', 20,
        25000.00, CURRENT_DATE + 20, 'OPEN'),
       (0, now(), 'demo', 'TRN-DEMO-002', 'Public procurement guidelines', 'Refresher on the National Procurement Guidelines.',
        'National Procurement Commission', CURRENT_DATE + 60, CURRENT_DATE + 61, 'CIABOC Auditorium', 30, 0, CURRENT_DATE + 50, 'PLANNED')
ON CONFLICT (code) DO NOTHING;
