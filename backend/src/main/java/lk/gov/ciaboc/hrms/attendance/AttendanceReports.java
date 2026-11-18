package lk.gov.ciaboc.hrms.attendance;

import lk.gov.ciaboc.hrms.reports.ReportDefinition;
import lk.gov.ciaboc.hrms.reports.ReportDefinition.Filter;
import lk.gov.ciaboc.hrms.reports.ReportProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** Attendance and no-pay reports (FR-ATT-018, -023, SRS Section 8). */
@Component
public class AttendanceReports implements ReportProvider {
    private static final String CAT = "Attendance";
    private static final String FROM = " FROM attendance_daily d JOIN employee e ON e.id = d.employee_id ";
    private static final String RANGE = " AND (CAST(:from AS date) IS NULL OR d.work_date >= CAST(:from AS date)) " +
            "AND (CAST(:to AS date) IS NULL OR d.work_date <= CAST(:to AS date)) ";
    private static final String PERIOD = "COALESCE(CAST(:period AS varchar), to_char(CURRENT_DATE, 'YYYY-MM'))";
    private static final String MONTH = " AND d.work_date >= to_date(" + PERIOD + " || '-01', 'YYYY-MM-DD') " +
            "AND d.work_date < (to_date(" + PERIOD + " || '-01', 'YYYY-MM-DD') + INTERVAL '1 month') ";

