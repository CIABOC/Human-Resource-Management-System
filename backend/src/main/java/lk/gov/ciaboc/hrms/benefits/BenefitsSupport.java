package lk.gov.ciaboc.hrms.benefits;

import lk.gov.ciaboc.hrms.dashboard.DashboardContributor;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataUsageChecker;
import lk.gov.ciaboc.hrms.reports.ReportDefinition;
import lk.gov.ciaboc.hrms.reports.ReportDefinition.Filter;
import lk.gov.ciaboc.hrms.reports.ReportProvider;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Reports, dashboard tiles and master-data usage for the benefits module. */
public final class BenefitsSupport {
    private BenefitsSupport() {
    }

    /** Prevents deactivating an INSURANCE_CATEGORY still used by a scheme (FR-MDM-003). */
    @Component
    @RequiredArgsConstructor
    public static class InsuranceCategoryUsage implements MasterDataUsageChecker {
        private final InsuranceSchemeRepository schemes;

        @Override
        public long usageCount(String category, String code) {
            return MasterDataCategory.INSURANCE_CATEGORY.name().equals(category) ? schemes.countByCategory(code) : 0;
        }
    }

    /** Insurance reports (FR-BEN-005, SRS Section 8). */
    @Component
    public static class BenefitsReports implements ReportProvider {
        private static final String EMP = " JOIN employee e ON e.id = c.employee_id ";

