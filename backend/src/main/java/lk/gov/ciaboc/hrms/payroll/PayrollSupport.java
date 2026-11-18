package lk.gov.ciaboc.hrms.payroll;

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

/** Reports, dashboard tiles and master-data usage for the payroll module. */
public final class PayrollSupport {
    private PayrollSupport() {
    }

    /** A LOAN_TYPE in use by a loan cannot be deactivated (FR-MDM-003). */
    @Component
    @RequiredArgsConstructor
    public static class LoanTypeUsage implements MasterDataUsageChecker {
        private final EmployeeLoanRepository loans;

        @Override
        public long usageCount(String category, String code) {
            return MasterDataCategory.LOAN_TYPE.name().equals(category) ? loans.countByLoanType(code) : 0;
        }
    }

    /** Payroll reports: sensitive (RPT_SENSITIVE) and require PAY_VIEW (RP-02). Finalized periods only unless stated. */
    @Component
    public static class PayrollReports implements ReportProvider {
        private static final String FINAL_LINES = " FROM pay_payroll_line l JOIN pay_payroll_run r ON r.id = l.run_id " +
                "JOIN employee e ON e.id = l.employee_id WHERE r.status = 'FINALIZED' " +
                "AND (CAST(:period AS varchar) IS NULL OR r.period = :period) /*SCOPE:e*/ /*ORG:l.org_unit_code*/ ";

        private static String item(String code, String type) {
            return "COALESCE((SELECT SUM(i.amount) FROM pay_payroll_line_item i WHERE i.line_id = l.id AND i.code = '" + code
                    + "' AND i.component_type = '" + type + "'), 0)";
        }

