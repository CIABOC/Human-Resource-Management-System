-- =====================================================================================================
-- Leave and attendance reference data. All values are configuration editable in the UI.
-- Leave entitlements and working hours mirror SRS 11.1 open items #7 and #8: TO BE CONFIRMED BY ADMINISTRATION.
-- =====================================================================================================

-- ------------------------------------------------------------------ leave types
INSERT INTO leave_type (version, created_at, created_by, code, name, name_si, name_ta, description, paid, balance_tracked,
                        requires_reason, requires_document, document_after_days, min_notice_days, max_consecutive_days,
                        allow_backdated, half_day_allowed, sort_order, active) VALUES
 (0, now(), 'system', 'CASUAL', 'Casual leave', 'අනියම් නිවාඩු', 'தற்செயல் விடுமுறை',
  'Short leave for personal matters; may be applied for on return. Entitlement and rules to be confirmed by Administration (SRS 11.1 #7).',
  TRUE, TRUE, FALSE, FALSE, NULL, 0, 3, TRUE, TRUE, 1, TRUE),
 (0, now(), 'system', 'VACATION', 'Annual (vacation) leave', 'වාර්ෂික නිවාඩු', 'வருடாந்த விடுமுறை',
  'Planned annual leave applied for in advance. Entitlement and notice period to be confirmed by Administration (SRS 11.1 #7).',
  TRUE, TRUE, FALSE, FALSE, NULL, 3, 24, FALSE, FALSE, 2, TRUE),
 (0, now(), 'system', 'MEDICAL', 'Medical (sick) leave', 'වෛද්‍ය නිවාඩු', 'மருத்துவ விடுமுறை',
  'Leave on medical grounds. A medical certificate is required for more than 2 days. To be confirmed by Administration (SRS 11.1 #7).',
  TRUE, TRUE, FALSE, TRUE, 2, 0, NULL, TRUE, FALSE, 3, TRUE),
 (0, now(), 'system', 'MATERNITY', 'Maternity leave', 'මාතෘ නිවාඩු', 'மகப்பேற்று விடுமுறை',
  'Maternity leave (working days) supported by a medical certificate. Entitlement to be confirmed by Administration (SRS 11.1 #7).',
  TRUE, TRUE, FALSE, TRUE, 0, 0, 84, TRUE, FALSE, 4, TRUE),
 (0, now(), 'system', 'NOPAY', 'No-pay leave', 'වැටුප් රහිත නිවාඩු', 'சம்பளமற்ற விடுமுறை',
  'Unpaid leave; approved days are deducted through the monthly no-pay calculation. Rules to be confirmed by Administration.',
  FALSE, FALSE, TRUE, FALSE, NULL, 3, NULL, FALSE, FALSE, 5, TRUE),
 (0, now(), 'system', 'DUTY', 'Duty leave', 'රාජකාරි නිවාඩු', 'கடமை விடுமுறை',
  'Absence on official duty outside the office (courts, training, official visits). Not deducted from any balance.',
  TRUE, FALSE, TRUE, FALSE, NULL, 0, NULL, TRUE, TRUE, 6, TRUE);

