package lk.gov.ciaboc.hrms.payroll;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup.EmployeeBrief;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.payroll.PayrollEntities.EmployeeLoan;
import lk.gov.ciaboc.hrms.payroll.PayrollEntities.LoanAdjustment;
import lk.gov.ciaboc.hrms.payroll.PayrollEntities.LoanTransaction;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static lk.gov.ciaboc.hrms.payroll.PayrollSupportUtil.*;

/**
 * Loans of seconded employees recovered through payroll (FR-PAY-020..023). Balances change only through payroll
 * finalization (deductions) and approved adjustments (LOAN_ADJUSTMENT workflow); every change is a loan transaction.
 */
@Service
@RequiredArgsConstructor
public class LoanService implements WorkflowHandler {
    public static final String PROCESS = "LOAN_ADJUSTMENT";
    static final String ENTITY = "LoanAdjustment";

    private final EmployeeLoanRepository loans;
    private final LoanTransactionRepository transactions;
    private final LoanAdjustmentRepository adjustments;
    private final PayrollLineItemRepository lineItems;
    private final EmployeeLookup lookup;
    private final MasterDataService masterData;
    private final WorkflowService workflow;
    private final AuditService audit;

    public record LoanRequest(Long employeeId, String loanType, String lender, String previousWorkplace, String referenceNo,
                              BigDecimal originalAmount, BigDecimal outstandingBalance, BigDecimal monthlyInstalment,
                              LocalDate startDate, String remarks) {
    }

    public record AdjustmentRequest(String adjustmentType, BigDecimal newInstalment, BigDecimal settlementAmount,
                                    LocalDate effectiveDate, String reason) {
    }

    public record LoanView(Long id, Long employeeId, String employeeNo, String employeeName, String loanType, String lender,
                           String previousWorkplace, String referenceNo, BigDecimal originalAmount,
                           BigDecimal outstandingBalance, BigDecimal monthlyInstalment, LocalDate startDate,
                           String status, LocalDate closedOn, String remarks) {
    }

    public record LoanDetail(LoanView loan, List<LoanTransaction> transactions, List<LoanAdjustment> adjustments) {
    }

    public record AdjustmentView(LoanAdjustment adjustment, LoanView loan) {
    }

    // ===================================================================== queries

    @Transactional(readOnly = true)
    public List<LoanView> list(String status, Long employeeId) {
        List<EmployeeLoan> rows;
        if (employeeId != null) {
            lookup.requireAccess(employeeId);
            rows = loans.findByEmployeeIdOrderByStartDateDesc(employeeId);
        } else {
            rows = loans.findAllByOrderByStatusAscStartDateDesc();
        }
        Set<Long> visible = lookup.visibleEmployeeIds();
        return views(rows.stream().filter(l -> visible == null || visible.contains(l.getEmployeeId()))
                .filter(l -> status == null || status.isBlank() || l.getStatus().name().equals(status)).toList());
    }

    @Transactional(readOnly = true)
    public LoanDetail detail(Long id) {
        EmployeeLoan l = loan(id);
        lookup.requireAccess(l.getEmployeeId());
        audit.log(AuditAction.VIEW, "EmployeeLoan", id, "Viewed loan and deduction history");
        return new LoanDetail(views(List.of(l)).get(0), transactions.findByLoanIdOrderByTxnDateAscIdAsc(id), adjustments.findByLoanIdOrderByIdDesc(id));
    }

    @Transactional(readOnly = true)
    public List<AdjustmentView> adjustmentList(String status) {
        Set<Long> visible = lookup.visibleEmployeeIds();
        List<LoanAdjustment> rows = adjustments.findAllByOrderByIdDesc().stream()
                .filter(a -> status == null || status.isBlank() || a.getStatus().name().equals(status)).toList();
        Map<Long, EmployeeLoan> byId = new HashMap<>();
        loans.findAllById(rows.stream().map(LoanAdjustment::getLoanId).toList()).forEach(l -> byId.put(l.getId(), l));
        Map<Long, LoanView> lv = new HashMap<>();
        views(new ArrayList<>(byId.values())).forEach(v -> lv.put(v.id(), v));
        return rows.stream().filter(a -> {
            EmployeeLoan l = byId.get(a.getLoanId());
            return l != null && (visible == null || visible.contains(l.getEmployeeId()));
        }).map(a -> new AdjustmentView(a, lv.get(a.getLoanId()))).toList();
    }

