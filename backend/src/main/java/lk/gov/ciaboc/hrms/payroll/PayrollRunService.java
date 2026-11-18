package lk.gov.ciaboc.hrms.payroll;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.benefits.BenefitsService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.contracts.NoPaySource;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup.EmployeeBrief;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.payroll.PayrollCalculator.*;
import lk.gov.ciaboc.hrms.payroll.PayrollEntities.*;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static lk.gov.ciaboc.hrms.payroll.PayrollSupportUtil.*;

/**
 * Payroll runs (FR-PAY-030..035): calculation, maker-checker approval (PAYROLL_RUN workflow), finalization and
 * locking, controlled adjustments, payslips and exports. Finalized runs are immutable (also enforced by database
 * triggers); corrections are made only through payroll adjustment records picked up by the next run.
 */
@Service
@RequiredArgsConstructor
public class PayrollRunService implements WorkflowHandler {
    public static final String PROCESS = "PAYROLL_RUN";
    static final String ENTITY = "PayrollRun";

    private final PayrollRunRepository runs;
    private final PayrollLineRepository lines;
    private final PayrollLineItemRepository items;
    private final PayrollAdjustmentRepository adjustments;
    private final EmployeeSalaryRepository salaries;
    private final AllowanceTypeRepository allowanceTypes;
    private final AllowanceRateRepository allowanceRates;
    private final EmployeeAllowanceRepository employeeAllowances;
    private final DeductionTypeRepository deductionTypes;
    private final DeductionRateRepository deductionRates;
    private final DeductionMembershipRepository memberships;
    private final EmployeeLoanRepository loans;
    private final LoanService loanService;
    private final BenefitsService benefits;
    private final ObjectProvider<NoPaySource> noPaySource;
    private final EmployeeLookup lookup;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final WorkflowService workflow;
    private final AuditService audit;

    // ===================================================================== DTOs

    public record RunRequest(String period, LocalDate payDate, String notes) {
    }

    public record SubmitRequest(String comment) {
    }

    public record LineSummary(Long id, Long employeeId, String employeeNo, String employeeName, String designation,
                              String orgUnitCode, String scaleCode, Integer step, BigDecimal basicSalary, BigDecimal grossPay,
                              BigDecimal totalDeductions, BigDecimal netPay, BigDecimal employerContributions,
                              BigDecimal noPayDays, String warnings) {
    }

    public record LineDetail(LineSummary line, String period, String runStatus, LocalDate payDate, String bankCode,
                             String bankBranch, String bankAccountNo, List<PayrollLineItem> items) {
    }

    public record ComponentTotal(String componentType, String code, String description, long employees, BigDecimal amount) {
    }

    public record RunDetail(PayrollRun run, List<ComponentTotal> components, boolean canCalculate, boolean canSubmit,
                            boolean openAdjustmentsPending, long openAdjustments) {
    }

    public record PayslipItem(Long lineId, String period, LocalDate payDate, BigDecimal grossPay, BigDecimal totalDeductions,
                              BigDecimal netPay) {
    }

    public record AdjustmentRequest(Long employeeId, String adjustmentType, String code, String description,
                                    BigDecimal amount, String targetPeriod, Long relatedRunId, String reason) {
    }

    public record AdjustmentView(Long id, Long employeeId, String employeeNo, String employeeName, String adjustmentType,
                                 String code, String description, BigDecimal amount, String targetPeriod,
                                 String relatedPeriod, String reason, String status, String appliedPeriod,
                                 String createdBy, Instant createdAt) {
    }

    public record DeductionHistoryRow(String period, String componentType, String sourceType, String code,
                                      String description, BigDecimal amount) {
    }

    public record Export(String filename, byte[] content) {
    }

    // ===================================================================== runs

    @Transactional(readOnly = true)
    public List<PayrollRun> list() {
        return runs.findAllByOrderByPeriodDesc();
    }

    @Transactional(readOnly = true)
    public RunDetail detail(Long id) {
        PayrollRun r = run(id);
        long open = adjustments.openUpTo(r.getPeriod()).size();
        boolean editable = !r.isLocked() && (r.getStatus() == PayrollRun.Status.DRAFT || r.getStatus() == PayrollRun.Status.RETURNED);
        boolean canSubmit = editable && r.getCalculatedAt() != null && r.getEmployeeCount() > 0
                && CurrentUser.get().getId().equals(r.getCalculatedByUserId());
        return new RunDetail(r, components(r), editable, canSubmit, open > 0 && editable, open);
    }

