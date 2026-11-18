package lk.gov.ciaboc.hrms.recruitment;

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
import java.util.Map;

/** Recruitment reports (SRS Section 8) and dashboard indicators. Applicants are not employees, so no SCOPE filter applies. */
@Component
@RequiredArgsConstructor
public class RecruitmentReports implements ReportProvider, DashboardContributor {
    private final JdbcTemplate jdbc;

    @Override
    public List<ReportDefinition> reports() {
        return List.of(
                ReportDefinition.builder("REC_VACANCIES", "Recruitment", "Recruitment vacancies")
                        .description("Vacancies with applications received and appointments made. Status filter: DRAFT, OPEN, CLOSED, FILLED, CANCELLED")
                        .permission("REC_VIEW").filters(Filter.ORG_UNIT, Filter.DESIGNATION, Filter.STATUS, Filter.DATE_RANGE)
                        .col("reference_no", "Reference").col("title", "Title").col("designation", "Designation")
                        .col("org_unit_code", "Unit").col("num_posts", "Posts", "number").col("status", "Status")
                        .col("opening_date", "Opens", "date").col("closing_date", "Closes", "date")
                        .col("applications", "Applications", "number").col("shortlisted", "Shortlisted", "number")
                        .col("selected", "Selected", "number").col("appointed", "Appointed", "number")
                        .sql("SELECT v.reference_no, v.title, d.name AS designation, v.org_unit_code, v.num_posts, v.status, v.opening_date, v.closing_date, " +
                                "(SELECT COUNT(*) FROM rec_applicant a WHERE a.vacancy_id = v.id) AS applications, " +
                                "(SELECT COUNT(*) FROM rec_applicant a WHERE a.vacancy_id = v.id AND a.status IN ('SHORTLISTED','RECOMMENDED','SELECTED','NOT_SELECTED','APPOINTED')) AS shortlisted, " +
                                "(SELECT COUNT(*) FROM rec_applicant a WHERE a.vacancy_id = v.id AND a.status IN ('SELECTED','APPOINTED')) AS selected, " +
                                "(SELECT COUNT(*) FROM rec_applicant a WHERE a.vacancy_id = v.id AND a.status = 'APPOINTED') AS appointed " +
                                "FROM rec_vacancy v JOIN designation d ON d.id = v.designation_id WHERE 1=1 /*ORG:v.org_unit_code*/ " +
                                "AND (CAST(:designationId AS bigint) IS NULL OR v.designation_id = :designationId) " +
                                "AND (CAST(:status AS varchar) IS NULL OR v.status = :status) " +
                                "AND (CAST(:from AS date) IS NULL OR v.created_at >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR v.created_at < CAST(:to AS date) + 1) ORDER BY v.id DESC")
                        .build(),
                ReportDefinition.builder("REC_APPLICATIONS_BY_STATUS", "Recruitment", "Applications by status")
                        .description("Number of applications per vacancy and status").permission("REC_VIEW")
                        .filters(Filter.ORG_UNIT, Filter.STATUS, Filter.DATE_RANGE)
                        .col("reference_no", "Vacancy").col("title", "Title").col("status", "Application status")
                        .col("applications", "Applications", "number")
                        .sql("SELECT v.reference_no, v.title, a.status, COUNT(*) AS applications FROM rec_applicant a " +
                                "JOIN rec_vacancy v ON v.id = a.vacancy_id WHERE 1=1 /*ORG:v.org_unit_code*/ " +
                                "AND (CAST(:status AS varchar) IS NULL OR a.status = :status) " +
                                "AND (CAST(:from AS date) IS NULL OR a.received_date >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR a.received_date <= CAST(:to AS date)) " +
                                "GROUP BY v.reference_no, v.title, a.status ORDER BY v.reference_no, a.status")
                        .build(),
                ReportDefinition.builder("REC_APPOINTMENTS", "Recruitment", "Appointments made through recruitment")
                        .permission("REC_VIEW").filters(Filter.DATE_RANGE, Filter.ORG_UNIT)
                        .col("reference_no", "Vacancy").col("application_no", "Application").col("name_with_initials", "Name")
                        .col("employee_no", "Emp. No").col("org_unit_code", "Unit").col("appointment_date", "Appointed", "date")
                        .sql("SELECT v.reference_no, a.application_no, a.name_with_initials, e.employee_no, e.org_unit_code, e.appointment_date " +
                                "FROM rec_applicant a JOIN rec_vacancy v ON v.id = a.vacancy_id JOIN employee e ON e.id = a.employee_id " +
                                "WHERE a.status = 'APPOINTED' /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                "AND (CAST(:from AS date) IS NULL OR e.appointment_date >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR e.appointment_date <= CAST(:to AS date)) ORDER BY e.appointment_date DESC")
                        .build(),
                ReportDefinition.builder("REC_CORRESPONDENCE", "Recruitment", "Recruitment correspondence log")
                        .permission("REC_VIEW").filters(Filter.DATE_RANGE)
                        .col("generated_at", "Generated", "datetime").col("reference_no", "Vacancy").col("application_no", "Application")
                        .col("category", "Letter").col("language", "Lang.").col("channel", "Channel").col("status", "Status")
                        .col("delivery", "Delivery").col("generated_by", "By")
                        .sql("SELECT c.generated_at, v.reference_no, a.application_no, c.category, c.language, c.channel, c.status, " +
                                "m.status AS delivery, c.generated_by FROM rec_correspondence c JOIN rec_applicant a ON a.id = c.applicant_id " +
                                "JOIN rec_vacancy v ON v.id = c.vacancy_id LEFT JOIN outbound_message m ON m.id = c.outbound_message_id " +
                                "WHERE (CAST(:from AS date) IS NULL OR c.generated_at >= CAST(:from AS date)) " +
                                "AND (CAST(:to AS date) IS NULL OR c.generated_at < CAST(:to AS date) + 1) ORDER BY c.generated_at DESC")
                        .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<KpiTile> tiles(UserPrincipal user) {
        List<KpiTile> out = new ArrayList<>();
        if (user.has(Permission.REC_VIEW)) {
            Long open = jdbc.queryForObject("SELECT COUNT(*) FROM rec_vacancy WHERE status = 'OPEN'", Long.class);
            Long posts = jdbc.queryForObject("SELECT COALESCE(SUM(num_posts), 0) FROM rec_vacancy WHERE status = 'OPEN'", Long.class);
            out.add(new KpiTile("rec-open-vacancies", "Open vacancies", String.valueOf(open), posts + " post(s) under recruitment",
                    "/recruitment/vacancies", "gold", 20));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Chart> charts(UserPrincipal user) {
        if (!user.has(Permission.REC_VIEW)) return List.of();
        List<Map<String, Object>> data = jdbc.queryForList(
                "SELECT a.status AS label, COUNT(*) AS value FROM rec_applicant a JOIN rec_vacancy v ON v.id = a.vacancy_id " +
                        "WHERE v.status IN ('OPEN','CLOSED') GROUP BY a.status ORDER BY 2 DESC");
        return data.isEmpty() ? List.of() : List.of(new Chart("rec-applications", "Applications in active recruitment", "pie", data, 20));
    }
}
