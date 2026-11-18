package lk.gov.ciaboc.hrms.payroll;

import com.fasterxml.jackson.databind.JsonNode;
import lk.gov.ciaboc.hrms.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class PayrollIntegrationTest extends IntegrationTestBase {
    @Autowired
    JdbcTemplate jdbc;

    private JsonNode lineOf(JsonNode lines, String employeeNo) {
        for (JsonNode l : lines) if (l.get("employeeNo").asText().equals(employeeNo)) return l;
        throw new AssertionError("No line for " + employeeNo);
    }

    @Test
    void makerCheckerPayrollRunIsFinalizedLockedAndSelfServiceIsScoped() throws Exception {
        String finance = login("finance", "Demo@12345");
        String finance2 = login("finance2", "Demo@12345");
        String dg = login("dg", "Demo@12345");
        String employee = login("employee", "Demo@12345");

        // Preparer creates and calculates the run.
        MvcResult created = postJson(finance, "/api/payroll/runs", Map.of("period", "2026-10"));
        expectStatus(created, 200);
        long runId = body(created).get("id").asLong();
        expectStatus(postJson(finance, "/api/payroll/runs", Map.of("period", "2026-09")), 422); // one open run at a time
        expectStatus(postJson(finance, "/api/payroll/runs/" + runId + "/calculate", Map.of()), 200);

        JsonNode lines = getJson(finance, "/api/payroll/runs/" + runId + "/lines");
        assertThat(lines.size()).isGreaterThanOrEqualTo(14);
        JsonNode emp8 = lineOf(lines, "CIA-0008");
        JsonNode detail8 = getJson(finance, "/api/payroll/runs/" + runId + "/lines/" + emp8.get("id").asLong());
        Map<String, BigDecimal> items8 = new HashMap<>();
        for (JsonNode i : detail8.get("items")) items8.merge(i.get("componentType").asText() + ":" + i.get("code").asText(), i.get("amount").decimalValue(), BigDecimal::add);
        // MN-4 step 5 = 48000 + 5 x 1200 = 54000; EPF 8% / 12%, ETF 3%, loan 5000, Agrahara 225.
        assertThat(items8.get("EARNING:BASIC")).isEqualByComparingTo("54000.00");
        assertThat(items8.get("DEDUCTION:EPF")).isEqualByComparingTo("4320.00");
        assertThat(items8.get("EMPLOYER:EPF")).isEqualByComparingTo("6480.00");
        assertThat(items8.get("EMPLOYER:ETF")).isEqualByComparingTo("1620.00");
        assertThat(items8.get("DEDUCTION:LOAN-DISTRESS")).isEqualByComparingTo("5000.00");
        assertThat(items8.get("DEDUCTION:INS-AGRAHARA")).isEqualByComparingTo("225.00");
        // Loan instalment capped at the outstanding balance (2,000 < 5,000).
        JsonNode emp13 = getJson(finance, "/api/payroll/runs/" + runId + "/lines/" + lineOf(lines, "CIA-0013").get("id").asLong());
        BigDecimal loan13 = BigDecimal.ZERO;
        for (JsonNode i : emp13.get("items")) if (i.get("code").asText().equals("LOAN-FESTIVAL")) loan13 = i.get("amount").decimalValue();
        assertThat(loan13).isEqualByComparingTo("2000.00");

        // Only the preparer may submit; then nobody may recalculate while it is in approval.
        expectStatus(postJson(finance2, "/api/payroll/runs/" + runId + "/submit", Map.of()), 422);
        MvcResult submitted = postJson(finance, "/api/payroll/runs/" + runId + "/submit", Map.of());
        expectStatus(submitted, 200);
        long instanceId = body(submitted).get("workflowInstanceId").asLong();
        expectStatus(postJson(finance, "/api/payroll/runs/" + runId + "/calculate", Map.of()), 422);

        // Maker cannot check; a second finance officer checks; management authorises.
        expectStatus(postJson(finance, "/api/workflow/instances/" + instanceId + "/act", Map.of("decision", "APPROVE")), 422);
        expectStatus(postJson(dg, "/api/workflow/instances/" + instanceId + "/act", Map.of("decision", "APPROVE")), 403);
        expectStatus(postJson(finance2, "/api/workflow/instances/" + instanceId + "/act", Map.of("decision", "APPROVE", "comment", "Checked")), 200);
        expectStatus(postJson(dg, "/api/workflow/instances/" + instanceId + "/act", Map.of("decision", "APPROVE", "comment", "Authorised")), 200);

        JsonNode run = getJson(finance, "/api/payroll/runs/" + runId).get("run");
        assertThat(run.get("status").asText()).isEqualTo("FINALIZED");
        assertThat(run.get("locked").asBoolean()).isTrue();
        // Finalized runs are immutable.
        expectStatus(postJson(finance, "/api/payroll/runs/" + runId + "/calculate", Map.of()), 422);
        expectStatus(postJson(finance, "/api/payroll/runs/" + runId + "/submit", Map.of()), 422);

        // Loan balances reduced only at finalization.
        BigDecimal bal8 = jdbc.queryForObject("SELECT outstanding_balance FROM pay_employee_loan WHERE reference_no = 'DEMO-L-002'", BigDecimal.class);
        assertThat(bal8).isEqualByComparingTo("20500.00");
        String status13 = jdbc.queryForObject("SELECT status FROM pay_employee_loan WHERE reference_no = 'DEMO-L-003'", String.class);
        assertThat(status13).isEqualTo("SETTLED");
        Integer deductions = jdbc.queryForObject("SELECT COUNT(*) FROM pay_loan_transaction WHERE txn_type = 'DEDUCTION' AND period = '2026-10'", Integer.class);
        assertThat(deductions).isEqualTo(3);

        // Employee sees own payslip only; management screens are forbidden.
        JsonNode mine = getJson(employee, "/api/payroll/my/payslips");
        assertThat(mine.size()).isEqualTo(1);
        long myLine = mine.get(0).get("lineId").asLong();
        assertThat(myLine).isEqualTo(emp8.get("id").asLong());
        JsonNode slip = getJson(employee, "/api/payroll/my/payslips/" + myLine);
        assertThat(slip.get("line").get("employeeNo").asText()).isEqualTo("CIA-0008");
        assertThat(slip.get("bankAccountNo").asText()).startsWith("*");
        long otherLine = lineOf(lines, "CIA-0001").get("id").asLong();
        expectStatus(mvc.perform(get("/api/payroll/my/payslips/" + otherLine).header("Authorization", employee)).andReturn(), 404);
        expectStatus(mvc.perform(get("/api/payroll/runs/" + runId + "/lines").header("Authorization", employee)).andReturn(), 403);
        expectStatus(mvc.perform(get("/api/payroll/runs").header("Authorization", employee)).andReturn(), 403);

        // Exports are available after finalization and are audited.
        MvcResult advice = mvc.perform(get("/api/payroll/runs/" + runId + "/bank-advice").header("Authorization", finance)).andReturn();
        expectStatus(advice, 200);
        assertThat(advice.getResponse().getContentAsString()).contains("CIA-0008").contains("Net Pay");
        expectStatus(mvc.perform(get("/api/payroll/runs/" + runId + "/posting-summary").header("Authorization", finance)).andReturn(), 200);
        Integer exports = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'EXPORT' AND entity_type = 'PayrollRun'", Integer.class);
        assertThat(exports).isGreaterThanOrEqualTo(2);
        Integer views = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'VIEW' AND entity_type = 'PayrollLine'", Integer.class);
        assertThat(views).isGreaterThanOrEqualTo(3);

        // The database itself refuses changes to a locked period.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("UPDATE pay_payroll_line SET net_pay = 0 WHERE run_id = ?", runId)).isInstanceOf(Exception.class);

        // Corrections go through adjustments that the next run picks up.
        Long emp8Id = jdbc.queryForObject("SELECT id FROM employee WHERE employee_no = 'CIA-0008'", Long.class);
        MvcResult adj = postJson(finance, "/api/payroll/adjustments", Map.of("employeeId", emp8Id, "adjustmentType", "EARNING",
                "code", "ARREARS", "description", "Increment arrears", "amount", 1200, "targetPeriod", "2026-11",
                "relatedRunId", runId, "reason", "Late increment"));
        expectStatus(adj, 200);
        MvcResult next = postJson(finance, "/api/payroll/runs", Map.of("period", "2026-11"));
        expectStatus(next, 200);
        long nextId = body(next).get("id").asLong();
        expectStatus(postJson(finance, "/api/payroll/runs/" + nextId + "/calculate", Map.of()), 200);
        JsonNode nextLine = getJson(finance, "/api/payroll/runs/" + nextId + "/lines/"
                + lineOf(getJson(finance, "/api/payroll/runs/" + nextId + "/lines"), "CIA-0008").get("id").asLong());
        List<String> codes = nextLine.get("items").findValuesAsText("code");
        assertThat(codes).contains("ARREARS", "LOAN-DISTRESS").doesNotContain("LOAN-FESTIVAL");
    }

    @Test
    void payrollAndBenefitReportsRunAndAreRestricted() throws Exception {
        String finance = login("finance", "Demo@12345");
        String hr = login("hr", "Demo@12345");
        for (String code : List.of("PAY_SALARY_SUMMARY", "PAY_DEDUCTION_SUMMARY", "PAY_PENSION_DEDUCTIONS", "PAY_EPF_ETF",
                "PAY_LOAN_DEDUCTIONS", "PAY_LOAN_REGISTER", "PAY_SALARY_PLACEMENT", "BEN_COVERAGE", "BEN_PREMIUMS"))
            expectStatus(postJson(finance, "/api/reports/" + code + "/run", Map.of()), 200);
        for (String code : List.of("BEN_COVERAGE", "BEN_ELIGIBILITY", "BEN_PREMIUMS", "BEN_EXPIRY", "BEN_CLAIMS"))
            expectStatus(postJson(hr, "/api/reports/" + code + "/run", Map.of()), 200);
        // HR has no PAY_VIEW / RPT_SENSITIVE: payroll reports are refused.
        assertThat(postJson(hr, "/api/reports/PAY_SALARY_SUMMARY/run", Map.of()).getResponse().getStatus()).isIn(403, 404);
        JsonNode placements = body(postJson(finance, "/api/reports/PAY_SALARY_PLACEMENT/run", Map.of()));
        assertThat(placements.toString()).contains("CIA-0008");
    }

    @Test
    void loanAdjustmentIsAppliedOnlyAfterApproval() throws Exception {
        String finance = login("finance", "Demo@12345");
        String finance2 = login("finance2", "Demo@12345");
        Long loanId = jdbc.queryForObject("SELECT id FROM pay_employee_loan WHERE reference_no = 'DEMO-L-001'", Long.class);
        MvcResult r = postJson(finance, "/api/payroll/loans/" + loanId + "/adjustments", Map.of("adjustmentType", "INSTALMENT_CHANGE",
                "newInstalment", 12500, "effectiveDate", "2026-11-01", "reason", "Revised by lender"));
        expectStatus(r, 200);
        assertThat(getJson(finance, "/api/payroll/loans/" + loanId).get("loan").get("monthlyInstalment").decimalValue()).isEqualByComparingTo("10000.00");
        long wi = body(r).get("workflowInstanceId").asLong();
        expectStatus(postJson(finance2, "/api/workflow/instances/" + wi + "/act", Map.of("decision", "APPROVE")), 200);
        JsonNode after = getJson(finance, "/api/payroll/loans/" + loanId);
        assertThat(after.get("loan").get("monthlyInstalment").decimalValue()).isEqualByComparingTo("12500.00");
        assertThat(after.get("transactions").findValuesAsText("txnType")).contains("OPENING", "INSTALMENT_CHANGE");
    }

    @Test
    void benefitsCoverageExpiryAndSelfService() throws Exception {
        String hr = login("hr", "Demo@12345");
        String employee = login("employee", "Demo@12345");
        JsonNode expiring = getJson(hr, "/api/benefits/coverages/expiring?days=30");
        assertThat(expiring.findValuesAsText("employeeNo")).contains("CIA-0005", "CIA-0012");
        JsonNode check = body(postJson(hr, "/api/benefits/coverages/expiry-check", Map.of()));
        assertThat(check.get("notified").asInt()).isGreaterThanOrEqualTo(0);
        Integer notes = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE template_code = 'BEN_POLICY_EXPIRY_HR'", Integer.class);
        assertThat(notes).isGreaterThan(0);

        JsonNode mine = getJson(employee, "/api/benefits/my");
        assertThat(mine.get("coverages").size()).isEqualTo(1);
        assertThat(mine.get("eligibility").get(0).get("covered").asBoolean()).isTrue();
        expectStatus(mvc.perform(get("/api/benefits/coverages").header("Authorization", employee)).andReturn(), 403);

        long myCoverage = mine.get("coverages").get(0).get("id").asLong();
        MvcResult claim = postJson(employee, "/api/benefits/my/claims", Map.of("coverageId", myCoverage, "claimType", "Hospitalisation",
                "amountClaimed", 15000, "incidentDate", "2026-10-02"));
        expectStatus(claim, 200);
        // Another employee's coverage cannot be claimed against.
        Long other = jdbc.queryForObject("SELECT c.id FROM ben_insurance_coverage c JOIN employee e ON e.id = c.employee_id WHERE e.employee_no = 'CIA-0001'", Long.class);
        expectStatus(postJson(employee, "/api/benefits/my/claims", Map.of("coverageId", other, "claimType", "X", "amountClaimed", 100)), 404);
        long claimId = body(claim).get("id").asLong();
        expectStatus(postJson(hr, "/api/benefits/claims/" + claimId + "/status", Map.of("status", "APPROVED", "amountApproved", 20000)), 422);
        expectStatus(postJson(hr, "/api/benefits/claims/" + claimId + "/status", Map.of("status", "APPROVED", "amountApproved", 12000)), 200);
    }
}