    @Transactional
    public PayrollRun create(RunRequest req) {
        YearMonth ym = period(req.period());
        if (runs.findByPeriod(req.period()).isPresent()) throw ApiException.conflict("A payroll run already exists for " + req.period());
        if (runs.existsByStatusNot(PayrollRun.Status.FINALIZED))
            throw ApiException.rule("OPEN_RUN_EXISTS", "Finalize the open payroll run before starting another period");
        runs.findTopByStatusOrderByPeriodDesc(PayrollRun.Status.FINALIZED).ifPresent(last -> {
            if (!ym.isAfter(YearMonth.parse(last.getPeriod())))
                throw ApiException.rule("PERIOD_CLOSED", "Period " + req.period() + " is not after the last finalized period " + last.getPeriod());
        });
        if (req.payDate() != null && (req.payDate().isBefore(ym.atDay(1).minusMonths(1)) || req.payDate().isAfter(ym.atEndOfMonth().plusMonths(1))))
            throw ApiException.badRequest("Pay date must be close to the payroll period");
        PayrollRun r = new PayrollRun();
        r.setPeriod(req.period());
        r.setPayDate(req.payDate());
        r.setNotes(blank(req.notes()));
        r.setStatus(PayrollRun.Status.DRAFT);
        runs.save(r);
        audit.log(AuditAction.CREATE, ENTITY, r.getId(), "Payroll run " + r.getPeriod() + " created");
        return r;
    }

