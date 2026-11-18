package lk.gov.ciaboc.hrms.extended.separation;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeAssignment;
import lk.gov.ciaboc.hrms.employee.EmployeeAssignmentRepository;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeService;
import lk.gov.ciaboc.hrms.extended.ExtSupport;
import lk.gov.ciaboc.hrms.extended.separation.SeparationEntities.*;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.RoleRepository;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static lk.gov.ciaboc.hrms.extended.ExtSupport.*;

/**
 * Separation cases with a configurable clearance checklist and final-settlement inputs (FR-EXT-002). Completion
 * calls {@link EmployeeService#changeStatus}, which closes the placement and deactivates the user account (access
 * removal; performed through SSO once it is connected).
 */
@Service
@RequiredArgsConstructor
public class SeparationService {
    static final String ENTITY = "SeparationCase";

    private final SeparationCaseRepository cases;
    private final ClearanceRepository clearances;
    private final SeparationTypeRepository types;
    private final ClearanceTemplateRepository templates;
    private final EmployeeLookup lookup;
    private final EmployeeService employees;
    private final EmployeeAssignmentRepository assignments;
    private final MasterDataService masterData;
    private final SettingsService settings;
    private final RoleRepository roles;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ExtSupport support;

    // ===================================================================== DTOs

    public record CaseInput(Long employeeId, String separationType, String separationReason, LocalDate separationDate,
                            String remarks) {
    }

    public record SettlementInput(BigDecimal amount, String notes) {
    }

    public record ItemAction(String remarks) {
    }

    public record CaseSummary(Long id, EmployeeLookup.EmployeeBrief employee, String separationType, String separationReason,
                              String targetStatus, LocalDate separationDate, String status, int itemsTotal, int itemsDone,
                              boolean settlementRecorded, Instant createdAt, String createdBy, Instant completedAt) {
    }

    public record CaseDetail(CaseSummary summary, String remarks, List<Clearance> items, BigDecimal settlementAmount,
                             String settlementNotes, String settlementBy, Instant settlementAt, boolean settlementRequired,
                             String settlementRole, String completedBy, String cancelReason, boolean canComplete,
                             List<String> blockers) {
    }

    public record TaskView(Long itemId, Long caseId, String itemCode, String itemName, String responsibleRole,
                           EmployeeLookup.EmployeeBrief employee, LocalDate separationDate, String separationType) {
    }

    public record TemplateInput(String code, String nameEn, String nameSi, String nameTa, String description,
                                String responsibleRole, String appliesTo, Integer sortOrder, Boolean active) {
    }

    // ===================================================================== configuration

    @Transactional(readOnly = true)
    public List<SeparationType> types() {
        return types.findAllByOrderBySortOrderAscNameEnAsc();
    }

    @Transactional(readOnly = true)
    public List<ClearanceItemTemplate> templates() {
        return templates.findAllByOrderBySortOrderAscCodeAsc();
    }

    @Transactional
    public ClearanceItemTemplate saveTemplate(Long id, TemplateInput in) {
        ClearanceItemTemplate t;
        if (id == null) {
            if (in.code() == null || !in.code().matches("^[A-Z0-9_]{1,40}$"))
                throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits or underscores");
            if (templates.existsByCode(in.code())) throw ApiException.conflict("Checklist item " + in.code() + " already exists");
            t = new ClearanceItemTemplate();
            t.setCode(in.code());
        } else {
            t = templates.findById(id).orElseThrow(() -> ApiException.notFound("Checklist item", id));
        }
        String before = id == null ? null : t.getNameEn() + "|" + t.getResponsibleRole() + "|" + t.isActive();
        require(in.nameEn(), "English name");
        require(in.responsibleRole(), "Responsible role");
        roles.findByCode(in.responsibleRole()).orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Role " + in.responsibleRole() + " does not exist"));
        if (!blank(in.appliesTo())) {
            for (String s : in.appliesTo().split(","))
                types.findByCode(s.trim()).orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Unknown separation type " + s.trim()));
        }
        maxLength(in.nameEn(), 200, "Name");
        maxLength(in.description(), 500, "Description");
        t.setNameEn(in.nameEn().trim());
        t.setNameSi(trimToNull(in.nameSi()));
        t.setNameTa(trimToNull(in.nameTa()));
        t.setDescription(trimToNull(in.description()));
        t.setResponsibleRole(in.responsibleRole());
        t.setAppliesTo(trimToNull(in.appliesTo()));
        t.setSortOrder(in.sortOrder() == null ? 0 : in.sortOrder());
        t.setActive(in.active() == null || in.active());
        templates.save(t);
        audit.log(id == null ? AuditAction.CREATE : AuditAction.UPDATE, "ClearanceItemTemplate", t.getId(),
                "Clearance checklist item " + t.getCode() + " saved", before, t.getNameEn() + "|" + t.getResponsibleRole() + "|" + t.isActive());
        return t;
    }

