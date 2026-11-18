package lk.gov.ciaboc.hrms.payroll;

import lk.gov.ciaboc.hrms.payroll.LoanService.LoanDetail;
import lk.gov.ciaboc.hrms.payroll.LoanService.LoanRequest;
import lk.gov.ciaboc.hrms.payroll.LoanService.LoanView;
import lk.gov.ciaboc.hrms.payroll.PayrollEntities.*;
import lk.gov.ciaboc.hrms.payroll.PayrollRunService.*;
import lk.gov.ciaboc.hrms.payroll.SalaryService.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Payroll API. Reading payroll data needs PAY_VIEW; operating payroll (placements, loans, runs, adjustments) needs
 * PAY_MANAGE; rates and scales need PAY_CONFIG. Employees use the /my endpoints, which only ever return their own data.
 */
@RestController
@RequestMapping("/api/payroll")
@RequiredArgsConstructor
public class PayrollController {
    private final SalaryService salary;
    private final LoanService loans;
    private final PayrollRunService runs;

    // ===================================================================== salary scales
    @GetMapping("/scales")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<SalaryScale> scales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                    @RequestParam(defaultValue = "false") boolean all) {
        return salary.scales(asOf, all);
    }

    @GetMapping("/scales/{code}/history")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<SalaryScale> scaleHistory(@PathVariable String code) {
        return salary.scaleHistory(code);
    }

    @PostMapping("/scales")
    @PreAuthorize("hasAuthority('PAY_CONFIG')")
    public SalaryScale createScale(@RequestBody ScaleRequest r) {
        return salary.createScale(r);
    }

    @PostMapping("/scales/{code}/revise")
    @PreAuthorize("hasAuthority('PAY_CONFIG')")
    public SalaryScale reviseScale(@PathVariable String code, @RequestBody ScaleRequest r) {
        return salary.reviseScale(code, r);
    }

    // ===================================================================== allowance types
    @GetMapping("/allowance-types")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<AllowanceTypeView> allowanceTypes(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return salary.allowanceTypes(asOf);
    }

    @PostMapping("/allowance-types")
    @PreAuthorize("hasAuthority('PAY_CONFIG')")
    public AllowanceType createAllowanceType(@RequestBody TypeRequest r) {
        return salary.createAllowanceType(r);
    }

    @PutMapping("/allowance-types/{id}")
    @PreAuthorize("hasAuthority('PAY_CONFIG')")
    public AllowanceType updateAllowanceType(@PathVariable Long id, @RequestBody TypeRequest r) {
        return salary.updateAllowanceType(id, r);
    }

    @GetMapping("/allowance-types/{id}/rates")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<AllowanceRate> allowanceRates(@PathVariable Long id) {
        return salary.allowanceRates(id);
    }

    @PostMapping("/allowance-types/{id}/rates")
    @PreAuthorize("hasAuthority('PAY_CONFIG')")
    public AllowanceRate addAllowanceRate(@PathVariable Long id, @RequestBody AllowanceRateRequest r) {
        return salary.addAllowanceRate(id, r);
    }

    // ===================================================================== deduction types
    @GetMapping("/deduction-types")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<DeductionTypeView> deductionTypes(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return salary.deductionTypes(asOf);
    }

    @PostMapping("/deduction-types")
    @PreAuthorize("hasAuthority('PAY_CONFIG')")
    public DeductionType createDeductionType(@RequestBody TypeRequest r) {
        return salary.createDeductionType(r);
    }

    @PutMapping("/deduction-types/{id}")
    @PreAuthorize("hasAuthority('PAY_CONFIG')")
    public DeductionType updateDeductionType(@PathVariable Long id, @RequestBody TypeRequest r) {
        return salary.updateDeductionType(id, r);
    }

    @GetMapping("/deduction-types/{id}/rates")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<DeductionRate> deductionRates(@PathVariable Long id) {
        return salary.deductionRates(id);
    }

    @PostMapping("/deduction-types/{id}/rates")
    @PreAuthorize("hasAuthority('PAY_CONFIG')")
    public DeductionRate addDeductionRate(@PathVariable Long id, @RequestBody DeductionRateRequest r) {
        return salary.addDeductionRate(id, r);
    }

    // ===================================================================== memberships
    @GetMapping("/memberships")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<MembershipView> memberships(@RequestParam(required = false) Long deductionTypeId, @RequestParam(required = false) Long employeeId) {
        return salary.memberships(deductionTypeId, employeeId);
    }

    @PostMapping("/employees/{employeeId}/memberships")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public DeductionMembership addMembership(@PathVariable Long employeeId, @RequestBody MembershipRequest r) {
        return salary.addMembership(employeeId, r);
    }

    @PostMapping("/memberships/{id}/end")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public DeductionMembership endMembership(@PathVariable Long id, @RequestBody EndRequest r) {
        return salary.endMembership(id, r);
    }

    // ===================================================================== employee salary
    @GetMapping("/employees/{employeeId}/salary")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public SalaryOverview salaryOverview(@PathVariable Long employeeId) {
        return salary.overview(employeeId);
    }

    @PostMapping("/employees/{employeeId}/salary")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public EmployeeSalary place(@PathVariable Long employeeId, @RequestBody PlacementRequest r) {
        return salary.placeEmployee(employeeId, r);
    }

    @PostMapping("/employees/{employeeId}/allowances")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public EmployeeAllowance assignAllowance(@PathVariable Long employeeId, @RequestBody EmployeeAllowanceRequest r) {
        return salary.assignAllowance(employeeId, r);
    }

    @PostMapping("/employee-allowances/{id}/end")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public EmployeeAllowance endAllowance(@PathVariable Long id, @RequestBody EndRequest r) {
        return salary.endAllowance(id, r);
    }

    @GetMapping("/employees/{employeeId}/deduction-history")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<DeductionHistoryRow> deductionHistory(@PathVariable Long employeeId) {
        return runs.deductionHistory(employeeId);
    }

    // ===================================================================== increments
    @GetMapping("/increments/preview")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public List<IncrementRow> incrementPreview(@RequestParam int month,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate) {
        return salary.incrementPreview(month, effectiveDate);
    }

    @PostMapping("/increments/post")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public IncrementPostResult postIncrements(@RequestBody IncrementPostRequest r) {
        return salary.postIncrements(r);
    }

    // ===================================================================== loans
    @GetMapping("/loans")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<LoanView> loans(@RequestParam(required = false) String status, @RequestParam(required = false) Long employeeId) {
        return loans.list(status, employeeId);
    }

    @GetMapping("/loans/{id}")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public LoanDetail loan(@PathVariable Long id) {
        return loans.detail(id);
    }

    @PostMapping("/loans")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public LoanView createLoan(@RequestBody LoanRequest r) {
        return loans.create(r);
    }

    @PostMapping("/loans/{id}/adjustments")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public LoanAdjustment requestLoanAdjustment(@PathVariable Long id, @RequestBody LoanService.AdjustmentRequest r) {
        return loans.requestAdjustment(id, r);
    }

    @GetMapping("/loan-adjustments")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<LoanService.AdjustmentView> loanAdjustments(@RequestParam(required = false) String status) {
        return loans.adjustmentList(status);
    }

    // ===================================================================== payroll adjustments
    @GetMapping("/adjustments")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<PayrollRunService.AdjustmentView> adjustments(@RequestParam(required = false) String status) {
        return runs.adjustmentList(status);
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public PayrollAdjustment createAdjustment(@RequestBody PayrollRunService.AdjustmentRequest r) {
        return runs.createAdjustment(r);
    }

    @PostMapping("/adjustments/{id}/cancel")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public PayrollAdjustment cancelAdjustment(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return runs.cancelAdjustment(id, body == null ? null : body.get("reason"));
    }

    // ===================================================================== runs
    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<PayrollRun> runs() {
        return runs.list();
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public PayrollRun createRun(@RequestBody RunRequest r) {
        return runs.create(r);
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public RunDetail run(@PathVariable Long id) {
        return runs.detail(id);
    }

    @PostMapping("/runs/{id}/calculate")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public PayrollRun calculate(@PathVariable Long id) {
        return runs.calculate(id);
    }

    @PostMapping("/runs/{id}/submit")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public PayrollRun submit(@PathVariable Long id, @RequestBody(required = false) SubmitRequest r) {
        return runs.submit(id, r);
    }

    @GetMapping("/runs/{id}/lines")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public List<LineSummary> lines(@PathVariable Long id) {
        return runs.lines(id);
    }

    @GetMapping("/runs/{id}/lines/{lineId}")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public LineDetail line(@PathVariable Long id, @PathVariable Long lineId) {
        return runs.line(id, lineId);
    }

    @GetMapping("/runs/{id}/bank-advice")
    @PreAuthorize("hasAuthority('PAY_MANAGE')")
    public ResponseEntity<byte[]> bankAdvice(@PathVariable Long id) {
        return csv(runs.bankAdvice(id));
    }

    @GetMapping("/runs/{id}/posting-summary")
    @PreAuthorize("hasAuthority('PAY_VIEW')")
    public ResponseEntity<byte[]> postingSummary(@PathVariable Long id) {
        return csv(runs.postingSummary(id));
    }

    // ===================================================================== self-service
    @GetMapping("/my/payslips")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public List<PayslipItem> myPayslips() {
        return runs.myPayslips();
    }

    @GetMapping("/my/payslips/{lineId}")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public LineDetail myPayslip(@PathVariable Long lineId) {
        return runs.myPayslip(lineId);
    }

    private static ResponseEntity<byte[]> csv(Export e) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(e.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(e.content());
    }
}