    /** (Re)calculates all lines of a run. Allowed only while DRAFT or RETURNED and never once locked (FR-PAY-033). */
    @Transactional
    public PayrollRun calculate(Long id) {
        PayrollRun run = run(id);
        if (run.isLocked() || (run.getStatus() != PayrollRun.Status.DRAFT && run.getStatus() != PayrollRun.Status.RETURNED))
            throw ApiException.rule("RUN_NOT_EDITABLE", "Payroll run " + run.getPeriod() + " is " + run.getStatus()
                    + " and cannot be recalculated; use a payroll adjustment for corrections");
        UserPrincipal me = CurrentUser.get();
        YearMonth ym = YearMonth.parse(run.getPeriod());
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        items.deleteByRun(id);
        lines.deleteByRun(id);

        // ---- reference data in force at period end (proration of mid-month changes: to be confirmed with Finance)
        Map<Long, EmployeeSalary> salaryByEmp = salaries.effectiveOn(end).stream()
                .collect(Collectors.toMap(EmployeeSalary::getEmployeeId, Function.identity(), (a, b) -> a));
        Map<Long, AllowanceType> aTypes = allowanceTypes.findAll().stream().filter(AllowanceType::isActive)
                .collect(Collectors.toMap(AllowanceType::getId, Function.identity()));
        Map<Long, AllowanceRate> aRates = allowanceRates.effectiveOn(end).stream()
                .collect(Collectors.toMap(AllowanceRate::getAllowanceTypeId, Function.identity(), (a, b) -> a));
        Map<Long, List<EmployeeAllowance>> empAllowances = employeeAllowances.effectiveOn(end).stream()
                .collect(Collectors.groupingBy(EmployeeAllowance::getEmployeeId));
        Map<Long, DeductionType> dTypes = deductionTypes.findAll().stream().filter(DeductionType::isActive)
                .collect(Collectors.toMap(DeductionType::getId, Function.identity()));
        Map<Long, DeductionRate> dRates = deductionRates.effectiveOn(end).stream()
                .collect(Collectors.toMap(DeductionRate::getDeductionTypeId, Function.identity(), (a, b) -> a));
        Map<Long, List<DeductionMembership>> empMemberships = memberships.effectiveOn(end).stream()
                .collect(Collectors.groupingBy(DeductionMembership::getEmployeeId));
        Map<Long, List<EmployeeLoan>> empLoans = loans.findByStatusOrderByStartDateDesc(EmployeeLoan.Status.ACTIVE).stream()
                .filter(l -> !l.getStartDate().isAfter(end) && l.getOutstandingBalance().signum() > 0)
                .collect(Collectors.groupingBy(EmployeeLoan::getEmployeeId));
        Map<Long, List<BenefitsService.PremiumDeduction>> premiums = benefits.payrollPremiums(start, end).stream()
                .collect(Collectors.groupingBy(BenefitsService.PremiumDeduction::employeeId));
        Map<Long, List<PayrollAdjustment>> empAdjustments = adjustments.openUpTo(run.getPeriod()).stream()
                .collect(Collectors.groupingBy(PayrollAdjustment::getEmployeeId));
        NoPaySource source = noPaySource.getIfAvailable();
        Map<Long, BigDecimal> noPay = source == null ? Map.of() : Optional.ofNullable(source.approvedNoPayDays(ym)).orElse(Map.of());
        BigDecimal divisor = settings.getDecimal("payroll.nopay.divisor", BigDecimal.valueOf(30));
        if (divisor.signum() <= 0) throw ApiException.rule("CONFIG", "Setting payroll.nopay.divisor must be positive");

        List<DeductionType> universal = dTypes.values().stream().filter(t -> !t.isMembershipRequired())
                .sorted(Comparator.comparing(DeductionType::getCode)).toList();
        List<AllowanceType> allStaff = aTypes.values().stream().filter(AllowanceType::isAppliesToAll)
                .sorted(Comparator.comparing(AllowanceType::getCode)).toList();
        Set<String> runWarnings = new LinkedHashSet<>();

        int count = 0;
        int warningLines = 0;
        BigDecimal tGross = BigDecimal.ZERO, tDed = BigDecimal.ZERO, tNet = BigDecimal.ZERO, tEr = BigDecimal.ZERO;
        for (EmployeeBrief b : lookup.inService()) {
            Employee e = lookup.require(b.id());
            EmployeeSalary sal = salaryByEmp.get(b.id());
            List<String> warnings = new ArrayList<>();
            Input input;
            if (sal == null) {
                input = new Input(BigDecimal.ZERO, null, null, null, BigDecimal.ZERO, divisor, null, null);
            } else {
                // allowances: all-staff types (assignment may override amount) + individually assigned types
                Map<Long, EmployeeAllowance> assigned = new LinkedHashMap<>();
                for (EmployeeAllowance a : empAllowances.getOrDefault(b.id(), List.of())) assigned.put(a.getAllowanceTypeId(), a);
                List<Allowance> allowances = new ArrayList<>();
                Set<Long> done = new HashSet<>();
                for (AllowanceType t : allStaff) {
                    allowance(t, aRates.get(t.getId()), assigned.get(t.getId()), allowances, runWarnings);
                    done.add(t.getId());
                }
                for (EmployeeAllowance a : assigned.values()) {
                    AllowanceType t = aTypes.get(a.getAllowanceTypeId());
                    if (t == null || done.contains(t.getId())) continue;
                    allowance(t, aRates.get(t.getId()), a, allowances, runWarnings);
                }
                // statutory / scheme / other deductions
                List<Deduction> deductions = new ArrayList<>();
                Map<Long, DeductionMembership> mem = new LinkedHashMap<>();
                for (DeductionMembership m : empMemberships.getOrDefault(b.id(), List.of())) mem.put(m.getDeductionTypeId(), m);
                List<DeductionType> applicable = new ArrayList<>(universal);
                mem.keySet().stream().map(dTypes::get).filter(Objects::nonNull).filter(DeductionType::isMembershipRequired)
                        .sorted(Comparator.comparing(DeductionType::getCode)).forEach(applicable::add);
                applicable.sort(Comparator.comparing((DeductionType t) -> t.getCategory().ordinal()).thenComparing(DeductionType::getCode));
                for (DeductionType t : applicable) {
                    DeductionRate rate = dRates.get(t.getId());
                    if (rate == null) {
                        runWarnings.add("No rate in force for deduction " + t.getCode());
                        continue;
                    }
                    DeductionMembership m = mem.get(t.getId());
                    BigDecimal ev = m != null && m.getEmployeeValueOverride() != null ? m.getEmployeeValueOverride() : rate.getEmployeeValue();
                    BigDecimal rv = m != null && m.getEmployerValueOverride() != null ? m.getEmployerValueOverride() : rate.getEmployerValue();
                    deductions.add(new Deduction(t.getCode(), t.getName(), Source.valueOf(t.getCategory().name()),
                            rate.getEmployeeBasis(), ev, rate.getEmployerBasis(), rv));
                }
                List<Loan> loanInputs = empLoans.getOrDefault(b.id(), List.of()).stream()
                        .map(l -> new Loan(l.getId(), "LOAN-" + l.getLoanType(), "Loan recovery - " + l.getLender(),
                                l.getMonthlyInstalment(), l.getOutstandingBalance())).toList();
                List<FixedDeduction> benefitInputs = premiums.getOrDefault(b.id(), List.of()).stream()
                        .map(p -> new FixedDeduction(p.coverageId(), "INS-" + p.schemeCode(), p.schemeName(), p.amount())).toList();
                List<Adjustment> adjInputs = empAdjustments.getOrDefault(b.id(), List.of()).stream()
                        .map(a -> new Adjustment(a.getId(), a.getAdjustmentType() == PayrollAdjustment.Type.EARNING, a.getCode(),
                                a.getDescription() + " (" + a.getTargetPeriod() + ")", a.getAmount())).toList();
                input = new Input(sal.getBasicSalary(), allowances, deductions, loanInputs,
                        noPay.getOrDefault(b.id(), BigDecimal.ZERO), divisor, benefitInputs, adjInputs);
            }
            Result res = PayrollCalculator.calculate(input);
            warnings.addAll(res.warnings());
            if (e.getBankAccountNo() == null || e.getBankCode() == null) warnings.add("Bank details are missing");

            PayrollLine line = new PayrollLine();
            line.setRunId(id);
            line.setEmployeeId(b.id());
            line.setEmployeeNo(b.employeeNo());
            line.setEmployeeName(b.nameWithInitials());
            line.setDesignation(b.designation());
            line.setOrgUnitCode(b.orgUnitCode());
            line.setBankCode(e.getBankCode());
            line.setBankBranch(e.getBankBranch());
            line.setBankAccountNo(e.getBankAccountNo());
            line.setScaleCode(sal == null ? null : sal.getScaleCode());
            line.setStep(sal == null ? null : sal.getStep());
            line.setBasicSalary(res.basic());
            line.setGrossPay(res.gross());
            line.setTotalDeductions(res.totalDeductions());
            line.setNetPay(res.net());
            line.setEmployerContributions(res.employerContributions());
            line.setNoPayDays(res.noPayDays());
            line.setWarnings(warnings.isEmpty() ? null : truncate(String.join("; ", warnings), 1000));
            lines.save(line);
            int order = 0;
            List<PayrollLineItem> lineItems = new ArrayList<>();
            for (Item it : res.items()) {
                PayrollLineItem pi = new PayrollLineItem();
                pi.setLineId(line.getId());
                pi.setComponentType(it.type());
                pi.setSourceType(it.source());
                pi.setSourceId(it.sourceId());
                pi.setCode(it.code());
                pi.setDescription(truncate(it.description(), 300));
                pi.setAmount(it.amount());
                pi.setSortOrder(order++);
                lineItems.add(pi);
            }
            items.saveAll(lineItems);
            count++;
            if (!warnings.isEmpty()) warningLines++;
            tGross = tGross.add(res.gross());
            tDed = tDed.add(res.totalDeductions());
            tNet = tNet.add(res.net());
            tEr = tEr.add(res.employerContributions());
        }
        run.setEmployeeCount(count);
        run.setTotalGross(tGross);
        run.setTotalDeductions(tDed);
        run.setTotalNet(tNet);
        run.setTotalEmployer(tEr);
        run.setWarningCount(warningLines + runWarnings.size());
        run.setCalculatedAt(Instant.now());
        run.setCalculatedBy(me.getUsername());
        run.setCalculatedByUserId(me.getId());
        String notes = runWarnings.isEmpty() ? null : "Configuration warnings: " + String.join("; ", runWarnings);
        if (notes != null) run.setNotes(truncate(notes, 1000));
        audit.log(AuditAction.PROCESS, ENTITY, id, "Payroll " + run.getPeriod() + " calculated: " + count + " employees, net "
                + tNet.toPlainString() + (source == null ? " (no-pay source not available; treated as zero)" : ""));
        return run;
    }

