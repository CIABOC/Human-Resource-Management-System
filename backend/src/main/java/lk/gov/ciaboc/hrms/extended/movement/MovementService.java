package lk.gov.ciaboc.hrms.extended.movement;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeAssignment;
import lk.gov.ciaboc.hrms.employee.EmployeeAssignmentRepository;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeService;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.establishment.Position;
import lk.gov.ciaboc.hrms.extended.ExtSupport;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static lk.gov.ciaboc.hrms.extended.ExtSupport.*;

/**
 * Transfers, promotions and confirmations (FR-EXT-001). Requests are raised by HR, validated against the approved
 * cadre, routed through the TRANSFER / PROMOTION / SERVICE_CONFIRMATION approval chains and, once fully approved,
 * applied through {@link EmployeeService} so placement and service history stay consistent.
 */
@Service
@RequiredArgsConstructor
public class MovementService {
    public static final String P_TRANSFER = "TRANSFER";
    public static final String P_PROMOTION = "PROMOTION";
    public static final String P_CONFIRMATION = "SERVICE_CONFIRMATION";
    public static final String ENTITY = "MovementRequest";
    static final Set<MovementRequest.Status> OPEN = EnumSet.of(MovementRequest.Status.PENDING, MovementRequest.Status.RETURNED);
    private static final String BLOCKING_SETTING = "ext.movement.cadre-blocking";

    private final MovementRequestRepository repo;
    private final EmployeeLookup lookup;
    private final EmployeeService employees;
    private final EmployeeAssignmentRepository assignments;
    private final EstablishmentService establishment;
    private final ExtSupport support;
    private final SettingsService settings;
    private final WorkflowService workflow;
    private final NotificationService notifications;
    private final AuditService audit;

    // ===================================================================== DTOs

    public record MovementInput(MovementRequest.Type requestType, Long employeeId, String targetOrgUnitCode,
                                Long targetDesignationId, Long targetPositionId, LocalDate effectiveDate, String reason,
                                String reference) {
    }

    public record CadreCheck(boolean applicable, boolean vacancyAvailable, boolean blocking, int approved, long occupied,
                             long otherPending, long available, String message) {
    }

    public record MovementView(Long id, String requestType, EmployeeLookup.EmployeeBrief employee,
                               String fromOrgUnitCode, String fromOrgUnitName, String fromDesignation, String fromPosition,
                               String targetOrgUnitCode, String targetOrgUnitName, Long targetDesignationId,
                               String targetDesignation, Long targetPositionId, String targetPosition,
                               LocalDate effectiveDate, String reason, String reference, String cadreWarning,
                               String status, Long workflowInstanceId, Instant appliedAt, String createdBy,
                               Instant createdAt) {
    }

    // ===================================================================== queries

    @Transactional(readOnly = true)
    public List<MovementView> list(MovementRequest.Status status, MovementRequest.Type type, Long employeeId) {
        Set<Long> visible = support.visibleIds();
        List<MovementRequest> rows = (employeeId == null ? repo.findAllByOrderByIdDesc() : repo.findByEmployeeIdOrderByIdDesc(employeeId))
                .stream().filter(m -> visible(visible, m.getEmployeeId()))
                .filter(m -> status == null || m.getStatus() == status)
                .filter(m -> type == null || m.getRequestType() == type)
                .toList();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = support.briefs(rows.stream().map(MovementRequest::getEmployeeId).toList());
        return rows.stream().map(m -> view(m, briefs.get(m.getEmployeeId()))).toList();
    }

    @Transactional(readOnly = true)
    public MovementView get(Long id) {
        MovementRequest m = request(id);
        lookup.requireAccess(m.getEmployeeId());
        return view(m, lookup.brief(m.getEmployeeId()));
    }

    /** Cadre check preview for the request form. */
    @Transactional(readOnly = true)
    public CadreCheck preview(MovementInput in) {
        if (in.requestType() == null || in.employeeId() == null) throw ApiException.badRequest("Request type and employee are required");
        Employee e = lookup.requireAccess(in.employeeId());
        if (in.requestType() == MovementRequest.Type.CONFIRMATION) return notApplicable();
        String unit = in.targetOrgUnitCode() == null || in.targetOrgUnitCode().isBlank() ? e.getOrgUnitCode() : in.targetOrgUnitCode();
        Long des = in.targetDesignationId() == null ? e.getDesignationId() : in.targetDesignationId();
        return cadre(des, unit, in.effectiveDate() == null ? LocalDate.now() : in.effectiveDate(), -1L);
    }

    // ===================================================================== commands

