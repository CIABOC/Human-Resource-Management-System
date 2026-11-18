package lk.gov.ciaboc.hrms.training;

import lk.gov.ciaboc.hrms.dashboard.DashboardContributor;
import lk.gov.ciaboc.hrms.reports.ReportDefinition;
import lk.gov.ciaboc.hrms.reports.ReportDefinition.Filter;
import lk.gov.ciaboc.hrms.reports.ReportProvider;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Training reports (FR-TRN-004) and dashboard tiles. */
@Component
@RequiredArgsConstructor
public class TrainingReports implements ReportProvider, DashboardContributor {
    private final JdbcTemplate jdbc;

    @Override
    public List<ReportDefinition> reports() {
        return List.of(
                ReportDefinition.builder("TRN_PROGRAMMES", "Training", "Training programmes")
                        .description("Programmes with capacity, nominations and completions. Status filter: PLANNED, OPEN, CLOSED, COMPLETED, CANCELLED")
                        .permission("TRN_VIEW").filters(Filter.DATE_RANGE, Filter.STATUS)
                        .col("code", "Code").col("title", "Programme").col("provider", "Provider").col("start_date", "Start", "date")
                        .col("end_date", "End", "date").col("venue", "Venue").col("capacity", "Capacity", "number")
                        .col("approved", "Approved", "number").col("completed", "Completed", "number").col("cost", "Cost", "money")
                        .col("status", "Status")
                        .sql("SELECT p.code, p.title, p.provider, p.start_date, p.end_date, p.venue, p.capacity, " +
                                "(SELECT COUNT(*) FROM trn_nomination n WHERE n.programme_id = p.id AND n.status = 'APPROVED') AS approved, " +
                                "(SELECT COUNT(*) FROM trn_nomination n WHERE n.programme_id = p.id AND n.completion_status = 'COMPLETED') AS completed, " +
                                "p.cost, p.status FROM trn_programme p WHERE (CAST(:status AS varchar) IS NULL OR p.status = :status) " +
                                "AND (CAST(:from AS date) IS NULL OR p.end_date >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR p.start_date <= CAST(:to AS date)) ORDER BY p.start_date DESC")
                        .build(),
                ReportDefinition.builder("TRN_PARTICIPATION", "Training", "Training participation")
                        .description("Nominations and participation by employee. Status filter: nomination status")
                        .permission("TRN_VIEW").filters(Filter.DATE_RANGE, Filter.ORG_UNIT, Filter.STATUS)
                        .col("code", "Programme").col("title", "Title").col("start_date", "Start", "date")
                        .col("employee_no", "Emp. No").col("name_with_initials", "Name").col("org_unit_code", "Unit")
                        .col("source", "Nominated by").col("status", "Nomination").col("completion_status", "Completion")
                        .col("result", "Result")
                        .sql("SELECT p.code, p.title, p.start_date, e.employee_no, e.name_with_initials, e.org_unit_code, n.source, n.status, " +
                                "n.completion_status, n.result FROM trn_nomination n JOIN trn_programme p ON p.id = n.programme_id " +
                                "JOIN employee e ON e.id = n.employee_id WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:status AS varchar) IS NULL OR n.status = :status) " +
                                "AND (CAST(:from AS date) IS NULL OR p.end_date >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR p.start_date <= CAST(:to AS date)) ORDER BY p.start_date DESC, e.employee_no")
                        .build(),
                ReportDefinition.builder("TRN_BY_DIVISION", "Training", "Training completed by division")
                        .permission("TRN_VIEW").filters(Filter.DATE_RANGE, Filter.ORG_UNIT)
                        .col("org_unit_code", "Unit").col("employees_trained", "Employees trained", "number")
                        .col("programmes_completed", "Completions", "number").col("cost", "Programme cost (attended)", "money")
                        .sql("SELECT e.org_unit_code, COUNT(DISTINCT n.employee_id) AS employees_trained, COUNT(*) AS programmes_completed, " +
                                "SUM(p.cost) AS cost FROM trn_nomination n JOIN trn_programme p ON p.id = n.programme_id " +
                                "JOIN employee e ON e.id = n.employee_id WHERE n.completion_status = 'COMPLETED' /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:from AS date) IS NULL OR p.end_date >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR p.start_date <= CAST(:to AS date)) " +
                                "GROUP BY e.org_unit_code ORDER BY e.org_unit_code")
                        .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<KpiTile> tiles(UserPrincipal user) {
        List<KpiTile> out = new ArrayList<>();
        if (user.getEmployeeId() != null) {
            Long upcoming = jdbc.queryForObject("SELECT COUNT(*) FROM trn_nomination n JOIN trn_programme p ON p.id = n.programme_id " +
                    "WHERE n.employee_id = ? AND n.status = 'APPROVED' AND p.end_date >= CURRENT_DATE AND p.status <> 'CANCELLED'",
                    Long.class, user.getEmployeeId());
            if (upcoming != null && upcoming > 0)
                out.add(new KpiTile("trn-my-upcoming", "My upcoming training", String.valueOf(upcoming), "Approved nominations",
                        "/training/my", "green", 23));
        }
        if (user.has(Permission.TRN_VIEW)) {
            Long open = jdbc.queryForObject("SELECT COUNT(*) FROM trn_programme WHERE status IN ('PLANNED','OPEN')", Long.class);
            out.add(new KpiTile("trn-programmes", "Planned / open programmes", String.valueOf(open), "Training calendar",
                    "/training/programmes", "navy", 24));
        }
        return out;
    }
}