    private static void allowance(AllowanceType t, AllowanceRate rate, EmployeeAllowance assignment, List<Allowance> out, Set<String> runWarnings) {
        if (assignment != null && assignment.getAmountOverride() != null) {
            out.add(new Allowance(t.getCode(), t.getName(), Basis.FIXED, assignment.getAmountOverride(), assignment.getAmountOverride()));
            return;
        }
        if (rate == null) {
            runWarnings.add("No rate in force for allowance " + t.getCode());
            return;
        }
        out.add(new Allowance(t.getCode(), t.getName(), rate.getBasis(), rate.getRateValue(), null));
    }

    @Transactional
    public PayrollRun submit(Long id, SubmitRequest req) {
        PayrollRun run = run(id);
        UserPrincipal me = CurrentUser.get();
        if (run.isLocked() || (run.getStatus() != PayrollRun.Status.DRAFT && run.getStatus() != PayrollRun.Status.RETURNED))
            throw ApiException.rule("RUN_NOT_EDITABLE", "Only draft or returned runs can be submitted");
        if (run.getCalculatedAt() == null || run.getEmployeeCount() == 0)
            throw ApiException.rule("NOT_CALCULATED", "Calculate the payroll before submitting it");
        if (!me.getId().equals(run.getCalculatedByUserId()))
            throw ApiException.rule("NOT_PREPARER", "Only the preparer who last calculated this run can submit it; recalculate first");
        // Loans changed since calculation (settled, transferred out) require recalculation.
        for (PayrollLine l : lines.findByRunIdOrderByEmployeeNoAsc(id)) {
            for (PayrollLineItem i : items.findByLineIdOrderBySortOrderAsc(l.getId())) {
                if (i.getSourceType() == Source.LOAN && loans.findById(i.getSourceId())
                        .map(x -> x.getStatus() != EmployeeLoan.Status.ACTIVE).orElse(true))
                    throw ApiException.rule("STALE_CALCULATION", "A loan in this run is no longer active; recalculate before submitting");
            }
        }
        run.setSubmittedAt(Instant.now());
        run.setSubmittedBy(me.getUsername());
        if (run.getStatus() == PayrollRun.Status.RETURNED && run.getWorkflowInstanceId() != null) {
            workflow.resubmit(run.getWorkflowInstanceId(), req == null ? null : req.comment());
            run.setStatus(PayrollRun.Status.SUBMITTED);
        } else {
            run.setStatus(PayrollRun.Status.SUBMITTED);
            WorkflowInstance wi = workflow.start(PROCESS, ENTITY, id, "Payroll " + run.getPeriod() + " (" + run.getEmployeeCount()
                    + " employees)", "/payroll/runs/" + id, null);
            run.setWorkflowInstanceId(wi.getId());
        }
        return run;
    }

