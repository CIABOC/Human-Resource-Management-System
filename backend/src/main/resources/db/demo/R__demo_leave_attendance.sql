-- Development-only demo data for Leave and Attendance (idempotent).

-- Leave balances for the current year from the configured entitlements.
INSERT INTO leave_balance (version, created_at, created_by, employee_id, leave_type_id, leave_year, entitled, adjusted, used, pending)
SELECT 0, now(), 'demo', e.id, t.id, CAST(EXTRACT(YEAR FROM CURRENT_DATE) AS integer), x.days_per_year, 0, 0, 0
FROM employee e
JOIN leave_type t ON t.balance_tracked AND t.active
JOIN leave_entitlement x ON x.leave_type_id = t.id AND x.employee_category = e.employee_category
     AND x.effective_from <= CURRENT_DATE AND (x.effective_to IS NULL OR x.effective_to > CURRENT_DATE)
WHERE e.status IN ('ACTIVE', 'PROBATION', 'ON_LEAVE', 'SECONDED')
ON CONFLICT (employee_id, leave_type_id, leave_year) DO NOTHING;

-- A few device events for the previous working day (administration, legal and ICT staff).
INSERT INTO attendance_event (version, created_at, created_by, employee_id, employee_no, event_time, work_date, direction, source,
                              device_id, batch_ref, flagged, flag_reason)
SELECT 0, now(), 'demo', e.id, e.employee_no,
       (d.day + v.t) AT TIME ZONE 'Asia/Colombo', d.day, v.dir, 'DEVICE', 'GATE-01', 'DEMO', v.flagged, v.reason
FROM (SELECT CAST(CASE EXTRACT(DOW FROM CURRENT_DATE) WHEN 1 THEN CURRENT_DATE - 3 WHEN 0 THEN CURRENT_DATE - 2
                                                       ELSE CURRENT_DATE - 1 END AS date) AS day) d
CROSS JOIN (VALUES
    ('CIA-0005', TIME '08:21', 'IN', FALSE, NULL),
    ('CIA-0005', TIME '16:31', 'OUT', FALSE, NULL),
    ('CIA-0013', TIME '08:57', 'IN', FALSE, NULL),
    ('CIA-0013', TIME '16:20', 'OUT', FALSE, NULL),
    ('CIA-0014', TIME '08:25', 'IN', FALSE, NULL),
    ('CIA-0014', TIME '08:26', 'IN', TRUE, 'Possible duplicate within 2 min'),
    ('CIA-0014', TIME '15:40', 'OUT', FALSE, NULL)
) AS v(emp_no, t, dir, flagged, reason)
JOIN employee e ON e.employee_no = v.emp_no
WHERE NOT EXISTS (SELECT 1 FROM attendance_event x WHERE x.employee_no = v.emp_no AND x.work_date = d.day
                  AND x.direction = v.dir AND x.batch_ref = 'DEMO' AND x.event_time = (d.day + v.t) AT TIME ZONE 'Asia/Colombo');
