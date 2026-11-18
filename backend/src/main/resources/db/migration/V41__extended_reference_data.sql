-- =====================================================================================================
-- Extended scope reference data (Release 5). All values are configuration editable through the UI.
-- Sinhala and Tamil wording is provisional and must be reviewed by CIABOC before go-live.
-- =====================================================================================================

-- ------------------------------------------------------------------ settings
INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('ext.movement.cadre-blocking', 'false', 'EXTENDED', 'BOOLEAN', 'Block transfer/promotion requests when the target designation and unit has no approved vacancy (false = warn only)'),
 ('ext.retirement.forecast-months', '12', 'EXTENDED', 'INT', 'Default horizon (months) of the retirement forecast'),
 ('ext.retirement.notice-lead-months', '12,6,3,1', 'EXTENDED', 'STRING', 'Comma-separated lead times (months before retirement) at which HR is notified'),
 ('ext.retirement.notify-role', 'HR_ADMIN', 'EXTENDED', 'STRING', 'Role notified of upcoming retirements'),
 ('ext.separation.require-settlement', 'true', 'EXTENDED', 'BOOLEAN', 'Require final-settlement inputs from Finance before a separation case can be completed'),
 ('ext.separation.settlement-role', 'FINANCE_OFFICER', 'EXTENDED', 'STRING', 'Role that records final-settlement inputs on separation cases'),
 ('ext.letters.approval-required', 'true', 'EXTENDED', 'BOOLEAN', 'Route employee letter requests through the LETTER_REQUEST approval before HR issues them'),
 ('ext.letters.document-prefix', 'CIABOC/HR', 'EXTENDED', 'STRING', 'Prefix of issued letter and certificate numbers'),
 ('ext.letters.signatory', 'Director (Administration)', 'EXTENDED', 'STRING', 'Signatory designation printed on letters and certificates'),
 ('ext.verify.base-url', 'https://hrms.ciaboc.gov.lk', 'EXTENDED', 'STRING', 'Public base URL printed on documents for verification'),
 ('ext.verify.rate-limit', '30', 'EXTENDED', 'INT', 'Maximum public verification look-ups per client IP per window'),
 ('ext.verify.rate-window-minutes', '10', 'EXTENDED', 'INT', 'Window (minutes) for the public verification rate limit')
ON CONFLICT (setting_key) DO NOTHING;