    // ===================================================================== create

    @Transactional
    public LoanView create(LoanRequest r) {
        if (r.employeeId() == null) throw ApiException.badRequest("Employee is required");
        Employee e = lookup.requireAccess(r.employeeId());
        if (!EmployeeLookup.IN_SERVICE.contains(e.getStatus())) throw ApiException.rule("NOT_IN_SERVICE", "The employee is not in service");
        String type = required(r.loanType(), "Loan type");
        masterData.requireActive(MasterDataCategory.LOAN_TYPE, type, "Loan type");
        BigDecimal original = money(r.originalAmount(), "Original amount");
        if (original.signum() <= 0) throw ApiException.badRequest("Original amount must be positive");
        BigDecimal outstanding = r.outstandingBalance() == null ? original : money(r.outstandingBalance(), "Outstanding balance");
        if (outstanding.compareTo(original) > 0) throw ApiException.badRequest("Outstanding balance cannot exceed the original amount");
        if (outstanding.signum() <= 0) throw ApiException.badRequest("Outstanding balance must be positive for a loan to be recovered");
        BigDecimal instalment = money(r.monthlyInstalment(), "Monthly instalment");
        if (instalment.signum() <= 0) throw ApiException.badRequest("Monthly instalment must be positive");
        EmployeeLoan l = new EmployeeLoan();
        l.setEmployeeId(e.getId());
        l.setLoanType(type);
        l.setLender(required(r.lender(), "Lender"));
        l.setPreviousWorkplace(blank(r.previousWorkplace()));
        l.setReferenceNo(blank(r.referenceNo()));
        l.setOriginalAmount(original);
        l.setOutstandingBalance(outstanding);
        l.setMonthlyInstalment(instalment);
        l.setStartDate(required(r.startDate(), "Start date"));
        l.setStatus(EmployeeLoan.Status.ACTIVE);
        l.setRemarks(blank(r.remarks()));
        loans.save(l);
        txn(l, LoanTransaction.Type.OPENING, l.getStartDate(), outstanding, null, null, "Opening balance recorded");
        audit.log(AuditAction.CREATE, "EmployeeLoan", l.getId(), "Loan (" + type + ") recorded for " + e.getEmployeeNo(), null,
                Map.of("original", original, "outstanding", outstanding, "instalment", instalment));
        return views(List.of(l)).get(0);
    }

    // ===================================================================== adjustments (FR-PAY-023)

