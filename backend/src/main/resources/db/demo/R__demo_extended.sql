-- =====================================================================================================
-- DEMO / DEVELOPMENT DATA ONLY - extended scope (Release 5). Idempotent.
-- =====================================================================================================

-- An open digitization batch so the screen is not empty on first use.
INSERT INTO ext_digitization_batch (version, created_at, created_by, batch_no, title, source_location, remarks, status)
SELECT 0, now(), 'demo', 'DIG-DEMO-0001', 'Legacy personal files - Investigation Division (demo)', 'HR record room, cabinet 3',
       'Demo batch: upload scanned pages, submit for QA, then file', 'OPEN'
WHERE NOT EXISTS (SELECT 1 FROM ext_digitization_batch WHERE batch_no = 'DIG-DEMO-0001');
