package lk.gov.ciaboc.hrms.leave;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.*;

/**
 * Leave balances per employee / type / year (FR-LEV-006). Balances are created on demand from the entitlement in force
 * for the employee's category; applications reserve (pending) and consume (used) days automatically.
 */
@Service
@RequiredArgsConstructor
public class LeaveBalanceService {
    private final LeaveBalanceRepository balances;
    private final LeaveBalanceAdjustmentRepository adjustments;
    private final LeaveEntitlementRepository entitlements;
    private final LeaveTypeRepository types;
    private final EmployeeLookup lookup;
    private final AuditService audit;
    private final NotificationService notifications;

    public record BalanceView(Long id, Long employeeId, Long leaveTypeId, String leaveTypeCode, String leaveTypeName, int year,
                             BigDecimal entitled, BigDecimal adjusted, BigDecimal used, BigDecimal pending,
                             BigDecimal available, EmployeeLookup.EmployeeBrief employee) {
    }

    public record AdjustRequest(Long employeeId, Long leaveTypeId, Integer year, BigDecimal days, String reason) {
    }

    public record InitialiseResult(int year, int created, int updated, int employees) {
    }

    /** Entitlement in force for the category during the year (latest version), or zero. */
    BigDecimal entitlementFor(Long leaveTypeId, String category, int year) {
        if (category == null) return BigDecimal.ZERO;
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);
        return entitlements.inForceDuring(leaveTypeId, category, from, to).stream().findFirst()
                .map(LeaveEntitlement::getDaysPerYear).orElse(BigDecimal.ZERO);
    }

    /** Returns the balance row, creating it from the entitlement if missing. */
    LeaveBalance getOrCreate(Long employeeId, LeaveType t, int year) {
        return balances.findByEmployeeIdAndLeaveTypeIdAndLeaveYear(employeeId, t.getId(), year).orElseGet(() -> {
            LeaveBalance b = new LeaveBalance();
            b.setEmployeeId(employeeId);
            b.setLeaveTypeId(t.getId());
            b.setLeaveYear(year);
            b.setEntitled(entitlementFor(t.getId(), lookup.require(employeeId).getEmployeeCategory(), year));
            return balances.saveAndFlush(b);
        });
    }

    /** Available days without creating a row. */
    BigDecimal available(Long employeeId, LeaveType t, int year) {
        return balances.findByEmployeeIdAndLeaveTypeIdAndLeaveYear(employeeId, t.getId(), year).map(LeaveBalance::available)
                .orElseGet(() -> entitlementFor(t.getId(), lookup.require(employeeId).getEmployeeCategory(), year));
    }

    void reserve(LeaveApplication a, LeaveType t) {
        if (!t.isBalanceTracked()) return;
        LeaveBalance b = getOrCreate(a.getEmployeeId(), t, a.getLeaveYear());
        if (b.available().compareTo(a.getDays()) < 0)
            throw ApiException.rule("INSUFFICIENT_BALANCE", "Insufficient " + t.getName() + " balance: " + b.available().stripTrailingZeros().toPlainString()
                    + " day(s) available for " + a.getLeaveYear() + ", " + a.getDays().stripTrailingZeros().toPlainString() + " requested");
        b.setPending(b.getPending().add(a.getDays()));
    }

    void releasePending(LeaveApplication a, LeaveType t) {
        if (!t.isBalanceTracked()) return;
        LeaveBalance b = getOrCreate(a.getEmployeeId(), t, a.getLeaveYear());
        b.setPending(b.getPending().subtract(a.getDays()).max(BigDecimal.ZERO));
    }

    void consume(LeaveApplication a, LeaveType t) {
        if (!t.isBalanceTracked()) return;
        LeaveBalance b = getOrCreate(a.getEmployeeId(), t, a.getLeaveYear());
        b.setPending(b.getPending().subtract(a.getDays()).max(BigDecimal.ZERO));
        b.setUsed(b.getUsed().add(a.getDays()));
    }

    void restoreUsed(LeaveApplication a, LeaveType t) {
        if (!t.isBalanceTracked()) return;
        LeaveBalance b = getOrCreate(a.getEmployeeId(), t, a.getLeaveYear());
        b.setUsed(b.getUsed().subtract(a.getDays()).max(BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------ queries

    /** Balances of one employee for a year: stored rows, or virtual rows from entitlements for types without one. */
    @Transactional(readOnly = true)
    public List<BalanceView> forEmployee(Long employeeId, int year) {
        Map<Long, LeaveBalance> stored = new HashMap<>();
        balances.findByEmployeeIdAndLeaveYear(employeeId, year).forEach(b -> stored.put(b.getLeaveTypeId(), b));
        String category = lookup.require(employeeId).getEmployeeCategory();
        List<BalanceView> out = new ArrayList<>();
        for (LeaveType t : types.findAllByOrderBySortOrderAscNameAsc()) {
            if (!t.isBalanceTracked()) continue;
            LeaveBalance b = stored.get(t.getId());
            if (b == null && !t.isActive()) continue;
            if (b == null) {
                BigDecimal ent = entitlementFor(t.getId(), category, year);
                out.add(new BalanceView(null, employeeId, t.getId(), t.getCode(), t.getName(), year, ent, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, ent, null));
            } else {
                out.add(view(b, t, null));
            }
        }
        return out;
    }

    /** Stored balances of the given employees (null = all) for a year. */
    @Transactional(readOnly = true)
    public List<BalanceView> list(int year, Collection<Long> employeeIds) {
        List<LeaveBalance> rows = employeeIds == null ? balances.findByLeaveYear(year)
                : employeeIds.isEmpty() ? List.of() : balances.findByLeaveYearAndEmployeeIdIn(year, employeeIds);
        Map<Long, LeaveType> typeById = new HashMap<>();
        types.findAll().forEach(t -> typeById.put(t.getId(), t));
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = lookup.briefs(rows.stream().map(LeaveBalance::getEmployeeId).distinct().toList());
        return rows.stream()
                .map(b -> view(b, typeById.get(b.getLeaveTypeId()), briefs.get(b.getEmployeeId())))
                .sorted(Comparator.comparing((BalanceView v) -> v.employee() == null ? "" : v.employee().employeeNo())
                        .thenComparing(v -> typeById.get(v.leaveTypeId()).getSortOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveBalanceAdjustment> adjustments(Long employeeId, Integer year) {
        return year == null ? adjustments.findByEmployeeIdOrderByIdDesc(employeeId)
                : adjustments.findByEmployeeIdAndLeaveYearOrderByIdDesc(employeeId, year);
    }

    private static BalanceView view(LeaveBalance b, LeaveType t, EmployeeLookup.EmployeeBrief e) {
        return new BalanceView(b.getId(), b.getEmployeeId(), b.getLeaveTypeId(), t == null ? null : t.getCode(),
                t == null ? null : t.getName(), b.getLeaveYear(), b.getEntitled(), b.getAdjusted(), b.getUsed(), b.getPending(),
                b.available(), e);
    }

    // ------------------------------------------------------------------ HR maintenance

    /**
     * Creates balances for every in-service employee and balance-tracked active type for the year, and refreshes the
     * entitled days of existing rows from the entitlement configuration (FR-LEV-006).
     */
    @Transactional
    public InitialiseResult initialise(int year) {
        validateYear(year);
        int created = 0, updated = 0;
        List<LeaveType> tracked = types.findByActiveTrueOrderBySortOrderAscNameAsc().stream().filter(LeaveType::isBalanceTracked).toList();
        List<EmployeeLookup.EmployeeBrief> staff = lookup.inService();
        for (EmployeeLookup.EmployeeBrief e : staff) {
            for (LeaveType t : tracked) {
                BigDecimal ent = entitlementFor(t.getId(), e.employeeCategory(), year);
                Optional<LeaveBalance> existing = balances.findByEmployeeIdAndLeaveTypeIdAndLeaveYear(e.id(), t.getId(), year);
                if (existing.isEmpty()) {
                    LeaveBalance b = new LeaveBalance();
                    b.setEmployeeId(e.id());
                    b.setLeaveTypeId(t.getId());
                    b.setLeaveYear(year);
                    b.setEntitled(ent);
                    balances.save(b);
                    log(b, LeaveBalanceAdjustment.Kind.INITIALISE, ent, "Initialised from entitlement for " + e.employeeCategory());
                    created++;
                } else if (existing.get().getEntitled().compareTo(ent) != 0) {
                    LeaveBalance b = existing.get();
                    BigDecimal delta = ent.subtract(b.getEntitled());
                    b.setEntitled(ent);
                    log(b, LeaveBalanceAdjustment.Kind.INITIALISE, delta, "Entitlement refreshed from configuration");
                    updated++;
                }
            }
        }
        audit.log(AuditAction.PROCESS, "LeaveBalance", year, "Leave balances initialised for " + year + ": " + created
                + " created, " + updated + " refreshed");
        return new InitialiseResult(year, created, updated, staff.size());
    }

    /** Manual adjustment by HR with a mandatory reason (audited, employee notified). */
    @Transactional
    public BalanceView adjust(AdjustRequest r) {
        if (r == null || r.employeeId() == null || r.leaveTypeId() == null) throw ApiException.badRequest("Employee and leave type are required");
        if (r.days() == null || r.days().signum() == 0) throw ApiException.badRequest("Adjustment days must be non-zero");
        if (r.days().remainder(new BigDecimal("0.5")).signum() != 0) throw ApiException.badRequest("Adjustments must be in half-day steps");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason for the adjustment is required");
        int year = r.year() == null ? Year.now().getValue() : r.year();
        validateYear(year);
        lookup.requireAccess(r.employeeId());
        LeaveType t = types.findById(r.leaveTypeId()).orElseThrow(() -> ApiException.notFound("Leave type", r.leaveTypeId()));
        if (!t.isBalanceTracked()) throw ApiException.rule("NOT_TRACKED", t.getName() + " does not use a balance");
        LeaveBalance b = getOrCreate(r.employeeId(), t, year);
        String before = snapshot(b);
        b.setAdjusted(b.getAdjusted().add(r.days()));
        if (b.available().signum() < 0)
            throw ApiException.rule("NEGATIVE_BALANCE", "The adjustment would leave a negative balance (used and pending days exceed the total)");
        log(b, LeaveBalanceAdjustment.Kind.ADJUST, r.days(), r.reason().trim());
        audit.log(AuditAction.UPDATE, "LeaveBalance", b.getId(), "Balance " + t.getCode() + " " + year + " adjusted by "
                + r.days().toPlainString() + ": " + r.reason().trim(), before, snapshot(b));
        notifications.notifyEmployee(r.employeeId(), "LEAVE_BALANCE_ADJUSTED", Map.of("leaveType", t.getName(), "year", year,
                "days", r.days().stripTrailingZeros().toPlainString(), "reason", r.reason().trim()), "/leave/my");
        return view(b, t, lookup.brief(r.employeeId()));
    }

    private void log(LeaveBalance b, LeaveBalanceAdjustment.Kind kind, BigDecimal days, String reason) {
        LeaveBalanceAdjustment a = new LeaveBalanceAdjustment();
        a.setBalanceId(b.getId());
        a.setEmployeeId(b.getEmployeeId());
        a.setLeaveTypeId(b.getLeaveTypeId());
        a.setLeaveYear(b.getLeaveYear());
        a.setKind(kind);
        a.setDays(days);
        a.setReason(reason);
        if (a.getBalanceId() == null) balances.flush();
        a.setBalanceId(b.getId());
        adjustments.save(a);
    }

    private static String snapshot(LeaveBalance b) {
        return "{\"entitled\":" + b.getEntitled() + ",\"adjusted\":" + b.getAdjusted() + ",\"used\":" + b.getUsed()
                + ",\"pending\":" + b.getPending() + "}";
    }

    private static void validateYear(int year) {
        int now = Year.now().getValue();
        if (year < now - 10 || year > now + 1) throw ApiException.badRequest("Year must be between " + (now - 10) + " and " + (now + 1));
    }
}
