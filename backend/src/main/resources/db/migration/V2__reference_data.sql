-- =====================================================================================================
-- Reference data. Everything here is configuration that administrators can change through the UI (SYS-01).
-- Values marked "to be confirmed" mirror SRS Section 11 open items.
-- =====================================================================================================

-- ------------------------------------------------------------------ roles (SRS Section 9, proposed)
INSERT INTO role (version, created_at, created_by, code, name, description, system_role) VALUES
 (0, now(), 'system', 'SYSTEM_ADMIN', 'System Administrator', 'System configuration; no access to payroll or confidential HR content by default', TRUE),
 (0, now(), 'system', 'HR_ADMIN', 'HR Administrator', 'Employee and HR records', TRUE),
 (0, now(), 'system', 'ESTABLISHMENT_OFFICER', 'Establishment Officer', 'Structure, designations, cadre and legal documents', TRUE),
 (0, now(), 'system', 'ADMIN_OFFICER', 'Administrative Officer', 'Recruitment coordination, correspondence, initial approvals', TRUE),
 (0, now(), 'system', 'FINANCE_OFFICER', 'Finance Officer', 'Payroll, deductions and loans', TRUE),
 (0, now(), 'system', 'DIVISION_HEAD', 'Division Head / Supervisor', 'Approvals and evaluations for own division', TRUE),
 (0, now(), 'system', 'EMPLOYEE', 'Employee', 'Self-service', TRUE),
 (0, now(), 'system', 'MANAGEMENT', 'Management (Commission / DG / Directors)', 'Dashboards, reports and high-level approvals', TRUE),
 (0, now(), 'system', 'AUDITOR', 'Auditor', 'Read-only audit history and controls', TRUE);

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','MDM_MANAGE','WFL_CONFIG','UAM_MANAGE','NOTIF_CONFIG','SYS_CONFIG','EST_VIEW','LEGAL_VIEW']) p
WHERE r.code = 'SYSTEM_ADMIN';

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','EMP_VIEW','EMP_MANAGE','EMP_IMPORT','REC_VIEW','REC_MANAGE',
  'LEAVE_VIEW','LEAVE_MANAGE','LEAVE_CONFIG','ATT_VIEW','ATT_MANAGE','ATT_CONFIG','BEN_VIEW','BEN_MANAGE','PER_VIEW','PER_MANAGE',
  'TRN_VIEW','TRN_MANAGE','EST_VIEW','LEGAL_VIEW','RPT_VIEW','WFL_APPROVE','EXT_VIEW','EXT_MANAGE']) p
WHERE r.code = 'HR_ADMIN';

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','EST_VIEW','EST_MANAGE','LEGAL_VIEW','LEGAL_MANAGE','EMP_VIEW','RPT_VIEW']) p
WHERE r.code = 'ESTABLISHMENT_OFFICER';

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','REC_VIEW','REC_MANAGE','EMP_VIEW','LEAVE_VIEW','ATT_VIEW','TRN_VIEW',
  'WFL_APPROVE','RPT_VIEW','EST_VIEW','EXT_VIEW']) p
WHERE r.code = 'ADMIN_OFFICER';

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','PAY_VIEW','PAY_MANAGE','PAY_CONFIG','BEN_VIEW','EMP_VIEW','ATT_VIEW',
  'RPT_VIEW','RPT_SENSITIVE','WFL_APPROVE']) p
WHERE r.code = 'FINANCE_OFFICER';

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','WFL_APPROVE','PER_EVALUATE','LEAVE_VIEW','ATT_VIEW','EMP_VIEW','RPT_VIEW','TRN_VIEW']) p
WHERE r.code = 'DIVISION_HEAD';

INSERT INTO role_permission (role_id, permission)
SELECT r.id, 'SELF_SERVICE' FROM role r WHERE r.code = 'EMPLOYEE';

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','RPT_VIEW','RPT_SENSITIVE','WFL_APPROVE','EST_VIEW','LEGAL_VIEW','EMP_VIEW','EXT_VIEW']) p
WHERE r.code = 'MANAGEMENT';

