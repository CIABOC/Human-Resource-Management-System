package lk.gov.ciaboc.hrms.extended;

import lk.gov.ciaboc.hrms.reports.ReportDefinition;
import lk.gov.ciaboc.hrms.reports.ReportDefinition.Filter;
import lk.gov.ciaboc.hrms.reports.ReportProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** Extended-scope reports (SRS 4.16). Employee-listing reports carry the data-scope marker. */
@Component
public class ExtendedReports implements ReportProvider {
    private static final String CATEGORY = "Transfers, retirement and records";
    private static final String PERMISSION = "EXT_VIEW";

    @Override
    public List<ReportDefinition> reports() {
        return List.of(
                ReportDefinition.builder("EXT_MOVEMENTS", CATEGORY, "Transfers and promotions register")
                        .description("Transfer, promotion and confirmation requests by proposed effective date. Status filter = "
                                + "request status (PENDING, APPLIED, ...) or type (TRANSFER, PROMOTION, CONFIRMATION)")
                        .permission(PERMISSION).filters(Filter.DATE_RANGE, Filter.ORG_UNIT, Filter.STATUS)
                        .col("id", "Ref", "number").col("request_type", "Type").col("employee_no", "Emp. No")
                        .col("name_with_initials", "Name").col("from_designation", "From designation").col("from_org_unit_code", "From unit")
                        .col("to_designation", "To designation").col("target_org_unit_code", "To unit")
                        .col("effective_date", "Effective", "date").col("status", "Status").col("cadre_warning", "Cadre check")
                        .col("requested_by", "Requested by").col("applied_at", "Applied", "datetime")
                        .sql("SELECT m.id, m.request_type, e.employee_no, e.name_with_initials, fd.name AS from_designation, m.from_org_unit_code, " +
                                "td.name AS to_designation, m.target_org_unit_code, m.effective_date, m.status, m.cadre_warning, " +
                                "m.created_by AS requested_by, m.applied_at FROM ext_movement_request m JOIN employee e ON e.id = m.employee_id " +
                                "LEFT JOIN designation fd ON fd.id = m.from_designation_id LEFT JOIN designation td ON td.id = m.target_designation_id " +
                                "WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:from AS date) IS NULL OR m.effective_date >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR m.effective_date <= CAST(:to AS date)) " +
                                "AND (CAST(:status AS varchar) IS NULL OR m.status = CAST(:status AS varchar) OR m.request_type = CAST(:status AS varchar)) " +
                                "ORDER BY m.id DESC")
                        .build(),
                ReportDefinition.builder("EXT_RETIREMENT_FORECAST", CATEGORY, "Retirement forecast")
                        .description("In-service employees retiring between the dates (default: today to 12 months ahead)")
                        .permission(PERMISSION).filters(Filter.DATE_RANGE, Filter.ORG_UNIT, Filter.DESIGNATION)
                        .col("employee_no", "Emp. No").col("name_with_initials", "Name").col("designation", "Designation")
                        .col("org_unit_code", "Unit").col("date_of_birth", "Date of birth", "date").col("retirement_date", "Retirement", "date")
                        .col("months_remaining", "Months remaining", "number").col("case_status", "Separation case")
                        .sql("SELECT e.employee_no, e.name_with_initials, d.name AS designation, e.org_unit_code, e.date_of_birth, e.retirement_date, " +
                                "(EXTRACT(YEAR FROM age(e.retirement_date, CURRENT_DATE)) * 12 + EXTRACT(MONTH FROM age(e.retirement_date, CURRENT_DATE)))::int AS months_remaining, " +
                                "(SELECT c.status FROM ext_separation_case c WHERE c.employee_id = e.id ORDER BY c.id DESC LIMIT 1) AS case_status " +
                                "FROM employee e LEFT JOIN designation d ON d.id = e.designation_id " +
                                "WHERE e.status IN ('ACTIVE','PROBATION','ON_LEAVE','SECONDED') /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:designationId AS bigint) IS NULL OR e.designation_id = :designationId) " +
                                "AND e.retirement_date >= COALESCE(CAST(:from AS date), CURRENT_DATE) " +
                                "AND e.retirement_date <= COALESCE(CAST(:to AS date), CAST(CURRENT_DATE + INTERVAL '12 months' AS date)) " +
                                "ORDER BY e.retirement_date, e.employee_no")
                        .build(),
                ReportDefinition.builder("EXT_SEPARATIONS", CATEGORY, "Separations and clearance status")
                        .description("Separation cases by separation date. Status filter = OPEN, COMPLETED or CANCELLED")
                        .permission(PERMISSION).filters(Filter.DATE_RANGE, Filter.ORG_UNIT, Filter.STATUS)
                        .col("id", "Case", "number").col("employee_no", "Emp. No").col("name_with_initials", "Name")
                        .col("separation_type", "Type").col("separation_reason", "Reason").col("separation_date", "Separation date", "date")
                        .col("status", "Status").col("items_total", "Clearance items", "number").col("items_pending", "Pending", "number")
                        .col("settlement_recorded", "Settlement recorded", "boolean").col("opened_by", "Opened by")
                        .col("completed_at", "Completed", "datetime")
                        .sql("SELECT c.id, e.employee_no, e.name_with_initials, c.separation_type, c.separation_reason, c.separation_date, c.status, " +
                                "(SELECT COUNT(*) FROM ext_separation_clearance x WHERE x.case_id = c.id) AS items_total, " +
                                "(SELECT COUNT(*) FROM ext_separation_clearance x WHERE x.case_id = c.id AND x.status = 'PENDING') AS items_pending, " +
                                "(c.settlement_at IS NOT NULL) AS settlement_recorded, c.created_by AS opened_by, c.completed_at " +
                                "FROM ext_separation_case c JOIN employee e ON e.id = c.employee_id WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:from AS date) IS NULL OR c.separation_date >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR c.separation_date <= CAST(:to AS date)) " +
                                "AND (CAST(:status AS varchar) IS NULL OR c.status = CAST(:status AS varchar)) ORDER BY c.separation_date DESC, c.id DESC")
                        .build(),
                ReportDefinition.builder("EXT_LETTERS", CATEGORY, "Issued letters and certificates register")
                        .description("Documents issued, by issue date. Status filter = VALID or REVOKED")
                        .permission(PERMISSION).filters(Filter.DATE_RANGE, Filter.ORG_UNIT, Filter.STATUS)
                        .col("document_no", "Document no.").col("issued_at", "Issued", "datetime").col("employee_no", "Emp. No")
                        .col("holder_name", "Issued to").col("document_type_name", "Document").col("language", "Language")
                        .col("issued_by", "Issued by").col("status", "Status").col("revoked_at", "Revoked", "datetime")
                        .col("revoke_reason", "Revocation reason")
                        .sql("SELECT d.document_no, d.issued_at, e.employee_no, d.holder_name, d.document_type_name, d.language, d.issued_by, " +
                                "d.status, d.revoked_at, d.revoke_reason FROM ext_issued_document d JOIN employee e ON e.id = d.employee_id " +
                                "WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:from AS date) IS NULL OR d.issued_at >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR d.issued_at < CAST(:to AS date) + 1) " +
                                "AND (CAST(:status AS varchar) IS NULL OR d.status = CAST(:status AS varchar)) ORDER BY d.issued_at DESC")
                        .build(),
                ReportDefinition.builder("EXT_DIGITIZATION", CATEGORY, "Digitization progress")
                        .description("Personal-file digitization batches and scan counts by status. Status filter = OPEN, IN_QA or COMPLETED")
                        .permission(PERMISSION).filters(Filter.DATE_RANGE, Filter.STATUS)
                        .col("batch_no", "Batch").col("title", "Title").col("status", "Status").col("total", "Scans", "number")
                        .col("uploaded", "Awaiting QA", "number").col("verified", "Verified", "number").col("rejected", "Rejected", "number")
                        .col("filed", "Filed", "number").col("withdrawn", "Withdrawn", "number").col("created_by", "Created by")
                        .col("created_at", "Created", "datetime").col("completed_at", "Completed", "datetime")
                        .sql("SELECT b.batch_no, b.title, b.status, COUNT(i.id) AS total, " +
                                "COUNT(i.id) FILTER (WHERE i.status = 'UPLOADED') AS uploaded, COUNT(i.id) FILTER (WHERE i.status = 'VERIFIED') AS verified, " +
                                "COUNT(i.id) FILTER (WHERE i.status = 'REJECTED') AS rejected, COUNT(i.id) FILTER (WHERE i.status = 'FILED') AS filed, " +
                                "COUNT(i.id) FILTER (WHERE i.status = 'WITHDRAWN') AS withdrawn, b.created_by, b.created_at, b.completed_at " +
                                "FROM ext_digitization_batch b LEFT JOIN ext_digitization_item i ON i.batch_id = b.id " +
                                "WHERE (CAST(:from AS date) IS NULL OR b.created_at >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR b.created_at < CAST(:to AS date) + 1) " +
                                "AND (CAST(:status AS varchar) IS NULL OR b.status = CAST(:status AS varchar)) " +
                                "GROUP BY b.id ORDER BY b.id DESC")
                        .build()
        );
    }
}
