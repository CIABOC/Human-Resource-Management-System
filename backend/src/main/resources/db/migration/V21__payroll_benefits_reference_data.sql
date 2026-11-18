-- =====================================================================================================
-- Payroll and Benefits reference data. All values are configuration editable by PAY_CONFIG / BEN_MANAGE users.
-- Items marked "to be confirmed" mirror SRS 11.1 open items (#9 pension/statutory rates, bank file formats).
-- =====================================================================================================

-- ------------------------------------------------------------------ allowance types (rates to be confirmed with Finance)
INSERT INTO pay_allowance_type (version, created_at, created_by, code, name, name_si, name_ta, applies_to_all, active, description) VALUES
 (0, now(), 'system', 'COLA', 'Cost of living allowance', 'ජීවන වියදම් දීමනාව', 'வாழ்க்கைச் செலவுப் படி', TRUE, TRUE, 'Paid to all employees in service. Amount to be confirmed with Finance.'),
 (0, now(), 'system', 'INTERIM', 'Interim allowance', 'අතුරු දීමනාව', 'இடைக்காலப் படி', TRUE, TRUE, 'Government interim allowance. Amount to be confirmed with Finance.'),
 (0, now(), 'system', 'SPECIAL', 'Special allowance (Commission)', 'විශේෂ දීමනාව', 'விசேட படி', FALSE, TRUE, 'Percentage of basic for assigned officers. Rate to be confirmed.'),
 (0, now(), 'system', 'ACTING', 'Acting allowance', 'වැඩ බැලීමේ දීමනාව', 'பதில் கடமைப் படி', FALSE, TRUE, 'Assigned per acting appointment; amount per assignment.'),
 (0, now(), 'system', 'FUEL', 'Fuel / transport allowance', 'ඉන්ධන දීමනාව', 'எரிபொருள் படி', FALSE, TRUE, 'Assigned to eligible officers. Amount to be confirmed.');

INSERT INTO pay_allowance_rate (version, created_at, created_by, allowance_type_id, basis, rate_value, effective_from, notes)
SELECT 0, now(), 'system', t.id, v.basis, v.val, DATE '2024-01-01', 'To be confirmed with Finance'
FROM pay_allowance_type t
JOIN (VALUES ('COLA', 'FIXED', 7800.00), ('INTERIM', 'FIXED', 10000.00), ('SPECIAL', 'PERCENT_OF_BASIC', 10.00),
             ('ACTING', 'FIXED', 0.00), ('FUEL', 'FIXED', 15000.00)) AS v(code, basis, val) ON v.code = t.code;

-- ------------------------------------------------------------------ deduction types and rates (FR-PAY-010)
INSERT INTO pay_deduction_type (version, created_at, created_by, code, name, name_si, name_ta, category, membership_required, pension_related, active, description) VALUES
 (0, now(), 'system', 'EPF', 'Employees'' Provident Fund', 'සේවක අර්ථසාධක අරමුදල', 'ஊழியர் சேமலாப நிதியம்', 'STATUTORY', TRUE, FALSE, TRUE, 'Statutory EPF contribution for members'),
 (0, now(), 'system', 'ETF', 'Employees'' Trust Fund', 'සේවක භාරකාර අරමුදල', 'ஊழியர் நம்பிக்கை நிதியம்', 'STATUTORY', TRUE, FALSE, TRUE, 'Employer-only ETF contribution for members'),
 (0, now(), 'system', 'WNOP', 'Widows'' and Orphans'' Pension', 'වැන්දඹු හා අනත්දරු විශ්‍රාම වැටුප', 'விதவைகள் அநாதைகள் ஓய்வூதியம்', 'SCHEME', TRUE, TRUE, TRUE, 'W&OP contribution for pensionable officers. Rate to be confirmed with Finance.'),
 (0, now(), 'system', 'PENSION', 'Pension scheme contribution', 'විශ්‍රාම වැටුප් යෝජනා ක්‍රමය', 'ஓய்வூதியத் திட்டம்', 'SCHEME', TRUE, TRUE, TRUE, 'Contributory pension scheme. Rates to be confirmed with Finance (SRS 11.1 #9).'),
 (0, now(), 'system', 'WELFARE', 'Staff welfare society', 'කාර්ය මණ්ඩල සුභසාධක සමිතිය', 'பணியாளர் நலன்புரிச் சங்கம்', 'OTHER', TRUE, FALSE, TRUE, 'Voluntary monthly subscription');

INSERT INTO pay_deduction_rate (version, created_at, created_by, deduction_type_id, employee_basis, employee_value, employer_basis, employer_value, effective_from, to_be_confirmed, notes)
SELECT 0, now(), 'system', t.id, v.eb, v.ev, v.rb, v.rv, DATE '2000-01-01', v.tbc, v.notes
FROM pay_deduction_type t
JOIN (VALUES
  ('EPF', 'PERCENT_OF_BASIC', 8.0, 'PERCENT_OF_BASIC', 12.0, TRUE, 'Employee 8% / employer 12%. Contribution base (basic vs total earnings) to be confirmed with Finance.'),
  ('ETF', 'NONE', 0.0, 'PERCENT_OF_BASIC', 3.0, TRUE, 'Employer 3%. Contribution base to be confirmed with Finance.'),
  ('WNOP', 'PERCENT_OF_BASIC', 6.0, 'NONE', 0.0, TRUE, 'To be confirmed with Finance (SRS 11.1 #9).'),
  ('PENSION', 'PERCENT_OF_BASIC', 0.0, 'PERCENT_OF_BASIC', 0.0, TRUE, 'All rates to be confirmed with Finance (SRS 11.1 #9).'),
  ('WELFARE', 'FIXED', 100.0, 'NONE', 0.0, TRUE, 'Subscription amount to be confirmed by the society.')
) AS v(code, eb, ev, rb, rv, tbc, notes) ON v.code = t.code;

-- ------------------------------------------------------------------ insurance schemes (premiums are set per scheme/policy)
INSERT INTO ben_insurance_scheme (version, created_at, created_by, code, name, name_si, name_ta, scheme_type, category, provider,
                                  employee_premium, employer_premium, coverage_amount, payroll_deduction, claims_enabled,
                                  eligible_employment_types, min_service_months, eligibility_notes, active) VALUES
 (0, now(), 'system', 'AGRAHARA', 'Agrahara medical insurance', 'අග්‍රහාර රක්ෂණය', 'அக்ரஹார காப்புறுதி', 'AGRAHARA', 'HEALTH',
  'National Insurance Trust Fund', 0, 0, NULL, TRUE, TRUE, 'PERMANENT,SECONDMENT', 0,
  'Public officers in permanent or seconded service. Premium and benefit levels by salary band to be confirmed with Finance / NITF.', TRUE);

-- ------------------------------------------------------------------ settings
INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('payroll.nopay.divisor', '30', 'PAYROLL', 'DECIMAL', 'No-pay deduction = basic salary / divisor x no-pay days (to be confirmed with Finance)'),
 ('payroll.bank-advice.format', 'CSV-v1', 'PAYROLL', 'STRING', 'Bank advice file layout identifier (format to be confirmed with Finance and the bank)'),
 ('benefits.expiry.notice-days', '30', 'BENEFITS', 'INT', 'Days before policy expiry when the employee and HR are notified (FR-BEN-004)')
ON CONFLICT (setting_key) DO NOTHING;

-- ------------------------------------------------------------------ notification templates (trilingual; translations to be reviewed)
INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'PAY_PAYSLIP_AVAILABLE', 'Sent to each employee when a payroll period is finalized',
  'Payslip available: {{period}}', 'වැටුප් පත්‍රිකාව ලබා ගත හැක: {{period}}', 'சம்பளச் சீட்டு கிடைக்கிறது: {{period}}',
  'Your payslip for {{period}} is now available in HRMS self-service.',
  '{{period}} සඳහා ඔබගේ වැටුප් පත්‍රිකාව දැන් HRMS ස්වයං සේවාවේ ලබා ගත හැක.',
  '{{period}} க்கான உங்கள் சம்பளச் சீட்டு இப்போது HRMS சுய சேவையில் கிடைக்கிறது.', TRUE),
 (0, now(), 'system', 'BEN_POLICY_EXPIRY', 'Sent to the employee before an insurance policy expires',
  'Insurance policy expiring: {{scheme}}', 'රක්ෂණ ඔප්පුව කල් ඉකුත් වේ: {{scheme}}', 'காப்புறுதிக் கொள்கை காலாவதியாகிறது: {{scheme}}',
  'Your {{scheme}} policy {{policyNo}} expires on {{expiryDate}}. Please contact HR to renew it.',
  'ඔබගේ {{scheme}} ඔප්පුව {{policyNo}} {{expiryDate}} දින කල් ඉකුත් වේ. අලුත් කිරීමට මානව සම්පත් අංශය අමතන්න.',
  'உங்கள் {{scheme}} கொள்கை {{policyNo}} {{expiryDate}} அன்று காலாவதியாகிறது. புதுப்பிக்க மனிதவளப் பிரிவைத் தொடர்பு கொள்ளவும்.', TRUE),
 (0, now(), 'system', 'BEN_POLICY_EXPIRY_HR', 'Sent to HR before an employee insurance policy expires',
  'Policy expiring: {{employeeNo}} {{scheme}}', 'ඔප්පුව කල් ඉකුත් වේ: {{employeeNo}} {{scheme}}', 'கொள்கை காலாவதியாகிறது: {{employeeNo}} {{scheme}}',
  'The {{scheme}} policy {{policyNo}} of {{employeeNo}} expires on {{expiryDate}}.',
  '{{employeeNo}} හි {{scheme}} ඔප්පුව {{policyNo}} {{expiryDate}} දින කල් ඉකුත් වේ.',
  '{{employeeNo}} இன் {{scheme}} கொள்கை {{policyNo}} {{expiryDate}} அன்று காலாவதியாகிறது.', FALSE);

-- ------------------------------------------------------------------ workflows (chains to be confirmed: SRS 11.1 #14)
INSERT INTO workflow_definition (version, created_at, created_by, process_code, name, description) VALUES
 (0, now(), 'system', 'PAYROLL_RUN', 'Payroll run finalization', 'Maker-checker: prepared by a Finance Officer, checked by another Finance Officer, authorised by Management'),
 (0, now(), 'system', 'LOAN_ADJUSTMENT', 'Loan adjustment', 'Early settlement, instalment change or transfer-out of a seconded employee loan');

INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'Checker', 'ROLE', 'FINANCE_OFFICER', 48, NULL FROM workflow_definition WHERE process_code = 'PAYROLL_RUN';
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 2, 'Authorisation', 'ROLE', 'MANAGEMENT', 72, NULL FROM workflow_definition WHERE process_code = 'PAYROLL_RUN';
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'Finance verification', 'ROLE', 'FINANCE_OFFICER', 72, NULL FROM workflow_definition WHERE process_code = 'LOAN_ADJUSTMENT';
