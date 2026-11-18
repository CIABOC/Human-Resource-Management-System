package lk.gov.ciaboc.hrms.attendance;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Manual attendance corrections (FR-ATT-019..020): a reason is mandatory, the change is applied only after approval
 * through workflow ATTENDANCE_CORRECTION, and it is applied as MANUAL events that supersede device events of the same
 * direction for that day - the original events are kept for audit.
 */
@Service
@RequiredArgsConstructor
public class AttendanceCorrectionService implements WorkflowHandler {
    public static final String PROCESS = "ATTENDANCE_CORRECTION";
    public static final String ENTITY = "AttendanceCorrection";
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final Set<AttendanceCorrection.Status> OPEN = EnumSet.of(AttendanceCorrection.Status.PENDING, AttendanceCorrection.Status.RETURNED);

    private final AttendanceCorrectionRepository corrections;
    private final AttendanceEventRepository events;
    private final AttendanceProcessingService processing;
    private final EmployeeLookup lookup;
    private final WorkflowService workflow;
    private final SettingsService settings;
    private final AuditService audit;

    public record CorrectionRequest(Long employeeId, LocalDate workDate, LocalTime checkIn, LocalTime checkOut, String reason) {
    }

    public record CorrectionView(Long id, Long employeeId, EmployeeLookup.EmployeeBrief employee, LocalDate workDate,
                                 LocalTime checkIn, LocalTime checkOut, String reason, String status, Long workflowInstanceId,
                                 Instant createdAt, String createdBy) {
    }

