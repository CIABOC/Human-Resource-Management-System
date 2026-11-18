-- =====================================================================================================
-- DEMO / DEVELOPMENT DATA ONLY (dev profile). Idempotent. Salary scales and amounts are ILLUSTRATIVE and must be
-- replaced with the approved scales and rates confirmed by Finance.
-- =====================================================================================================

INSERT INTO pay_salary_scale (version, created_at, created_by, code, name, grade, initial_salary, increment_amount, max_steps, effective_from, notes)
SELECT 0, now(), 'demo', v.code, v.name, v.grade, v.initial, v.inc, v.steps, DATE '2020-01-01', 'Illustrative demo scale - to be confirmed with Finance'
FROM (VALUES
  ('SL-4', 'Senior Level 4 (illustrative)', 'SL4', 120000.00, 3000.00, 15),
  ('SL-3', 'Senior Level 3 (illustrative)', 'SL3', 95000.00, 2500.00, 18),
  ('SL-1', 'Senior Level 1 (illustrative)', 'SL1', 72000.00, 1800.00, 20),
  ('MN-7', 'Middle Level 7 (illustrative)', 'MN7', 60000.00, 1500.00, 20),
  ('MN-4', 'Middle Level 4 (illustrative)', 'MN4', 48000.00, 1200.00, 22),
  ('MN-2', 'Middle Level 2 (illustrative)', 'MN2', 40000.00, 1000.00, 25),
  ('PL-1', 'Primary Level 1 (illustrative)', 'PL1', 30000.00, 600.00, 25)
) AS v(code, name, grade, initial, inc, steps)
WHERE NOT EXISTS (SELECT 1 FROM pay_salary_scale s WHERE s.code = v.code);

-- Salary placements for CIA-0001..CIA-0015 (scale chosen from the designation grade).
INSERT INTO pay_employee_salary (version, created_at, created_by, employee_id, scale_code, step, basic_salary, effective_from, reason, remarks)
SELECT 0, now(), 'demo', e.id, s.code, v.step, s.initial_salary + s.increment_amount * v.step,
       GREATEST(DATE '2025-01-01', e.appointment_date), 'INITIAL', 'Demo placement'
FROM (VALUES ('CIA-0001', 'SL-4', 8), ('CIA-0002', 'SL-4', 5), ('CIA-0003', 'MN-7', 7), ('CIA-0004', 'MN-7', 9),
             ('CIA-0005', 'MN-7', 4), ('CIA-0006', 'SL-1', 8), ('CIA-0007', 'SL-3', 6), ('CIA-0008', 'MN-4', 5),
             ('CIA-0009', 'MN-4', 3), ('CIA-0010', 'MN-2', 12), ('CIA-0011', 'SL-1', 10), ('CIA-0012', 'MN-2', 4),
             ('CIA-0013', 'SL-1', 6), ('CIA-0014', 'MN-4', 2), ('CIA-0015', 'MN-2', 25)) AS v(no, scale, step)
JOIN employee e ON e.employee_no = v.no
JOIN pay_salary_scale s ON s.code = v.scale AND s.effective_to IS NULL
WHERE NOT EXISTS (SELECT 1 FROM pay_employee_salary x WHERE x.employee_id = e.id);

-- EPF / ETF membership for every demo employee; W&OP for selected pensionable officers; welfare society members.
INSERT INTO pay_deduction_membership (version, created_at, created_by, employee_id, deduction_type_id, member_no, effective_from, remarks)
SELECT 0, now(), 'demo', e.id, t.id, CASE WHEN t.code = 'EPF' THEN 'EPF-' || substr(e.employee_no, 5) END,
       GREATEST(DATE '2025-01-01', e.appointment_date), 'Demo membership'
FROM employee e
JOIN pay_deduction_type t ON t.code IN ('EPF', 'ETF')
WHERE e.employee_no BETWEEN 'CIA-0001' AND 'CIA-0015'
  AND NOT EXISTS (SELECT 1 FROM pay_deduction_membership m WHERE m.employee_id = e.id AND m.deduction_type_id = t.id);