    // ===================================================================== workflow callbacks

    @Override
    public String processCode() {
        return PROCESS;
    }

    /** Final authorisation: post loan deductions, mark adjustments applied, then finalize and lock the run. */
    @Override
    public void onApproved(WorkflowInstance wi) {
        PayrollRun run = run(wi.getEntityId());
        if (run.getStatus() != PayrollRun.Status.SUBMITTED || run.isLocked())
            throw ApiException.rule("RUN_NOT_SUBMITTED", "Payroll run is not awaiting approval");
        YearMonth ym = YearMonth.parse(run.getPeriod());
        LocalDate postDate = run.getPayDate() != null ? run.getPayDate() : ym.atEndOfMonth();
        List<PayrollLine> runLines = lines.findByRunIdOrderByEmployeeNoAsc(run.getId());
        Map<Long, PayrollLine> lineById = runLines.stream().collect(Collectors.toMap(PayrollLine::getId, Function.identity()));
        Set<Long> appliedAdjustments = new HashSet<>();
        for (PayrollLineItem i : items.findByLineIdIn(lineById.keySet())) {
            if (i.getSourceType() == Source.LOAN && i.getComponentType() == ComponentType.DEDUCTION && i.getSourceId() != null)
                loanService.applyPayrollDeduction(i.getSourceId(), i.getAmount(), run.getId(), run.getPeriod(), postDate);
            if (i.getSourceType() == Source.ADJUSTMENT && i.getSourceId() != null) appliedAdjustments.add(i.getSourceId());
        }
        for (PayrollAdjustment a : adjustments.findAllById(appliedAdjustments)) {
            if (a.getStatus() != PayrollAdjustment.Status.OPEN) continue;
            a.setStatus(PayrollAdjustment.Status.APPLIED);
            a.setAppliedRunId(run.getId());
        }
        run.setStatus(PayrollRun.Status.FINALIZED);
        run.setFinalizedAt(Instant.now());
        run.setLocked(true);
        runs.saveAndFlush(run);
        audit.log(AuditAction.APPROVE, ENTITY, run.getId(), "Payroll " + run.getPeriod() + " finalized and locked (net "
                + run.getTotalNet().toPlainString() + ")");
        Map<String, Object> vars = Map.of("period", run.getPeriod());
        for (PayrollLine l : runLines) notifications.notifyEmployee(l.getEmployeeId(), "PAY_PAYSLIP_AVAILABLE", vars, "/self/payslips");
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        PayrollRun run = run(wi.getEntityId());
        run.setStatus(PayrollRun.Status.RETURNED);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        run(wi.getEntityId()).setStatus(PayrollRun.Status.SUBMITTED);
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        run(wi.getEntityId()).setStatus(PayrollRun.Status.DRAFT);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        run(wi.getEntityId()).setStatus(PayrollRun.Status.DRAFT);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        PayrollRun r = run(wi.getEntityId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Period", r.getPeriod());
        m.put("Pay date", r.getPayDate());
        m.put("Employees", r.getEmployeeCount());
        m.put("Total gross", r.getTotalGross());
        m.put("Total deductions", r.getTotalDeductions());
        m.put("Total net pay", r.getTotalNet());
        m.put("Employer contributions", r.getTotalEmployer());
        m.put("Lines with warnings", r.getWarningCount());
        m.put("Prepared by", r.getCalculatedBy());
        m.put("Calculated at", r.getCalculatedAt());
        for (ComponentTotal c : components(r)) {
            if (!"EARNING".equals(c.componentType()) || !"BASIC".equals(c.code()))
                m.put(c.componentType().charAt(0) + c.componentType().substring(1).toLowerCase() + ": " + c.description(), c.amount());
        }
        if (r.getNotes() != null) m.put("Notes", r.getNotes());
        return m;
    }

    // ===================================================================== lines, payslips

    @Transactional(readOnly = true)
    public List<LineSummary> lines(Long runId) {
        PayrollRun r = run(runId);
        List<PayrollLine> rows = lines.findByRunIdOrderByEmployeeNoAsc(runId);
        Map<Long, EmployeeBrief> briefs = lookup.briefs(rows.stream().map(PayrollLine::getEmployeeId).toList());
        audit.log(AuditAction.VIEW, ENTITY, runId, "Viewed payroll lines for " + r.getPeriod());
        return rows.stream().map(l -> summary(l, briefs.get(l.getEmployeeId()))).toList();
    }

    @Transactional(readOnly = true)
    public LineDetail line(Long runId, Long lineId) {
        PayrollRun r = run(runId);
        PayrollLine l = lines.findById(lineId).filter(x -> x.getRunId().equals(runId)).orElseThrow(() -> ApiException.notFound("Payroll line", lineId));
        lookup.requireAccess(l.getEmployeeId());
        audit.log(AuditAction.VIEW, "PayrollLine", lineId, "Viewed payroll line of " + l.getEmployeeNo() + " for " + r.getPeriod());
        return detail(r, l, lookup.brief(l.getEmployeeId()));
    }

    @Transactional(readOnly = true)
    public List<PayslipItem> myPayslips() {
        Long me = CurrentUser.employeeId();
        Map<Long, PayrollRun> runById = runs.findAll().stream().collect(Collectors.toMap(PayrollRun::getId, Function.identity()));
        return lines.finalizedForEmployee(me).stream().map(l -> {
            PayrollRun r = runById.get(l.getRunId());
            return new PayslipItem(l.getId(), r.getPeriod(), r.getPayDate(), l.getGrossPay(), l.getTotalDeductions(), l.getNetPay());
        }).toList();
    }

    /** An employee's own payslip; any other line (or a non-finalized one) is reported as not found. */
    @Transactional(readOnly = true)
    public LineDetail myPayslip(Long lineId) {
        Long me = CurrentUser.employeeId();
        PayrollLine l = lines.findById(lineId).filter(x -> x.getEmployeeId().equals(me)).orElseThrow(() -> ApiException.notFound("Payslip", lineId));
        PayrollRun r = run(l.getRunId());
        if (r.getStatus() != PayrollRun.Status.FINALIZED) throw ApiException.notFound("Payslip", lineId);
        audit.log(AuditAction.VIEW, "PayrollLine", lineId, "Viewed own payslip for " + r.getPeriod());
        return detail(r, l, lookup.brief(me));
    }

    @Transactional(readOnly = true)
    public Optional<PayslipItem> lastPayslip(Long employeeId) {
        Map<Long, PayrollRun> runById = runs.findAll().stream().collect(Collectors.toMap(PayrollRun::getId, Function.identity()));
        return lines.finalizedForEmployee(employeeId).stream().findFirst().map(l -> {
            PayrollRun r = runById.get(l.getRunId());
            return new PayslipItem(l.getId(), r.getPeriod(), r.getPayDate(), l.getGrossPay(), l.getTotalDeductions(), l.getNetPay());
        });
    }

    @Transactional(readOnly = true)
    public List<DeductionHistoryRow> deductionHistory(Long employeeId) {
        lookup.requireAccess(employeeId);
        audit.log(AuditAction.VIEW, "PayrollLine", employeeId, "Viewed deduction history of employee " + employeeId);
        return items.deductionHistory(employeeId).stream().map(o -> {
            PayrollLineItem i = (PayrollLineItem) o[1];
            return new DeductionHistoryRow((String) o[0], i.getComponentType().name(), i.getSourceType().name(), i.getCode(),
                    i.getDescription(), i.getAmount());
        }).toList();
    }

    private LineSummary summary(PayrollLine l, EmployeeBrief b) {
        boolean hide = b != null && masked(b);
        return new LineSummary(l.getId(), l.getEmployeeId(), l.getEmployeeNo(), hide ? RESTRICTED_NAME : l.getEmployeeName(),
                hide ? null : l.getDesignation(), hide ? null : l.getOrgUnitCode(), l.getScaleCode(), l.getStep(),
                l.getBasicSalary(), l.getGrossPay(), l.getTotalDeductions(), l.getNetPay(), l.getEmployerContributions(),
                l.getNoPayDays(), l.getWarnings());
    }

    private LineDetail detail(PayrollRun r, PayrollLine l, EmployeeBrief b) {
        return new LineDetail(summary(l, b), r.getPeriod(), r.getStatus().name(), r.getPayDate(), l.getBankCode(), l.getBankBranch(),
                maskAccount(l.getBankAccountNo()), items.findByLineIdOrderBySortOrderAsc(l.getId()));
    }

    private static String maskAccount(String acct) {
        if (acct == null || acct.length() <= 4) return acct;
        return "*".repeat(acct.length() - 4) + acct.substring(acct.length() - 4);
    }

    private List<ComponentTotal> components(PayrollRun r) {
        List<PayrollLine> rl = lines.findByRunIdOrderByEmployeeNoAsc(r.getId());
        if (rl.isEmpty()) return List.of();
        Map<String, ComponentTotal> agg = new LinkedHashMap<>();
        Map<String, Set<Long>> emps = new HashMap<>();
        Map<Long, Long> lineEmp = rl.stream().collect(Collectors.toMap(PayrollLine::getId, PayrollLine::getEmployeeId));
        List<PayrollLineItem> all = new ArrayList<>(items.findByLineIdIn(lineEmp.keySet()));
        all.sort(Comparator.comparing((PayrollLineItem i) -> i.getComponentType().ordinal()).thenComparing(PayrollLineItem::getSortOrder));
        for (PayrollLineItem i : all) {
            String key = i.getComponentType() + "|" + i.getCode();
            emps.computeIfAbsent(key, k -> new HashSet<>()).add(lineEmp.get(i.getLineId()));
            ComponentTotal prev = agg.get(key);
            BigDecimal amt = prev == null ? i.getAmount() : prev.amount().add(i.getAmount());
            String desc = prev == null ? (i.getSourceType() == Source.LOAN || i.getSourceType() == Source.ADJUSTMENT
                    || i.getSourceType() == Source.NOPAY ? genericDescription(i) : i.getDescription()) : prev.description();
            agg.put(key, new ComponentTotal(i.getComponentType().name(), i.getCode(), desc, emps.get(key).size(), amt));
        }
        return new ArrayList<>(agg.values());
    }

    private static String genericDescription(PayrollLineItem i) {
        return switch (i.getSourceType()) {
            case LOAN -> "Loan recovery (" + i.getCode().replace("LOAN-", "") + ")";
            case NOPAY -> "No-pay";
            default -> "Adjustment " + i.getCode();
        };
    }

    // ===================================================================== exports (formats to be confirmed with Finance)

    /** Bank advice: employee no, name, bank, branch, account, net (FR-PAY-035). Layout to be confirmed with Finance/bank. */
    @Transactional(readOnly = true)
    public Export bankAdvice(Long runId) {
        PayrollRun r = run(runId);
        if (r.getStatus() != PayrollRun.Status.FINALIZED) throw ApiException.rule("NOT_FINALIZED", "Bank advice is available only for finalized runs");
        StringBuilder sb = new StringBuilder();
        sb.append("# CIABOC HRMS bank advice - period ").append(r.getPeriod())
                .append(" - format ").append(settings.get("payroll.bank-advice.format", "CSV-v1")).append(" (to be confirmed with Finance)\n");
        sb.append("Employee No,Name,Bank,Branch,Account No,Net Pay\n");
        BigDecimal total = BigDecimal.ZERO;
        int n = 0;
        for (PayrollLine l : lines.findByRunIdOrderByEmployeeNoAsc(runId)) {
            if (l.getNetPay().signum() <= 0) continue;
            sb.append(csv(l.getEmployeeNo())).append(',').append(csv(l.getEmployeeName())).append(',').append(csv(l.getBankCode()))
                    .append(',').append(csv(l.getBankBranch())).append(',').append(csv(l.getBankAccountNo())).append(',')
                    .append(l.getNetPay().toPlainString()).append('\n');
            total = total.add(l.getNetPay());
            n++;
        }
        sb.append("TOTAL,").append(n).append(",,,,").append(total.toPlainString()).append('\n');
        audit.log(AuditAction.EXPORT, ENTITY, runId, "Bank advice exported for " + r.getPeriod() + " (" + n + " payments)");
        return new Export("bank-advice-" + r.getPeriod() + ".csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Payroll posting summary by component for the finance ledger. Layout to be confirmed with Finance. */
    @Transactional(readOnly = true)
    public Export postingSummary(Long runId) {
        PayrollRun r = run(runId);
        if (r.getStatus() != PayrollRun.Status.FINALIZED) throw ApiException.rule("NOT_FINALIZED", "The posting summary is available only for finalized runs");
        StringBuilder sb = new StringBuilder();
        sb.append("# CIABOC HRMS payroll posting summary - period ").append(r.getPeriod()).append(" (format to be confirmed with Finance)\n");
        sb.append("Component Type,Code,Description,Employees,Amount\n");
        for (ComponentTotal c : components(r)) {
            sb.append(c.componentType()).append(',').append(csv(c.code())).append(',').append(csv(c.description())).append(',')
                    .append(c.employees()).append(',').append(c.amount().toPlainString()).append('\n');
        }
        sb.append("TOTAL,GROSS,Total gross pay,").append(r.getEmployeeCount()).append(',').append(r.getTotalGross().toPlainString()).append('\n');
        sb.append("TOTAL,DEDUCTIONS,Total deductions,").append(r.getEmployeeCount()).append(',').append(r.getTotalDeductions().toPlainString()).append('\n');
        sb.append("TOTAL,NET,Total net pay,").append(r.getEmployeeCount()).append(',').append(r.getTotalNet().toPlainString()).append('\n');
        sb.append("TOTAL,EMPLOYER,Total employer contributions,").append(r.getEmployeeCount()).append(',').append(r.getTotalEmployer().toPlainString()).append('\n');
        audit.log(AuditAction.EXPORT, ENTITY, runId, "Payroll posting summary exported for " + r.getPeriod());
        return new Export("payroll-posting-" + r.getPeriod() + ".csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ===================================================================== controlled adjustments

    @Transactional(readOnly = true)
    public List<AdjustmentView> adjustmentList(String status) {
        List<PayrollAdjustment> rows = status == null || status.isBlank() ? adjustments.findAllByOrderByIdDesc()
                : adjustments.findByStatusOrderByIdDesc(parseStatus(status));
        Set<Long> visible = lookup.visibleEmployeeIds();
        rows = rows.stream().filter(a -> visible == null || visible.contains(a.getEmployeeId())).toList();
        Map<Long, String> periods = runs.findAll().stream().collect(Collectors.toMap(PayrollRun::getId, PayrollRun::getPeriod));
        Map<Long, EmployeeBrief> briefs = lookup.briefs(rows.stream().map(PayrollAdjustment::getEmployeeId).toList());
        return rows.stream().map(a -> {
            EmployeeBrief b = briefs.get(a.getEmployeeId());
            return new AdjustmentView(a.getId(), a.getEmployeeId(), b == null ? null : b.employeeNo(),
                    b == null ? null : (masked(b) ? RESTRICTED_NAME : b.nameWithInitials()), a.getAdjustmentType().name(),
                    a.getCode(), a.getDescription(), a.getAmount(), a.getTargetPeriod(),
                    a.getRelatedRunId() == null ? null : periods.get(a.getRelatedRunId()), a.getReason(), a.getStatus().name(),
                    a.getAppliedRunId() == null ? null : periods.get(a.getAppliedRunId()), a.getCreatedBy(), a.getCreatedAt());
        }).toList();
    }

    @Transactional
    public PayrollAdjustment createAdjustment(AdjustmentRequest r) {
        if (r.employeeId() == null) throw ApiException.badRequest("Employee is required");
        Employee e = lookup.requireAccess(r.employeeId());
        PayrollAdjustment a = new PayrollAdjustment();
        a.setEmployeeId(e.getId());
        try {
            a.setAdjustmentType(PayrollAdjustment.Type.valueOf(required(r.adjustmentType(), "Adjustment type")));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Adjustment type must be EARNING or DEDUCTION");
        }
        a.setCode(code(r.code()));
        a.setDescription(truncate(required(r.description(), "Description"), 300));
        BigDecimal amt = money(r.amount(), "Amount");
        if (amt.signum() <= 0) throw ApiException.badRequest("Amount must be positive");
        a.setAmount(amt);
        period(r.targetPeriod());
        a.setTargetPeriod(r.targetPeriod());
        if (r.relatedRunId() != null) run(r.relatedRunId());
        a.setRelatedRunId(r.relatedRunId());
        a.setReason(truncate(required(r.reason(), "Reason"), 1000));
        a.setStatus(PayrollAdjustment.Status.OPEN);
        adjustments.save(a);
        audit.log(AuditAction.CREATE, "PayrollAdjustment", a.getId(), "Payroll adjustment " + a.getAdjustmentType() + " " + a.getCode()
                + " " + amt.toPlainString() + " for " + e.getEmployeeNo() + " (" + a.getTargetPeriod() + ")");
        return a;
    }

    @Transactional
    public PayrollAdjustment cancelAdjustment(Long id, String reason) {
        PayrollAdjustment a = adjustments.findById(id).orElseThrow(() -> ApiException.notFound("Payroll adjustment", id));
        lookup.requireAccess(a.getEmployeeId());
        if (a.getStatus() != PayrollAdjustment.Status.OPEN) throw ApiException.rule("NOT_OPEN", "Only open adjustments can be cancelled");
        boolean inSubmitted = runs.findAll().stream().filter(r -> r.getStatus() == PayrollRun.Status.SUBMITTED)
                .anyMatch(r -> lines.findByRunIdOrderByEmployeeNoAsc(r.getId()).stream()
                        .flatMap(l -> items.findByLineIdOrderBySortOrderAsc(l.getId()).stream())
                        .anyMatch(i -> i.getSourceType() == Source.ADJUSTMENT && id.equals(i.getSourceId())));
        if (inSubmitted) throw ApiException.rule("IN_PAYROLL", "The adjustment is included in a payroll run awaiting approval");
        a.setStatus(PayrollAdjustment.Status.CANCELLED);
        audit.log(AuditAction.CANCEL, "PayrollAdjustment", id, "Payroll adjustment cancelled" + (reason == null ? "" : ": " + reason));
        return a;
    }

    private static PayrollAdjustment.Status parseStatus(String s) {
        try {
            return PayrollAdjustment.Status.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid status");
        }
    }

    // ===================================================================== helpers

    @Transactional(readOnly = true)
    public Optional<PayrollRun> latestRun() {
        return runs.findTopByOrderByPeriodDesc();
    }

    private PayrollRun run(Long id) {
        return runs.findById(id).orElseThrow(() -> ApiException.notFound("Payroll run", id));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
