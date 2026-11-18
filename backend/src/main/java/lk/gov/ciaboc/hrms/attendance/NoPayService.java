package lk.gov.ciaboc.hrms.attendance;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.WorkingDays;
import lk.gov.ciaboc.hrms.common.contracts.NoPaySource;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.leave.LeaveService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Monthly no-pay calculation from unauthorized absences, approved unpaid leave and (optionally) late arrivals,
 * according to configurable rules (system settings nopay.*). The result is reviewed, may be adjusted with a reason,
 * and is released to payroll only after approval through workflow NOPAY_APPROVAL (FR-ATT-021..023, FR-LEV-010).
 */
@Service
@RequiredArgsConstructor
public class NoPayService implements WorkflowHandler, NoPaySource {
    public static final String PROCESS = "NOPAY_APPROVAL";
    public static final String ENTITY = "NoPayRun";
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final NoPayRunRepository runs;
    private final NoPayLineRepository lines;
    private final AttendanceDailyRepository daily;
    private final AttendanceProcessingService processing;
    private final HolidayService holidays;
    private final LeaveService leave;
    private final EmployeeLookup lookup;
    private final SettingsService settings;
    private final WorkflowService workflow;
    private final NotificationService notifications;
    private final AuditService audit;

    public record PeriodRequest(String period) {
    }

    public record AdjustRequest(BigDecimal days, String reason) {
    }

    public record LineView(Long id, Long employeeId, EmployeeLookup.EmployeeBrief employee, BigDecimal absenceDays,
                           BigDecimal unpaidLeaveDays, int lateCount, BigDecimal lateDays, BigDecimal calculatedDays,
                           BigDecimal adjustedDays, String adjustmentReason, BigDecimal effectiveDays) {
    }

    public record RunView(NoPayRun run, List<LineView> lines) {
    }

    // ------------------------------------------------------------------ calculation

    @Transactional
    public RunView calculate(String periodText) {
        YearMonth period = parse(periodText);
        LocalDate today = processing.today();
        if (period.isAfter(YearMonth.from(today))) throw ApiException.rule("FUTURE_PERIOD", "No-pay cannot be calculated for a future month");
        NoPayRun run = runs.findByPeriod(period.toString()).orElse(null);
        if (run != null && (run.getStatus() == NoPayRun.Status.PENDING || run.getStatus() == NoPayRun.Status.APPROVED))
            throw ApiException.conflict("The no-pay for " + period + " is " + run.getStatus().name().toLowerCase()
                    + " and can no longer be recalculated");
        if (run == null) {
            run = new NoPayRun();
            run.setPeriod(period.toString());
        } else {
            lines.deleteByRunId(run.getId());
            lines.flush();
            run.setWorkflowInstanceId(run.getStatus() == NoPayRun.Status.RETURNED ? run.getWorkflowInstanceId() : null);
        }
        boolean countAbsence = settings.getBool("nopay.count-unauthorized-absence", true);
        BigDecimal absenceValue = settings.getDecimal("nopay.absence-day-value", BigDecimal.ONE);
        boolean includeUnpaid = settings.getBool("nopay.include-unpaid-leave", true);
        int latesPerHalf = Math.max(0, settings.getInt("nopay.late-arrivals-per-half-day", 0));

        LocalDate ms = period.atDay(1);
        LocalDate end = period.atEndOfMonth().isAfter(today) ? today : period.atEndOfMonth();
        Set<LocalDate> hol = holidays.holidaysBetween(ms, end);
        Set<LocalDate> processed = new HashSet<>(daily.processedDates(ms, end));
        int unprocessed = 0;
        for (LocalDate d = ms; !d.isAfter(end); d = d.plusDays(1))
            if (WorkingDays.isWorkingDay(d, hol) && !processed.contains(d)) unprocessed++;

        Map<Long, int[]> counts = new HashMap<>(); // [absent, late]
        for (AttendanceDaily r : daily.findByWorkDateBetween(ms, end)) {
            int[] c = counts.computeIfAbsent(r.getEmployeeId(), k -> new int[2]);
            if (r.getStatus() == AttendanceDaily.Status.ABSENT) c[0]++;
            if (r.getLateMinutes() > 0) c[1]++;
        }
        Map<Long, BigDecimal> unpaid = includeUnpaid ? leave.approvedUnpaidDays(period) : Map.of();
        Set<Long> emps = new TreeSet<>(counts.keySet());
        emps.addAll(unpaid.keySet());

        if (run.getId() == null) {
            run.setStatus(NoPayRun.Status.DRAFT);
            run.setCalculatedAt(Instant.now());
            runs.saveAndFlush(run);
        }
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (Long emp : emps) {
            int[] c = counts.getOrDefault(emp, new int[2]);
            BigDecimal absence = countAbsence ? absenceValue.multiply(BigDecimal.valueOf(c[0])) : BigDecimal.ZERO;
            BigDecimal late = latesPerHalf > 0 ? HALF.multiply(BigDecimal.valueOf(c[1] / latesPerHalf)) : BigDecimal.ZERO;
            BigDecimal up = unpaid.getOrDefault(emp, BigDecimal.ZERO);
            BigDecimal sum = absence.add(late).add(up);
            if (sum.signum() <= 0) continue;
            NoPayLine l = new NoPayLine();
            l.setRunId(run.getId());
            l.setEmployeeId(emp);
            l.setAbsenceDays(absence);
            l.setUnpaidLeaveDays(up);
            l.setLateCount(c[1]);
            l.setLateDays(late);
            l.setCalculatedDays(sum);
            lines.save(l);
            total = total.add(sum);
            count++;
        }
        run.setStatus(run.getStatus() == NoPayRun.Status.RETURNED ? NoPayRun.Status.RETURNED : NoPayRun.Status.DRAFT);
        run.setCalculatedAt(Instant.now());
        run.setCalculatedBy(CurrentUser.username());
        run.setUnprocessedDays(unprocessed);
        run.setTotalDays(total);
        run.setEmployeeCount(count);
        run.setApprovedAt(null);
        run.setRulesSnapshot("count-unauthorized-absence=" + countAbsence + "; absence-day-value=" + absenceValue.toPlainString()
                + "; include-unpaid-leave=" + includeUnpaid + "; late-arrivals-per-half-day=" + latesPerHalf);
        audit.log(AuditAction.PROCESS, ENTITY, run.getId(), "No-pay calculated for " + period + ": " + count + " employees, "
                + total.toPlainString() + " days" + (unprocessed > 0 ? " (" + unprocessed + " working days not processed)" : ""));
        return view(run);
    }