    @Transactional
    public LoanAdjustment requestAdjustment(Long loanId, AdjustmentRequest r) {
        EmployeeLoan l = loan(loanId);
        lookup.requireAccess(l.getEmployeeId());
        if (l.getStatus() != EmployeeLoan.Status.ACTIVE) throw ApiException.rule("LOAN_CLOSED", "Only active loans can be adjusted");
        if (adjustments.existsByLoanIdAndStatusIn(loanId, List.of(LoanAdjustment.Status.PENDING, LoanAdjustment.Status.RETURNED)))
            throw ApiException.conflict("This loan already has an adjustment awaiting approval");
        LoanAdjustment a = new LoanAdjustment();
        a.setLoanId(loanId);
        try {
            a.setAdjustmentType(LoanAdjustment.Type.valueOf(required(r.adjustmentType(), "Adjustment type")));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Adjustment type must be EARLY_SETTLEMENT, INSTALMENT_CHANGE or TRANSFER_OUT");
        }
        a.setEffectiveDate(required(r.effectiveDate(), "Effective date"));
        a.setReason(required(r.reason(), "Reason"));
        switch (a.getAdjustmentType()) {
            case INSTALMENT_CHANGE -> {
                BigDecimal inst = money(r.newInstalment(), "New instalment");
                if (inst.signum() <= 0) throw ApiException.badRequest("New instalment must be positive");
                a.setNewInstalment(inst);
            }
            case EARLY_SETTLEMENT -> {
                BigDecimal amt = r.settlementAmount() == null ? l.getOutstandingBalance() : money(r.settlementAmount(), "Settlement amount");
                if (amt.signum() <= 0 || amt.compareTo(l.getOutstandingBalance()) > 0)
                    throw ApiException.badRequest("Settlement amount must be positive and not exceed the outstanding balance");
                a.setSettlementAmount(amt);
            }
            case TRANSFER_OUT -> {
            }
        }
        a.setStatus(LoanAdjustment.Status.PENDING);
        adjustments.save(a);
        EmployeeBrief b = lookup.brief(l.getEmployeeId());
        WorkflowInstance wi = workflow.start(PROCESS, ENTITY, a.getId(), "Loan adjustment (" + a.getAdjustmentType().name().replace('_', ' ').toLowerCase()
                + ") - " + (b == null ? "" : b.employeeNo()), "/payroll/loans/" + loanId, l.getEmployeeId());
        a.setWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.CREATE, ENTITY, a.getId(), "Loan adjustment requested: " + a.getAdjustmentType());
        return a;
    }

    @Override
    public String processCode() {
        return PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        LoanAdjustment a = adjustment(wi.getEntityId());
        EmployeeLoan l = loan(a.getLoanId());
        if (l.getStatus() != EmployeeLoan.Status.ACTIVE) throw ApiException.rule("LOAN_CLOSED", "The loan is no longer active");
        if (lineItems.loanInSubmittedRun(l.getId()))
            throw ApiException.rule("LOAN_IN_PAYROLL", "A payroll run that deducts this loan is awaiting approval. "
                    + "Approve the adjustment after that run is finalized or returned.");
        Map<String, Object> before = Map.of("status", l.getStatus(), "outstanding", l.getOutstandingBalance(), "instalment", l.getMonthlyInstalment());
        switch (a.getAdjustmentType()) {
            case INSTALMENT_CHANGE -> {
                l.setMonthlyInstalment(a.getNewInstalment());
                txn(l, LoanTransaction.Type.INSTALMENT_CHANGE, a.getEffectiveDate(), BigDecimal.ZERO.setScale(2), null, null,
                        "Instalment changed to " + a.getNewInstalment().toPlainString() + ": " + a.getReason());
            }
            case EARLY_SETTLEMENT -> {
                BigDecimal amt = a.getSettlementAmount().min(l.getOutstandingBalance());
                l.setOutstandingBalance(l.getOutstandingBalance().subtract(amt));
                if (l.getOutstandingBalance().signum() == 0) {
                    l.setStatus(EmployeeLoan.Status.SETTLED);
                    l.setClosedOn(a.getEffectiveDate());
                }
                txn(l, LoanTransaction.Type.SETTLEMENT, a.getEffectiveDate(), amt, null, null, "Early settlement: " + a.getReason());
            }
            case TRANSFER_OUT -> {
                l.setStatus(EmployeeLoan.Status.TRANSFERRED_OUT);
                l.setClosedOn(a.getEffectiveDate());
                txn(l, LoanTransaction.Type.TRANSFER_OUT, a.getEffectiveDate(), l.getOutstandingBalance(), null, null,
                        "Balance transferred out at end of secondment: " + a.getReason());
            }
        }
        a.setStatus(LoanAdjustment.Status.APPLIED);
        a.setAppliedAt(Instant.now());
        audit.log(AuditAction.UPDATE, "EmployeeLoan", l.getId(), "Loan adjustment applied: " + a.getAdjustmentType(), before,
                Map.of("status", l.getStatus(), "outstanding", l.getOutstandingBalance(), "instalment", l.getMonthlyInstalment()));
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        adjustment(wi.getEntityId()).setStatus(LoanAdjustment.Status.REJECTED);
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        adjustment(wi.getEntityId()).setStatus(LoanAdjustment.Status.RETURNED);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        adjustment(wi.getEntityId()).setStatus(LoanAdjustment.Status.PENDING);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        adjustment(wi.getEntityId()).setStatus(LoanAdjustment.Status.CANCELLED);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        LoanAdjustment a = adjustment(wi.getEntityId());
        EmployeeLoan l = loan(a.getLoanId());
        EmployeeBrief b = lookup.brief(l.getEmployeeId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Employee", b == null ? l.getEmployeeId() : b.employeeNo() + " - " + (masked(b) ? RESTRICTED_NAME : b.nameWithInitials()));
        m.put("Loan", l.getLoanType() + " / " + l.getLender() + (l.getReferenceNo() == null ? "" : " (" + l.getReferenceNo() + ")"));
        m.put("Outstanding balance", l.getOutstandingBalance());
        m.put("Current instalment", l.getMonthlyInstalment());
        m.put("Adjustment", a.getAdjustmentType().name().replace('_', ' '));
        if (a.getNewInstalment() != null) m.put("New instalment", a.getNewInstalment());
        if (a.getSettlementAmount() != null) m.put("Settlement amount", a.getSettlementAmount());
        m.put("Effective date", a.getEffectiveDate());
        m.put("Reason", a.getReason());
        return m;
    }

    // ===================================================================== payroll finalization

    /** Applies a finalized payroll deduction to a loan; capped at the outstanding balance. Returns the amount applied. */
    BigDecimal applyPayrollDeduction(Long loanId, BigDecimal amount, Long runId, String period, LocalDate date) {
        EmployeeLoan l = loan(loanId);
        BigDecimal amt = amount.min(l.getOutstandingBalance());
        if (amt.signum() <= 0) return BigDecimal.ZERO;
        l.setOutstandingBalance(l.getOutstandingBalance().subtract(amt));
        if (l.getOutstandingBalance().signum() == 0 && l.getStatus() == EmployeeLoan.Status.ACTIVE) {
            l.setStatus(EmployeeLoan.Status.SETTLED);
            l.setClosedOn(date);
        }
        txn(l, LoanTransaction.Type.DEDUCTION, date, amt, runId, period, "Payroll deduction " + period);
        return amt;
    }

    // ===================================================================== helpers

    private void txn(EmployeeLoan l, LoanTransaction.Type type, LocalDate date, BigDecimal amount, Long runId, String period, String remarks) {
        LoanTransaction t = new LoanTransaction();
        t.setLoanId(l.getId());
        t.setTxnDate(date);
        t.setTxnType(type);
        t.setAmount(amount);
        t.setBalanceAfter(l.getOutstandingBalance());
        t.setPayrollRunId(runId);
        t.setPeriod(period);
        t.setRemarks(remarks == null || remarks.length() <= 500 ? remarks : remarks.substring(0, 500));
        transactions.save(t);
    }

    List<LoanView> views(List<EmployeeLoan> rows) {
        Map<Long, EmployeeBrief> briefs = lookup.briefs(rows.stream().map(EmployeeLoan::getEmployeeId).toList());
        return rows.stream().map(l -> {
            EmployeeBrief b = briefs.get(l.getEmployeeId());
            return new LoanView(l.getId(), l.getEmployeeId(), b == null ? null : b.employeeNo(),
                    b == null ? null : (masked(b) ? RESTRICTED_NAME : b.nameWithInitials()), l.getLoanType(), l.getLender(),
                    l.getPreviousWorkplace(), l.getReferenceNo(), l.getOriginalAmount(), l.getOutstandingBalance(),
                    l.getMonthlyInstalment(), l.getStartDate(), l.getStatus().name(), l.getClosedOn(), l.getRemarks());
        }).toList();
    }

    private EmployeeLoan loan(Long id) {
        return loans.findById(id).orElseThrow(() -> ApiException.notFound("Loan", id));
    }

    private LoanAdjustment adjustment(Long id) {
        return adjustments.findById(id).orElseThrow(() -> ApiException.notFound("Loan adjustment", id));
    }
}