-- ------------------------------------------------------------------ notification templates
INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'EXT_RETIREMENT_DUE', 'Advance notice to HR of an upcoming retirement',
  'Upcoming retirement: {{employeeNo}} {{employeeName}}', 'ඉදිරි විශ්‍රාම ගැනීම: {{employeeNo}} {{employeeName}}', 'வரவிருக்கும் ஓய்வு: {{employeeNo}} {{employeeName}}',
  '{{employeeName}} ({{employeeNo}}), {{designation}}, is due to retire on {{retirementDate}} (in about {{months}} month(s)). Please start retirement processing.',
  '{{employeeName}} ({{employeeNo}}), {{designation}}, {{retirementDate}} දින විශ්‍රාම යාමට නියමිතය (මාස {{months}} පමණ). කරුණාකර විශ්‍රාම කටයුතු ආරම්භ කරන්න.',
  '{{employeeName}} ({{employeeNo}}), {{designation}}, {{retirementDate}} அன்று ஓய்வு பெற உள்ளார் (சுமார் {{months}} மாதங்கள்). ஓய்வு நடவடிக்கைகளைத் தொடங்கவும்.', TRUE),
 (0, now(), 'system', 'EXT_MOVEMENT_APPLIED', 'Sent to the employee when a transfer, promotion or confirmation takes effect',
  'Service record updated: {{type}}', 'සේවා වාර්තාව යාවත්කාලීන විය: {{type}}', 'சேவைப் பதிவு புதுப்பிக்கப்பட்டது: {{type}}',
  'Your {{type}} effective {{effectiveDate}} has been approved and recorded. {{details}}',
  '{{effectiveDate}} සිට බලපැවැත්වෙන ඔබගේ {{type}} අනුමත කර වාර්තා කර ඇත. {{details}}',
  '{{effectiveDate}} முதல் நடைமுறைக்கு வரும் உங்கள் {{type}} அங்கீகரிக்கப்பட்டு பதிவு செய்யப்பட்டது. {{details}}', TRUE),
 (0, now(), 'system', 'EXT_LETTER_ISSUED', 'Sent to the employee when a requested letter or certificate is issued',
  'Your {{document}} is ready', 'ඔබගේ {{document}} සූදානම්', 'உங்கள் {{document}} தயார்',
  'Document {{documentNo}} has been issued. You can view and print it under My letters.',
  'ලේඛනය {{documentNo}} නිකුත් කර ඇත. "මගේ ලිපි" යටතේ එය බලා මුද්‍රණය කළ හැක.',
  'ஆவணம் {{documentNo}} வழங்கப்பட்டுள்ளது. "எனது கடிதங்கள்" பகுதியில் பார்த்து அச்சிடலாம்.', TRUE),
 (0, now(), 'system', 'EXT_LETTER_REQUESTED', 'Sent to HR when an employee requests a letter that needs no approval',
  'Letter request: {{document}}', 'ලිපි ඉල්ලීම: {{document}}', 'கடிதக் கோரிக்கை: {{document}}',
  '{{employeeName}} has requested a {{document}}. Purpose: {{purpose}}',
  '{{employeeName}} {{document}} ඉල්ලා ඇත. අරමුණ: {{purpose}}',
  '{{employeeName}} {{document}} கோரியுள்ளார். நோக்கம்: {{purpose}}', FALSE),
 (0, now(), 'system', 'EXT_CLEARANCE_PENDING', 'Sent to the responsible role when a separation clearance item is opened',
  'Clearance required: {{employeeNo}} {{employeeName}}', 'නිෂ්කාශනය අවශ්‍යයි: {{employeeNo}} {{employeeName}}', 'அனுமதிச் சான்று தேவை: {{employeeNo}} {{employeeName}}',
  'Please complete "{{item}}" for {{employeeName}} ({{employeeNo}}), separating on {{separationDate}}.',
  '{{separationDate}} දින සේවයෙන් ඉවත් වන {{employeeName}} ({{employeeNo}}) සඳහා "{{item}}" සම්පූර්ණ කරන්න.',
  '{{separationDate}} அன்று விலகும் {{employeeName}} ({{employeeNo}}) க்கான "{{item}}" ஐ நிறைவு செய்யவும்.', TRUE),
 (0, now(), 'system', 'EXT_DIGITIZATION_QA', 'Sent to HR when a digitization batch is submitted for quality verification',
  'Digitization batch awaiting QA: {{batchNo}}', 'ගුණතා පරීක්ෂාව අපේක්ෂිත ඩිජිටල්කරණ කාණ්ඩය: {{batchNo}}', 'தர சரிபார்ப்புக்காக காத்திருக்கும் இலக்கமயமாக்கல் தொகுதி: {{batchNo}}',
  'Batch {{batchNo}} ({{title}}) with {{count}} scanned document(s) was submitted by {{submittedBy}} and needs verification by a second officer.',
  'ස්කෑන් කළ ලේඛන {{count}} ක් සහිත {{batchNo}} ({{title}}) කාණ්ඩය {{submittedBy}} විසින් ඉදිරිපත් කර ඇති අතර දෙවන නිලධාරියෙකු විසින් සත්‍යාපනය කළ යුතුය.',
  '{{count}} ஸ்கேன் ஆவணங்களுடன் தொகுதி {{batchNo}} ({{title}}) {{submittedBy}} ஆல் சமர்ப்பிக்கப்பட்டது; இரண்டாவது அலுவலர் சரிபார்க்க வேண்டும்.', FALSE)
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------------ workflow definitions (chains to be confirmed: SRS 11.1 #14)
INSERT INTO workflow_definition (version, created_at, created_by, process_code, name, description) VALUES
 (0, now(), 'system', 'TRANSFER', 'Internal transfer', 'Transfer of an employee to another unit (FR-EXT-001)'),
 (0, now(), 'system', 'PROMOTION', 'Promotion', 'Promotion of an employee to a higher designation (FR-EXT-001)'),
 (0, now(), 'system', 'SERVICE_CONFIRMATION', 'Confirmation in service', 'Confirmation of an employee in service after probation (FR-EXT-001)'),
 (0, now(), 'system', 'LETTER_REQUEST', 'Letter / certificate request', 'Employee request for a service letter or certificate (FR-EXT-005)')
ON CONFLICT (process_code) DO NOTHING;

INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT d.id, s.stage_order, s.name, 'ROLE', s.role, s.sla, NULL
FROM workflow_definition d
JOIN (VALUES
  ('TRANSFER', 1, 'HR review', 'HR_ADMIN', 72),
  ('TRANSFER', 2, 'Director General approval', 'MANAGEMENT', 120),
  ('PROMOTION', 1, 'HR review', 'HR_ADMIN', 72),
  ('PROMOTION', 2, 'Director General approval', 'MANAGEMENT', 120),
  ('SERVICE_CONFIRMATION', 1, 'HR review', 'HR_ADMIN', 72),
  ('SERVICE_CONFIRMATION', 2, 'Director General approval', 'MANAGEMENT', 120),
  ('LETTER_REQUEST', 1, 'HR approval', 'HR_ADMIN', 48)
) AS s(process_code, stage_order, name, role, sla) ON s.process_code = d.process_code
WHERE NOT EXISTS (SELECT 1 FROM workflow_stage x WHERE x.definition_id = d.id AND x.stage_order = s.stage_order);

-- ------------------------------------------------------------------ separation types
INSERT INTO ext_separation_type (version, created_at, created_by, code, name_en, name_si, name_ta, employee_status, default_separation_reason, sort_order) VALUES
 (0, now(), 'system', 'RETIREMENT', 'Retirement', 'විශ්‍රාම ගැනීම', 'ஓய்வு', 'RETIRED', 'AGE_RETIREMENT', 1),
 (0, now(), 'system', 'RESIGNATION', 'Resignation', 'ඉල්ලා අස්වීම', 'பதவி விலகல்', 'RESIGNED', 'RESIGNATION', 2),
 (0, now(), 'system', 'END_OF_SECONDMENT', 'End of secondment', 'අනුයුක්තිය අවසන් වීම', 'இணைப்பு முடிவு', 'TRANSFERRED', 'END_OF_SECONDMENT', 3),
 (0, now(), 'system', 'TRANSFER_OUT', 'Transfer out', 'පිටතට ස්ථාන මාරුව', 'வெளி இடமாற்றம்', 'TRANSFERRED', 'TRANSFER_OUT', 4)
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------------ clearance checklist templates
INSERT INTO ext_clearance_item_template (version, created_at, created_by, code, name_en, name_si, name_ta, description, responsible_role, applies_to, sort_order) VALUES
 (0, now(), 'system', 'ID_CARD', 'Return of official identity card', 'නිල හැඳුනුම්පත ආපසු භාරදීම', 'உத்தியோகபூர்வ அடையாள அட்டையை ஒப்படைத்தல்', 'Staff ID card and access tokens returned to HR', 'HR_ADMIN', NULL, 1),
 (0, now(), 'system', 'ICT_ASSETS', 'ICT asset return and account handover', 'තොරතුරු තාක්ෂණ උපකරණ ආපසු භාරදීම', 'தகவல் தொழில்நுட்ப சொத்துகளை ஒப்படைத்தல்', 'Laptop, phones, storage media returned; mailbox and system accounts handed over', 'SYSTEM_ADMIN', NULL, 2),
 (0, now(), 'system', 'FINANCE', 'Finance clearance', 'මුදල් නිෂ්කාශනය', 'நிதி அனுமதி', 'Outstanding loans, advances and dues settled or recovered', 'FINANCE_OFFICER', NULL, 3),
 (0, now(), 'system', 'LIBRARY', 'Library clearance', 'පුස්තකාල නිෂ්කාශනය', 'நூலக அனுமதி', 'Borrowed books and publications returned', 'ADMIN_OFFICER', NULL, 4),
 (0, now(), 'system', 'FILE_HANDOVER', 'Handover of files and case records', 'ලිපිගොනු සහ නඩු වාර්තා භාරදීම', 'கோப்புகள் மற்றும் வழக்குப் பதிவுகளை ஒப்படைத்தல்', 'Official files, case records and work in progress handed over to the supervisor', 'DIVISION_HEAD', NULL, 5)
ON CONFLICT (code) DO NOTHING;

-- ------------------------------------------------------------------ letter and certificate templates
-- Placeholders: {{employee.title}} {{employee.fullName}} {{employee.nameWithInitials}} {{employee.employeeNo}}
-- {{employee.nic}} {{employee.designation}} {{employee.orgUnit}} {{employee.appointmentDate}}
-- {{employee.confirmationDate}} {{employee.retirementDate}} {{employee.employmentType}} {{org.name}}
-- {{document.no}} {{document.date}} {{request.purpose}} {{request.addressee}} {{signatory}} {{field.<name>}}
INSERT INTO ext_letter_template (version, created_at, created_by, code, language, name, document_kind, title, body_html, self_service) VALUES
 (0, now(), 'system', 'SERVICE_LETTER', 'en', 'Service letter', 'LETTER', 'Service Letter',
  '<p>{{request.addressee}}</p><p>This is to certify that <strong>{{employee.title}} {{employee.fullName}}</strong> (Employee No. {{employee.employeeNo}}, NIC {{employee.nic}}) has been serving at the {{org.name}} since {{employee.appointmentDate}} and presently holds the post of <strong>{{employee.designation}}</strong> in the {{employee.orgUnit}}.</p><p>This letter is issued at the request of the officer for the purpose of {{request.purpose}}.</p>', TRUE),
 (0, now(), 'system', 'SERVICE_LETTER', 'si', 'සේවා ලිපිය', 'LETTER', 'සේවා ලිපිය',
  '<p>{{request.addressee}}</p><p><strong>{{employee.title}} {{employee.fullName}}</strong> (සේවක අංකය {{employee.employeeNo}}, ජා.හැ.අ. {{employee.nic}}) {{employee.appointmentDate}} සිට {{org.name}} හි සේවය කරන බවත්, දැනට {{employee.orgUnit}} හි <strong>{{employee.designation}}</strong> තනතුර දරන බවත් මෙයින් සහතික කරමි.</p><p>මෙම ලිපිය {{request.purpose}} සඳහා නිලධාරියාගේ ඉල්ලීම පරිදි නිකුත් කරනු ලැබේ.</p>', TRUE),
 (0, now(), 'system', 'SERVICE_LETTER', 'ta', 'சேவைக் கடிதம்', 'LETTER', 'சேவைக் கடிதம்',
  '<p>{{request.addressee}}</p><p><strong>{{employee.title}} {{employee.fullName}}</strong> (பணியாளர் இல. {{employee.employeeNo}}, தே.அ.அ. {{employee.nic}}) {{employee.appointmentDate}} முதல் {{org.name}} இல் பணியாற்றி வருவதுடன் தற்போது {{employee.orgUnit}} இல் <strong>{{employee.designation}}</strong> பதவியை வகிக்கிறார் என இத்தால் உறுதிப்படுத்துகிறேன்.</p><p>இக்கடிதம் {{request.purpose}} நோக்கத்திற்காக அலுவலரின் கோரிக்கையின் பேரில் வழங்கப்படுகிறது.</p>', TRUE),
 (0, now(), 'system', 'SALARY_CONFIRMATION', 'en', 'Salary confirmation letter', 'LETTER', 'Salary Confirmation',
  '<p>{{request.addressee}}</p><p>This is to confirm that <strong>{{employee.title}} {{employee.fullName}}</strong> (Employee No. {{employee.employeeNo}}) is a {{employee.employmentType}} employee of the {{org.name}}, holding the post of {{employee.designation}}.</p><p>The officer''s gross monthly salary is <strong>Rs. {{field.grossSalary}}</strong> and the net monthly salary is <strong>Rs. {{field.netSalary}}</strong>.</p><p>This letter is issued for the purpose of {{request.purpose}} and the Commission accepts no financial liability thereby.</p>', TRUE),
 (0, now(), 'system', 'SALARY_CONFIRMATION', 'si', 'වැටුප් තහවුරු කිරීමේ ලිපිය', 'LETTER', 'වැටුප් තහවුරු කිරීම',
  '<p>{{request.addressee}}</p><p><strong>{{employee.title}} {{employee.fullName}}</strong> (සේවක අංකය {{employee.employeeNo}}) {{org.name}} හි {{employee.designation}} තනතුර දරන {{employee.employmentType}} සේවකයෙකු බව තහවුරු කරමි.</p><p>නිලධාරියාගේ දළ මාසික වැටුප <strong>රු. {{field.grossSalary}}</strong> ද ශුද්ධ මාසික වැටුප <strong>රු. {{field.netSalary}}</strong> ද වේ.</p><p>මෙම ලිපිය {{request.purpose}} සඳහා නිකුත් කරන අතර ඒ හේතුවෙන් කොමිෂන් සභාව කිසිදු මූල්‍ය වගකීමක් භාර නොගනී.</p>', TRUE),
 (0, now(), 'system', 'SALARY_CONFIRMATION', 'ta', 'சம்பள உறுதிப்படுத்தல் கடிதம்', 'LETTER', 'சம்பள உறுதிப்படுத்தல்',
  '<p>{{request.addressee}}</p><p><strong>{{employee.title}} {{employee.fullName}}</strong> (பணியாளர் இல. {{employee.employeeNo}}) {{org.name}} இல் {{employee.designation}} பதவி வகிக்கும் {{employee.employmentType}} பணியாளர் என உறுதிப்படுத்துகிறேன்.</p><p>அலுவலரின் மொத்த மாதச் சம்பளம் <strong>ரூ. {{field.grossSalary}}</strong>, தேறிய மாதச் சம்பளம் <strong>ரூ. {{field.netSalary}}</strong>.</p><p>இக்கடிதம் {{request.purpose}} நோக்கத்திற்காக வழங்கப்படுகிறது; இதனால் ஆணைக்குழு எந்த நிதிப் பொறுப்பையும் ஏற்காது.</p>', TRUE),
 (0, now(), 'system', 'SERVICE_CERTIFICATE', 'en', 'Service certificate', 'CERTIFICATE', 'Certificate of Service',
  '<p>This is to certify that <strong>{{employee.title}} {{employee.fullName}}</strong> (Employee No. {{employee.employeeNo}}) served at the {{org.name}} from {{employee.appointmentDate}} and last held the post of <strong>{{employee.designation}}</strong> in the {{employee.orgUnit}}.</p><p>{{field.remarks}}</p>', FALSE),
 (0, now(), 'system', 'SERVICE_CERTIFICATE', 'si', 'සේවා සහතිකය', 'CERTIFICATE', 'සේවා සහතිකය',
  '<p><strong>{{employee.title}} {{employee.fullName}}</strong> (සේවක අංකය {{employee.employeeNo}}) {{employee.appointmentDate}} සිට {{org.name}} හි සේවය කළ බවත්, අවසන් වරට {{employee.orgUnit}} හි <strong>{{employee.designation}}</strong> තනතුර දැරූ බවත් සහතික කරමි.</p><p>{{field.remarks}}</p>', FALSE),
 (0, now(), 'system', 'SERVICE_CERTIFICATE', 'ta', 'சேவைச் சான்றிதழ்', 'CERTIFICATE', 'சேவைச் சான்றிதழ்',
  '<p><strong>{{employee.title}} {{employee.fullName}}</strong> (பணியாளர் இல. {{employee.employeeNo}}) {{employee.appointmentDate}} முதல் {{org.name}} இல் பணியாற்றியதுடன் இறுதியாக {{employee.orgUnit}} இல் <strong>{{employee.designation}}</strong> பதவியை வகித்தார் என சான்றளிக்கிறேன்.</p><p>{{field.remarks}}</p>', FALSE)
ON CONFLICT (code, language) DO NOTHING;