    @Transactional
    public CorrectionView request(CorrectionRequest r) {
        if (r == null) throw ApiException.badRequest("Correction details are required");
        UserPrincipal me = CurrentUser.get();
        Long emp = r.employeeId() == null ? CurrentUser.employeeId() : r.employeeId();
        if (!emp.equals(me.getEmployeeId())) {
            if (!me.has(Permission.ATT_MANAGE)) throw ApiException.forbidden("You may only request corrections of your own attendance");
            lookup.requireAccess(emp);
        } else if (!me.has(Permission.SELF_SERVICE) && !me.has(Permission.ATT_MANAGE)) {
            throw ApiException.forbidden("You are not authorized to request attendance corrections");
        }
        LocalDate d = r.workDate();
        if (d == null) throw ApiException.badRequest("Date is required");
        LocalDate today = processing.today();
        if (d.isAfter(today)) throw ApiException.rule("FUTURE_DATE", "Corrections cannot be requested for future dates");
        int max = settings.getInt("attendance.correction-max-days", 31);
        if (ChronoUnit.DAYS.between(d, today) > max)
            throw ApiException.rule("TOO_OLD", "Corrections can be requested for at most " + max + " days back");
        if (r.checkIn() == null && r.checkOut() == null) throw ApiException.badRequest("Enter a check-in and/or check-out time");
        if (r.checkIn() != null && r.checkOut() != null && !r.checkOut().isAfter(r.checkIn()))
            throw ApiException.badRequest("Check-out must be after check-in");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason for the correction is required");
        if (r.reason().length() > 1000) throw ApiException.badRequest("Reason is too long (maximum 1000 characters)");
        if (corrections.existsByEmployeeIdAndWorkDateAndStatusIn(emp, d, OPEN))
            throw ApiException.conflict("A correction for this employee and date is already awaiting approval");
        EmployeeLookup.EmployeeBrief b = lookup.brief(emp);
        if (b == null) throw ApiException.notFound("Employee", emp);
        AttendanceCorrection c = new AttendanceCorrection();
        c.setEmployeeId(emp);
        c.setWorkDate(d);
        c.setCheckIn(r.checkIn());
        c.setCheckOut(r.checkOut());
        c.setReason(r.reason().trim());
        c.setStatus(AttendanceCorrection.Status.PENDING);
        corrections.save(c);
        WorkflowInstance wi = workflow.start(PROCESS, ENTITY, c.getId(), "Attendance correction " + d.format(D) + " - " + b.nameWithInitials(),
                "/attendance/corrections", emp);
        c.setWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.CREATE, ENTITY, c.getId(), "Attendance correction requested for " + b.employeeNo() + " on " + d
                + " (in " + r.checkIn() + ", out " + r.checkOut() + "): " + c.getReason());
        return view(c, b);
    }

    @Transactional(readOnly = true)
    public List<CorrectionView> mine() {
        Long emp = CurrentUser.employeeId();
        EmployeeLookup.EmployeeBrief b = lookup.brief(emp);
        return corrections.findByEmployeeIdOrderByWorkDateDesc(emp).stream().map(c -> view(c, b)).toList();
    }

    @Transactional(readOnly = true)
    public List<CorrectionView> list(String status) {
        Set<Long> visible = lookup.visibleEmployeeIds();
        List<AttendanceCorrection> rows;
        if (status == null || status.isBlank()) rows = corrections.findAllByOrderByIdDesc();
        else {
            try {
                rows = corrections.findByStatusOrderByIdDesc(AttendanceCorrection.Status.valueOf(status.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown status: " + status);
            }
        }
        rows = rows.stream().filter(c -> visible == null || visible.contains(c.getEmployeeId())).limit(500).toList();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = lookup.briefs(rows.stream().map(AttendanceCorrection::getEmployeeId).distinct().toList());
        return rows.stream().map(c -> view(c, briefs.get(c.getEmployeeId()))).toList();
    }

    // ------------------------------------------------------------------ workflow callbacks

    @Override
    public String processCode() {
        return PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        AttendanceCorrection c = get(wi.getEntityId());
        EmployeeLookup.EmployeeBrief b = lookup.brief(c.getEmployeeId());
        ZoneId zone = processing.zone();
        Instant now = Instant.now();
        for (AttendanceEvent e : events.findByEmployeeIdAndWorkDateOrderByEventTimeAsc(c.getEmployeeId(), c.getWorkDate())) {
            boolean replaced = (e.getDirection() == AttendanceEvent.Direction.IN && c.getCheckIn() != null)
                    || (e.getDirection() == AttendanceEvent.Direction.OUT && c.getCheckOut() != null);
            if (replaced && e.getReviewStatus() != AttendanceEvent.ReviewStatus.REJECTED && e.getReviewStatus() != AttendanceEvent.ReviewStatus.SUPERSEDED) {
                e.setReviewStatus(AttendanceEvent.ReviewStatus.SUPERSEDED);
                e.setReviewComment("Superseded by approved correction #" + c.getId());
                e.setReviewedBy(CurrentUser.username());
                e.setReviewedAt(now);
            }
        }
        if (c.getCheckIn() != null) manualEvent(c, b, AttendanceEvent.Direction.IN, c.getCheckIn(), zone);
        if (c.getCheckOut() != null) manualEvent(c, b, AttendanceEvent.Direction.OUT, c.getCheckOut(), zone);
        events.flush();
        c.setStatus(AttendanceCorrection.Status.APPROVED);
        processing.processEmployeeDay(c.getEmployeeId(), c.getWorkDate());
        audit.log(AuditAction.UPDATE, ENTITY, c.getId(), "Attendance correction applied for " + b.employeeNo() + " on " + c.getWorkDate());
    }

    private void manualEvent(AttendanceCorrection c, EmployeeLookup.EmployeeBrief b, AttendanceEvent.Direction dir, LocalTime t, ZoneId zone) {
        AttendanceEvent e = new AttendanceEvent();
        e.setEmployeeId(c.getEmployeeId());
        e.setEmployeeNo(b.employeeNo());
        e.setEventTime(c.getWorkDate().atTime(t).atZone(zone).toInstant());
        e.setWorkDate(c.getWorkDate());
        e.setDirection(dir);
        e.setSource(AttendanceEvent.Source.MANUAL);
        e.setBatchRef("CORR-" + c.getId());
        e.setCorrectionId(c.getId());
        events.save(e);
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        get(wi.getEntityId()).setStatus(AttendanceCorrection.Status.REJECTED);
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        get(wi.getEntityId()).setStatus(AttendanceCorrection.Status.RETURNED);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        get(wi.getEntityId()).setStatus(AttendanceCorrection.Status.PENDING);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        get(wi.getEntityId()).setStatus(AttendanceCorrection.Status.CANCELLED);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        AttendanceCorrection c = get(wi.getEntityId());
        EmployeeLookup.EmployeeBrief b = lookup.brief(c.getEmployeeId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Employee", b.employeeNo() + " - " + b.nameWithInitials());
        m.put("Unit", b.orgUnitName());
        m.put("Date", c.getWorkDate());
        m.put("Corrected check-in", c.getCheckIn() == null ? "(unchanged)" : c.getCheckIn().toString());
        m.put("Corrected check-out", c.getCheckOut() == null ? "(unchanged)" : c.getCheckOut().toString());
        List<String> recorded = events.findByEmployeeIdAndWorkDateOrderByEventTimeAsc(c.getEmployeeId(), c.getWorkDate()).stream()
                .filter(e -> e.getSource() != AttendanceEvent.Source.MANUAL)
                .map(e -> e.getDirection() + " " + e.getEventTime().atZone(processing.zone()).toLocalTime().withNano(0)
                        + (e.isFlagged() ? " (flagged)" : "")).toList();
        m.put("Recorded events", recorded.isEmpty() ? "None" : String.join(", ", recorded));
        m.put("Reason", c.getReason());
        return m;
    }

    private AttendanceCorrection get(Long id) {
        return corrections.findById(id).orElseThrow(() -> ApiException.notFound("Attendance correction", id));
    }

    private static CorrectionView view(AttendanceCorrection c, EmployeeLookup.EmployeeBrief b) {
        return new CorrectionView(c.getId(), c.getEmployeeId(), b, c.getWorkDate(), c.getCheckIn(), c.getCheckOut(), c.getReason(),
                c.getStatus().name(), c.getWorkflowInstanceId(), c.getCreatedAt(), c.getCreatedBy());
    }
}
