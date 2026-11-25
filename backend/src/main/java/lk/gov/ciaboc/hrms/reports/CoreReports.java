package lk.gov.ciaboc.hrms.reports;

import lk.gov.ciaboc.hrms.reports.ReportDefinition.Filter;
import org.springframework.stereotype.Component;

import java.util.List;

/** Establishment, employee and audit reports (SRS Section 8). */
@Component
public class CoreReports implements ReportProvider {
    private static final String EFFECTIVE = " effective_from <= CAST(:asOf AS date) AND (effective_to IS NULL OR effective_to > CAST(:asOf AS date)) ";

    @Override
    public List<ReportDefinition> reports() {
        return List.of(
                // ------------------------------------------------------------ Establishment
                ReportDefinition.builder("EST_ORG_STRUCTURE", "Establishment", "Organizational structure")
                        .description("Organizational units as at a date").permission("EST_VIEW").filters(Filter.AS_OF, Filter.ORG_UNIT)
                        .col("code", "Code").col("name", "Unit").col("unit_type", "Type").col("parent_code", "Parent code")
                        .col("parent_name", "Parent unit").col("effective_from", "Effective from", "date")
                        .sql("SELECT o.code, o.name, o.unit_type, o.parent_code, p.name AS parent_name, o.effective_from, o.sort_order " +
                                "FROM org_unit o LEFT JOIN org_unit p ON p.code = o.parent_code AND p.effective_from <= CAST(:asOf AS date) " +
                                "AND (p.effective_to IS NULL OR p.effective_to > CAST(:asOf AS date)) " +
                                "WHERE o.effective_from <= CAST(:asOf AS date) AND (o.effective_to IS NULL OR o.effective_to > CAST(:asOf AS date)) " +
                                "/*ORG:o.code*/ ORDER BY o.sort_order, o.name")
                        .build(),
                ReportDefinition.builder("EST_DESIGNATIONS", "Establishment", "Designation list")
                        .permission("EST_VIEW").filters(Filter.STATUS)
                        .description("Status filter: ACTIVE or INACTIVE")
                        .col("code", "Code").col("name", "Designation").col("grade", "Grade").col("service_category", "Service category")
                        .col("active", "Active", "boolean")
                        .sql("SELECT code, name, grade, service_category, active FROM designation " +
                                "WHERE (CAST(:status AS varchar) IS NULL OR (CAST(:status AS varchar) = 'ACTIVE' AND active) " +
                                "OR (CAST(:status AS varchar) = 'INACTIVE' AND NOT active)) ORDER BY name")
                        .build(),
                ReportDefinition.builder("EST_POSITIONS", "Establishment", "Position list")
                        .permission("EST_VIEW").filters(Filter.ORG_UNIT, Filter.DESIGNATION)
                        .col("code", "Code").col("title", "Position").col("designation", "Designation").col("org_unit_code", "Unit")
                        .col("reports_to_position_code", "Reports to").col("active", "Active", "boolean").col("filled", "Filled", "boolean")
                        .sql("SELECT p.code, p.title, d.name AS designation, p.org_unit_code, p.reports_to_position_code, p.active, " +
                                "EXISTS (SELECT 1 FROM employee_assignment a WHERE a.position_id = p.id AND a.effective_from <= CURRENT_DATE " +
                                "AND (a.effective_to IS NULL OR a.effective_to > CURRENT_DATE)) AS filled " +
                                "FROM org_position p JOIN designation d ON d.id = p.designation_id WHERE 1=1 /*ORG:p.org_unit_code*/ " +
                                "AND (CAST(:designationId AS bigint) IS NULL OR p.designation_id = :designationId) ORDER BY p.org_unit_code, p.code")
                        .build(),
                ReportDefinition.builder("EST_CADRE_APPROVED", "Establishment", "Approved cadre")
                        .permission("EST_VIEW").filters(Filter.AS_OF, Filter.ORG_UNIT, Filter.DESIGNATION)
                        .col("org_unit_code", "Unit").col("designation", "Designation").col("grade", "Grade")
                        .col("approved_qty", "Approved", "number").col("effective_from", "Effective from", "date")
                        .sql("SELECT c.org_unit_code, d.name AS designation, d.grade, c.approved_qty, c.effective_from " +
                                "FROM cadre_allocation c JOIN designation d ON d.id = c.designation_id " +
                                "WHERE c." + EFFECTIVE.trim().replace("effective_to", "c.effective_to") +
                                " /*ORG:c.org_unit_code*/ AND (CAST(:designationId AS bigint) IS NULL OR c.designation_id = :designationId) " +
                                "ORDER BY c.org_unit_code, d.name")
                        .build(),
                ReportDefinition.builder("EST_CADRE_PROPOSED", "Establishment", "Proposed cadre")
                        .description("Cadre changes awaiting approval or correction").permission("EST_VIEW").filters(Filter.ORG_UNIT)
                        .col("org_unit_code", "Unit").col("designation", "Designation").col("current_approved", "Currently approved", "number")
                        .col("proposed_qty", "Proposed", "number").col("effective_date", "Proposed effective", "date")
                        .col("status", "Status").col("reason", "Reason").col("submitted_by", "Submitted by")
                        .sql("SELECT ec.cadre_org_unit_code AS org_unit_code, d.name AS designation, " +
                                "COALESCE((SELECT c.approved_qty FROM cadre_allocation c WHERE c.designation_id = ec.cadre_designation_id " +
                                "AND c.org_unit_code = ec.cadre_org_unit_code AND c.effective_from <= CURRENT_DATE " +
                                "AND (c.effective_to IS NULL OR c.effective_to > CURRENT_DATE)), 0) AS current_approved, " +
                                "ec.cadre_proposed_qty AS proposed_qty, ec.effective_date, ec.status, ec.reason, ec.created_by AS submitted_by " +
                                "FROM establishment_change ec JOIN designation d ON d.id = ec.cadre_designation_id " +
                                "WHERE ec.change_type = 'CADRE_SET' AND ec.status IN ('PENDING','RETURNED') /*ORG:ec.cadre_org_unit_code*/ " +
                                "ORDER BY ec.cadre_org_unit_code, d.name")
                        .build(),
                ReportDefinition.builder("EST_CADRE_VACANCY", "Establishment", "Occupied and vacant positions")
                        .description("Approved vs occupied cadre by unit and designation").permission("EST_VIEW")
                        .filters(Filter.AS_OF, Filter.ORG_UNIT, Filter.DESIGNATION)
                        .col("org_unit_code", "Unit").col("designation", "Designation").col("approved", "Approved", "number")
                        .col("occupied", "Occupied", "number").col("vacant", "Vacant", "number").col("excess", "Excess", "number")
                        .sql(VACANCY_SQL + " WHERE 1=1 /*ORG:x.org_unit_code*/ AND (CAST(:designationId AS bigint) IS NULL OR x.designation_id = :designationId) " +
                                "ORDER BY x.org_unit_code, d.name")
                        .build(),
                ReportDefinition.builder("EST_CADRE_BY_DIVISION", "Establishment", "Cadre distribution by division")
                        .permission("EST_VIEW").filters(Filter.AS_OF, Filter.ORG_UNIT)
                        .col("org_unit_code", "Unit").col("unit_name", "Unit name").col("approved", "Approved", "number")
                        .col("occupied", "Occupied", "number").col("vacant", "Vacant", "number")
                        .sql("SELECT x.org_unit_code, u.name AS unit_name, SUM(x.approved) AS approved, SUM(x.occupied) AS occupied, " +
                                "SUM(GREATEST(x.approved - x.occupied, 0)) AS vacant FROM (" + VACANCY_BASE + ") x " +
                                "LEFT JOIN org_unit u ON u.code = x.org_unit_code AND u.effective_from <= CAST(:asOf AS date) " +
                                "AND (u.effective_to IS NULL OR u.effective_to > CAST(:asOf AS date)) WHERE 1=1 /*ORG:x.org_unit_code*/ " +
                                "GROUP BY x.org_unit_code, u.name ORDER BY u.name")
                        .build(),
                ReportDefinition.builder("EST_CHANGE_HISTORY", "Establishment", "Establishment change history")
                        .permission("EST_VIEW").filters(Filter.DATE_RANGE, Filter.STATUS)
                        .col("id", "Ref", "number").col("submitted_at", "Submitted", "datetime").col("change_type", "Type")
                        .col("summary", "Summary").col("effective_date", "Effective", "date").col("status", "Status")
                        .col("submitted_by", "Submitted by").col("applied_at", "Applied", "datetime")
                        .sql("SELECT id, created_at AS submitted_at, change_type, summary, effective_date, status, created_by AS submitted_by, applied_at " +
                                "FROM establishment_change WHERE (CAST(:from AS date) IS NULL OR created_at >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR created_at < CAST(:to AS date) + 1) " +
                                "AND (CAST(:status AS varchar) IS NULL OR status = :status) ORDER BY id DESC")
                        .build(),

                ReportDefinition.builder("EST_CATEGORIZATION_HISTORY", "Establishment", "Position categorization audit")
                        .description("Who moved which position between categories, with effective dates").permission("EST_VIEW")
                        .filters(Filter.DATE_RANGE)
                        .col("submitted_at", "When", "datetime").col("submitted_by", "By").col("designation", "Designation")
                        .col("from_category", "From").col("to_category", "To").col("effective_date", "Effective", "date").col("status", "Status")
                        .sql("SELECT b.created_at AS submitted_at, b.created_by AS submitted_by, " +
                                "CASE WHEN l.line_type = 'CATEGORY' THEN 'Category: ' || c.name ELSE d.name END AS designation, " +
                                "CASE WHEN l.line_type = 'CATEGORY' THEN COALESCE(fp.name, 'Top level') ELSE COALESCE(f.name, 'Unassigned') END AS from_category, " +
                                "CASE WHEN l.line_type = 'CATEGORY' THEN COALESCE(tp.name, 'Top level') ELSE COALESCE(t.name, 'Unassigned') END AS to_category, " +
                                "b.effective_date, b.status FROM categorization_line l JOIN categorization_batch b ON b.id = l.batch_id " +
                                "LEFT JOIN designation d ON d.id = l.designation_id LEFT JOIN position_category f ON f.id = l.from_category_id " +
                                "LEFT JOIN position_category t ON t.id = l.to_category_id LEFT JOIN position_category c ON c.id = l.category_id " +
                                "LEFT JOIN position_category fp ON fp.id = l.from_parent_id LEFT JOIN position_category tp ON tp.id = l.to_parent_id " +
                                "WHERE (CAST(:from AS date) IS NULL OR b.created_at >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR b.created_at < CAST(:to AS date) + 1) ORDER BY b.created_at DESC, l.id")
                        .build(),

                // ------------------------------------------------------------ Employee
                ReportDefinition.builder("EMP_DETAILS", "Employee", "Employee details")
                        .permission("EMP_VIEW").filters(Filter.ORG_UNIT, Filter.DESIGNATION, Filter.STATUS)
                        .col("employee_no", "Emp. No").col("name_with_initials", "Name").col("designation", "Designation")
                        .col("org_unit_code", "Unit").col("status", "Status").col("employee_category", "Category")
                        .col("employment_type", "Employment type").col("appointment_date", "Appointed", "date")
                        .col("retirement_date", "Retirement", "date").col("mobile", "Mobile").col("email", "E-mail")
                        .sql("SELECT e.employee_no, e.name_with_initials, d.name AS designation, e.org_unit_code, e.status, e.employee_category, " +
                                "e.employment_type, e.appointment_date, e.retirement_date, e.mobile, e.email FROM employee e " +
                                "LEFT JOIN designation d ON d.id = e.designation_id WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:designationId AS bigint) IS NULL OR e.designation_id = :designationId) " +
                                "AND (CAST(:status AS varchar) IS NULL OR e.status = :status) ORDER BY e.employee_no")
                        .build(),
                ReportDefinition.builder("EMP_BY_DIVISION", "Employee", "Employee distribution by division")
                        .permission("EMP_VIEW").filters(Filter.ORG_UNIT, Filter.STATUS)
                        .col("org_unit_code", "Unit").col("unit_name", "Unit name").col("headcount", "Employees", "number")
                        .sql("SELECT e.org_unit_code, u.name AS unit_name, COUNT(*) AS headcount FROM employee e " +
                                "LEFT JOIN org_unit u ON u.code = e.org_unit_code AND u.effective_from <= CURRENT_DATE " +
                                "AND (u.effective_to IS NULL OR u.effective_to > CURRENT_DATE) WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:status AS varchar) IS NULL OR e.status = :status) " +
                                "AND (CAST(:status AS varchar) IS NOT NULL OR e.status IN ('ACTIVE','PROBATION','ON_LEAVE','SECONDED')) " +
                                "GROUP BY e.org_unit_code, u.name ORDER BY u.name")
                        .build(),
                ReportDefinition.builder("EMP_BY_DESIGNATION", "Employee", "Employee distribution by designation")
                        .permission("EMP_VIEW").filters(Filter.ORG_UNIT, Filter.STATUS)
                        .col("designation", "Designation").col("grade", "Grade").col("headcount", "Employees", "number")
                        .sql("SELECT d.name AS designation, d.grade, COUNT(*) AS headcount FROM employee e " +
                                "JOIN designation d ON d.id = e.designation_id WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:status AS varchar) IS NULL OR e.status = :status) " +
                                "AND (CAST(:status AS varchar) IS NOT NULL OR e.status IN ('ACTIVE','PROBATION','ON_LEAVE','SECONDED')) " +
                                "GROUP BY d.name, d.grade ORDER BY d.name")
                        .build(),
                ReportDefinition.builder("EMP_BY_STATUS", "Employee", "Employee status summary")
                        .permission("EMP_VIEW").filters(Filter.ORG_UNIT)
                        .col("status", "Status").col("headcount", "Employees", "number")
                        .sql("SELECT e.status, COUNT(*) AS headcount FROM employee e WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "GROUP BY e.status ORDER BY e.status")
                        .build(),
                ReportDefinition.builder("EMP_SERVICE_HISTORY", "Employee", "Appointment and promotion history")
                        .permission("EMP_VIEW").filters(Filter.DATE_RANGE, Filter.ORG_UNIT, Filter.STATUS)
                        .description("Status filter = event type, e.g. APPOINTMENT, PROMOTION, TRANSFER, CONFIRMATION")
                        .col("event_date", "Date", "date").col("employee_no", "Emp. No").col("name_with_initials", "Name")
                        .col("event_type", "Event").col("description", "Details").col("reference", "Reference")
                        .sql("SELECT s.event_date, e.employee_no, e.name_with_initials, s.event_type, s.description, s.reference " +
                                "FROM service_event s JOIN employee e ON e.id = s.employee_id WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:from AS date) IS NULL OR s.event_date >= :from) AND (CAST(:to AS date) IS NULL OR s.event_date <= :to) " +
                                "AND ((CAST(:status AS varchar) IS NULL AND s.event_type IN ('APPOINTMENT','PROMOTION','TRANSFER','CONFIRMATION')) " +
                                "OR s.event_type = :status) ORDER BY s.event_date DESC, e.employee_no")
                        .build(),

                // ------------------------------------------------------------ Audit and access
                ReportDefinition.builder("AUD_AUDIT_LOG", "Audit and access", "Audit log")
                        .permission("AUDIT_VIEW").filters(Filter.DATE_RANGE, Filter.STATUS)
                        .description("Status filter = action, e.g. LOGIN, EXPORT, APPROVE")
                        .col("id", "#", "number").col("occurred_at", "When", "datetime").col("username", "User").col("action", "Action")
                        .col("entity_type", "Record type").col("entity_id", "Record").col("summary", "Summary").col("ip_address", "IP")
                        .sql("SELECT id, occurred_at, username, action, entity_type, entity_id, summary, ip_address FROM audit_log " +
                                "WHERE (CAST(:from AS date) IS NULL OR occurred_at >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR occurred_at < CAST(:to AS date) + 1) " +
                                "AND (CAST(:status AS varchar) IS NULL OR action = :status) ORDER BY id DESC")
                        .build(),
                ReportDefinition.builder("AUD_ACCESS_REVIEW", "Audit and access", "Access review")
                        .description("Periodic review of who holds which roles (FR-UAM-006)").permission("AUDIT_VIEW")
                        .col("username", "Username").col("full_name", "Name").col("roles", "Roles").col("data_scope", "Data scope")
                        .col("active", "Active", "boolean").col("last_login_at", "Last sign-in", "datetime")
                        .sql("SELECT u.username, u.full_name, string_agg(r.code, ', ' ORDER BY r.code) AS roles, u.data_scope, u.active, u.last_login_at " +
                                "FROM app_user u LEFT JOIN user_role ur ON ur.user_id = u.id LEFT JOIN role r ON r.id = ur.role_id " +
                                "GROUP BY u.id ORDER BY u.username")
                        .build(),
                ReportDefinition.builder("AUD_APPROVAL_HISTORY", "Audit and access", "Approval history")
                        .permission("AUDIT_VIEW").filters(Filter.DATE_RANGE)
                        .col("acted_at", "When", "datetime").col("process_code", "Process").col("title", "Request")
                        .col("stage_name", "Stage").col("actor_name", "By").col("on_behalf_of_name", "On behalf of")
                        .col("action", "Action").col("comment_text", "Comment")
                        .sql("SELECT a.acted_at, i.process_code, i.title, a.stage_name, a.actor_name, a.on_behalf_of_name, a.action, a.comment_text " +
                                "FROM approval_action a JOIN workflow_instance i ON i.id = a.instance_id " +
                                "WHERE (CAST(:from AS date) IS NULL OR a.acted_at >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR a.acted_at < CAST(:to AS date) + 1) ORDER BY a.id DESC")
                        .build()
        );
    }

    private static final String VACANCY_BASE =
            "SELECT COALESCE(c.designation_id, o.designation_id) AS designation_id, COALESCE(c.org_unit_code, o.org_unit_code) AS org_unit_code, " +
                    "COALESCE(c.approved_qty, 0) AS approved, COALESCE(o.occ, 0) AS occupied FROM " +
                    "(SELECT designation_id, org_unit_code, approved_qty FROM cadre_allocation WHERE" + EFFECTIVE + ") c FULL OUTER JOIN " +
                    "(SELECT designation_id, org_unit_code, COUNT(*) AS occ FROM employee_assignment WHERE" + EFFECTIVE +
                    "GROUP BY designation_id, org_unit_code) o ON c.designation_id = o.designation_id AND c.org_unit_code = o.org_unit_code";

    private static final String VACANCY_SQL =
            "SELECT x.org_unit_code, d.name AS designation, x.approved, x.occupied, GREATEST(x.approved - x.occupied, 0) AS vacant, " +
                    "GREATEST(x.occupied - x.approved, 0) AS excess FROM (" + VACANCY_BASE + ") x JOIN designation d ON d.id = x.designation_id";
}