    @Transactional
    public LineView adjust(Long runId, Long lineId, AdjustRequest r) {
        NoPayRun run = run(runId);
        if (run.getStatus() != NoPayRun.Status.DRAFT && run.getStatus() != NoPayRun.Status.RETURNED)
            throw ApiException.rule("NOT_EDITABLE", "Only draft or returned no-pay calculations can be adjusted");
        NoPayLine l = lines.findById(lineId).filter(x -> x.getRunId().equals(runId))
                .orElseThrow(() -> ApiException.notFound("No-pay line", lineId));
        lookup.requireAccess(l.getEmployeeId());
        if (r == null || r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason for the adjustment is required");
        if (r.days() != null && (r.days().signum() < 0 || r.days().remainder(HALF).signum() != 0 || r.days().compareTo(BigDecimal.valueOf(31)) > 0))
            throw ApiException.badRequest("Days must be between 0 and 31 in half-day steps");
        String before = l.effectiveDays().toPlainString();
        l.setAdjustedDays(r.days());
        l.setAdjustmentReason(r.reason().trim());
        run.setTotalDays(lines.findByRunIdOrderByIdAsc(runId).stream().map(NoPayLine::effectiveDays).reduce(BigDecimal.ZERO, BigDecimal::add));
        audit.log(AuditAction.UPDATE, "NoPayLine", lineId, "No-pay " + run.getPeriod() + " adjusted for employee " + l.getEmployeeId()
                + ": " + r.reason().trim(), before, l.effectiveDays().toPlainString());
        return line(l, lookup.brief(l.getEmployeeId()));
    }

    @Transactional
    public RunView submit(Long runId) {
        NoPayRun run = run(runId);
        if (run.getStatus() == NoPayRun.Status.DRAFT) {
            WorkflowInstance wi = workflow.start(PROCESS, ENTITY, run.getId(), "No-pay for " + run.getPeriod() + " ("
                    + run.getEmployeeCount() + " employees, " + run.getTotalDays().stripTrailingZeros().toPlainString() + " days)",
                    "/attendance/nopay?period=" + run.getPeriod(), null);
            run.setWorkflowInstanceId(wi.getId());
        } else if (run.getStatus() == NoPayRun.Status.RETURNED) {
            workflow.resubmit(run.getWorkflowInstanceId(), "Recalculated / reviewed");
        } else {
            throw ApiException.rule("NOT_SUBMITTABLE", "Only draft or returned calculations can be submitted (recalculate a rejected one first)");
        }
        run.setStatus(NoPayRun.Status.PENDING);
        return view(run);
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<NoPayRun> runs() {
        return runs.findAllByOrderByPeriodDesc();
    }

    @Transactional(readOnly = true)
    public RunView byPeriod(String period) {
        return view(runs.findByPeriod(parse(period).toString()).orElseThrow(() -> ApiException.notFound("No-pay calculation", period)));
    }

    /** Released (approved) no-pay days per employee for payroll (FR-ATT-022). */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> approvedNoPayDays(YearMonth period) {
        return runs.findByPeriod(period.toString()).filter(r -> r.getStatus() == NoPayRun.Status.APPROVED)
                .map(r -> {
                    Map<Long, BigDecimal> m = new LinkedHashMap<>();
                    for (NoPayLine l : lines.findByRunIdOrderByIdAsc(r.getId()))
                        if (l.effectiveDays().signum() > 0) m.put(l.getEmployeeId(), l.effectiveDays());
                    return m;
                }).orElse(Map.of());
    }

    // ------------------------------------------------------------------ workflow callbacks

    @Override
    public String processCode() {
        return PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        NoPayRun r = run(wi.getEntityId());
        r.setStatus(NoPayRun.Status.APPROVED);
        r.setApprovedAt(Instant.now());
        audit.log(AuditAction.UPDATE, ENTITY, r.getId(), "No-pay for " + r.getPeriod() + " approved and released to payroll");
        notifications.notifyRole("FINANCE_OFFICER", "NOPAY_RELEASED", Map.of("period", r.getPeriod(),
                "employees", r.getEmployeeCount(), "days", r.getTotalDays().stripTrailingZeros().toPlainString()), "/reports");
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        run(wi.getEntityId()).setStatus(NoPayRun.Status.REJECTED);
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        run(wi.getEntityId()).setStatus(NoPayRun.Status.RETURNED);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        run(wi.getEntityId()).setStatus(NoPayRun.Status.PENDING);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        NoPayRun r = run(wi.getEntityId());
        r.setStatus(NoPayRun.Status.DRAFT);
        r.setWorkflowInstanceId(null);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        NoPayRun r = run(wi.getEntityId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Period", r.getPeriod());
        m.put("Employees", r.getEmployeeCount());
        m.put("Total no-pay days", r.getTotalDays().stripTrailingZeros().toPlainString());
        m.put("Working days without processed attendance", r.getUnprocessedDays());
        m.put("Calculated", r.getCalculatedAt() + " by " + r.getCalculatedBy());
        m.put("Rules applied", r.getRulesSnapshot());
        long adjusted = lines.findByRunIdOrderByIdAsc(r.getId()).stream().filter(l -> l.getAdjustedDays() != null).count();
        m.put("Manually adjusted lines", adjusted);
        m.put("Details", "Open Attendance > No-pay for the employee breakdown");
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private RunView view(NoPayRun r) {
        Set<Long> visible = lookup.visibleEmployeeIds();
        List<NoPayLine> ls = lines.findByRunIdOrderByIdAsc(r.getId()).stream()
                .filter(l -> visible == null || visible.contains(l.getEmployeeId())).toList();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = lookup.briefs(ls.stream().map(NoPayLine::getEmployeeId).toList());
        return new RunView(r, ls.stream().map(l -> line(l, briefs.get(l.getEmployeeId())))
                .sorted(Comparator.comparing(v -> v.employee() == null ? "" : v.employee().employeeNo())).toList());
    }

    private static LineView line(NoPayLine l, EmployeeLookup.EmployeeBrief b) {
        return new LineView(l.getId(), l.getEmployeeId(), b, l.getAbsenceDays(), l.getUnpaidLeaveDays(), l.getLateCount(), l.getLateDays(),
                l.getCalculatedDays(), l.getAdjustedDays(), l.getAdjustmentReason(), l.effectiveDays());
    }

    private NoPayRun run(Long id) {
        return runs.findById(id).orElseThrow(() -> ApiException.notFound("No-pay calculation", id));
    }

    private static YearMonth parse(String p) {
        if (p == null || p.isBlank()) throw ApiException.badRequest("Period (YYYY-MM) is required");
        try {
            return YearMonth.parse(p.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Period must be in the form YYYY-MM");
        }
    }
}