INSERT INTO pay_deduction_membership (version, created_at, created_by, employee_id, deduction_type_id, effective_from, remarks)
SELECT 0, now(), 'demo', e.id, t.id, GREATEST(DATE '2025-01-01', e.appointment_date), 'Demo membership'
FROM employee e
JOIN pay_deduction_type t ON (t.code = 'WNOP' AND e.employee_no IN ('CIA-0001', 'CIA-0002', 'CIA-0007', 'CIA-0015'))
                          OR (t.code = 'WELFARE' AND e.employee_no IN ('CIA-0003', 'CIA-0008', 'CIA-0010', 'CIA-0012'))
WHERE NOT EXISTS (SELECT 1 FROM pay_deduction_membership m WHERE m.employee_id = e.id AND m.deduction_type_id = t.id);

-- Individually assigned allowances.
INSERT INTO pay_employee_allowance (version, created_at, created_by, employee_id, allowance_type_id, amount_override, effective_from, remarks)
SELECT 0, now(), 'demo', e.id, t.id, v.amt, DATE '2025-01-01', 'Demo assignment'
FROM (VALUES ('CIA-0001', 'FUEL', NULL::numeric), ('CIA-0007', 'FUEL', NULL::numeric), ('CIA-0008', 'SPECIAL', NULL::numeric),
             ('CIA-0009', 'SPECIAL', NULL::numeric), ('CIA-0005', 'ACTING', 8500.00)) AS v(no, code, amt)
JOIN employee e ON e.employee_no = v.no
JOIN pay_allowance_type t ON t.code = v.code
WHERE NOT EXISTS (SELECT 1 FROM pay_employee_allowance a WHERE a.employee_id = e.id AND a.allowance_type_id = t.id);

-- Loans brought by seconded / transferred-in employees.
INSERT INTO pay_employee_loan (version, created_at, created_by, employee_id, loan_type, lender, previous_workplace, reference_no,
                               original_amount, outstanding_balance, monthly_instalment, start_date, status, remarks)
SELECT 0, now(), 'demo', e.id, v.lt, v.lender, v.prev, v.ref, v.orig, v.outs, v.inst, DATE '2025-06-01', 'ACTIVE', 'Demo loan'
FROM (VALUES ('CIA-0014', 'PROPERTY', 'State Mortgage and Investment Bank', 'Ministry of Technology', 'DEMO-L-001', 500000.00, 320000.00, 10000.00),
             ('CIA-0008', 'DISTRESS', 'Public Service Mutual Fund', 'Department of Police', 'DEMO-L-002', 100000.00, 25500.00, 5000.00),
             ('CIA-0013', 'FESTIVAL', 'Festival advance (previous workplace)', 'Attorney General''s Department', 'DEMO-L-003', 10000.00, 2000.00, 5000.00))
     AS v(no, lt, lender, prev, ref, orig, outs, inst)
JOIN employee e ON e.employee_no = v.no
WHERE NOT EXISTS (SELECT 1 FROM pay_employee_loan l WHERE l.reference_no = v.ref);

INSERT INTO pay_loan_transaction (version, created_at, created_by, loan_id, txn_date, txn_type, amount, balance_after, remarks)
SELECT 0, now(), 'demo', l.id, l.start_date, 'OPENING', l.outstanding_balance, l.outstanding_balance, 'Opening balance (demo)'
FROM pay_employee_loan l
WHERE l.created_by = 'demo' AND NOT EXISTS (SELECT 1 FROM pay_loan_transaction t WHERE t.loan_id = l.id);

-- Agrahara coverage for all demo employees; two policies expire soon to demonstrate expiry notifications.
INSERT INTO ben_insurance_coverage (version, created_at, created_by, employee_id, scheme_id, policy_no, start_date, expiry_date,
                                    employee_premium, employer_premium, coverage_amount, status, remarks)
SELECT 0, now(), 'demo', e.id, s.id, 'AGR-' || substr(e.employee_no, 5), DATE '2025-01-01',
       CASE WHEN e.employee_no IN ('CIA-0005', 'CIA-0012') THEN CURRENT_DATE + 20 ELSE DATE '2027-12-31' END,
       225.00, 0, 300000.00, 'ACTIVE', 'Demo coverage'
FROM employee e
JOIN ben_insurance_scheme s ON s.code = 'AGRAHARA'
WHERE e.employee_no BETWEEN 'CIA-0001' AND 'CIA-0015'
  AND NOT EXISTS (SELECT 1 FROM ben_insurance_coverage c WHERE c.employee_id = e.id AND c.scheme_id = s.id);
