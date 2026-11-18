package lk.gov.ciaboc.hrms.leave;

import lk.gov.ciaboc.hrms.reports.ReportDefinition;
import lk.gov.ciaboc.hrms.reports.ReportDefinition.Filter;
import lk.gov.ciaboc.hrms.reports.ReportProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** Leave reports (FR-LEV-009, SRS Section 8): balances, utilization, pending, approved and rejected leave. */
@Component
public class LeaveReports implements ReportProvider {
    private static final String CAT = "Leave";
    private static final String RANGE = " AND (CAST(:from AS date) IS NULL OR a.end_date >= CAST(:from AS date)) " +
            "AND (CAST(:to AS date) IS NULL OR a.start_date <= CAST(:to AS date)) ";
    private static final String APP_FROM = " FROM leave_application a JOIN employee e ON e.id = a.employee_id " +
            "JOIN leave_type t ON t.id = a.leave_type_id ";

    @Override
    public List<ReportDefinition> reports() {
        return List.of(
                ReportDefinition.builder("LEAVE_BALANCES", CAT, "Leave balances")
                        .description("Entitled, adjusted, used, pending and available days for the year of the 'as of' date")
                        .permission("LEAVE_VIEW").filters(Filter.AS_OF, Filter.ORG_UNIT)
                        .col("employee_no", "Employee no").col("employee", "Employee").col("org_unit_code", "Unit")
                        .col("leave_type", "Leave type").col("leave_year", "Year", "number").col("entitled", "Entitled", "number")
                        .col("adjusted", "Adjusted", "number").col("used", "Used", "number").col("pending", "Pending", "number")
                        .col("available", "Available", "number")
                        .sql("SELECT e.employee_no, e.name_with_initials AS employee, e.org_unit_code, t.name AS leave_type, b.leave_year, " +
                                "b.entitled, b.adjusted, b.used, b.pending, (b.entitled + b.adjusted - b.used - b.pending) AS available " +
                                "FROM leave_balance b JOIN employee e ON e.id = b.employee_id JOIN leave_type t ON t.id = b.leave_type_id " +
                                "WHERE b.leave_year = CAST(EXTRACT(YEAR FROM CAST(:asOf AS date)) AS integer) /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "ORDER BY e.employee_no, t.sort_order")
                        .build(),
                ReportDefinition.builder("LEAVE_UTILIZATION", CAT, "Leave utilization")
                        .description("Approved leave days per employee and type in the period, against the annual entitlement")
                        .permission("LEAVE_VIEW").filters(Filter.DATE_RANGE, Filter.ORG_UNIT)
                        .col("employee_no", "Employee no").col("employee", "Employee").col("org_unit_code", "Unit")
                        .col("leave_type", "Leave type").col("leave_year", "Year", "number").col("applications", "Applications", "number")
                        .col("days_taken", "Days taken", "number").col("entitlement", "Entitlement", "number")
                        .col("utilization_pct", "Utilization %", "number")
                        .sql("SELECT e.employee_no, e.name_with_initials AS employee, e.org_unit_code, t.name AS leave_type, a.leave_year, " +
                                "COUNT(*) AS applications, SUM(a.days) AS days_taken, MAX(b.entitled + b.adjusted) AS entitlement, " +
                                "CASE WHEN MAX(b.entitled + b.adjusted) > 0 THEN ROUND(100 * SUM(a.days) / MAX(b.entitled + b.adjusted), 1) END AS utilization_pct" +
                                APP_FROM + "LEFT JOIN leave_balance b ON b.employee_id = a.employee_id AND b.leave_type_id = a.leave_type_id " +
                                "AND b.leave_year = a.leave_year WHERE a.status = 'APPROVED'" + RANGE + "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "GROUP BY e.employee_no, e.name_with_initials, e.org_unit_code, t.name, t.sort_order, a.leave_year " +
                                "ORDER BY e.employee_no, t.sort_order")
                        .build(),
                ReportDefinition.builder("LEAVE_PENDING", CAT, "Pending leave applications")
                        .description("Applications awaiting approval or returned for correction, with the current approval stage")
                        .permission("LEAVE_VIEW").filters(Filter.ORG_UNIT)
                        .col("employee_no", "Employee no").col("employee", "Employee").col("org_unit_code", "Unit")
                        .col("leave_type", "Leave type").col("start_date", "From", "date").col("end_date", "To", "date")
                        .col("days", "Days", "number").col("status", "Status").col("stage", "Current stage")
                        .col("submitted_at", "Submitted", "datetime")
                        .sql("SELECT e.employee_no, e.name_with_initials AS employee, e.org_unit_code, t.name AS leave_type, a.start_date, " +
                                "a.end_date, a.days, a.status, w.current_stage_name AS stage, w.submitted_at" + APP_FROM +
                                "LEFT JOIN workflow_instance w ON w.id = a.workflow_instance_id " +
                                "WHERE a.status IN ('PENDING','RETURNED') /*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY w.submitted_at")
                        .build(),
                ReportDefinition.builder("LEAVE_APPROVED", CAT, "Approved leave")
                        .description("Approved leave overlapping the period").permission("LEAVE_VIEW").filters(Filter.DATE_RANGE, Filter.ORG_UNIT)
                        .col("employee_no", "Employee no").col("employee", "Employee").col("org_unit_code", "Unit")
                        .col("leave_type", "Leave type").col("paid", "Paid", "boolean").col("start_date", "From", "date")
                        .col("end_date", "To", "date").col("days", "Days", "number").col("decided_at", "Approved", "datetime")
                        .sql("SELECT e.employee_no, e.name_with_initials AS employee, e.org_unit_code, t.name AS leave_type, t.paid, " +
                                "a.start_date, a.end_date, a.days, a.decided_at" + APP_FROM + "WHERE a.status = 'APPROVED'" + RANGE +
                                "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY a.start_date, e.employee_no")
                        .build(),
                ReportDefinition.builder("LEAVE_REJECTED", CAT, "Rejected leave")
                        .description("Rejected applications with the approver's reason").permission("LEAVE_VIEW")
                        .filters(Filter.DATE_RANGE, Filter.ORG_UNIT)
                        .col("employee_no", "Employee no").col("employee", "Employee").col("org_unit_code", "Unit")
                        .col("leave_type", "Leave type").col("start_date", "From", "date").col("end_date", "To", "date")
                        .col("days", "Days", "number").col("rejected_by", "Rejected by").col("reason", "Reason")
                        .col("decided_at", "Rejected", "datetime")
                        .sql("SELECT e.employee_no, e.name_with_initials AS employee, e.org_unit_code, t.name AS leave_type, a.start_date, " +
                                "a.end_date, a.days, r.actor_name AS rejected_by, r.comment AS reason, a.decided_at" + APP_FROM +
                                "LEFT JOIN LATERAL (SELECT x.actor_name, x.comment_text AS comment FROM approval_action x WHERE x.instance_id = a.workflow_instance_id " +
                                "AND x.action = 'REJECT' ORDER BY x.id DESC LIMIT 1) r ON TRUE " +
                                "WHERE a.status = 'REJECTED'" + RANGE + "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY a.start_date, e.employee_no")
                        .build(),
                ReportDefinition.builder("LEAVE_APPLICATIONS", CAT, "Leave applications by status")
                        .description("All applications in the period. Status: PENDING, RETURNED, APPROVED, REJECTED, WITHDRAWN, CANCELLED")
                        .permission("LEAVE_VIEW").filters(Filter.DATE_RANGE, Filter.ORG_UNIT, Filter.STATUS)
                        .col("employee_no", "Employee no").col("employee", "Employee").col("org_unit_code", "Unit")
                        .col("leave_type", "Leave type").col("start_date", "From", "date").col("end_date", "To", "date")
                        .col("days", "Days", "number").col("status", "Status").col("cancellation_status", "Cancellation")
                        .col("created_at", "Applied", "datetime")
                        .sql("SELECT e.employee_no, e.name_with_initials AS employee, e.org_unit_code, t.name AS leave_type, a.start_date, " +
                                "a.end_date, a.days, a.status, a.cancellation_status, a.created_at" + APP_FROM + "WHERE 1=1" + RANGE +
                                "AND (CAST(:status AS varchar) IS NULL OR a.status = CAST(:status AS varchar)) " +
                                "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY a.start_date DESC, e.employee_no")
                        .build());
    }
}
