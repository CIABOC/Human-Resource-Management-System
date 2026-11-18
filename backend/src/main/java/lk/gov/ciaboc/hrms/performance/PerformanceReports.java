package lk.gov.ciaboc.hrms.performance;

import lk.gov.ciaboc.hrms.dashboard.DashboardContributor;
import lk.gov.ciaboc.hrms.reports.ReportDefinition;
import lk.gov.ciaboc.hrms.reports.ReportDefinition.Filter;
import lk.gov.ciaboc.hrms.reports.ReportProvider;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Performance reports (FR-PER-008). All are SENSITIVE (RP-02: need RPT_SENSITIVE) and scoped to the caller's employees.
 * The period is chosen with the status filter set to the period CODE (e.g. 2025-ANNUAL).
 */
@Component
@RequiredArgsConstructor
public class PerformanceReports implements ReportProvider, DashboardContributor {
    private final JdbcTemplate jdbc;

    private static final String PERIOD = " AND (CAST(:status AS varchar) IS NULL OR p.code = :status) ";

    @Override
    public List<ReportDefinition> reports() {
        return List.of(
                ReportDefinition.builder("PER_COMPLETION", "Performance", "Evaluation completion status")
                        .description("Progress of evaluations per period and status. Status filter = period code.")
                        .permission("PER_VIEW").sensitive().filters(Filter.STATUS, Filter.ORG_UNIT)
                        .col("period", "Period").col("org_unit_code", "Unit").col("draft", "Draft", "number")
                        .col("submitted", "Submitted", "number").col("returned", "Returned / rejected", "number")
                        .col("finalized", "Finalized", "number").col("acknowledged", "Acknowledged", "number")
                        .col("total", "Total", "number")
                        .sql("SELECT p.name AS period, e.org_unit_code, " +
                                "COUNT(*) FILTER (WHERE v.status = 'DRAFT') AS draft, " +
                                "COUNT(*) FILTER (WHERE v.status = 'SUBMITTED') AS submitted, " +
                                "COUNT(*) FILTER (WHERE v.status IN ('RETURNED','REJECTED')) AS returned, " +
                                "COUNT(*) FILTER (WHERE v.status = 'FINALIZED') AS finalized, " +
                                "COUNT(*) FILTER (WHERE v.acknowledged_at IS NOT NULL) AS acknowledged, COUNT(*) AS total " +
                                "FROM per_evaluation v JOIN per_period p ON p.id = v.period_id JOIN employee e ON e.id = v.employee_id " +
                                "WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/" + PERIOD +
                                "GROUP BY p.name, p.start_date, e.org_unit_code ORDER BY p.start_date DESC, e.org_unit_code")
                        .build(),
                ReportDefinition.builder("PER_SUMMARY", "Performance", "Evaluation summary")
                        .description("Finalized evaluation scores per employee. Status filter = period code.")
                        .permission("PER_VIEW").sensitive().filters(Filter.STATUS, Filter.ORG_UNIT, Filter.DESIGNATION)
                        .col("period", "Period").col("employee_no", "Emp. No").col("name_with_initials", "Name")
                        .col("designation", "Designation").col("org_unit_code", "Unit").col("model", "Model")
                        .col("overall_score", "Score", "number").col("overall_percent", "Score %", "number")
                        .col("evaluator_name", "Evaluator").col("finalized_at", "Finalized", "datetime")
                        .col("acknowledged_at", "Acknowledged", "datetime")
                        .sql("SELECT p.name AS period, e.employee_no, e.name_with_initials, d.name AS designation, e.org_unit_code, " +
                                "m.code AS model, v.overall_score, v.overall_percent, v.evaluator_name, v.finalized_at, v.acknowledged_at " +
                                "FROM per_evaluation v JOIN per_period p ON p.id = v.period_id JOIN employee e ON e.id = v.employee_id " +
                                "JOIN per_model m ON m.id = v.model_id LEFT JOIN designation d ON d.id = e.designation_id " +
                                "WHERE v.status = 'FINALIZED' /*SCOPE:e*/ /*ORG:e.org_unit_code*/" + PERIOD +
                                "AND (CAST(:designationId AS bigint) IS NULL OR e.designation_id = :designationId) " +
                                "ORDER BY p.start_date DESC, e.employee_no")
                        .build(),
                ReportDefinition.builder("PER_HISTORY", "Performance", "Employee evaluation history")
                        .description("All finalized evaluations of employees across periods")
                        .permission("PER_VIEW").sensitive().filters(Filter.ORG_UNIT, Filter.DATE_RANGE)
                        .col("employee_no", "Emp. No").col("name_with_initials", "Name").col("period", "Period")
                        .col("start_date", "From", "date").col("end_date", "To", "date")
                        .col("overall_score", "Score", "number").col("overall_percent", "Score %", "number")
                        .sql("SELECT e.employee_no, e.name_with_initials, p.name AS period, p.start_date, p.end_date, v.overall_score, v.overall_percent " +
                                "FROM per_evaluation v JOIN per_period p ON p.id = v.period_id JOIN employee e ON e.id = v.employee_id " +
                                "WHERE v.status = 'FINALIZED' /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:from AS date) IS NULL OR p.end_date >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR p.start_date <= CAST(:to AS date)) " +
                                "ORDER BY e.employee_no, p.start_date DESC")
                        .build(),
                ReportDefinition.builder("PER_BY_DIVISION", "Performance", "Division-wise performance")
                        .description("Average finalized score by unit. Status filter = period code.")
                        .permission("PER_VIEW").sensitive().filters(Filter.STATUS, Filter.ORG_UNIT)
                        .col("period", "Period").col("org_unit_code", "Unit").col("unit_name", "Unit name")
                        .col("evaluated", "Evaluated", "number").col("avg_percent", "Average score %", "number")
                        .col("min_percent", "Lowest %", "number").col("max_percent", "Highest %", "number")
                        .sql("SELECT p.name AS period, e.org_unit_code, u.name AS unit_name, COUNT(*) AS evaluated, " +
                                "ROUND(AVG(v.overall_percent), 2) AS avg_percent, MIN(v.overall_percent) AS min_percent, MAX(v.overall_percent) AS max_percent " +
                                "FROM per_evaluation v JOIN per_period p ON p.id = v.period_id JOIN employee e ON e.id = v.employee_id " +
                                "LEFT JOIN org_unit u ON u.code = e.org_unit_code AND u.effective_from <= CURRENT_DATE " +
                                "AND (u.effective_to IS NULL OR u.effective_to > CURRENT_DATE) " +
                                "WHERE v.status = 'FINALIZED' /*SCOPE:e*/ /*ORG:e.org_unit_code*/" + PERIOD +
                                "GROUP BY p.name, p.start_date, e.org_unit_code, u.name ORDER BY p.start_date DESC, u.name")
                        .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<KpiTile> tiles(UserPrincipal user) {
        List<KpiTile> out = new ArrayList<>();
        Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM per_evaluation v JOIN per_period p ON p.id = v.period_id " +
                "WHERE v.evaluator_user_id = ? AND v.status IN ('DRAFT','RETURNED','REJECTED') AND p.status = 'OPEN'", Long.class, user.getId());
        if (pending != null && pending > 0) {
            out.add(new KpiTile("per-to-evaluate", "Evaluations to complete", String.valueOf(pending), "Assigned to you as evaluator",
                    "/performance/assigned", "gold", 21));
        }
        if (user.getEmployeeId() != null) {
            Long ack = jdbc.queryForObject("SELECT COUNT(*) FROM per_evaluation WHERE employee_id = ? AND status = 'FINALIZED' " +
                    "AND acknowledged_at IS NULL", Long.class, user.getEmployeeId());
            if (ack != null && ack > 0) {
                out.add(new KpiTile("per-to-acknowledge", "Evaluations to acknowledge", String.valueOf(ack), "Your finalized evaluations",
                        "/performance/my", "navy", 22));
            }
        }
        return out;
    }
}
