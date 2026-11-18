-- DEMO ONLY: illustrative position categories for the org chart. Idempotent.
INSERT INTO position_category (version, created_at, created_by, code, name, name_si, name_ta, color, sort_order, closed) VALUES
 (0, now(), 'demo', 'EXECUTIVE', 'Executive', 'විධායක', 'நிறைவேற்று', '#0B1F4B', 1, FALSE),
 (0, now(), 'demo', 'LEGAL', 'Legal', 'නීති', 'சட்டம்', '#1F8A70', 2, FALSE),
 (0, now(), 'demo', 'INVESTIGATION', 'Investigation', 'විමර්ශන', 'விசாரணை', '#3B5BDB', 3, FALSE),
 (0, now(), 'demo', 'MGMT_ASSISTANT', 'Management Assistant', 'කළමනාකරණ සහකාර', 'முகாமைத்துவ உதவியாளர்', '#7B3FE4', 4, FALSE),
 (0, now(), 'demo', 'SUPPORT', 'Support Staff', 'සහායක කාර්ය මණ්ඩලය', 'உதவி ஊழியர்கள்', '#E8590C', 5, FALSE),
 (0, now(), 'demo', 'LEGACY', 'Legacy Grades', 'පැරණි ශ්‍රේණි', 'பழைய தரங்கள்', '#6B7A99', 6, TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO designation_category_assignment (version, created_at, created_by, designation_id, category_id, effective_from)
SELECT 0, now(), 'demo', d.id, c.id, '2020-01-01'
FROM (VALUES ('DG', 'EXECUTIVE'), ('DDG', 'EXECUTIVE'), ('DIR', 'EXECUTIVE'), ('AD', 'EXECUTIVE'),
             ('LO', 'LEGAL'), ('IO', 'INVESTIGATION'), ('ADG', 'INVESTIGATION'),
             ('MA', 'MGMT_ASSISTANT'), ('AO', 'MGMT_ASSISTANT'),
             ('DRV', 'SUPPORT'), ('OA', 'SUPPORT')) AS v(des, cat)
JOIN designation d ON d.code = v.des
JOIN position_category c ON c.code = v.cat
WHERE NOT EXISTS (SELECT 1 FROM designation_category_assignment a WHERE a.designation_id = d.id);