    @Transactional
    public MovementView create(MovementInput in) {
        if (in.requestType() == null) throw ApiException.badRequest("Request type is required");
        if (in.employeeId() == null) throw ApiException.badRequest("Employee is required");
        Employee e = lookup.requireAccess(in.employeeId());
        if (repo.existsByEmployeeIdAndStatusIn(e.getId(), OPEN))
            throw ApiException.rule("OPEN_REQUEST", "This employee already has a transfer, promotion or confirmation request in progress");
        MovementRequest m = new MovementRequest();
        m.setRequestType(in.requestType());
        m.setEmployeeId(e.getId());
        fill(m, e, in);
        m.setStatus(MovementRequest.Status.PENDING);
        repo.save(m);
        WorkflowInstance wi = workflow.start(processFor(m.getRequestType()), ENTITY, m.getId(), title(m, e),
                "/extended/movements/" + m.getId(), e.getId());
        m.setWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.CREATE, ENTITY, m.getId(), title(m, e), null, snapshot(m));
        return view(m, lookup.brief(e.getId()));
    }

    /** Corrects a request returned by an approver; the requester then resubmits it from the approvals screen. */
    @Transactional
    public MovementView revise(Long id, MovementInput in) {
        MovementRequest m = request(id);
        if (m.getStatus() != MovementRequest.Status.RETURNED)
            throw ApiException.rule("NOT_RETURNED", "Only requests returned for correction can be revised");
        if (!CurrentUser.username().equals(m.getCreatedBy())) throw ApiException.forbidden("Only the requester can revise this request");
        Employee e = lookup.requireAccess(m.getEmployeeId());
        Map<String, Object> before = snapshot(m);
        fill(m, e, new MovementInput(m.getRequestType(), m.getEmployeeId(), in.targetOrgUnitCode(), in.targetDesignationId(),
                in.targetPositionId(), in.effectiveDate(), in.reason(), in.reference()));
        audit.log(AuditAction.UPDATE, ENTITY, id, "Revised: " + title(m, e), before, snapshot(m));
        return view(m, lookup.brief(e.getId()));
    }

    private void fill(MovementRequest m, Employee e, MovementInput in) {
        if (!inService(e)) throw ApiException.rule("SEPARATED", "Employee is not in service");
        require(in.reason(), "Reason");
        maxLength(in.reason(), 1000, "Reason");
        maxLength(in.reference(), 100, "Reference");
        if (in.effectiveDate() == null) throw ApiException.badRequest("Proposed effective date is required");
        m.setFromOrgUnitCode(e.getOrgUnitCode());
        m.setFromDesignationId(e.getDesignationId());
        m.setFromPositionId(e.getPositionId());
        m.setEffectiveDate(in.effectiveDate());
        m.setReason(in.reason().trim());
        m.setReference(trimToNull(in.reference()));
        if (m.getRequestType() == MovementRequest.Type.CONFIRMATION) {
            m.setTargetOrgUnitCode(null);
            m.setTargetDesignationId(null);
            m.setTargetPositionId(null);
        } else {
            m.setTargetOrgUnitCode(blank(in.targetOrgUnitCode()) ? e.getOrgUnitCode() : in.targetOrgUnitCode().trim());
            m.setTargetDesignationId(in.targetDesignationId() == null ? e.getDesignationId() : in.targetDesignationId());
            m.setTargetPositionId(in.targetPositionId());
        }
        m.setCadreWarning(validate(m, e));
    }

    /** Validates the request against the employee, establishment and cadre; returns a cadre warning or null. */
    private String validate(MovementRequest m, Employee e) {
        LocalDate d = m.getEffectiveDate();
        if (m.getRequestType() == MovementRequest.Type.CONFIRMATION) {
            if (e.getConfirmationDate() != null)
                throw ApiException.rule("ALREADY_CONFIRMED", "Employee was already confirmed on " + e.getConfirmationDate());
            if (e.getAppointmentDate() != null && d.isBefore(e.getAppointmentDate()))
                throw ApiException.rule("EFFECTIVE_DATE", "Confirmation date cannot be before the appointment date");
            return null;
        }
        support.activeUnit(m.getTargetOrgUnitCode(), d);
        support.activeDesignation(m.getTargetDesignationId());
        if (m.getRequestType() == MovementRequest.Type.TRANSFER && Objects.equals(m.getTargetOrgUnitCode(), e.getOrgUnitCode())
                && Objects.equals(m.getTargetPositionId(), e.getPositionId()))
            throw ApiException.rule("NO_CHANGE", "A transfer must move the employee to another unit or position");
        if (m.getRequestType() == MovementRequest.Type.PROMOTION && Objects.equals(m.getTargetDesignationId(), e.getDesignationId()))
            throw ApiException.rule("NO_CHANGE", "A promotion must be to a different designation");
        if (m.getTargetPositionId() != null) {
            Position p = support.position(m.getTargetPositionId());
            if (!p.isActive()) throw ApiException.rule("INVALID_REFERENCE", "Position is not active");
            if (!p.getOrgUnitCode().equals(m.getTargetOrgUnitCode()) || !p.getDesignationId().equals(m.getTargetDesignationId()))
                throw ApiException.rule("POSITION_MISMATCH", "Position " + p.getCode() + " does not belong to the selected unit and designation");
        }
        assignments.findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(e.getId())
                .map(EmployeeAssignment::getEffectiveFrom)
                .filter(from -> !d.isAfter(from))
                .ifPresent(from -> {
                    throw ApiException.rule("EFFECTIVE_DATE", "Effective date must be after the current placement start (" + from + ")");
                });
        CadreCheck c = cadre(m.getTargetDesignationId(), m.getTargetOrgUnitCode(), d, m.getId() == null ? -1L : m.getId());
        if (!c.vacancyAvailable() && c.blocking()) throw ApiException.rule("NO_VACANCY", c.message());
        return c.vacancyAvailable() ? null : c.message();
    }

    private CadreCheck cadre(Long designationId, String unit, LocalDate d, Long excludeId) {
        boolean blocking = settings.getBool(BLOCKING_SETTING, false);
        var row = establishment.cadreSummary(d, null).stream()
                .filter(r -> Objects.equals(r.designationId(), designationId) && Objects.equals(r.orgUnitCode(), unit))
                .findFirst();
        int approved = row.map(EstablishmentService.CadreRow::approved).orElse(0);
        long occupied = row.map(EstablishmentService.CadreRow::occupied).orElse(0L);
        long pending = designationId == null ? 0 : repo.countOpenTargeting(OPEN, designationId, unit, excludeId, MovementRequest.Type.CONFIRMATION);
        long available = approved - occupied - pending;
        boolean ok = available > 0;
        String msg = ok ? "Vacancy available (" + available + " of " + approved + " approved)"
                : "No approved vacancy for " + support.designationName(designationId) + " in " + support.unitName(unit)
                + " (approved " + approved + ", occupied " + occupied + ", other pending requests " + pending + ")";
        return new CadreCheck(true, ok, blocking, approved, occupied, pending, available, msg);
    }

    private static CadreCheck notApplicable() {
        return new CadreCheck(false, true, false, 0, 0, 0, 0, "Cadre check does not apply to confirmations");
    }

    // ===================================================================== workflow callbacks (see MovementWorkflowHandlers)

    @Transactional
    public void onApproved(WorkflowInstance wi) {
        MovementRequest m = request(wi.getEntityId());
        Employee e = lookup.require(m.getEmployeeId());
        if (!inService(e)) throw ApiException.rule("SEPARATED", "Employee is no longer in service");
        // Re-validate at the moment of approval: cadre or placement may have changed since submission.
        String warning = validate(m, e);
        m.setCadreWarning(warning);
        String details;
        switch (m.getRequestType()) {
            case TRANSFER, PROMOTION -> {
                employees.assign(e.getId(), new EmployeeService.AssignRequest(m.getTargetOrgUnitCode(), m.getTargetDesignationId(),
                        m.getTargetPositionId(), m.getEffectiveDate(), m.getRequestType().name(), m.getReference(),
                        abbreviate(m.getReason(), 300)));
                details = support.designationName(m.getTargetDesignationId()) + ", " + support.unitName(m.getTargetOrgUnitCode());
            }
            case CONFIRMATION -> {
                confirm(e, m);
                details = "Confirmed in service as " + support.designationName(e.getDesignationId());
            }
            default -> throw ApiException.badRequest("Unsupported request type");
        }
        m.setStatus(MovementRequest.Status.APPLIED);
        m.setAppliedAt(Instant.now());
        audit.log(AuditAction.UPDATE, ENTITY, m.getId(), "Applied: " + title(m, e));
        Map<String, Object> vars = new HashMap<>();
        vars.put("type", m.getRequestType().name().toLowerCase());
        vars.put("effectiveDate", m.getEffectiveDate());
        vars.put("details", details);
        notifications.notifyEmployee(e.getId(), "EXT_MOVEMENT_APPLIED", vars, null);
    }

    /** Records confirmation in service: confirmation date, service event and (from probation) the ACTIVE status. */
    private void confirm(Employee e, MovementRequest m) {
        LocalDate d = m.getEffectiveDate();
        LocalDate old = e.getConfirmationDate();
        e.setConfirmationDate(d);
        if ("PROBATION".equals(e.getStatus())) {
            employees.changeStatus(e.getId(), new EmployeeService.StatusRequest("ACTIVE", d, null, "Confirmed in service"));
        }
        employees.event(e.getId(), "CONFIRMATION", d, "Confirmed in service as " + support.designationName(e.getDesignationId()),
                m.getReference());
        audit.log(AuditAction.UPDATE, "Employee", e.getId(), "Confirmation date set to " + d + " (request #" + m.getId() + ")", old, d);
    }

    @Transactional
    public void setStatus(WorkflowInstance wi, MovementRequest.Status status) {
        request(wi.getEntityId()).setStatus(status);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(WorkflowInstance wi) {
        MovementRequest m = request(wi.getEntityId());
        Employee e = lookup.require(m.getEmployeeId());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("Employee", e.getEmployeeNo() + " — " + e.getNameWithInitials());
        s.put("Request type", m.getRequestType().name().charAt(0) + m.getRequestType().name().substring(1).toLowerCase());
        s.put("Current placement", support.designationName(m.getFromDesignationId()) + ", " + support.unitName(m.getFromOrgUnitCode()));
        if (m.getRequestType() != MovementRequest.Type.CONFIRMATION) {
            s.put("Proposed placement", support.designationName(m.getTargetDesignationId()) + ", " + support.unitName(m.getTargetOrgUnitCode())
                    + (m.getTargetPositionId() == null ? "" : " (" + support.positionTitle(m.getTargetPositionId()) + ")"));
            CadreCheck c = cadre(m.getTargetDesignationId(), m.getTargetOrgUnitCode(), m.getEffectiveDate(), m.getId());
            s.put("Cadre check", c.message());
        } else {
            s.put("Appointment date", e.getAppointmentDate());
        }
        s.put("Proposed effective date", m.getEffectiveDate());
        s.put("Reason", m.getReason());
        if (m.getReference() != null) s.put("Reference", m.getReference());
        return s;
    }

    // ===================================================================== helpers

    MovementRequest request(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Movement request", id));
    }

    static String processFor(MovementRequest.Type t) {
        return switch (t) {
            case TRANSFER -> P_TRANSFER;
            case PROMOTION -> P_PROMOTION;
            case CONFIRMATION -> P_CONFIRMATION;
        };
    }

    private String title(MovementRequest m, Employee e) {
        String who = e.getEmployeeNo() + " " + e.getNameWithInitials();
        return switch (m.getRequestType()) {
            case TRANSFER -> "Transfer: " + who + " → " + support.unitName(m.getTargetOrgUnitCode());
            case PROMOTION -> "Promotion: " + who + " → " + support.designationName(m.getTargetDesignationId());
            case CONFIRMATION -> "Confirmation in service: " + who;
        };
    }

    private MovementView view(MovementRequest m, EmployeeLookup.EmployeeBrief b) {
        return new MovementView(m.getId(), m.getRequestType().name(), b, m.getFromOrgUnitCode(), support.unitName(m.getFromOrgUnitCode()),
                support.designationName(m.getFromDesignationId()), support.positionTitle(m.getFromPositionId()),
                m.getTargetOrgUnitCode(), support.unitName(m.getTargetOrgUnitCode()), m.getTargetDesignationId(),
                support.designationName(m.getTargetDesignationId()), m.getTargetPositionId(), support.positionTitle(m.getTargetPositionId()),
                m.getEffectiveDate(), m.getReason(), m.getReference(), m.getCadreWarning(), m.getStatus().name(),
                m.getWorkflowInstanceId(), m.getAppliedAt(), m.getCreatedBy(), m.getCreatedAt());
    }

    private static Map<String, Object> snapshot(MovementRequest m) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", m.getRequestType());
        s.put("employeeId", m.getEmployeeId());
        s.put("targetOrgUnitCode", m.getTargetOrgUnitCode());
        s.put("targetDesignationId", m.getTargetDesignationId());
        s.put("targetPositionId", m.getTargetPositionId());
        s.put("effectiveDate", String.valueOf(m.getEffectiveDate()));
        s.put("reason", m.getReason());
        s.put("reference", m.getReference());
        s.put("cadreWarning", m.getCadreWarning());
        return s;
    }

    private static String abbreviate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