        @Override
        public List<ReportDefinition> reports() {
            return List.of(
                    ReportDefinition.builder("BEN_COVERAGE", "Benefits", "Insurance coverage")
                            .description("Employee policies by scheme. Status filter: ACTIVE, EXPIRED or CANCELLED")
                            .permission("BEN_VIEW").filters(Filter.ORG_UNIT, Filter.STATUS)
                            .col("employee_no", "Employee no").col("name", "Name").col("org_unit_code", "Unit")
                            .col("scheme", "Scheme").col("scheme_type", "Type").col("policy_no", "Policy no")
                            .col("start_date", "Start", "date").col("expiry_date", "Expiry", "date")
                            .col("coverage_amount", "Coverage", "money").col("status", "Status")
                            .sql("SELECT e.employee_no, e.name_with_initials AS name, e.org_unit_code, s.name AS scheme, s.scheme_type, " +
                                    "c.policy_no, c.start_date, c.expiry_date, c.coverage_amount, c.status " +
                                    "FROM ben_insurance_coverage c JOIN ben_insurance_scheme s ON s.id = c.scheme_id" + EMP +
                                    "WHERE 1=1 /*SCOPE:e*/ /*ORG:e.org_unit_code*/ AND (CAST(:status AS varchar) IS NULL OR c.status = :status) " +
                                    "ORDER BY e.employee_no, s.code")
                            .build(),
                    ReportDefinition.builder("BEN_ELIGIBILITY", "Benefits", "Insurance eligibility")
                            .description("In-service employees against each active scheme's employment-type rule, and whether they are covered")
                            .permission("BEN_VIEW").filters(Filter.ORG_UNIT)
                            .col("employee_no", "Employee no").col("name", "Name").col("employment_type", "Employment type")
                            .col("scheme", "Scheme").col("eligible", "Eligible", "boolean").col("covered", "Covered", "boolean")
                            .sql("SELECT e.employee_no, e.name_with_initials AS name, e.employment_type, s.name AS scheme, " +
                                    "(s.eligible_employment_types IS NULL OR (',' || s.eligible_employment_types || ',') LIKE ('%,' || e.employment_type || ',%')) " +
                                    "AND (e.appointment_date IS NULL OR e.appointment_date <= (CURRENT_DATE - make_interval(months => s.min_service_months))) AS eligible, " +
                                    "EXISTS (SELECT 1 FROM ben_insurance_coverage c WHERE c.employee_id = e.id AND c.scheme_id = s.id AND c.status = 'ACTIVE') AS covered " +
                                    "FROM employee e CROSS JOIN ben_insurance_scheme s " +
                                    "WHERE s.active AND e.status IN ('ACTIVE','PROBATION','ON_LEAVE','SECONDED') /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                    "ORDER BY e.employee_no, s.code")
                            .build(),
                    ReportDefinition.builder("BEN_PREMIUMS", "Benefits", "Premiums and contributions")
                            .description("Active coverage premiums per scheme (monthly)").permission("BEN_VIEW")
                            .col("scheme", "Scheme").col("provider", "Provider").col("policies", "Active policies", "number")
                            .col("employee_premiums", "Employee premiums", "money").col("employer_premiums", "Employer premiums", "money")
                            .col("total", "Total", "money")
                            .sql("SELECT s.name AS scheme, s.provider, COUNT(c.id) AS policies, COALESCE(SUM(c.employee_premium), 0) AS employee_premiums, " +
                                    "COALESCE(SUM(c.employer_premium), 0) AS employer_premiums, " +
                                    "COALESCE(SUM(c.employee_premium + c.employer_premium), 0) AS total " +
                                    "FROM ben_insurance_scheme s LEFT JOIN ben_insurance_coverage c ON c.scheme_id = s.id AND c.status = 'ACTIVE' " +
                                    "GROUP BY s.id, s.name, s.provider ORDER BY s.name")
                            .build(),
                    ReportDefinition.builder("BEN_EXPIRY", "Benefits", "Policy expiry")
                            .description("Active policies expiring in the date range (defaults to the next 90 days)")
                            .permission("BEN_VIEW").filters(Filter.DATE_RANGE, Filter.ORG_UNIT)
                            .col("employee_no", "Employee no").col("name", "Name").col("scheme", "Scheme").col("policy_no", "Policy no")
                            .col("expiry_date", "Expiry", "date").col("days_left", "Days left", "number")
                            .col("notified", "Notified on", "date")
                            .sql("SELECT e.employee_no, e.name_with_initials AS name, s.name AS scheme, c.policy_no, c.expiry_date, " +
                                    "(c.expiry_date - CURRENT_DATE) AS days_left, c.expiry_notified_on AS notified " +
                                    "FROM ben_insurance_coverage c JOIN ben_insurance_scheme s ON s.id = c.scheme_id" + EMP +
                                    "WHERE c.status = 'ACTIVE' AND c.expiry_date IS NOT NULL " +
                                    "AND c.expiry_date >= COALESCE(CAST(:from AS date), CURRENT_DATE) " +
                                    "AND c.expiry_date <= COALESCE(CAST(:to AS date), CURRENT_DATE + 90) /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                    "ORDER BY c.expiry_date")
                            .build(),
                    ReportDefinition.builder("BEN_CLAIMS", "Benefits", "Insurance claims")
                            .description("Claims lodged in the date range. Status filter: SUBMITTED, IN_REVIEW, APPROVED, REJECTED, PAID")
                            .permission("BEN_VIEW").filters(Filter.DATE_RANGE, Filter.STATUS)
                            .col("employee_no", "Employee no").col("name", "Name").col("scheme", "Scheme").col("claim_date", "Claim date", "date")
                            .col("claim_type", "Type").col("amount_claimed", "Claimed", "money").col("amount_approved", "Approved", "money")
                            .col("status", "Status")
                            .sql("SELECT e.employee_no, e.name_with_initials AS name, s.name AS scheme, cl.claim_date, cl.claim_type, " +
                                    "cl.amount_claimed, cl.amount_approved, cl.status " +
                                    "FROM ben_insurance_claim cl JOIN ben_insurance_coverage c ON c.id = cl.coverage_id " +
                                    "JOIN ben_insurance_scheme s ON s.id = c.scheme_id" + EMP +
                                    "WHERE (CAST(:from AS date) IS NULL OR cl.claim_date >= CAST(:from AS date)) " +
                                    "AND (CAST(:to AS date) IS NULL OR cl.claim_date <= CAST(:to AS date)) " +
                                    "AND (CAST(:status AS varchar) IS NULL OR cl.status = :status) /*SCOPE:e*/ ORDER BY cl.claim_date DESC")
                            .build());
        }
    }

    /** Dashboard: policies expiring soon (BEN_VIEW). */
    @Component
    @RequiredArgsConstructor
    public static class BenefitsDashboard implements DashboardContributor {
        private final BenefitsService service;

        @Override
        @Transactional(readOnly = true)
        public List<KpiTile> tiles(UserPrincipal user) {
            List<KpiTile> out = new ArrayList<>();
            if (user.has(Permission.BEN_VIEW)) {
                int n = service.expiring(30).size();
                out.add(new KpiTile("ben-expiring", "Policies expiring in 30 days", String.valueOf(n),
                        "Insurance policies to renew", "/benefits/expiring", n > 0 ? "gold" : "neutral", 40));
            }
            return out;
        }
    }
}
