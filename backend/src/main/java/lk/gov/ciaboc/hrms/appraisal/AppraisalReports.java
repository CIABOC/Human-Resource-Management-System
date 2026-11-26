package lk.gov.ciaboc.hrms.appraisal;

import lk.gov.ciaboc.hrms.dashboard.DashboardContributor;
import lk.gov.ciaboc.hrms.reports.ReportDefinition;
import lk.gov.ciaboc.hrms.reports.ReportDefinition.Filter;
import lk.gov.ciaboc.hrms.reports.ReportProvider;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Appraisal reports (restricted: APR_REPORT + sensitive) used by the management dashboard exports, and dashboard
 * tiles: aggregated completion for management, queues for supervisors and reviewing officers.
 * The period is chosen with the status filter set to the period CODE.
 */
@Component
@RequiredArgsConstructor
public class AppraisalReports implements ReportProvider, DashboardContributor {
    private final JdbcTemplate jdbc;
    private final AppraisalAccess access;

    private static final String PERIOD = " AND (CAST(:status AS varchar) IS NULL OR p.code = :status) ";

    @Override
    public List<ReportDefinition> reports() {
        return List.of(
                ReportDefinition.builder("APR_COMPLETION", "Performance appraisal", "Appraisal completion (process status)")
                        .description("How far the appraisal process has progressed per unit. It does not show how well employees performed. Status filter = period code.")
                        .permission("APR_REPORT").sensitive().filters(Filter.STATUS, Filter.ORG_UNIT, Filter.DESIGNATION)
                        .col("period", "Period").col("org_unit", "Unit").col("total", "Appraisals", "number")
                        .col("agreement", "Agreement stage", "number").col("in_progress", "In progress", "number")
                        .col("with_supervisor", "With supervisor", "number").col("with_reviewer", "With reviewing officer", "number")
                        .col("approved", "Awaiting acknowledgement", "number").col("finalized", "Finalized", "number")
                        .sql("SELECT p.name AS period, COALESCE(u.name, e.org_unit_code) AS org_unit, COUNT(*) AS total, " +
                                "COUNT(*) FILTER (WHERE a.agreement_status <> 'FINAL') AS agreement, " +
                                "COUNT(*) FILTER (WHERE a.agreement_status = 'FINAL' AND a.status = 'DRAFT') AS in_progress, " +
                                "COUNT(*) FILTER (WHERE a.status = 'SUBMITTED') AS with_supervisor, " +
                                "COUNT(*) FILTER (WHERE a.status = 'VERIFIED') AS with_reviewer, " +
                                "COUNT(*) FILTER (WHERE a.status IN ('APPROVED','ACKNOWLEDGED')) AS approved, " +
                                "COUNT(*) FILTER (WHERE a.status = 'FINALIZED') AS finalized " +
                                "FROM apr_appraisal a JOIN per_period p ON p.id = a.period_id JOIN employee e ON e.id = a.employee_id " +
                                "LEFT JOIN org_unit u ON u.code = e.org_unit_code AND u.effective_to IS NULL " +
                                "WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/" + PERIOD +
                                "AND (CAST(:designationId AS bigint) IS NULL OR e.designation_id = :designationId) " +
                                "GROUP BY p.name, p.start_date, COALESCE(u.name, e.org_unit_code) ORDER BY p.start_date DESC, 2")
                        .build(),
                ReportDefinition.builder("APR_RESULTS", "Performance appraisal", "Appraisal results (finalized)")
                        .description("Finalized appraisal results per employee, calculated exactly as in the individual appraisals. Status filter = period code.")
                        .permission("APR_REPORT").sensitive().filters(Filter.STATUS, Filter.ORG_UNIT, Filter.DESIGNATION)
                        .col("period", "Period").col("employee_no", "Emp. No").col("name", "Name").col("designation", "Designation")
                        .col("org_unit", "Unit").col("kpi_score", "KPI result", "number").col("behaviour_score", "Behavioural result", "number")
                        .col("overall_score", "Calculated score", "number").col("adjustment", "Moderation", "number")
                        .col("final_score", "Final score", "number").col("category", "Category").col("finalized_at", "Finalized", "datetime")
                        .sql("SELECT p.name AS period, e.employee_no, COALESCE(e.name_with_initials, e.full_name) AS name, d.name AS designation, " +
                                "COALESCE(u.name, e.org_unit_code) AS org_unit, a.kpi_score, a.behaviour_score, a.overall_score, a.adjustment, " +
                                "a.final_score, a.category, a.finalized_at " +
                                "FROM apr_appraisal a JOIN per_period p ON p.id = a.period_id JOIN employee e ON e.id = a.employee_id " +
                                "LEFT JOIN designation d ON d.id = e.designation_id " +
                                "LEFT JOIN org_unit u ON u.code = e.org_unit_code AND u.effective_to IS NULL " +
                                "WHERE a.status = 'FINALIZED' /*SCOPE:e*/ /*ORG:e.org_unit_code*/" + PERIOD +
                                "AND (CAST(:designationId AS bigint) IS NULL OR e.designation_id = :designationId) " +
                                "ORDER BY p.start_date DESC, org_unit, e.employee_no")
                        .build());
    }

    @Override
    public List<KpiTile> tiles(UserPrincipal user) {
        List<KpiTile> out = new ArrayList<>();
        Long pid = access.currentPeriodId();
        if (pid == null) return out;
        if (user.has(Permission.APR_OVERVIEW)) {
            Map<String, Object> m = jdbc.queryForMap(
                    "SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE status = 'FINALIZED') AS done, " +
                            "COUNT(*) FILTER (WHERE status = 'VERIFIED') AS reviewing, " +
                            "ROUND(AVG(final_score) FILTER (WHERE status = 'FINALIZED'), 1) AS avg FROM apr_appraisal WHERE period_id = ?", pid);
            long total = ((Number) m.get("total")).longValue();
            long done = ((Number) m.get("done")).longValue();
            out.add(new KpiTile("per-apr-completion", "Appraisals finalized", done + " / " + total,
                    total == 0 ? "No appraisals started" : Math.round(done * 100.0 / total) + "% of the current period", "/appraisal/management", "navy", 60));
            Object avg = m.get("avg");
            out.add(new KpiTile("per-apr-average", "Average appraisal score", avg == null ? "—" : ((BigDecimal) avg).toPlainString(),
                    "Finalized appraisals, current period", "/appraisal/management", "gold", 61));
        }
        long toVerify = count("SELECT COUNT(*) FROM apr_appraisal WHERE period_id = ? AND supervisor_user_id = ? AND status = 'SUBMITTED'", pid, user.getId());
        if (toVerify > 0) out.add(new KpiTile("per-apr-verify", "Appraisals to verify", String.valueOf(toVerify), "Your team", "/appraisal/team", "gold", 62));
        long toApprove = count("SELECT COUNT(*) FROM apr_appraisal WHERE period_id = ? AND reviewer_user_id = ? AND status = 'VERIFIED'", pid, user.getId());
        if (toApprove > 0) out.add(new KpiTile("per-apr-approve", "Appraisals to approve", String.valueOf(toApprove), "As reviewing officer", "/appraisal/approvals", "gold", 63));
        return out;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