INSERT INTO role_permission (role_id, permission)
SELECT r.id, p FROM role r, unnest(ARRAY['DASHBOARD_VIEW','AUDIT_VIEW','RPT_VIEW']) p
WHERE r.code = 'AUDITOR';

-- ------------------------------------------------------------------ master data
INSERT INTO master_data_item (version, created_at, created_by, category, code, name_en, name_si, name_ta, sort_order, effective_from) VALUES
 -- Employee status (FR-EMP-008; final list to be confirmed by Administration)
 (0, now(), 'system', 'EMPLOYEE_STATUS', 'ACTIVE', 'Active', 'සක්‍රිය', 'செயலில்', 1, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_STATUS', 'PROBATION', 'On probation', 'පරිවාස කාලයේ', 'தகுதிகாண் காலத்தில்', 2, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_STATUS', 'ON_LEAVE', 'On leave', 'නිවාඩු මත', 'விடுப்பில்', 3, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_STATUS', 'SECONDED', 'Seconded', 'අනුයුක්ත', 'இணைப்பில்', 4, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_STATUS', 'TRANSFERRED', 'Transferred out', 'ස්ථාන මාරු කළ', 'இடமாற்றம் செய்யப்பட்டது', 5, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_STATUS', 'RETIRED', 'Retired', 'විශ්‍රාමික', 'ஓய்வு பெற்றவர்', 6, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_STATUS', 'RESIGNED', 'Resigned', 'ඉල්ලා අස්වූ', 'பதவி விலகியவர்', 7, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_STATUS', 'TERMINATED', 'Terminated', 'සේවය අවසන් කළ', 'பணி நீக்கம்', 8, '2000-01-01'),
 -- Employment type
 (0, now(), 'system', 'EMPLOYMENT_TYPE', 'PERMANENT', 'Permanent', 'ස්ථිර', 'நிரந்தர', 1, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYMENT_TYPE', 'SECONDMENT', 'Secondment', 'අනුයුක්ත කිරීම', 'இணைப்பு', 2, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYMENT_TYPE', 'CONTRACT', 'Contract', 'කොන්ත්‍රාත්', 'ஒப்பந்தம்', 3, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYMENT_TYPE', 'TEMPORARY', 'Temporary / Casual', 'තාවකාලික', 'தற்காலிக', 4, '2000-01-01'),
 -- Employee category (drives leave entitlement, appraisal model, working schedule)
 (0, now(), 'system', 'EMPLOYEE_CATEGORY', 'SENIOR_MANAGEMENT', 'Senior management', 'ජ්‍යෙෂ්ඨ කළමනාකරණ', 'மூத்த முகாமைத்துவம்', 1, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_CATEGORY', 'EXECUTIVE', 'Executive', 'විධායක', 'நிறைவேற்று', 2, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_CATEGORY', 'INVESTIGATION', 'Investigation staff', 'විමර්ශන කාර්ය මණ්ඩලය', 'விசாரணை ஊழியர்கள்', 3, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_CATEGORY', 'TERTIARY', 'Tertiary / technological', 'තෘතීයික', 'மூன்றாம் நிலை', 4, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_CATEGORY', 'SECONDARY', 'Management assistant / secondary', 'කළමනාකරණ සහකාර', 'முகாமைத்துவ உதவியாளர்', 5, '2000-01-01'),
 (0, now(), 'system', 'EMPLOYEE_CATEGORY', 'PRIMARY', 'Primary level', 'ප්‍රාථමික මට්ටම', 'ஆரம்ப நிலை', 6, '2000-01-01'),
 -- Service category
 (0, now(), 'system', 'SERVICE_CATEGORY', 'COMMISSION_SERVICE', 'CIABOC service', 'කොමිෂන් සේවය', 'ஆணைக்குழு சேவை', 1, '2000-01-01'),
 (0, now(), 'system', 'SERVICE_CATEGORY', 'COMBINED_SERVICE', 'Combined service', 'ඒකාබද්ධ සේවය', 'ஒருங்கிணைந்த சேவை', 2, '2000-01-01'),
 (0, now(), 'system', 'SERVICE_CATEGORY', 'SLAS', 'Sri Lanka Administrative Service', 'ශ්‍රී ලංකා පරිපාලන සේවය', 'இலங்கை நிர்வாக சேவை', 3, '2000-01-01'),
 (0, now(), 'system', 'SERVICE_CATEGORY', 'POLICE', 'Police (attached)', 'පොලිස් (අනුයුක්ත)', 'பொலிஸ் (இணைப்பு)', 4, '2000-01-01'),
 -- Grades
 (0, now(), 'system', 'GRADE', 'SL4', 'SL-4', NULL, NULL, 1, '2000-01-01'),
 (0, now(), 'system', 'GRADE', 'SL3', 'SL-3', NULL, NULL, 2, '2000-01-01'),
 (0, now(), 'system', 'GRADE', 'SL1', 'SL-1', NULL, NULL, 3, '2000-01-01'),
 (0, now(), 'system', 'GRADE', 'MN7', 'MN-7', NULL, NULL, 4, '2000-01-01'),
 (0, now(), 'system', 'GRADE', 'MN4', 'MN-4', NULL, NULL, 5, '2000-01-01'),
 (0, now(), 'system', 'GRADE', 'MN2', 'MN-2', NULL, NULL, 6, '2000-01-01'),
 (0, now(), 'system', 'GRADE', 'MN1', 'MN-1', NULL, NULL, 7, '2000-01-01'),
 (0, now(), 'system', 'GRADE', 'PL1', 'PL-1', NULL, NULL, 8, '2000-01-01'),
 -- Titles, gender, civil status
 (0, now(), 'system', 'TITLE', 'MR', 'Mr', 'මයා', 'திரு', 1, '2000-01-01'),
 (0, now(), 'system', 'TITLE', 'MRS', 'Mrs', 'මිය', 'திருமதி', 2, '2000-01-01'),
 (0, now(), 'system', 'TITLE', 'MS', 'Ms', 'මෙනවිය', 'செல்வி', 3, '2000-01-01'),
 (0, now(), 'system', 'TITLE', 'DR', 'Dr', 'වෛද්‍ය/ආචාර්ය', 'கலாநிதி', 4, '2000-01-01'),
 (0, now(), 'system', 'GENDER', 'MALE', 'Male', 'පුරුෂ', 'ஆண்', 1, '2000-01-01'),
 (0, now(), 'system', 'GENDER', 'FEMALE', 'Female', 'ස්ත්‍රී', 'பெண்', 2, '2000-01-01'),
 (0, now(), 'system', 'CIVIL_STATUS', 'SINGLE', 'Single', 'අවිවාහක', 'திருமணமாகாதவர்', 1, '2000-01-01'),
 (0, now(), 'system', 'CIVIL_STATUS', 'MARRIED', 'Married', 'විවාහක', 'திருமணமானவர்', 2, '2000-01-01'),
 (0, now(), 'system', 'CIVIL_STATUS', 'WIDOWED', 'Widowed', 'වැන්දඹු', 'விதவை', 3, '2000-01-01'),
 (0, now(), 'system', 'CIVIL_STATUS', 'DIVORCED', 'Divorced', 'දික්කසාද', 'விவாகரத்து', 4, '2000-01-01'),
 -- Document types for the digital personal file
 (0, now(), 'system', 'DOCUMENT_TYPE', 'APPOINTMENT_LETTER', 'Appointment letter', 'පත්වීම් ලිපිය', 'நியமனக் கடிதம்', 1, '2000-01-01'),
 (0, now(), 'system', 'DOCUMENT_TYPE', 'NIC_COPY', 'NIC copy', 'ජා.හැ.ප. පිටපත', 'தே.அ.அ. நகல்', 2, '2000-01-01'),
 (0, now(), 'system', 'DOCUMENT_TYPE', 'BIRTH_CERTIFICATE', 'Birth certificate', 'උප්පැන්න සහතිකය', 'பிறப்புச் சான்றிதழ்', 3, '2000-01-01'),
 (0, now(), 'system', 'DOCUMENT_TYPE', 'QUALIFICATION', 'Qualification certificate', 'සුදුසුකම් සහතිකය', 'தகுதிச் சான்றிதழ்', 4, '2000-01-01'),
 (0, now(), 'system', 'DOCUMENT_TYPE', 'CONFIRMATION_LETTER', 'Confirmation letter', 'ස්ථිර කිරීමේ ලිපිය', 'உறுதிப்படுத்தல் கடிதம்', 5, '2000-01-01'),
 (0, now(), 'system', 'DOCUMENT_TYPE', 'MEDICAL', 'Medical record', 'වෛද්‍ය වාර්තාව', 'மருத்துவ பதிவு', 6, '2000-01-01'),
 (0, now(), 'system', 'DOCUMENT_TYPE', 'OTHER', 'Other', 'වෙනත්', 'ஏனையவை', 99, '2000-01-01'),
 -- Qualification types
 (0, now(), 'system', 'QUALIFICATION_TYPE', 'GCE_OL', 'G.C.E. (O/L)', 'අ.පො.ස. (සා/පෙළ)', 'க.பொ.த. (சா/த)', 1, '2000-01-01'),
 (0, now(), 'system', 'QUALIFICATION_TYPE', 'GCE_AL', 'G.C.E. (A/L)', 'අ.පො.ස. (උ/පෙළ)', 'க.பொ.த. (உ/த)', 2, '2000-01-01'),
 (0, now(), 'system', 'QUALIFICATION_TYPE', 'DIPLOMA', 'Diploma', 'ඩිප්ලෝමා', 'டிப்ளோமா', 3, '2000-01-01'),
 (0, now(), 'system', 'QUALIFICATION_TYPE', 'DEGREE', 'Degree', 'උපාධිය', 'பட்டம்', 4, '2000-01-01'),
 (0, now(), 'system', 'QUALIFICATION_TYPE', 'POSTGRADUATE', 'Postgraduate', 'පශ්චාත් උපාධි', 'முதுகலை', 5, '2000-01-01'),
 (0, now(), 'system', 'QUALIFICATION_TYPE', 'PROFESSIONAL', 'Professional', 'වෘත්තීය', 'தொழில்முறை', 6, '2000-01-01'),
 -- Loan types (seconded-employee loans; FR-PAY-020)
 (0, now(), 'system', 'LOAN_TYPE', 'DISTRESS', 'Distress loan', 'ආපදා ණය', 'துயர் கடன்', 1, '2000-01-01'),
 (0, now(), 'system', 'LOAN_TYPE', 'PROPERTY', 'Property loan', 'දේපළ ණය', 'சொத்துக் கடன்', 2, '2000-01-01'),
 (0, now(), 'system', 'LOAN_TYPE', 'FESTIVAL', 'Festival advance', 'උත්සව අත්තිකාරම්', 'பண்டிகை முற்பணம்', 3, '2000-01-01'),
 (0, now(), 'system', 'LOAN_TYPE', 'BANK', 'Bank loan (salary deduction)', 'බැංකු ණය', 'வங்கிக் கடன்', 4, '2000-01-01'),
 -- Banks
 (0, now(), 'system', 'BANK', 'BOC', 'Bank of Ceylon', 'ලංකා බැංකුව', 'இலங்கை வங்கி', 1, '2000-01-01'),
 (0, now(), 'system', 'BANK', 'PEOPLES', 'People''s Bank', 'මහජන බැංකුව', 'மக்கள் வங்கி', 2, '2000-01-01'),
 (0, now(), 'system', 'BANK', 'NSB', 'National Savings Bank', 'ජාතික ඉතිරිකිරීමේ බැංකුව', 'தேசிய சேமிப்பு வங்கி', 3, '2000-01-01'),
 -- Insurance categories
 (0, now(), 'system', 'INSURANCE_CATEGORY', 'HEALTH', 'Health', 'සෞඛ්‍ය', 'சுகாதாரம்', 1, '2000-01-01'),
 (0, now(), 'system', 'INSURANCE_CATEGORY', 'LIFE', 'Life', 'ජීවිත', 'ஆயுள்', 2, '2000-01-01'),
 (0, now(), 'system', 'INSURANCE_CATEGORY', 'ACCIDENT', 'Accident', 'අනතුරු', 'விபத்து', 3, '2000-01-01'),
 -- Legal provision topics (FR-EST-042)
 (0, now(), 'system', 'LEGAL_TOPIC', 'APPOINTMENT', 'Appointment', 'පත්කිරීම', 'நியமனம்', 1, '2000-01-01'),
 (0, now(), 'system', 'LEGAL_TOPIC', 'POWERS_FUNCTIONS', 'Powers and functions', 'බලතල සහ කාර්යයන්', 'அதிகாரங்களும் செயற்பாடுகளும்', 2, '2000-01-01'),
 (0, now(), 'system', 'LEGAL_TOPIC', 'QUALIFICATIONS', 'Qualifications', 'සුදුසුකම්', 'தகைமைகள்', 3, '2000-01-01'),
 (0, now(), 'system', 'LEGAL_TOPIC', 'TENURE', 'Tenure', 'ධුර කාලය', 'பதவிக்காலம்', 4, '2000-01-01'),
 (0, now(), 'system', 'LEGAL_TOPIC', 'DUTIES', 'Duties', 'රාජකාරි', 'கடமைகள்', 5, '2000-01-01'),
 -- Separation reasons
 (0, now(), 'system', 'SEPARATION_REASON', 'AGE_RETIREMENT', 'Retirement on age', 'වයස අනුව විශ්‍රාම', 'வயது அடிப்படை ஓய்வு', 1, '2000-01-01'),
 (0, now(), 'system', 'SEPARATION_REASON', 'VOLUNTARY_RETIREMENT', 'Voluntary retirement', 'ස්වේච්ඡා විශ්‍රාම', 'தன்னார்வ ஓய்வு', 2, '2000-01-01'),
 (0, now(), 'system', 'SEPARATION_REASON', 'RESIGNATION', 'Resignation', 'ඉල්ලා අස්වීම', 'பதவி விலகல்', 3, '2000-01-01'),
 (0, now(), 'system', 'SEPARATION_REASON', 'END_OF_SECONDMENT', 'End of secondment', 'අනුයුක්තිය අවසන්', 'இணைப்பு முடிவு', 4, '2000-01-01'),
 (0, now(), 'system', 'SEPARATION_REASON', 'TRANSFER_OUT', 'Transfer out', 'පිටතට ස්ථාන මාරු', 'வெளி இடமாற்றம்', 5, '2000-01-01'),
 (0, now(), 'system', 'SEPARATION_REASON', 'DISMISSAL', 'Dismissal / termination', 'සේවයෙන් පහ කිරීම', 'பணிநீக்கம்', 6, '2000-01-01'),
 (0, now(), 'system', 'SEPARATION_REASON', 'DEATH', 'Death', 'මරණය', 'மரணம்', 7, '2000-01-01'),
 -- Relationships
 (0, now(), 'system', 'RELATIONSHIP', 'SPOUSE', 'Spouse', 'කලත්‍රයා', 'வாழ்க்கைத் துணை', 1, '2000-01-01'),
 (0, now(), 'system', 'RELATIONSHIP', 'PARENT', 'Parent', 'දෙමාපියන්', 'பெற்றோர்', 2, '2000-01-01'),
 (0, now(), 'system', 'RELATIONSHIP', 'CHILD', 'Child', 'දරුවා', 'பிள்ளை', 3, '2000-01-01'),
 (0, now(), 'system', 'RELATIONSHIP', 'SIBLING', 'Sibling', 'සහෝදර/සහෝදරිය', 'உடன்பிறப்பு', 4, '2000-01-01'),
 (0, now(), 'system', 'RELATIONSHIP', 'OTHER', 'Other', 'වෙනත්', 'ஏனையவை', 9, '2000-01-01');

-- ------------------------------------------------------------------ system settings
INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('org.name', 'Commission to Investigate Allegations of Bribery or Corruption', 'GENERAL', 'STRING', 'Organization name printed on reports and letters'),
 ('session.idle-minutes', '15', 'SECURITY', 'INT', 'Idle minutes before the web session is locked (FR-UAM-007)'),
 ('employee.retirement-age', '60', 'EMPLOYEE', 'INT', 'Default retirement age used to compute retirement dates (to be confirmed)'),
 ('employee.maker-checker.enabled', 'true', 'EMPLOYEE', 'BOOLEAN', 'Require second-person approval for sensitive employee fields (FR-EMP-012)'),
 ('reports.max-rows', '10000', 'REPORTS', 'INT', 'Maximum rows returned by a single report run');

-- ------------------------------------------------------------------ integrations (SRS 3.2) - all off until approved
INSERT INTO integration_config (version, created_at, created_by, code, ref_id, name, purpose, priority, enabled, notes) VALUES
 (0, now(), 'system', 'SSO_IAM', 'INT-01', 'CIABOC SSO / IAM', 'Authentication, SSO, MFA, role sync, joiner-mover-leaver', 'M', FALSE, 'Local password + OTP is used until the SSO platform is connected'),
 (0, now(), 'system', 'EMAIL', 'INT-02', 'E-mail service', 'HR notifications and generated letters with delivery status', 'M', TRUE, 'Messages are simulated when no SMTP host is configured'),
 (0, now(), 'system', 'SMS', 'INT-03', 'SMS service', 'Notifications where required', 'S', FALSE, NULL),
 (0, now(), 'system', 'ATTENDANCE_DEVICE', 'INT-04', 'Attendance system / biometric devices', 'Check-in/check-out events with exception and tamper controls', 'M', FALSE, 'Device model and export format to be confirmed in discovery'),
 (0, now(), 'system', 'FINANCE_BANK', 'INT-05', 'Finance, payroll and banking', 'Payroll posting, bank advice, reconciliation', 'S', FALSE, NULL),
 (0, now(), 'system', 'INSURANCE_PROVIDER', 'INT-06', 'External insurance providers', 'Insurance information; future API', 'C', FALSE, NULL),
 (0, now(), 'system', 'PENSION_STATUTORY', 'INT-07', 'Pensions and statutory entities', 'Retirement processing and statutory submissions', 'C', FALSE, NULL),
 (0, now(), 'system', 'DOCUMENT_SIGNATURE', 'INT-08', 'Document and signature services', 'Digital signatures and verification codes', 'C', FALSE, NULL),
 (0, now(), 'system', 'GOV_IDENTITY', 'INT-09', 'Government identity / data exchange', 'Future authorized identity verification', 'C', FALSE, NULL),
 (0, now(), 'system', 'CIABOC_APPS', 'INT-10', 'Other CIABOC applications', 'Minimum necessary organizational/employee reference data', 'S', FALSE, 'Must never expose asset-declaration contents (C-04)'),
 (0, now(), 'system', 'SECURITY_OPS', 'INT-11', 'Security operations tooling', 'Forwarding of security events to central monitoring', 'S', FALSE, NULL);

-- ------------------------------------------------------------------ notification templates (trilingual; translations to be reviewed)
INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'WFL_PENDING', 'Sent to approvers when a request reaches their stage',
  'Approval required: {{title}}', 'අනුමැතිය අවශ්‍යයි: {{title}}', 'ஒப்புதல் தேவை: {{title}}',
  'Request {{reference}} from {{requester}} is awaiting your action at stage "{{stage}}".',
  '{{requester}} වෙතින් ලැබුණු ඉල්ලීම {{reference}} "{{stage}}" අදියරේදී ඔබගේ ක්‍රියාමාර්ගය අපේක්ෂා කරයි.',
  '{{requester}} இடமிருந்து வந்த கோரிக்கை {{reference}} "{{stage}}" நிலையில் உங்கள் நடவடிக்கைக்காக காத்திருக்கிறது.', TRUE),
 (0, now(), 'system', 'WFL_APPROVED', 'Sent to the requester when fully approved',
  'Approved: {{title}}', 'අනුමතයි: {{title}}', 'அங்கீகரிக்கப்பட்டது: {{title}}',
  'Your request {{reference}} has been approved. {{comment}}',
  'ඔබගේ ඉල්ලීම {{reference}} අනුමත කර ඇත. {{comment}}',
  'உங்கள் கோரிக்கை {{reference}} அங்கீகரிக்கப்பட்டது. {{comment}}', TRUE),
 (0, now(), 'system', 'WFL_REJECTED', 'Sent to the requester when rejected',
  'Rejected: {{title}}', 'ප්‍රතික්ෂේපිතයි: {{title}}', 'நிராகரிக்கப்பட்டது: {{title}}',
  'Your request {{reference}} was rejected. Reason: {{comment}}',
  'ඔබගේ ඉල්ලීම {{reference}} ප්‍රතික්ෂේප විය. හේතුව: {{comment}}',
  'உங்கள் கோரிக்கை {{reference}} நிராகரிக்கப்பட்டது. காரணம்: {{comment}}', TRUE),
 (0, now(), 'system', 'WFL_RETURNED', 'Sent to the requester when returned for correction',
  'Returned for correction: {{title}}', 'නිවැරදි කිරීමට ආපසු එවා ඇත: {{title}}', 'திருத்தத்திற்காக திருப்பி அனுப்பப்பட்டது: {{title}}',
  'Your request {{reference}} needs correction: {{comment}}',
  'ඔබගේ ඉල්ලීම {{reference}} නිවැරදි කළ යුතුය: {{comment}}',
  'உங்கள் கோரிக்கை {{reference}} திருத்தப்பட வேண்டும்: {{comment}}', TRUE),
 (0, now(), 'system', 'WFL_ESCALATED', 'Sent when an approval exceeds its time limit',
  'Overdue approval: {{title}}', 'කල් ඉකුත් වූ අනුමැතිය: {{title}}', 'தாமதமான ஒப்புதல்: {{title}}',
  'Request {{reference}} has exceeded the time allowed at stage "{{stage}}".',
  'ඉල්ලීම {{reference}} "{{stage}}" අදියරේ නියමිත කාලය ඉක්මවා ඇත.',
  'கோரிக்கை {{reference}} "{{stage}}" நிலையில் அனுமதிக்கப்பட்ட நேரத்தை மீறியுள்ளது.', TRUE);

-- ------------------------------------------------------------------ workflow definitions (chains to be confirmed: SRS 11.1 #14)
INSERT INTO workflow_definition (version, created_at, created_by, process_code, name, description) VALUES
 (0, now(), 'system', 'ESTABLISHMENT_CHANGE', 'Establishment change', 'Changes to structure, designations, positions and cadre'),
 (0, now(), 'system', 'PROFILE_CHANGE', 'Employee profile change', 'Self-service profile updates and maker-checker on sensitive fields');

INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'Director General approval', 'ROLE', 'MANAGEMENT', 120, NULL FROM workflow_definition WHERE process_code = 'ESTABLISHMENT_CHANGE';
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'HR verification', 'ROLE', 'HR_ADMIN', 72, NULL FROM workflow_definition WHERE process_code = 'PROFILE_CHANGE';