    // ===================================================================== cases

    @Transactional(readOnly = true)
    public List<CaseSummary> list(SeparationCase.Status status) {
        Set<Long> visible = support.visibleIds();
        List<SeparationCase> rows = cases.findAllByOrderByIdDesc().stream()
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> visible(visible, c.getEmployeeId())).toList();
        Map<Long, List<Clearance>> items = new HashMap<>();
        if (!rows.isEmpty())
            clearances.findByCaseIdIn(rows.stream().map(SeparationCase::getId).toList())
                    .forEach(i -> items.computeIfAbsent(i.getCaseId(), k -> new ArrayList<>()).add(i));
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = support.briefs(rows.stream().map(SeparationCase::getEmployeeId).toList());
        return rows.stream().map(c -> summary(c, briefs.get(c.getEmployeeId()), items.getOrDefault(c.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public CaseDetail get(Long id) {
        SeparationCase c = caseOf(id);
        UserPrincipal me = CurrentUser.get();
        boolean ext = me.has(Permission.EXT_VIEW) || me.has(Permission.EXT_MANAGE);
        boolean involved = me.hasRole(settlementRole()) || clearances.findByCaseIdOrderBySortOrderAscIdAsc(c.getId()).stream()
                .anyMatch(i -> me.hasRole(i.getResponsibleRole()));
        if (!ext && !involved) throw ApiException.notFound("Separation case", id);
        lookup.requireAccess(c.getEmployeeId());
        return detail(c);
    }

    @Transactional
    public CaseDetail open(CaseInput in) {
        if (in.employeeId() == null) throw ApiException.badRequest("Employee is required");
        Employee e = lookup.requireAccess(in.employeeId());
        if (!inService(e)) throw ApiException.rule("SEPARATED", "Employee is not in service");
        if (CurrentUser.get().getEmployeeId() != null && CurrentUser.get().getEmployeeId().equals(e.getId()))
            throw ApiException.rule("SEGREGATION_OF_DUTIES", "You cannot open a separation case for yourself");
        if (cases.findFirstByEmployeeIdAndStatus(e.getId(), SeparationCase.Status.OPEN).isPresent())
            throw ApiException.rule("OPEN_CASE", "This employee already has an open separation case");
        require(in.separationType(), "Separation type");
        SeparationType type = types.findByCode(in.separationType()).filter(SeparationType::isActive)
                .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Separation type " + in.separationType() + " is not active"));
        String reason = blank(in.separationReason()) ? type.getDefaultSeparationReason() : in.separationReason();
        masterData.requireActive(MasterDataCategory.SEPARATION_REASON, reason, "Separation reason");
        masterData.requireActive(MasterDataCategory.EMPLOYEE_STATUS, type.getEmployeeStatus(), "Employee status");
        if (in.separationDate() == null) throw ApiException.badRequest("Separation date is required");
        checkSeparationDate(e.getId(), in.separationDate());
        maxLength(in.remarks(), 1000, "Remarks");

        SeparationCase c = new SeparationCase();
        c.setEmployeeId(e.getId());
        c.setSeparationType(type.getCode());
        c.setSeparationReason(reason);
        c.setTargetStatus(type.getEmployeeStatus());
        c.setSeparationDate(in.separationDate());
        c.setRemarks(trimToNull(in.remarks()));
        c.setStatus(SeparationCase.Status.OPEN);
        cases.save(c);

        List<ClearanceItemTemplate> applicable = templates.findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .filter(t -> t.appliesTo(type.getCode())).toList();
        Set<String> notifiedRoles = new LinkedHashSet<>();
        for (ClearanceItemTemplate t : applicable) {
            Clearance i = new Clearance();
            i.setCaseId(c.getId());
            i.setItemCode(t.getCode());
            i.setItemName(t.getNameEn());
            i.setResponsibleRole(t.getResponsibleRole());
            i.setSortOrder(t.getSortOrder());
            i.setStatus(Clearance.Status.PENDING);
            clearances.save(i);
            if (notifiedRoles.add(t.getResponsibleRole() + "|" + t.getCode())) {
                Map<String, Object> vars = new HashMap<>();
                vars.put("employeeNo", e.getEmployeeNo());
                vars.put("employeeName", e.getNameWithInitials());
                vars.put("item", t.getNameEn());
                vars.put("separationDate", c.getSeparationDate());
                notifications.notifyRole(t.getResponsibleRole(), "EXT_CLEARANCE_PENDING", vars, "/extended/clearance");
            }
        }
        audit.log(AuditAction.CREATE, ENTITY, c.getId(), "Separation case opened for " + e.getEmployeeNo() + " (" + type.getCode()
                + ", " + c.getSeparationDate() + ") with " + applicable.size() + " clearance item(s)");
        return detail(c);
    }

    /** Clearance items awaiting action by a role the current user holds, within the user's data scope. */
    @Transactional(readOnly = true)
    public List<TaskView> myTasks() {
        UserPrincipal me = CurrentUser.get();
        if (me.getRoles().isEmpty()) return List.of();
        List<Clearance> items = clearances.findByStatusAndResponsibleRoleIn(Clearance.Status.PENDING, me.getRoles());
        if (items.isEmpty()) return List.of();
        Map<Long, SeparationCase> byId = new HashMap<>();
        cases.findAllById(items.stream().map(Clearance::getCaseId).distinct().toList()).forEach(c -> byId.put(c.getId(), c));
        Set<Long> visible = support.visibleIds();
        List<Clearance> mine = items.stream().filter(i -> {
            SeparationCase c = byId.get(i.getCaseId());
            return c != null && c.getStatus() == SeparationCase.Status.OPEN && visible(visible, c.getEmployeeId())
                    && !c.getEmployeeId().equals(me.getEmployeeId());
        }).toList();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = support.briefs(mine.stream().map(i -> byId.get(i.getCaseId()).getEmployeeId()).toList());
        return mine.stream().map(i -> {
            SeparationCase c = byId.get(i.getCaseId());
            return new TaskView(i.getId(), c.getId(), i.getItemCode(), i.getItemName(), i.getResponsibleRole(),
                    briefs.get(c.getEmployeeId()), c.getSeparationDate(), c.getSeparationType());
        }).sorted(Comparator.comparing(TaskView::separationDate)).toList();
    }

    /** Clears an item; only holders of the item's responsible role may do so (and never for their own case). */
    @Transactional
    public CaseDetail clear(Long itemId, ItemAction a) {
        Clearance i = item(itemId);
        SeparationCase c = openCase(i.getCaseId());
        UserPrincipal me = CurrentUser.get();
        if (!me.hasRole(i.getResponsibleRole()))
            throw ApiException.forbidden("Only a " + i.getResponsibleRole().replace('_', ' ').toLowerCase() + " can clear this item");
        lookup.requireAccess(c.getEmployeeId());
        notSelf(c);
        if (i.getStatus() != Clearance.Status.PENDING) throw ApiException.rule("ALREADY_CLEARED", "This item is already " + i.getStatus().name().toLowerCase());
        maxLength(a == null ? null : a.remarks(), 1000, "Remarks");
        i.setStatus(Clearance.Status.CLEARED);
        i.setRemarks(a == null ? null : trimToNull(a.remarks()));
        i.setClearedBy(me.getUsername());
        i.setClearedAt(Instant.now());
        audit.log(AuditAction.APPROVE, "SeparationClearance", i.getId(), "Cleared '" + i.getItemName() + "' for case #" + c.getId());
        return detail(c);
    }

    /** HR may waive an item that does not apply (e.g. no library membership); a reason is mandatory. */
    @Transactional
    public CaseDetail waive(Long itemId, ItemAction a) {
        Clearance i = item(itemId);
        SeparationCase c = openCase(i.getCaseId());
        lookup.requireAccess(c.getEmployeeId());
        notSelf(c);
        if (a == null || blank(a.remarks())) throw ApiException.badRequest("A reason is required to waive a clearance item");
        maxLength(a.remarks(), 1000, "Remarks");
        if (i.getStatus() != Clearance.Status.PENDING) throw ApiException.rule("ALREADY_CLEARED", "This item is already " + i.getStatus().name().toLowerCase());
        i.setStatus(Clearance.Status.WAIVED);
        i.setRemarks(a.remarks().trim());
        i.setClearedBy(CurrentUser.username());
        i.setClearedAt(Instant.now());
        audit.log(AuditAction.UPDATE, "SeparationClearance", i.getId(), "Waived '" + i.getItemName() + "' for case #" + c.getId() + ": " + a.remarks().trim());
        return detail(c);
    }

    /** Final-settlement inputs recorded by Finance (the settlement role is a system setting). */
    @Transactional
    public CaseDetail settlement(Long caseId, SettlementInput in) {
        SeparationCase c = openCase(caseId);
        String role = settlementRole();
        if (!CurrentUser.get().hasRole(role)) throw ApiException.forbidden("Only the " + role.replace('_', ' ').toLowerCase() + " role can record the final settlement");
        lookup.requireAccess(c.getEmployeeId());
        notSelf(c);
        if (in == null || in.amount() == null) throw ApiException.badRequest("Settlement amount is required (use 0.00 if nothing is payable)");
        require(in.notes(), "Settlement notes");
        if (in.amount().abs().compareTo(new BigDecimal("1000000000")) >= 0) throw ApiException.badRequest("Settlement amount is out of range");
        String before = c.getSettlementAmount() == null ? null : c.getSettlementAmount().toPlainString();
        c.setSettlementAmount(in.amount().setScale(2, RoundingMode.HALF_UP));
        c.setSettlementNotes(in.notes().trim());
        c.setSettlementBy(CurrentUser.username());
        c.setSettlementAt(Instant.now());
        audit.log(AuditAction.UPDATE, ENTITY, c.getId(), "Final settlement recorded", before, c.getSettlementAmount().toPlainString());
        return detail(c);
    }

    @Transactional
    public CaseDetail complete(Long caseId) {
        SeparationCase c = openCase(caseId);
        lookup.requireAccess(c.getEmployeeId());
        notSelf(c);
        List<String> blockers = blockers(c, clearances.findByCaseIdOrderBySortOrderAscIdAsc(c.getId()));
        if (!blockers.isEmpty()) throw ApiException.rule("CLEARANCE_INCOMPLETE", String.join("; ", blockers));
        employees.changeStatus(c.getEmployeeId(), new EmployeeService.StatusRequest(c.getTargetStatus(), c.getSeparationDate(),
                c.getSeparationReason(), "Separation case #" + c.getId() + (c.getRemarks() == null ? "" : ": " + c.getRemarks())));
        c.setStatus(SeparationCase.Status.COMPLETED);
        c.setCompletedBy(CurrentUser.username());
        c.setCompletedAt(Instant.now());
        audit.log(AuditAction.UPDATE, ENTITY, c.getId(), "Separation completed: status " + c.getTargetStatus()
                + " from " + c.getSeparationDate() + "; user access removed");
        return detail(c);
    }

    @Transactional
    public CaseDetail cancel(Long caseId, ItemAction a) {
        SeparationCase c = openCase(caseId);
        lookup.requireAccess(c.getEmployeeId());
        if (a == null || blank(a.remarks())) throw ApiException.badRequest("A reason is required to cancel a separation case");
        maxLength(a.remarks(), 500, "Reason");
        c.setStatus(SeparationCase.Status.CANCELLED);
        c.setCancelReason(a.remarks().trim());
        audit.log(AuditAction.CANCEL, ENTITY, c.getId(), "Separation case cancelled: " + a.remarks().trim());
        return detail(c);
    }

    // ===================================================================== helpers

    private void checkSeparationDate(Long employeeId, LocalDate d) {
        assignments.findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(employeeId)
                .map(EmployeeAssignment::getEffectiveFrom)
                .filter(from -> !d.isAfter(from))
                .ifPresent(from -> {
                    throw ApiException.rule("EFFECTIVE_DATE", "Separation date must be after the current placement start (" + from + ")");
                });
    }

    private List<String> blockers(SeparationCase c, List<Clearance> items) {
        List<String> out = new ArrayList<>();
        long pending = items.stream().filter(i -> i.getStatus() == Clearance.Status.PENDING).count();
        if (pending > 0) out.add(pending + " clearance item(s) still pending");
        if (settlementRequired() && c.getSettlementAt() == null) out.add("Final settlement has not been recorded by Finance");
        return out;
    }

    private boolean settlementRequired() {
        return settings.getBool("ext.separation.require-settlement", true);
    }

    private String settlementRole() {
        return settings.get("ext.separation.settlement-role", "FINANCE_OFFICER");
    }

    private void notSelf(SeparationCase c) {
        Long me = CurrentUser.get().getEmployeeId();
        if (me != null && me.equals(c.getEmployeeId()))
            throw ApiException.rule("SEGREGATION_OF_DUTIES", "You cannot act on your own separation case");
    }

    private SeparationCase caseOf(Long id) {
        return cases.findById(id).orElseThrow(() -> ApiException.notFound("Separation case", id));
    }

    private SeparationCase openCase(Long id) {
        SeparationCase c = caseOf(id);
        if (c.getStatus() != SeparationCase.Status.OPEN) throw ApiException.rule("CASE_CLOSED", "This separation case is " + c.getStatus().name().toLowerCase());
        return c;
    }

    private Clearance item(Long id) {
        return clearances.findById(id).orElseThrow(() -> ApiException.notFound("Clearance item", id));
    }

    private CaseSummary summary(SeparationCase c, EmployeeLookup.EmployeeBrief b, List<Clearance> items) {
        int done = (int) items.stream().filter(i -> i.getStatus() != Clearance.Status.PENDING).count();
        return new CaseSummary(c.getId(), b, c.getSeparationType(), c.getSeparationReason(), c.getTargetStatus(),
                c.getSeparationDate(), c.getStatus().name(), items.size(), done, c.getSettlementAt() != null,
                c.getCreatedAt(), c.getCreatedBy(), c.getCompletedAt());
    }

    private CaseDetail detail(SeparationCase c) {
        List<Clearance> items = clearances.findByCaseIdOrderBySortOrderAscIdAsc(c.getId());
        List<String> blockers = c.getStatus() == SeparationCase.Status.OPEN ? blockers(c, items) : List.of();
        UserPrincipal me = CurrentUser.get();
        boolean canManage = me.has(Permission.EXT_MANAGE);
        // Settlement figures are shown only to HR (EXT_VIEW/EXT_MANAGE) and the settlement role, not to other clearers.
        boolean seeSettlement = canManage || me.has(Permission.EXT_VIEW) || me.hasRole(settlementRole());
        return new CaseDetail(summary(c, lookup.brief(c.getEmployeeId()), items), c.getRemarks(), items,
                seeSettlement ? c.getSettlementAmount() : null, seeSettlement ? c.getSettlementNotes() : null,
                c.getSettlementBy(), c.getSettlementAt(),
                settlementRequired(), settlementRole(), c.getCompletedBy(), c.getCancelReason(),
                canManage && c.getStatus() == SeparationCase.Status.OPEN && blockers.isEmpty(), blockers);
    }
}