-- ------------------------------------------------------------------ entitlements per employee category (days / calendar year)
-- Values are indicative public-sector figures and MUST be confirmed by Administration (SRS 11.1 #7, #8).
INSERT INTO leave_entitlement (version, created_at, created_by, leave_type_id, employee_category, days_per_year, effective_from, remarks)
SELECT 0, now(), 'system', t.id, c.cat, v.days, DATE '2000-01-01', 'Seeded default - to be confirmed by Administration'
FROM leave_type t
JOIN (VALUES ('CASUAL', 7.00), ('VACATION', 24.00), ('MEDICAL', 21.00), ('MATERNITY', 84.00)) AS v(code, days) ON v.code = t.code
CROSS JOIN (VALUES ('SENIOR_MANAGEMENT'), ('EXECUTIVE'), ('INVESTIGATION'), ('TERTIARY'), ('SECONDARY'), ('PRIMARY')) AS c(cat);

-- ------------------------------------------------------------------ default working schedule (SRS 11.1 #8: to be confirmed)
INSERT INTO work_schedule (version, created_at, created_by, code, name, start_time, end_time, late_grace_minutes,
                           early_grace_minutes, work_days, org_unit_code, employee_category, effective_from, remarks) VALUES
 (0, now(), 'system', 'STANDARD', 'Standard office hours', TIME '08:30', TIME '16:15', 10, 10, 'MON,TUE,WED,THU,FRI',
  NULL, NULL, DATE '2000-01-01', 'Institution-wide default - working hours and grace periods to be confirmed by Administration');

-- ------------------------------------------------------------------ holidays (SAMPLE - confirm against the Government Gazette)
INSERT INTO public_holiday (version, created_at, created_by, holiday_date, name, name_si, name_ta, holiday_type, remarks) VALUES
 (0, now(), 'system', DATE '2026-01-15', 'Tamil Thai Pongal Day', 'දෙමළ තෛපොංගල් දිනය', 'தைப்பொங்கல் தினம்', 'PUBLIC', 'Sample - confirm against Gazette'),
 (0, now(), 'system', DATE '2026-02-04', 'National Day', 'ජාතික දිනය', 'தேசிய தினம்', 'PUBLIC', 'Sample - confirm against Gazette'),
 (0, now(), 'system', DATE '2026-04-13', 'Day prior to Sinhala and Tamil New Year', 'සිංහල හා දෙමළ අලුත් අවුරුද්දට පෙර දිනය', 'சிங்கள தமிழ் புத்தாண்டுக்கு முந்தைய நாள்', 'PUBLIC', 'Sample - confirm against Gazette'),
 (0, now(), 'system', DATE '2026-04-14', 'Sinhala and Tamil New Year Day', 'සිංහල හා දෙමළ අලුත් අවුරුදු දිනය', 'சிங்கள தமிழ் புத்தாண்டு தினம்', 'PUBLIC', 'Sample - confirm against Gazette'),
 (0, now(), 'system', DATE '2026-05-01', 'May Day', 'මැයි දිනය', 'மே தினம்', 'PUBLIC', 'Sample - confirm against Gazette'),
 (0, now(), 'system', DATE '2026-12-25', 'Christmas Day', 'නත්තල් දිනය', 'நத்தார் தினம்', 'PUBLIC', 'Sample - confirm against Gazette');

-- ------------------------------------------------------------------ settings (rules, never hard-coded)
INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('leave.backdate-max-days', '30', 'LEAVE', 'INT', 'How many days in the past a back-dated application may start (types that allow back-dating)'),
 ('leave.max-advance-days', '365', 'LEAVE', 'INT', 'How far in advance leave may be applied for'),
 ('attendance.duplicate-window-minutes', '2', 'ATTENDANCE', 'INT', 'Events of the same employee and direction within this window are flagged as duplicates'),
 ('attendance.valid-hours.start', '05:00', 'ATTENDANCE', 'STRING', 'Events before this local time (HH:mm) are flagged for review'),
 ('attendance.valid-hours.end', '22:00', 'ATTENDANCE', 'STRING', 'Events after this local time (HH:mm) are flagged for review'),
 ('attendance.max-future-minutes', '5', 'ATTENDANCE', 'INT', 'Events time-stamped further in the future than this are rejected (device clock tampering)'),
 ('attendance.nightly.enabled', 'true', 'ATTENDANCE', 'BOOLEAN', 'Process the previous day''s attendance automatically every night'),
 ('attendance.correction-max-days', '31', 'ATTENDANCE', 'INT', 'How many days back a manual attendance correction may be requested'),
 ('nopay.count-unauthorized-absence', 'true', 'NOPAY', 'BOOLEAN', 'Count unauthorized absences (no attendance, no approved leave) as no-pay'),
 ('nopay.absence-day-value', '1', 'NOPAY', 'DECIMAL', 'No-pay days charged per unauthorized absence day (to be confirmed)'),
 ('nopay.include-unpaid-leave', 'true', 'NOPAY', 'BOOLEAN', 'Include approved unpaid leave days in the no-pay calculation'),
 ('nopay.late-arrivals-per-half-day', '0', 'NOPAY', 'INT', 'Every N late arrivals in a month are charged as half a no-pay day (0 = rule disabled; to be confirmed)');

-- ------------------------------------------------------------------ notification templates
INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'LEAVE_BALANCE_ADJUSTED', 'Sent to an employee when HR adjusts a leave balance',
  'Leave balance adjusted: {{leaveType}}', 'නිවාඩු ශේෂය සංශෝධනය විය: {{leaveType}}', 'விடுமுறை மீதி திருத்தப்பட்டது: {{leaveType}}',
  'Your {{leaveType}} balance for {{year}} was adjusted by {{days}} day(s). Reason: {{reason}}',
  'ඔබගේ {{year}} {{leaveType}} ශේෂය දින {{days}} කින් සංශෝධනය කරන ලදී. හේතුව: {{reason}}',
  'உங்கள் {{year}} {{leaveType}} மீதி {{days}} நாட்களால் திருத்தப்பட்டது. காரணம்: {{reason}}', FALSE),
 (0, now(), 'system', 'NOPAY_RELEASED', 'Sent to finance when an approved no-pay calculation is released to payroll',
  'No-pay released for {{period}}', '{{period}} සඳහා වැටුප් රහිත දින නිකුත් කෙරිණි', '{{period}} சம்பளமற்ற நாட்கள் வெளியிடப்பட்டன',
  'The no-pay calculation for {{period}} ({{employees}} employees, {{days}} days) was approved and is available to payroll.',
  '{{period}} සඳහා වැටුප් රහිත ගණනය (සේවකයින් {{employees}}, දින {{days}}) අනුමත කර වැටුප් පද්ධතියට ලබා දී ඇත.',
  '{{period}} சம்பளமற்ற கணிப்பு ({{employees}} ஊழியர்கள், {{days}} நாட்கள்) அங்கீகரிக்கப்பட்டு சம்பளப் பட்டியலுக்கு கிடைக்கிறது.', TRUE);

-- ------------------------------------------------------------------ workflow definitions (chains to be confirmed: SRS 11.1 #14)
INSERT INTO workflow_definition (version, created_at, created_by, process_code, name, description) VALUES
 (0, now(), 'system', 'LEAVE', 'Leave application', 'Employee leave applications'),
 (0, now(), 'system', 'LEAVE_CANCEL', 'Leave cancellation', 'Cancellation of approved leave; restores the balance on approval'),
 (0, now(), 'system', 'ATTENDANCE_CORRECTION', 'Attendance correction', 'Manual check-in / check-out corrections'),
 (0, now(), 'system', 'NOPAY_APPROVAL', 'Monthly no-pay approval', 'Review of the monthly no-pay calculation before release to payroll');

INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'Division head recommendation', 'DIVISION_HEAD', NULL, 48, 'HR_ADMIN' FROM workflow_definition WHERE process_code = 'LEAVE';
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 2, 'HR approval', 'ROLE', 'HR_ADMIN', 48, NULL FROM workflow_definition WHERE process_code = 'LEAVE';

INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'Division head recommendation', 'DIVISION_HEAD', NULL, 48, 'HR_ADMIN' FROM workflow_definition WHERE process_code = 'LEAVE_CANCEL';
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 2, 'HR approval', 'ROLE', 'HR_ADMIN', 48, NULL FROM workflow_definition WHERE process_code = 'LEAVE_CANCEL';

INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'Division head approval', 'DIVISION_HEAD', NULL, 72, 'HR_ADMIN' FROM workflow_definition WHERE process_code = 'ATTENDANCE_CORRECTION';

INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'Authorized officer approval', 'ROLE', 'HR_ADMIN', 72, NULL FROM workflow_definition WHERE process_code = 'NOPAY_APPROVAL';
