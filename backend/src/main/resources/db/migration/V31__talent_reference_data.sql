-- =====================================================================================================
-- Reference data for Recruitment, Performance and Training. All of it is configuration editable in the UI.
-- Approval chains are proposals to be confirmed (SRS 11.1 #14). Evaluation models are SAMPLES to be confirmed with
-- Administration (SRS 11.1 #12). Sinhala/Tamil texts are to be reviewed by CIABOC before go-live.
-- =====================================================================================================

-- ------------------------------------------------------------------ workflow definitions
INSERT INTO workflow_definition (version, created_at, created_by, process_code, name, description) VALUES
 (0, now(), 'system', 'RECRUITMENT_SELECTION', 'Recruitment selection', 'Approval of the recommended selection list for a vacancy (human decision on system ranking)'),
 (0, now(), 'system', 'PERFORMANCE_EVALUATION', 'Performance evaluation', 'HR review of a submitted performance evaluation before it is finalized'),
 (0, now(), 'system', 'TRAINING_NOMINATION', 'Training nomination', 'Approval of a nomination to a training programme');

INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'Management approval of selection', 'ROLE', 'MANAGEMENT', 120, NULL FROM workflow_definition WHERE process_code = 'RECRUITMENT_SELECTION';
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'HR review', 'ROLE', 'HR_ADMIN', 120, NULL FROM workflow_definition WHERE process_code = 'PERFORMANCE_EVALUATION';
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 1, 'Division head recommendation', 'DIVISION_HEAD', NULL, 72, 'HR_ADMIN' FROM workflow_definition WHERE process_code = 'TRAINING_NOMINATION';
INSERT INTO workflow_stage (definition_id, stage_order, name, approver_type, approver_ref, sla_hours, escalation_role_code)
SELECT id, 2, 'HR approval', 'ROLE', 'HR_ADMIN', 72, NULL FROM workflow_definition WHERE process_code = 'TRAINING_NOMINATION';

-- ------------------------------------------------------------------ settings
INSERT INTO system_setting (setting_key, setting_value, category, value_type, description) VALUES
 ('recruitment.default-exam-weight', '60', 'RECRUITMENT', 'DECIMAL', 'Default weight (%) of examination results in the selection ranking of a new vacancy'),
 ('recruitment.default-interview-weight', '40', 'RECRUITMENT', 'DECIMAL', 'Default weight (%) of interview marks in the selection ranking of a new vacancy'),
 ('recruitment.enforce-cadre', 'true', 'RECRUITMENT', 'BOOLEAN', 'Reject vacancies for more posts than the approved cadre leaves vacant (FR-REC-001)'),
 ('performance.reminder-days-before', '14', 'PERFORMANCE', 'INT', 'Start reminding evaluators this many days before an evaluation period is due'),
 ('performance.reminder-interval-days', '3', 'PERFORMANCE', 'INT', 'Minimum days between automatic reminders for the same evaluation'),
 ('training.self-nomination.enabled', 'true', 'TRAINING', 'BOOLEAN', 'Allow employees to nominate themselves for open programmes through self-service');

-- ------------------------------------------------------------------ notification templates
INSERT INTO notification_template (version, created_at, created_by, code, description, title_en, title_si, title_ta, body_en, body_si, body_ta, send_email) VALUES
 (0, now(), 'system', 'PER_EVALUATION_ASSIGNED', 'Sent to an evaluator when an evaluation is assigned',
  'Performance evaluation assigned: {{employee}}', 'කාර්ය සාධන ඇගයීමක් පවරා ඇත: {{employee}}', 'செயல்திறன் மதிப்பீடு ஒதுக்கப்பட்டது: {{employee}}',
  'You have been assigned to evaluate {{employee}} for {{period}}. Please complete it by {{dueDate}}.',
  '{{period}} සඳහා {{employee}} ඇගයීමට ඔබ පවරා ඇත. කරුණාකර {{dueDate}} දිනට පෙර සම්පූර්ණ කරන්න.',
  '{{period}} க்கான {{employee}} மதிப்பீட்டிற்கு நீங்கள் ஒதுக்கப்பட்டுள்ளீர்கள். {{dueDate}} க்கு முன் நிறைவு செய்யவும்.', TRUE),
 (0, now(), 'system', 'PER_REMINDER_EVALUATOR', 'Reminder to evaluators with incomplete evaluations',
  'Reminder: {{count}} evaluation(s) to complete', 'සිහිකැඳවීම: සම්පූර්ණ කළ යුතු ඇගයීම් {{count}}', 'நினைவூட்டல்: நிறைவு செய்ய வேண்டிய {{count}} மதிப்பீடு(கள்)',
  'You have {{count}} performance evaluation(s) for {{period}} still to complete. Due date: {{dueDate}}.',
  '{{period}} සඳහා ඔබට තවමත් සම්පූර්ණ කළ යුතු කාර්ය සාධන ඇගයීම් {{count}} ක් ඇත. නියමිත දිනය: {{dueDate}}.',
  '{{period}} க்கான {{count}} செயல்திறன் மதிப்பீடு(கள்) இன்னும் நிறைவு செய்யப்பட வேண்டும். இறுதித் திகதி: {{dueDate}}.', TRUE),
 (0, now(), 'system', 'PER_FINALIZED', 'Sent to the employee when the evaluation is finalized',
  'Your performance evaluation for {{period}} is available', '{{period}} සඳහා ඔබගේ කාර්ය සාධන ඇගයීම ලබා ගත හැක', '{{period}} க்கான உங்கள் செயல்திறன் மதிப்பீடு கிடைக்கிறது',
  'Your evaluation for {{period}} has been finalized. Please review it, add your comments and acknowledge.',
  '{{period}} සඳහා ඔබගේ ඇගයීම අවසන් කර ඇත. කරුණාකර එය සමාලෝචනය කර, ඔබගේ අදහස් එක් කර පිළිගන්න.',
  '{{period}} க்கான உங்கள் மதிப்பீடு இறுதி செய்யப்பட்டது. அதைப் பார்வையிட்டு, கருத்துகளைச் சேர்த்து ஏற்றுக்கொள்ளவும்.', TRUE),
 (0, now(), 'system', 'PER_REMINDER_ACK', 'Reminder to employees to acknowledge a finalized evaluation',
  'Reminder: please acknowledge your evaluation for {{period}}', 'සිහිකැඳවීම: {{period}} ඇගයීම පිළිගන්න', 'நினைவூட்டல்: {{period}} மதிப்பீட்டை ஏற்றுக்கொள்ளவும்',
  'Your finalized performance evaluation for {{period}} is awaiting your acknowledgement.',
  '{{period}} සඳහා ඔබගේ අවසන් කළ ඇගයීම ඔබගේ පිළිගැනීම අපේක්ෂා කරයි.',
  '{{period}} க்கான உங்கள் இறுதி மதிப்பீடு உங்கள் ஏற்றுக்கொள்ளலுக்காக காத்திருக்கிறது.', TRUE),
 (0, now(), 'system', 'TRN_NOMINATION_APPROVED', 'Sent to the employee when a training nomination is approved',
  'Training nomination approved: {{programme}}', 'පුහුණු නාමයෝජනාව අනුමතයි: {{programme}}', 'பயிற்சி பரிந்துரை அங்கீகரிக்கப்பட்டது: {{programme}}',
  'You have been nominated for "{{programme}}" from {{startDate}} to {{endDate}} at {{venue}}.',
  '{{startDate}} සිට {{endDate}} දක්වා {{venue}} හි පැවැත්වෙන "{{programme}}" සඳහා ඔබ නම් කර ඇත.',
  '{{startDate}} முதல் {{endDate}} வரை {{venue}} இல் நடைபெறும் "{{programme}}" க்கு நீங்கள் பரிந்துரைக்கப்பட்டுள்ளீர்கள்.', TRUE),
 (0, now(), 'system', 'TRN_NOMINATION_REJECTED', 'Sent to the employee when a training nomination is not approved',
  'Training nomination not approved: {{programme}}', 'පුහුණු නාමයෝජනාව අනුමත නොවීය: {{programme}}', 'பயிற்சி பரிந்துரை அங்கீகரிக்கப்படவில்லை: {{programme}}',
  'Your nomination for "{{programme}}" was not approved.',
  '"{{programme}}" සඳහා ඔබගේ නාමයෝජනාව අනුමත නොවීය.',
  '"{{programme}}" க்கான உங்கள் பரிந்துரை அங்கீகரிக்கப்படவில்லை.', TRUE);

-- ------------------------------------------------------------------ recruitment correspondence templates (FR-REC-009)
-- Placeholders: {{orgName}} {{date}} {{applicationNo}} {{applicantTitle}} {{applicantName}} {{nameWithInitials}} {{address}}
-- {{vacancyRef}} {{vacancyTitle}} {{designation}} {{orgUnit}} {{examTitle}} {{examType}} {{examDate}} {{examTime}} {{examVenue}}
-- {{interviewDate}} {{interviewTime}} {{interviewVenue}} {{appointmentDate}} {{employeeNo}}
INSERT INTO rec_letter_template (version, created_at, created_by, code, language, name, category, subject, body, active) VALUES
 (0, now(), 'system', 'APPOINTMENT', 'en', 'Letter of appointment', 'APPOINTMENT',
  'Appointment to the post of {{designation}} — {{vacancyRef}}',
  E'Dear {{applicantTitle}} {{nameWithInitials}},\n\nWith reference to your application {{applicationNo}}, I am pleased to inform you that you have been selected for appointment to the post of {{designation}} in the {{orgUnit}} of the {{orgName}}, with effect from {{appointmentDate}}.\n\nYour employee number is {{employeeNo}}. Please report to the Human Resources Section on the above date with the originals of your certificates and your National Identity Card.\n\nThis appointment is subject to the terms and conditions of service applicable to the post.\n\nDirector General', TRUE),
 (0, now(), 'system', 'APPOINTMENT', 'si', 'පත්වීම් ලිපිය', 'APPOINTMENT',
  '{{designation}} තනතුරට පත්කිරීම — {{vacancyRef}}',
  E'{{applicantTitle}} {{nameWithInitials}} වෙත,\n\nඔබගේ අයදුම්පත {{applicationNo}} සම්බන්ධයෙනි. {{appointmentDate}} දින සිට {{orgName}} හි {{orgUnit}} හි {{designation}} තනතුරට පත්කිරීම සඳහා ඔබ තෝරාගෙන ඇති බව සතුටින් දන්වමි.\n\nඔබගේ සේවක අංකය {{employeeNo}} වේ. ඉහත දින ඔබගේ සහතිකවල මුල් පිටපත් සහ ජාතික හැඳුනුම්පත සමඟ මානව සම්පත් අංශයට වාර්තා කරන්න.\n\nඅධ්‍යක්ෂ ජනරාල්', TRUE),
 (0, now(), 'system', 'APPOINTMENT', 'ta', 'நியமனக் கடிதம்', 'APPOINTMENT',
  '{{designation}} பதவிக்கு நியமனம் — {{vacancyRef}}',
  E'{{applicantTitle}} {{nameWithInitials}} அவர்களுக்கு,\n\nஉங்கள் விண்ணப்பம் {{applicationNo}} தொடர்பாக, {{appointmentDate}} முதல் {{orgName}} இன் {{orgUnit}} இல் {{designation}} பதவிக்கு நியமிக்கப்பட நீங்கள் தெரிவு செய்யப்பட்டுள்ளீர்கள் என்பதை மகிழ்ச்சியுடன் அறிவிக்கிறேன்.\n\nஉங்கள் ஊழியர் இலக்கம் {{employeeNo}}. மேற்படி திகதியில் உங்கள் சான்றிதழ்களின் மூலப் பிரதிகள் மற்றும் தேசிய அடையாள அட்டையுடன் மனித வளப் பகுதிக்கு சமூகமளிக்கவும்.\n\nபணிப்பாளர் நாயகம்', TRUE),
 (0, now(), 'system', 'EXAM_CALL', 'en', 'Examination call letter', 'EXAM_CALL',
  'Call for {{examTitle}} — {{vacancyRef}}',
  E'Dear {{applicantTitle}} {{nameWithInitials}},\n\nWith reference to your application {{applicationNo}} for the post of {{designation}}, you are requested to sit the {{examType}} examination "{{examTitle}}" on {{examDate}} at {{examTime}} at {{examVenue}}.\n\nPlease bring this letter and your National Identity Card.\n\nDirector General', TRUE),
 (0, now(), 'system', 'EXAM_CALL', 'si', 'විභාග කැඳවීම් ලිපිය', 'EXAM_CALL',
  '{{examTitle}} සඳහා කැඳවීම — {{vacancyRef}}',
  E'{{applicantTitle}} {{nameWithInitials}} වෙත,\n\n{{designation}} තනතුර සඳහා වූ ඔබගේ අයදුම්පත {{applicationNo}} සම්බන්ධයෙනි. {{examDate}} දින {{examTime}} ට {{examVenue}} හිදී පැවැත්වෙන "{{examTitle}}" විභාගයට පෙනී සිටින ලෙස ඉල්ලා සිටිමි.\n\nමෙම ලිපිය සහ ජාතික හැඳුනුම්පත රැගෙන එන්න.\n\nඅධ්‍යක්ෂ ජනරාල්', TRUE),
 (0, now(), 'system', 'EXAM_CALL', 'ta', 'பரீட்சை அழைப்புக் கடிதம்', 'EXAM_CALL',
  '{{examTitle}} க்கான அழைப்பு — {{vacancyRef}}',
  E'{{applicantTitle}} {{nameWithInitials}} அவர்களுக்கு,\n\n{{designation}} பதவிக்கான உங்கள் விண்ணப்பம் {{applicationNo}} தொடர்பாக, {{examDate}} அன்று {{examTime}} மணிக்கு {{examVenue}} இல் நடைபெறும் "{{examTitle}}" பரீட்சைக்கு தோற்றுமாறு கேட்டுக்கொள்கிறேன்.\n\nஇக்கடிதம் மற்றும் தேசிய அடையாள அட்டையைக் கொண்டு வரவும்.\n\nபணிப்பாளர் நாயகம்', TRUE),
 (0, now(), 'system', 'INTERVIEW_CALL', 'en', 'Interview call letter', 'INTERVIEW_CALL',
  'Interview for the post of {{designation}} — {{vacancyRef}}',
  E'Dear {{applicantTitle}} {{nameWithInitials}},\n\nWith reference to your application {{applicationNo}}, you are invited to attend an interview for the post of {{designation}} on {{interviewDate}} at {{interviewTime}} at {{interviewVenue}}.\n\nPlease bring the originals of your certificates and your National Identity Card.\n\nDirector General', TRUE),
 (0, now(), 'system', 'INTERVIEW_CALL', 'si', 'සම්මුඛ පරීක්ෂණ කැඳවීම් ලිපිය', 'INTERVIEW_CALL',
  '{{designation}} තනතුර සඳහා සම්මුඛ පරීක්ෂණය — {{vacancyRef}}',
  E'{{applicantTitle}} {{nameWithInitials}} වෙත,\n\nඔබගේ අයදුම්පත {{applicationNo}} සම්බන්ධයෙනි. {{interviewDate}} දින {{interviewTime}} ට {{interviewVenue}} හිදී පැවැත්වෙන {{designation}} තනතුර සඳහා වූ සම්මුඛ පරීක්ෂණයට සහභාගී වන ලෙස ඔබට ආරාධනා කරමි.\n\nසහතිකවල මුල් පිටපත් සහ ජාතික හැඳුනුම්පත රැගෙන එන්න.\n\nඅධ්‍යක්ෂ ජනරාල්', TRUE),
 (0, now(), 'system', 'INTERVIEW_CALL', 'ta', 'நேர்முகப் பரீட்சை அழைப்புக் கடிதம்', 'INTERVIEW_CALL',
  '{{designation}} பதவிக்கான நேர்முகப் பரீட்சை — {{vacancyRef}}',
  E'{{applicantTitle}} {{nameWithInitials}} அவர்களுக்கு,\n\nஉங்கள் விண்ணப்பம் {{applicationNo}} தொடர்பாக, {{interviewDate}} அன்று {{interviewTime}} மணிக்கு {{interviewVenue}} இல் நடைபெறும் {{designation}} பதவிக்கான நேர்முகப் பரீட்சைக்கு உங்களை அழைக்கிறேன்.\n\nசான்றிதழ்களின் மூலப் பிரதிகள் மற்றும் தேசிய அடையாள அட்டையைக் கொண்டு வரவும்.\n\nபணிப்பாளர் நாயகம்', TRUE),
 (0, now(), 'system', 'REGRET', 'en', 'Regret letter', 'REGRET',
  'Your application for the post of {{designation}} — {{vacancyRef}}',
  E'Dear {{applicantTitle}} {{nameWithInitials}},\n\nThank you for your application {{applicationNo}} for the post of {{designation}}. We regret to inform you that you have not been selected on this occasion.\n\nWe appreciate your interest in the {{orgName}} and wish you success in your future endeavours.\n\nDirector General', TRUE),
 (0, now(), 'system', 'REGRET', 'si', 'කනගාටු ලිපිය', 'REGRET',
  '{{designation}} තනතුර සඳහා ඔබගේ අයදුම්පත — {{vacancyRef}}',
  E'{{applicantTitle}} {{nameWithInitials}} වෙත,\n\n{{designation}} තනතුර සඳහා ඔබ ඉදිරිපත් කළ අයදුම්පත {{applicationNo}} ට ස්තූතියි. මෙවර ඔබ තෝරා නොගත් බව කනගාටුවෙන් දන්වමි.\n\n{{orgName}} කෙරෙහි ඔබ දැක්වූ උනන්දුව අගය කරමු.\n\nඅධ්‍යක්ෂ ජනරාල්', TRUE),
 (0, now(), 'system', 'REGRET', 'ta', 'வருத்தக் கடிதம்', 'REGRET',
  '{{designation}} பதவிக்கான உங்கள் விண்ணப்பம் — {{vacancyRef}}',
  E'{{applicantTitle}} {{nameWithInitials}} அவர்களுக்கு,\n\n{{designation}} பதவிக்கான உங்கள் விண்ணப்பம் {{applicationNo}} க்கு நன்றி. இம்முறை நீங்கள் தெரிவு செய்யப்படவில்லை என்பதை வருத்தத்துடன் அறிவிக்கிறேன்.\n\n{{orgName}} மீதான உங்கள் ஆர்வத்தைப் பாராட்டுகிறோம்.\n\nபணிப்பாளர் நாயகம்', TRUE);

-- ------------------------------------------------------------------ SAMPLE evaluation models (to be confirmed: SRS 11.1 #12)
-- CIABOC expects ~10-15 models for ~29 employee types. These three are illustrative only and are flagged sample = TRUE.
INSERT INTO per_model (version, created_at, created_by, code, name, description, rating_min, rating_max, sample, active) VALUES
 (0, now(), 'system', 'SAMPLE-MGMT', 'Sample: Senior management and executive officers',
  'SAMPLE model to be confirmed with Administration (SRS 11.1 #12). Rating scale 1 (unsatisfactory) to 5 (outstanding).', 1, 5, TRUE, TRUE),
 (0, now(), 'system', 'SAMPLE-INV', 'Sample: Investigation staff',
  'SAMPLE model to be confirmed with Administration (SRS 11.1 #12). Rating scale 1 (unsatisfactory) to 5 (outstanding).', 1, 5, TRUE, TRUE),
 (0, now(), 'system', 'SAMPLE-SUPPORT', 'Sample: Tertiary, secondary and primary level staff',
  'SAMPLE model to be confirmed with Administration (SRS 11.1 #12). Rating scale 1 (unsatisfactory) to 4 (very good).', 1, 4, TRUE, TRUE);

INSERT INTO per_criterion (version, created_at, created_by, model_id, criterion_type, title, description, weight, sort_order)
SELECT 0, now(), 'system', m.id, v.ctype, v.title, v.descr, v.weight, v.ord
FROM per_model m
JOIN (VALUES
  ('SAMPLE-MGMT', 'OBJECTIVE', 'Achievement of agreed objectives', 'Delivery against objectives agreed at the start of the period', 30, 1),
  ('SAMPLE-MGMT', 'CRITERION', 'Leadership and staff development', 'Direction, delegation and development of subordinates', 20, 2),
  ('SAMPLE-MGMT', 'CRITERION', 'Decision making and judgement', 'Quality and timeliness of decisions', 20, 3),
  ('SAMPLE-MGMT', 'CRITERION', 'Integrity and adherence to rules', 'Compliance with the Establishments Code, financial regulations and the Code of Conduct', 20, 4),
  ('SAMPLE-MGMT', 'CRITERION', 'Communication and coordination', 'Written and oral communication, coordination with other divisions', 10, 5),
  ('SAMPLE-INV', 'KPI', 'Investigations completed within target time', 'Proportion of assigned investigations completed within the target time', 30, 1),
  ('SAMPLE-INV', 'KPI', 'Quality of investigation reports', 'Completeness and accuracy of reports and evidence handling', 25, 2),
  ('SAMPLE-INV', 'CRITERION', 'Knowledge of law and procedure', 'Application of the Anti-Corruption Act and procedures', 15, 3),
  ('SAMPLE-INV', 'CRITERION', 'Integrity and confidentiality', 'Protection of confidential information and conduct', 20, 4),
  ('SAMPLE-INV', 'CRITERION', 'Teamwork and initiative', 'Cooperation and initiative', 10, 5),
  ('SAMPLE-SUPPORT', 'CRITERION', 'Quality and accuracy of work', NULL, 30, 1),
  ('SAMPLE-SUPPORT', 'CRITERION', 'Punctuality and attendance', NULL, 20, 2),
  ('SAMPLE-SUPPORT', 'CRITERION', 'Job knowledge', NULL, 20, 3),
  ('SAMPLE-SUPPORT', 'CRITERION', 'Conduct and discipline', NULL, 20, 4),
  ('SAMPLE-SUPPORT', 'CRITERION', 'Cooperation with colleagues', NULL, 10, 5)
) AS v(model, ctype, title, descr, weight, ord) ON v.model = m.code;

-- Each employee category is mapped to a model (never one forced template). Mappings are editable.
INSERT INTO per_category_model (version, created_at, created_by, employee_category, model_id)
SELECT 0, now(), 'system', v.cat, m.id
FROM (VALUES ('SENIOR_MANAGEMENT', 'SAMPLE-MGMT'), ('EXECUTIVE', 'SAMPLE-MGMT'), ('INVESTIGATION', 'SAMPLE-INV'),
             ('TERTIARY', 'SAMPLE-SUPPORT'), ('SECONDARY', 'SAMPLE-SUPPORT'), ('PRIMARY', 'SAMPLE-SUPPORT')) AS v(cat, model)
JOIN per_model m ON m.code = v.model;