    @Override
    public List<ReportDefinition> reports() {
        return List.of(
                ReportDefinition.builder("ATT_DAILY", CAT, "Daily attendance")
                        .description("Processed attendance for the 'as of' date. Status: PRESENT, LATE, EARLY, ABSENT, ON_LEAVE, HOLIDAY, WEEKEND, NO_SCHEDULE")
                        .permission("ATT_VIEW").filters(Filter.AS_OF, Filter.ORG_UNIT, Filter.STATUS)
                        .col("employee_no", "Employee no").col("employee", "Employee").col("org_unit_code", "Unit")
                        .col("status", "Status").col("check_in", "Check-in", "datetime").col("check_out", "Check-out", "datetime")
                        .col("worked_minutes", "Worked (min)", "number").col("late_minutes", "Late (min)", "number")
                        .col("early_minutes", "Early (min)", "number").col("leave_type_code", "Leave").col("exception_note", "Notes")
                        .sql("SELECT e.employee_no, e.name_with_initials AS employee, e.org_unit_code, d.status, d.check_in, d.check_out, " +
                                "d.worked_minutes, d.late_minutes, d.early_minutes, d.leave_type_code, d.exception_note" + FROM +
                                "WHERE d.work_date = CAST(:asOf AS date) AND (CAST(:status AS varchar) IS NULL OR d.status = CAST(:status AS varchar)) " +
                                "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY e.employee_no")
                        .build(),
                ReportDefinition.builder("ATT_MONTHLY", CAT, "Monthly attendance summary")
                        .description("Days by status per employee for the month (default: current month)")
                        .permission("ATT_VIEW").filters(Filter.PERIOD, Filter.ORG_UNIT)
                        .col("employee_no", "Employee no").col("employee", "Employee").col("org_unit_code", "Unit")
                        .col("present", "Present", "number").col("late", "Late", "number").col("early", "Early", "number")
                        .col("absent", "Absent", "number").col("on_leave", "On leave", "number").col("holidays", "Holidays", "number")
                        .col("late_minutes", "Late (min)", "number").col("worked_hours", "Worked (h)", "number")
                        .sql("SELECT e.employee_no, e.name_with_initials AS employee, e.org_unit_code, " +
                                "COUNT(*) FILTER (WHERE d.status = 'PRESENT') AS present, COUNT(*) FILTER (WHERE d.status = 'LATE') AS late, " +
                                "COUNT(*) FILTER (WHERE d.status = 'EARLY') AS early, COUNT(*) FILTER (WHERE d.status = 'ABSENT') AS absent, " +
                                "COUNT(*) FILTER (WHERE d.status = 'ON_LEAVE') AS on_leave, COUNT(*) FILTER (WHERE d.status = 'HOLIDAY') AS holidays, " +
                                "SUM(d.late_minutes) AS late_minutes, ROUND(SUM(d.worked_minutes) / 60.0, 1) AS worked_hours" + FROM +
                                "WHERE 1=1" + MONTH + "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "GROUP BY e.employee_no, e.name_with_initials, e.org_unit_code ORDER BY e.employee_no")
                        .build(),
                ReportDefinition.builder("ATT_ABSENCES", CAT, "Absences")
                        .description("Working days without attendance or approved leave").permission("ATT_VIEW")
                        .filters(Filter.DATE_RANGE, Filter.ORG_UNIT)
                        .col("work_date", "Date", "date").col("employee_no", "Employee no").col("employee", "Employee")
                        .col("org_unit_code", "Unit").col("exception_note", "Notes")
                        .sql("SELECT d.work_date, e.employee_no, e.name_with_initials AS employee, e.org_unit_code, d.exception_note" + FROM +
                                "WHERE d.status = 'ABSENT'" + RANGE + "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY d.work_date, e.employee_no")
                        .build(),
                ReportDefinition.builder("ATT_LATE", CAT, "Late arrivals")
                        .description("Check-ins after the schedule start plus grace period").permission("ATT_VIEW")
                        .filters(Filter.DATE_RANGE, Filter.ORG_UNIT)
                        .col("work_date", "Date", "date").col("employee_no", "Employee no").col("employee", "Employee")
                        .col("org_unit_code", "Unit").col("check_in", "Check-in", "datetime").col("late_minutes", "Late (min)", "number")
                        .sql("SELECT d.work_date, e.employee_no, e.name_with_initials AS employee, e.org_unit_code, d.check_in, d.late_minutes" + FROM +
                                "WHERE d.late_minutes > 0" + RANGE + "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY d.work_date, e.employee_no")
                        .build(),
                ReportDefinition.builder("ATT_EARLY", CAT, "Early departures")
                        .description("Check-outs before the schedule end minus grace period").permission("ATT_VIEW")
                        .filters(Filter.DATE_RANGE, Filter.ORG_UNIT)
                        .col("work_date", "Date", "date").col("employee_no", "Employee no").col("employee", "Employee")
                        .col("org_unit_code", "Unit").col("check_out", "Check-out", "datetime").col("early_minutes", "Early (min)", "number")
                        .sql("SELECT d.work_date, e.employee_no, e.name_with_initials AS employee, e.org_unit_code, d.check_out, d.early_minutes" + FROM +
                                "WHERE d.early_minutes > 0" + RANGE + "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY d.work_date, e.employee_no")
                        .build(),
                ReportDefinition.builder("ATT_EXCEPTIONS", CAT, "Flagged attendance events")
                        .description("Suspicious or exceptional device events and their review outcome").permission("ATT_VIEW")
                        .filters(Filter.DATE_RANGE, Filter.ORG_UNIT)
                        .col("work_date", "Date", "date").col("employee_no", "Employee no").col("employee", "Employee")
                        .col("event_time", "Time", "datetime").col("direction", "Direction").col("device_id", "Device")
                        .col("flag_reason", "Flag").col("review_status", "Review").col("reviewed_by", "Reviewed by")
                        .sql("SELECT d.work_date, e.employee_no, e.name_with_initials AS employee, d.event_time, d.direction, d.device_id, " +
                                "d.flag_reason, COALESCE(d.review_status, 'AWAITING') AS review_status, d.reviewed_by " +
                                "FROM attendance_event d JOIN employee e ON e.id = d.employee_id WHERE d.flagged" + RANGE +
                                "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY d.event_time")
                        .build(),
                ReportDefinition.builder("ATT_NOPAY_SUMMARY", CAT, "No-pay summary")
                        .description("Monthly no-pay days per employee and the approval status of the calculation (default: current month)")
                        .permission("ATT_VIEW").filters(Filter.PERIOD, Filter.ORG_UNIT)
                        .col("period", "Period").col("run_status", "Calculation status").col("employee_no", "Employee no")
                        .col("employee", "Employee").col("org_unit_code", "Unit").col("absence_days", "Absence days", "number")
                        .col("unpaid_leave_days", "Unpaid leave days", "number").col("late_days", "Late-arrival days", "number")
                        .col("calculated_days", "Calculated", "number").col("adjusted_days", "Adjusted", "number")
                        .col("no_pay_days", "No-pay days", "number").col("adjustment_reason", "Adjustment reason")
                        .sql("SELECT r.period, r.status AS run_status, e.employee_no, e.name_with_initials AS employee, e.org_unit_code, " +
                                "l.absence_days, l.unpaid_leave_days, l.late_days, l.calculated_days, l.adjusted_days, " +
                                "COALESCE(l.adjusted_days, l.calculated_days) AS no_pay_days, l.adjustment_reason " +
                                "FROM nopay_line l JOIN nopay_run r ON r.id = l.run_id JOIN employee e ON e.id = l.employee_id " +
                                "WHERE r.period = " + PERIOD + " /*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY e.employee_no")
                        .build());
    }
}