        @Override
        public List<ReportDefinition> reports() {
            return List.of(
                    ReportDefinition.builder("PAY_SALARY_SUMMARY", "Payroll", "Salary summary")
                            .description("Basic, gross, deductions and net pay per employee for a period (any run status)")
                            .permission("PAY_VIEW").sensitive().filters(Filter.PERIOD, Filter.ORG_UNIT, Filter.DESIGNATION)
                            .col("period", "Period").col("run_status", "Run status").col("employee_no", "Employee no").col("name", "Name")
                            .col("designation", "Designation").col("org_unit_code", "Unit").col("basic_salary", "Basic", "money")
                            .col("gross_pay", "Gross", "money").col("total_deductions", "Deductions", "money")
                            .col("net_pay", "Net", "money").col("employer_contributions", "Employer contributions", "money")
                            .sql("SELECT r.period, r.status AS run_status, l.employee_no, l.employee_name AS name, l.designation, l.org_unit_code, " +
                                    "l.basic_salary, l.gross_pay, l.total_deductions, l.net_pay, l.employer_contributions " +
                                    "FROM pay_payroll_line l JOIN pay_payroll_run r ON r.id = l.run_id JOIN employee e ON e.id = l.employee_id " +
                                    "WHERE (CAST(:period AS varchar) IS NULL OR r.period = :period) /*SCOPE:e*/ /*ORG:l.org_unit_code*/ " +
                                    "AND (CAST(:designationId AS bigint) IS NULL OR e.designation_id = :designationId) " +
                                    "ORDER BY r.period DESC, l.employee_no")
                            .build(),
                    ReportDefinition.builder("PAY_DEDUCTION_SUMMARY", "Payroll", "Deduction summary")
                            .description("Totals by deduction / contribution component for finalized periods")
                            .permission("PAY_VIEW").sensitive().filters(Filter.PERIOD)
                            .col("period", "Period").col("component_type", "Type").col("code", "Code").col("employees", "Employees", "number")
                            .col("amount", "Amount", "money")
                            .sql("SELECT r.period, i.component_type, i.code, COUNT(DISTINCT l.employee_id) AS employees, SUM(i.amount) AS amount " +
                                    "FROM pay_payroll_line_item i JOIN pay_payroll_line l ON l.id = i.line_id JOIN pay_payroll_run r ON r.id = l.run_id " +
                                    "WHERE r.status = 'FINALIZED' AND i.component_type IN ('DEDUCTION','EMPLOYER') " +
                                    "AND (CAST(:period AS varchar) IS NULL OR r.period = :period) " +
                                    "GROUP BY r.period, i.component_type, i.code ORDER BY r.period DESC, i.component_type, i.code")
                            .build(),
                    ReportDefinition.builder("PAY_PENSION_DEDUCTIONS", "Payroll", "Pension-related deductions")
                            .description("Deductions of pension-related schemes (e.g. W&OP, pension) per employee")
                            .permission("PAY_VIEW").sensitive().filters(Filter.PERIOD, Filter.ORG_UNIT)
                            .col("period", "Period").col("employee_no", "Employee no").col("name", "Name").col("scheme", "Scheme")
                            .col("employee_amount", "Employee", "money").col("employer_amount", "Employer", "money")
                            .sql("SELECT r.period, l.employee_no, l.employee_name AS name, dt.name AS scheme, " +
                                    "SUM(CASE WHEN i.component_type = 'DEDUCTION' THEN i.amount ELSE 0 END) AS employee_amount, " +
                                    "SUM(CASE WHEN i.component_type = 'EMPLOYER' THEN i.amount ELSE 0 END) AS employer_amount " +
                                    "FROM pay_payroll_line_item i JOIN pay_deduction_type dt ON dt.code = i.code AND dt.pension_related " +
                                    "JOIN pay_payroll_line l ON l.id = i.line_id JOIN pay_payroll_run r ON r.id = l.run_id " +
                                    "JOIN employee e ON e.id = l.employee_id WHERE r.status = 'FINALIZED' " +
                                    "AND (CAST(:period AS varchar) IS NULL OR r.period = :period) /*SCOPE:e*/ /*ORG:l.org_unit_code*/ " +
                                    "GROUP BY r.period, l.employee_no, l.employee_name, dt.name ORDER BY r.period DESC, l.employee_no")
                            .build(),
                    ReportDefinition.builder("PAY_EPF_ETF", "Payroll", "EPF / ETF contributions")
                            .description("EPF employee and employer contributions and ETF per employee")
                            .permission("PAY_VIEW").sensitive().filters(Filter.PERIOD, Filter.ORG_UNIT)
                            .col("period", "Period").col("employee_no", "Employee no").col("name", "Name").col("nic", "NIC")
                            .col("member_no", "EPF member no").col("basic_salary", "Basic", "money")
                            .col("epf_employee", "EPF employee", "money").col("epf_employer", "EPF employer", "money")
                            .col("etf_employer", "ETF employer", "money")
                            .sql("SELECT * FROM (SELECT r.period, l.employee_no, l.employee_name AS name, e.nic, " +
                                    "(SELECT m.member_no FROM pay_deduction_membership m JOIN pay_deduction_type t ON t.id = m.deduction_type_id " +
                                    " WHERE t.code = 'EPF' AND m.employee_id = l.employee_id ORDER BY m.effective_from DESC LIMIT 1) AS member_no, " +
                                    "l.basic_salary, " + item("EPF", "DEDUCTION") + " AS epf_employee, " + item("EPF", "EMPLOYER") + " AS epf_employer, " +
                                    item("ETF", "EMPLOYER") + " AS etf_employer" + FINAL_LINES + ") x " +
                                    "WHERE x.epf_employee > 0 OR x.epf_employer > 0 OR x.etf_employer > 0 ORDER BY x.period DESC, x.employee_no")
                            .build(),
                    ReportDefinition.builder("PAY_LOAN_DEDUCTIONS", "Payroll", "Loan deductions")
                            .description("Loan recoveries posted by finalized payroll runs")
                            .permission("PAY_VIEW").sensitive().filters(Filter.PERIOD, Filter.ORG_UNIT)
                            .col("period", "Period").col("employee_no", "Employee no").col("name", "Name").col("loan_type", "Loan type")
                            .col("lender", "Lender").col("amount", "Deducted", "money").col("balance_after", "Balance after", "money")
                            .sql("SELECT t.period, e.employee_no, e.name_with_initials AS name, ln.loan_type, ln.lender, t.amount, t.balance_after " +
                                    "FROM pay_loan_transaction t JOIN pay_employee_loan ln ON ln.id = t.loan_id JOIN employee e ON e.id = ln.employee_id " +
                                    "WHERE t.txn_type = 'DEDUCTION' AND (CAST(:period AS varchar) IS NULL OR t.period = :period) " +
                                    "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ ORDER BY t.period DESC, e.employee_no")
                            .build(),
                    ReportDefinition.builder("PAY_LOAN_REGISTER", "Payroll", "Loan register")
                            .description("Seconded-employee loans. Status filter: ACTIVE, SETTLED or TRANSFERRED_OUT")
                            .permission("PAY_VIEW").sensitive().filters(Filter.STATUS, Filter.ORG_UNIT)
                            .col("employee_no", "Employee no").col("name", "Name").col("loan_type", "Loan type").col("lender", "Lender")
                            .col("previous_workplace", "Previous workplace").col("original_amount", "Original", "money")
                            .col("outstanding_balance", "Outstanding", "money").col("monthly_instalment", "Instalment", "money")
                            .col("start_date", "Start", "date").col("status", "Status")
                            .sql("SELECT e.employee_no, e.name_with_initials AS name, ln.loan_type, ln.lender, ln.previous_workplace, " +
                                    "ln.original_amount, ln.outstanding_balance, ln.monthly_instalment, ln.start_date, ln.status " +
                                    "FROM pay_employee_loan ln JOIN employee e ON e.id = ln.employee_id " +
                                    "WHERE (CAST(:status AS varchar) IS NULL OR ln.status = :status) /*SCOPE:e*/ /*ORG:e.org_unit_code*/ " +
                                    "ORDER BY e.employee_no, ln.start_date")
                            .build(),
                    ReportDefinition.builder("PAY_SALARY_PLACEMENT", "Payroll", "Salary placements")
                            .description("Salary scale, step and basic salary in force on a date")
                            .permission("PAY_VIEW").sensitive().filters(Filter.AS_OF, Filter.ORG_UNIT, Filter.DESIGNATION)
                            .col("employee_no", "Employee no").col("name", "Name").col("scale_code", "Scale").col("step", "Step", "number")
                            .col("basic_salary", "Basic", "money").col("effective_from", "Since", "date").col("reason", "Reason")
                            .sql("SELECT e.employee_no, e.name_with_initials AS name, s.scale_code, s.step, s.basic_salary, s.effective_from, s.reason " +
                                    "FROM pay_employee_salary s JOIN employee e ON e.id = s.employee_id " +
                                    "WHERE s.effective_from <= CAST(:asOf AS date) AND (s.effective_to IS NULL OR s.effective_to > CAST(:asOf AS date)) " +
                                    "/*SCOPE:e*/ /*ORG:e.org_unit_code*/ AND (CAST(:designationId AS bigint) IS NULL OR e.designation_id = :designationId) " +
                                    "ORDER BY e.employee_no")
                            .build());
        }
    }

