package lk.gov.ciaboc.hrms.leave;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.Json;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.masterdata.MasterDataUsageChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Leave types and effective-dated entitlements (FR-LEV-001, -002). */
@Service
@RequiredArgsConstructor
public class LeaveConfigService implements MasterDataUsageChecker {
    private final LeaveTypeRepository types;
    private final LeaveEntitlementRepository entitlements;
    private final MasterDataService masterData;
    private final AuditService audit;

    public record LeaveTypeRequest(String code, String name, String nameSi, String nameTa, String description, Boolean paid,
                                   Boolean balanceTracked, Boolean requiresReason, Boolean requiresDocument,
                                   Integer documentAfterDays, Integer minNoticeDays, Integer maxConsecutiveDays,
                                   Boolean allowBackdated, Boolean halfDayAllowed, Integer sortOrder, Boolean active) {
    }

    public record EntitlementRequest(Long leaveTypeId, String employeeCategory, BigDecimal daysPerYear, LocalDate effectiveFrom,
                                     String remarks) {
    }

    @Transactional(readOnly = true)
    public List<LeaveType> types(boolean activeOnly) {
        return activeOnly ? types.findByActiveTrueOrderBySortOrderAscNameAsc() : types.findAllByOrderBySortOrderAscNameAsc();
    }

    public LeaveType type(Long id) {
        return types.findById(id).orElseThrow(() -> ApiException.notFound("Leave type", id));
    }

    @Transactional
    public LeaveType createType(LeaveTypeRequest r) {
        if (r == null || r.code() == null || !r.code().matches("^[A-Z0-9_-]{1,40}$"))
            throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits, '-' or '_'");
        if (types.existsByCode(r.code())) throw ApiException.conflict("Leave type code " + r.code() + " already exists");
        LeaveType t = new LeaveType();
        t.setCode(r.code());
        copy(t, r);
        types.save(t);
        audit.log(AuditAction.CREATE, "LeaveType", t.getId(), "Leave type " + t.getCode() + " created", null, Json.write(r));
        return t;
    }

    @Transactional
    public LeaveType updateType(Long id, LeaveTypeRequest r) {
        LeaveType t = type(id);
        String before = Json.write(t);
        copy(t, r);
        audit.log(AuditAction.CONFIG_CHANGE, "LeaveType", id, "Leave type " + t.getCode() + " updated", before, Json.write(r));
        return t;
    }

    private static void copy(LeaveType t, LeaveTypeRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Leave type name is required");
        int notice = r.minNoticeDays() == null ? 0 : r.minNoticeDays();
        if (notice < 0) throw ApiException.badRequest("Minimum notice days cannot be negative");
        if (r.maxConsecutiveDays() != null && r.maxConsecutiveDays() <= 0)
            throw ApiException.badRequest("Maximum consecutive days must be positive (leave empty for no limit)");
        if (r.documentAfterDays() != null && r.documentAfterDays() < 0)
            throw ApiException.badRequest("Document threshold cannot be negative");
        t.setName(r.name().trim());
        t.setNameSi(r.nameSi());
        t.setNameTa(r.nameTa());
        t.setDescription(r.description());
        t.setPaid(r.paid() == null || r.paid());
        t.setBalanceTracked(r.balanceTracked() == null || r.balanceTracked());
        t.setRequiresReason(Boolean.TRUE.equals(r.requiresReason()));
        t.setRequiresDocument(Boolean.TRUE.equals(r.requiresDocument()));
        t.setDocumentAfterDays(r.documentAfterDays());
        t.setMinNoticeDays(notice);
        t.setMaxConsecutiveDays(r.maxConsecutiveDays());
        t.setAllowBackdated(Boolean.TRUE.equals(r.allowBackdated()));
        t.setHalfDayAllowed(Boolean.TRUE.equals(r.halfDayAllowed()));
        t.setSortOrder(r.sortOrder() == null ? 0 : r.sortOrder());
        t.setActive(r.active() == null || r.active());
    }

    // ------------------------------------------------------------------ entitlements

    @Transactional(readOnly = true)
    public List<LeaveEntitlement> entitlements(Long leaveTypeId, LocalDate asOf) {
        if (leaveTypeId != null) return entitlements.findByLeaveTypeIdOrderByEmployeeCategoryAscEffectiveFromDesc(leaveTypeId);
        return entitlements.effectiveOn(asOf == null ? LocalDate.now() : asOf);
    }

    /** Sets the entitlement from a date: the version in force is closed and a new one starts (history kept). */
    @Transactional
    public LeaveEntitlement setEntitlement(EntitlementRequest r) {
        if (r == null || r.leaveTypeId() == null) throw ApiException.badRequest("Leave type is required");
        LeaveType t = type(r.leaveTypeId());
        if (r.employeeCategory() == null || r.employeeCategory().isBlank()) throw ApiException.badRequest("Employee category is required");
        masterData.requireActive(MasterDataCategory.EMPLOYEE_CATEGORY, r.employeeCategory(), "Employee category");
        if (r.daysPerYear() == null || r.daysPerYear().signum() < 0 || r.daysPerYear().compareTo(BigDecimal.valueOf(366)) > 0)
            throw ApiException.badRequest("Days per year must be between 0 and 366");
        if (r.daysPerYear().remainder(new BigDecimal("0.5")).signum() != 0)
            throw ApiException.badRequest("Days per year must be in half-day steps");
        LocalDate d = r.effectiveFrom() == null ? LocalDate.now() : r.effectiveFrom();
        if (entitlements.countStartingAfter(t.getId(), r.employeeCategory(), d) > 0)
            throw ApiException.rule("FUTURE_VERSION", "A later entitlement version already exists for this category");
        String before = null;
        var cur = entitlements.effectiveOn(t.getId(), r.employeeCategory(), d);
        if (cur.isPresent()) {
            before = Json.write(cur.get());
            if (cur.get().getEffectiveFrom().equals(d)) {
                entitlements.delete(cur.get());
            } else {
                cur.get().setEffectiveTo(d);
            }
            entitlements.flush();
        }
        LeaveEntitlement e = new LeaveEntitlement();
        e.setLeaveTypeId(t.getId());
        e.setEmployeeCategory(r.employeeCategory());
        e.setDaysPerYear(r.daysPerYear());
        e.setEffectiveFrom(d);
        e.setRemarks(r.remarks());
        entitlements.save(e);
        audit.log(AuditAction.CONFIG_CHANGE, "LeaveEntitlement", e.getId(), "Entitlement " + t.getCode() + "/" + r.employeeCategory()
                + " set to " + r.daysPerYear() + " days from " + d, before, Json.write(r));
        return e;
    }

    @Override
    public long usageCount(String category, String code) {
        if (!MasterDataCategory.EMPLOYEE_CATEGORY.name().equals(category)) return 0;
        return entitlements.countByEmployeeCategory(code);
    }
}