    /** Dashboard: current payroll status (PAY_VIEW) and the employee's last net pay (self-service). */
    @Component
    @RequiredArgsConstructor
    public static class PayrollDashboard implements DashboardContributor {
        private final PayrollRunService runs;

        @Override
        @Transactional(readOnly = true)
        public List<KpiTile> tiles(UserPrincipal user) {
            List<KpiTile> out = new ArrayList<>();
            if (user.has(Permission.PAY_VIEW)) {
                runs.latestRun().ifPresentOrElse(r -> out.add(new KpiTile("pay-run", "Payroll " + r.getPeriod(),
                                r.getStatus().name().charAt(0) + r.getStatus().name().substring(1).toLowerCase(),
                                r.getEmployeeCount() + " employees", "/payroll/runs/" + r.getId(),
                                r.getStatus().name().equals("FINALIZED") ? "green" : "gold", 30)),
                        () -> out.add(new KpiTile("pay-run", "Payroll", "No runs", "Start the first payroll run", "/payroll/runs", "neutral", 30)));
            }
            if (user.has(Permission.SELF_SERVICE) && user.getEmployeeId() != null) {
                runs.lastPayslip(user.getEmployeeId()).ifPresent(p -> out.add(new KpiTile("my-net-pay", "My last net pay",
                        "LKR " + String.format("%,.2f", p.netPay()), p.period(), "/self/payslips", "navy", 31)));
            }
            return out;
        }
    }
}
