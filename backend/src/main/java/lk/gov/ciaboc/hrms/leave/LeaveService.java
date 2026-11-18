package lk.gov.ciaboc.hrms.leave;

import jakarta.persistence.criteria.Predicate;
import lk.gov.ciaboc.hrms.attendance.HolidayService;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.Json;
import lk.gov.ciaboc.hrms.common.PageResponse;
import lk.gov.ciaboc.hrms.common.WorkingDays;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeRepository;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.files.StoredFile;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Leave applications (FR-LEV-003..010): validation against balance, overlaps, notice and maximum-length rules;
 * approval through workflow process LEAVE; balances maintained automatically; cancellation of approved leave through
 * process LEAVE_CANCEL ({@link LeaveCancellationHandler}).
 */
@Service
@RequiredArgsConstructor
public class LeaveService implements WorkflowHandler {
    public static final String PROCESS = "LEAVE";
    public static final String CANCEL_PROCESS = "LEAVE_CANCEL";
    public static final String ENTITY = "LeaveApplication";
    public static final String CANCEL_ENTITY = "LeaveCancellation";
    public static final String FILE_MODULE = "LEAVE";
    private static final Set<LeaveApplication.Status> HOLDING =
            EnumSet.of(LeaveApplication.Status.PENDING, LeaveApplication.Status.RETURNED, LeaveApplication.Status.APPROVED);
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final LeaveApplicationRepository apps;
    private final LeaveTypeRepository types;
    private final LeaveBalanceService balances;
    private final EmployeeLookup lookup;
    private final EmployeeRepository employees;
    private final EstablishmentService establishment;
    private final WorkflowService workflow;
    private final AuditService audit;
    private final SettingsService settings;
    private final FileStorageService files;
    private final HolidayService holidays;
    private final ApplicationEventPublisher events;

    // ===================================================================== payloads

    public record ApplyRequest(Long leaveTypeId, LocalDate startDate, LocalDate endDate, Boolean halfDay,
                               String halfDaySession, String reason, String contactDuringLeave, Long documentFileId) {
    }

    public record ApplicationView(Long id, Long employeeId, EmployeeLookup.EmployeeBrief employee, Long leaveTypeId,
                                  String leaveTypeCode, String leaveTypeName, boolean paid, int leaveYear, LocalDate startDate,
                                  LocalDate endDate, boolean halfDay, String halfDaySession, BigDecimal days, String reason,
                                  String contactDuringLeave, boolean hasDocument, String status, Long workflowInstanceId,
                                  String cancellationStatus, String cancellationReason, Long cancelWorkflowInstanceId,
                                  Instant createdAt, Instant decidedAt) {
    }

    public record PreviewResult(BigDecimal days, boolean balanceTracked, BigDecimal available, boolean documentRequired,
                                boolean valid, String problem) {
    }

    public record CalendarEntry(Long applicationId, Long employeeId, String employeeNo, String employeeName,
                                String orgUnitCode, String orgUnitName, String leaveTypeCode, String leaveTypeName,
                                LocalDate startDate, LocalDate endDate, boolean halfDay, BigDecimal days, String status) {
    }

    public record TeamCalendar(LocalDate from, LocalDate to, List<CalendarEntry> entries, Set<LocalDate> holidays) {
    }

    /** Approved leave for consumers such as attendance. */
    public record LeaveDay(Long applicationId, Long employeeId, LocalDate startDate, LocalDate endDate, boolean halfDay,
                           String leaveTypeCode, boolean paid) {
        public boolean covers(LocalDate d) {
            return !d.isBefore(startDate) && !d.isAfter(endDate);
        }
    }

    public record SearchFilter(String status, Long employeeId, Long leaveTypeId, LocalDate from, LocalDate to,
                               String orgUnitCode, int page, int size) {
    }

    public record DocumentRef(Long fileId, String name) {
    }

    private record Checked(LeaveType type, BigDecimal days, int year) {
    }

    // ===================================================================== self service

    @Transactional
    public DocumentRef uploadDocument(MultipartFile file) {
        CurrentUser.employeeId();
        StoredFile f = files.store(file, FILE_MODULE);
        audit.log(AuditAction.UPLOAD, "LeaveDocument", f.getId(), "Supporting document uploaded: " + f.getOriginalName());
        return new DocumentRef(f.getId(), f.getOriginalName());
    }

    @Transactional
    public ApplicationView apply(ApplyRequest r) {
        Long emp = CurrentUser.employeeId();
        requireInService(emp);
        Checked c = check(emp, r, null, null);
        LeaveApplication a = new LeaveApplication();
        a.setEmployeeId(emp);
        fill(a, r, c);
        a.setStatus(LeaveApplication.Status.PENDING);
        balances.reserve(a, c.type());
        apps.save(a);
        EmployeeLookup.EmployeeBrief me = lookup.brief(emp);
        WorkflowInstance wi = workflow.start(PROCESS, ENTITY, a.getId(), title(a, c.type(), me), "/leave/applications/" + a.getId(), emp);
        a.setWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.CREATE, ENTITY, a.getId(), "Leave applied: " + describe(a, c.type()), null, Json.write(r));
        return view(a, c.type(), me);
    }

    /** Corrects an application returned by an approver and resubmits it. */
    @Transactional
    public ApplicationView revise(Long id, ApplyRequest r) {
        LeaveApplication a = owned(id);
        if (a.getStatus() != LeaveApplication.Status.RETURNED)
            throw ApiException.rule("NOT_RETURNED", "Only applications returned for correction can be revised");
        if (r != null && r.documentFileId() == null && a.getDocumentFileId() != null) {
            // Keep the previously attached document unless a new one is supplied.
            r = new ApplyRequest(r.leaveTypeId(), r.startDate(), r.endDate(), r.halfDay(), r.halfDaySession(), r.reason(),
                    r.contactDuringLeave(), a.getDocumentFileId());
        }
        Checked c = check(a.getEmployeeId(), r, a.getId(), a.getDocumentFileId());
        LeaveType oldType = type(a.getLeaveTypeId());
        String before = describe(a, oldType);
        balances.releasePending(a, oldType);
        fill(a, r, c);
        balances.reserve(a, c.type());
        workflow.resubmit(a.getWorkflowInstanceId(), "Revised by applicant");
        audit.log(AuditAction.UPDATE, ENTITY, a.getId(), "Leave application revised: " + describe(a, c.type()), before, Json.write(r));
        return view(a, c.type(), lookup.brief(a.getEmployeeId()));
    }

    /** Withdraws an application that has not been decided (cancels its approval request). */
    @Transactional
    public ApplicationView withdraw(Long id, String comment) {
        LeaveApplication a = owned(id);
        if (a.getStatus() != LeaveApplication.Status.PENDING && a.getStatus() != LeaveApplication.Status.RETURNED)
            throw ApiException.rule("NOT_OPEN", "Only pending or returned applications can be withdrawn; request cancellation of approved leave instead");
        workflow.cancel(a.getWorkflowInstanceId(), comment == null || comment.isBlank() ? "Withdrawn by applicant" : comment);
        return view(a, type(a.getLeaveTypeId()), lookup.brief(a.getEmployeeId()));
    }

    /** Requests cancellation of approved leave; on approval the days are restored (FR-LEV-008). */
    @Transactional
    public ApplicationView requestCancellation(Long id, String reason) {
        LeaveApplication a = owned(id);
        if (a.getStatus() != LeaveApplication.Status.APPROVED)
            throw ApiException.rule("NOT_APPROVED", "Only approved leave can be cancelled through a cancellation request");
        if (a.getCancellationStatus() == LeaveApplication.CancellationStatus.PENDING)
            throw ApiException.conflict("A cancellation request for this leave is already awaiting approval");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason for the cancellation is required");
        LeaveType t = type(a.getLeaveTypeId());
        a.setCancellationStatus(LeaveApplication.CancellationStatus.PENDING);
        a.setCancellationReason(reason.trim());
        EmployeeLookup.EmployeeBrief me = lookup.brief(a.getEmployeeId());
        WorkflowInstance wi = workflow.start(CANCEL_PROCESS, CANCEL_ENTITY, a.getId(), "Cancel " + title(a, t, me),
                "/leave/applications/" + a.getId(), a.getEmployeeId());
        a.setCancelWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.SUBMIT, ENTITY, a.getId(), "Cancellation requested: " + describe(a, t) + " - " + reason.trim());
        return view(a, t, me);
    }

    /** Working days, balance and rule check for a prospective application (no changes are made). */
    @Transactional(readOnly = true)
    public PreviewResult preview(ApplyRequest r) {
        Long emp = CurrentUser.employeeId();
        if (r == null || r.leaveTypeId() == null || r.startDate() == null || r.endDate() == null)
            return new PreviewResult(BigDecimal.ZERO, false, null, false, false, "Select a leave type and dates");
        LeaveType t = type(r.leaveTypeId());
        BigDecimal days = BigDecimal.ZERO;
        if (!r.endDate().isBefore(r.startDate())) {
            days = Boolean.TRUE.equals(r.halfDay()) ? HALF
                    : BigDecimal.valueOf(WorkingDays.count(r.startDate(), r.endDate(), holidays.holidaysBetween(r.startDate(), r.endDate())));
        }
        BigDecimal available = t.isBalanceTracked() ? balances.available(emp, t, r.startDate().getYear()) : null;
        try {
            check(emp, r, null, null);
            if (available != null && available.compareTo(days) < 0)
                return new PreviewResult(days, true, available, t.documentRequiredFor(days), false,
                        "Insufficient balance: " + available.stripTrailingZeros().toPlainString() + " day(s) available");
            return new PreviewResult(days, t.isBalanceTracked(), available, t.documentRequiredFor(days), true, null);
        } catch (ApiException e) {
            return new PreviewResult(days, t.isBalanceTracked(), available, t.documentRequiredFor(days), false, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<ApplicationView> mine() {
        Long emp = CurrentUser.employeeId();
        Map<Long, LeaveType> t = typeMap();
        EmployeeLookup.EmployeeBrief me = lookup.brief(emp);
        return apps.findByEmployeeIdOrderByStartDateDesc(emp).stream().map(a -> view(a, t.get(a.getLeaveTypeId()), me)).toList();
    }

    // ===================================================================== management queries

    @Transactional(readOnly = true)
    public ApplicationView get(Long id) {
        LeaveApplication a = app(id);
        requireView(a);
        return view(a, type(a.getLeaveTypeId()), lookup.brief(a.getEmployeeId()));
    }

    /** Document download (owner or authorized viewer only). */
    @Transactional(readOnly = true)
    public Long documentFileId(Long id) {
        LeaveApplication a = app(id);
        requireView(a);
        if (a.getDocumentFileId() == null) throw ApiException.notFound("Supporting document for application", id);
        audit.log(AuditAction.DOWNLOAD, ENTITY, id, "Leave supporting document downloaded");
        return a.getDocumentFileId();
    }

    @Transactional(readOnly = true)
    public PageResponse<ApplicationView> search(SearchFilter f) {
        Set<Long> visible = lookup.visibleEmployeeIds();
        Collection<Long> unitIds = null;
        if (f.orgUnitCode() != null && !f.orgUnitCode().isBlank()) {
            Set<String> units = establishment.unitAndDescendants(f.orgUnitCode(), LocalDate.now());
            unitIds = employees.idsInUnits(units);
        }
        if (f.employeeId() != null) lookup.requireAccess(f.employeeId());
        final Collection<Long> byUnit = unitIds;
        LeaveApplication.Status status = parseStatus(f.status());
        Specification<LeaveApplication> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (visible != null) p.add(visible.isEmpty() ? cb.disjunction() : root.get("employeeId").in(visible));
            if (byUnit != null) p.add(byUnit.isEmpty() ? cb.disjunction() : root.get("employeeId").in(byUnit));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (f.employeeId() != null) p.add(cb.equal(root.get("employeeId"), f.employeeId()));
            if (f.leaveTypeId() != null) p.add(cb.equal(root.get("leaveTypeId"), f.leaveTypeId()));
            if (f.from() != null) p.add(cb.greaterThanOrEqualTo(root.get("endDate"), f.from()));
            if (f.to() != null) p.add(cb.lessThanOrEqualTo(root.get("startDate"), f.to()));
            return cb.and(p.toArray(new Predicate[0]));
        };
        int size = Math.min(Math.max(f.size(), 1), 200);
        var page = apps.findAll(spec, PageRequest.of(Math.max(f.page(), 0), size, Sort.by(Sort.Direction.DESC, "startDate", "id")));
        Map<Long, LeaveType> t = typeMap();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = lookup.briefs(page.getContent().stream().map(LeaveApplication::getEmployeeId).distinct().toList());
        return PageResponse.of(page, a -> view(a, t.get(a.getLeaveTypeId()), briefs.get(a.getEmployeeId())));
    }

    /** Team leave calendar for managers (FR-EXT-004): approved and pending leave of employees in the user's scope. */
    @Transactional(readOnly = true)
    public TeamCalendar teamCalendar(LocalDate from, LocalDate to, String orgUnitCode) {
        LocalDate f = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate t = to == null ? f.plusMonths(1).minusDays(1) : to;
        if (t.isBefore(f)) throw ApiException.badRequest("End date cannot be before start date");
        if (ChronoUnit.DAYS.between(f, t) > 92) throw ApiException.badRequest("The calendar range cannot exceed 3 months");
        Set<Long> visible = lookup.visibleEmployeeIds();
        Set<String> units = orgUnitCode == null || orgUnitCode.isBlank() ? null : establishment.unitAndDescendants(orgUnitCode, LocalDate.now());
        List<LeaveApplication> rows = apps.inRange(f, t, EnumSet.of(LeaveApplication.Status.APPROVED, LeaveApplication.Status.PENDING,
                LeaveApplication.Status.RETURNED));
        rows = rows.stream().filter(a -> visible == null || visible.contains(a.getEmployeeId())).toList();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = lookup.briefs(rows.stream().map(LeaveApplication::getEmployeeId).distinct().toList());
        Map<Long, LeaveType> types = typeMap();
        List<CalendarEntry> out = new ArrayList<>();
        for (LeaveApplication a : rows) {
            EmployeeLookup.EmployeeBrief e = briefs.get(a.getEmployeeId());
            if (e == null) continue;
            if (units != null && (e.orgUnitCode() == null || !units.contains(e.orgUnitCode()))) continue;
            LeaveType lt = types.get(a.getLeaveTypeId());
            out.add(new CalendarEntry(a.getId(), e.id(), e.employeeNo(), e.nameWithInitials(), e.orgUnitCode(), e.orgUnitName(),
                    lt.getCode(), lt.getName(), a.getStartDate(), a.getEndDate(), a.isHalfDay(), a.getDays(),
                    a.getStatus() == LeaveApplication.Status.APPROVED ? "APPROVED" : "PENDING"));
        }
        out.sort(Comparator.comparing(CalendarEntry::employeeNo).thenComparing(CalendarEntry::startDate));
        return new TeamCalendar(f, t, out, new TreeSet<>(holidays.holidaysBetween(f, t)));
    }

    // ===================================================================== public API for attendance / payroll

    /** Approved (not cancelled) leave overlapping the range. */
    @Transactional(readOnly = true)
    public List<LeaveDay> approvedLeave(LocalDate from, LocalDate to) {
        Map<Long, LeaveType> t = typeMap();
        return apps.inRange(from, to, EnumSet.of(LeaveApplication.Status.APPROVED)).stream()
                .map(a -> {
                    LeaveType lt = t.get(a.getLeaveTypeId());
                    return new LeaveDay(a.getId(), a.getEmployeeId(), a.getStartDate(), a.getEndDate(), a.isHalfDay(), lt.getCode(), lt.isPaid());
                }).toList();
    }

    /** Approved unpaid leave days per employee within the month (working days only; half days count 0.5). */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> approvedUnpaidDays(YearMonth period) {
        LocalDate ms = period.atDay(1), me = period.atEndOfMonth();
        Set<LocalDate> hol = holidays.holidaysBetween(ms, me);
        Map<Long, BigDecimal> out = new HashMap<>();
        for (LeaveDay d : approvedLeave(ms, me)) {
            if (d.paid()) continue;
            LocalDate s = d.startDate().isBefore(ms) ? ms : d.startDate();
            LocalDate e = d.endDate().isAfter(me) ? me : d.endDate();
            BigDecimal days = d.halfDay() ? (WorkingDays.isWorkingDay(s, hol) ? HALF : BigDecimal.ZERO)
                    : BigDecimal.valueOf(WorkingDays.count(s, e, hol));
            if (days.signum() > 0) out.merge(d.employeeId(), days, BigDecimal::add);
        }
        return out;
    }

    // ===================================================================== workflow callbacks (process LEAVE)

    @Override
    public String processCode() {
        return PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        LeaveApplication a = app(wi.getEntityId());
        LeaveType t = type(a.getLeaveTypeId());
        if (a.getStatus() != LeaveApplication.Status.PENDING)
            throw ApiException.rule("INVALID_STATE", "Leave application is not pending (" + a.getStatus() + ")");
        balances.consume(a, t);
        a.setStatus(LeaveApplication.Status.APPROVED);
        a.setDecidedAt(Instant.now());
        audit.log(AuditAction.UPDATE, ENTITY, a.getId(), "Leave approved and balance updated: " + describe(a, t));
        events.publishEvent(new LeaveChangedEvent(a.getEmployeeId(), a.getStartDate(), a.getEndDate()));
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        LeaveApplication a = app(wi.getEntityId());
        balances.releasePending(a, type(a.getLeaveTypeId()));
        a.setStatus(LeaveApplication.Status.REJECTED);
        a.setDecidedAt(Instant.now());
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        app(wi.getEntityId()).setStatus(LeaveApplication.Status.RETURNED);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        app(wi.getEntityId()).setStatus(LeaveApplication.Status.PENDING);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        LeaveApplication a = app(wi.getEntityId());
        balances.releasePending(a, type(a.getLeaveTypeId()));
        a.setStatus(LeaveApplication.Status.WITHDRAWN);
        a.setDecidedAt(Instant.now());
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        LeaveApplication a = app(wi.getEntityId());
        return summaryOf(a, false);
    }

    // ---- cancellation (process LEAVE_CANCEL, see LeaveCancellationHandler)

    void cancellationApproved(Long id) {
        LeaveApplication a = app(id);
        if (a.getStatus() != LeaveApplication.Status.APPROVED)
            throw ApiException.rule("INVALID_STATE", "Only approved leave can be cancelled (current status " + a.getStatus() + ")");
        LeaveType t = type(a.getLeaveTypeId());
        balances.restoreUsed(a, t);
        a.setStatus(LeaveApplication.Status.CANCELLED);
        a.setCancellationStatus(LeaveApplication.CancellationStatus.APPROVED);
        audit.log(AuditAction.UPDATE, ENTITY, a.getId(), "Approved leave cancelled, balance restored: " + describe(a, t));
        events.publishEvent(new LeaveChangedEvent(a.getEmployeeId(), a.getStartDate(), a.getEndDate()));
    }

    void cancellationClosed(Long id, LeaveApplication.CancellationStatus status) {
        app(id).setCancellationStatus(status);
    }

    Map<String, Object> summaryOf(Long id, boolean cancellation) {
        return summaryOf(app(id), cancellation);
    }

    private Map<String, Object> summaryOf(LeaveApplication a, boolean cancellation) {
        LeaveType t = type(a.getLeaveTypeId());
        EmployeeLookup.EmployeeBrief e = lookup.brief(a.getEmployeeId());
        Map<String, Object> m = new LinkedHashMap<>();
        if (cancellation) m.put("Request", "Cancellation of approved leave");
        m.put("Employee", e.employeeNo() + " - " + e.nameWithInitials());
        m.put("Designation", e.designation());
        m.put("Unit", e.orgUnitName());
        m.put("Leave type", t.getName() + (t.isPaid() ? "" : " (unpaid)"));
        m.put("From", a.getStartDate());
        m.put("To", a.getEndDate());
        if (a.isHalfDay()) m.put("Half day", a.getHalfDaySession());
        m.put("Working days", a.getDays().stripTrailingZeros().toPlainString());
        if (a.getReason() != null) m.put("Reason", a.getReason());
        if (a.getContactDuringLeave() != null) m.put("Contact during leave", a.getContactDuringLeave());
        m.put("Supporting document", a.getDocumentFileId() == null ? "None" : "Attached (open the application to view)");
        if (t.isBalanceTracked()) {
            m.put("Balance available " + a.getLeaveYear() + " (after this request)",
                    balances.available(a.getEmployeeId(), t, a.getLeaveYear()).stripTrailingZeros().toPlainString());
        }
        if (cancellation) m.put("Cancellation reason", a.getCancellationReason());
        return m;
    }

    // ===================================================================== validation

    private Checked check(Long employeeId, ApplyRequest r, Long excludeId, Long existingDocId) {
        if (r == null || r.leaveTypeId() == null) throw ApiException.badRequest("Leave type is required");
        LeaveType t = type(r.leaveTypeId());
        if (!t.isActive()) throw ApiException.rule("INACTIVE_TYPE", t.getName() + " is no longer available");
        LocalDate s = r.startDate(), e = r.endDate();
        if (s == null || e == null) throw ApiException.badRequest("Start and end dates are required");
        if (e.isBefore(s)) throw ApiException.badRequest("End date cannot be before start date");
        if (s.getYear() != e.getYear())
            throw ApiException.rule("CROSS_YEAR", "Leave spanning two calendar years must be applied for as two applications");
        boolean half = Boolean.TRUE.equals(r.halfDay());
        if (half) {
            if (!t.isHalfDayAllowed()) throw ApiException.rule("HALF_DAY_NOT_ALLOWED", t.getName() + " cannot be taken as a half day");
            if (!s.equals(e)) throw ApiException.badRequest("Half-day leave must start and end on the same date");
            if (!"AM".equals(r.halfDaySession()) && !"PM".equals(r.halfDaySession()))
                throw ApiException.badRequest("Select the half-day session (AM or PM)");
        }
        int wd = WorkingDays.count(s, e, holidays.holidaysBetween(s, e));
        if (wd == 0) throw ApiException.rule("NO_WORKING_DAYS", "The selected period has no working days (weekends and holidays are not charged)");
        BigDecimal days = half ? HALF : BigDecimal.valueOf(wd);
        LocalDate today = LocalDate.now();
        if (s.isBefore(today)) {
            if (!t.isAllowBackdated())
                throw ApiException.rule("BACKDATED_NOT_ALLOWED", t.getName() + " cannot be applied for past dates");
            int max = settings.getInt("leave.backdate-max-days", 30);
            if (ChronoUnit.DAYS.between(s, today) > max)
                throw ApiException.rule("BACKDATED_TOO_FAR", "Leave can be applied for at most " + max + " days after it starts");
        } else if (ChronoUnit.DAYS.between(today, s) < t.getMinNoticeDays()) {
            throw ApiException.rule("INSUFFICIENT_NOTICE", t.getName() + " requires at least " + t.getMinNoticeDays() + " day(s) notice");
        }
        int ahead = settings.getInt("leave.max-advance-days", 365);
        if (s.isAfter(today.plusDays(ahead)))
            throw ApiException.rule("TOO_FAR_AHEAD", "Leave can be applied for at most " + ahead + " days in advance");
        if (t.getMaxConsecutiveDays() != null && days.compareTo(BigDecimal.valueOf(t.getMaxConsecutiveDays())) > 0)
            throw ApiException.rule("MAX_CONSECUTIVE", t.getName() + " cannot exceed " + t.getMaxConsecutiveDays() + " consecutive working days");
        if (t.isRequiresReason() && (r.reason() == null || r.reason().isBlank()))
            throw ApiException.badRequest("A reason is required for " + t.getName());
        if (r.reason() != null && r.reason().length() > 1000) throw ApiException.badRequest("Reason is too long (maximum 1000 characters)");
        if (r.contactDuringLeave() != null && r.contactDuringLeave().length() > 300)
            throw ApiException.badRequest("Contact details are too long (maximum 300 characters)");
        if (r.documentFileId() == null && t.documentRequiredFor(days))
            throw ApiException.rule("DOCUMENT_REQUIRED", "A supporting document is required for " + t.getName()
                    + (t.getDocumentAfterDays() != null && t.getDocumentAfterDays() > 0 ? " of more than " + t.getDocumentAfterDays() + " day(s)" : ""));
        if (r.documentFileId() != null && !r.documentFileId().equals(existingDocId)) {
            StoredFile f = files.meta(r.documentFileId());
            if (!FILE_MODULE.equals(f.getModule()) || !CurrentUser.username().equals(f.getUploadedBy()))
                throw ApiException.rule("INVALID_DOCUMENT", "The supporting document was not uploaded by you for a leave application");
        }
        for (LeaveApplication o : apps.overlapping(employeeId, s, e, HOLDING, excludeId == null ? -1L : excludeId)) {
            boolean halvesApart = half && o.isHalfDay() && !Objects.equals(o.getHalfDaySession(), r.halfDaySession());
            if (!halvesApart) {
                throw ApiException.rule("OVERLAP", "The dates overlap with your " + type(o.getLeaveTypeId()).getName() + " application #"
                        + o.getId() + " (" + o.getStartDate().format(D) + " - " + o.getEndDate().format(D) + ", "
                        + o.getStatus().name().toLowerCase() + ")");
            }
        }
        return new Checked(t, days, s.getYear());
    }

    private void requireInService(Long employeeId) {
        EmployeeLookup.EmployeeBrief b = lookup.brief(employeeId);
        if (b == null || !EmployeeLookup.IN_SERVICE.contains(b.status()))
            throw ApiException.rule("NOT_IN_SERVICE", "Leave can only be applied for by employees in service");
    }

    private static void fill(LeaveApplication a, ApplyRequest r, Checked c) {
        a.setLeaveTypeId(c.type().getId());
        a.setLeaveYear(c.year());
        a.setStartDate(r.startDate());
        a.setEndDate(r.endDate());
        a.setHalfDay(Boolean.TRUE.equals(r.halfDay()));
        a.setHalfDaySession(a.isHalfDay() ? r.halfDaySession() : null);
        a.setDays(c.days());
        a.setReason(r.reason() == null || r.reason().isBlank() ? null : r.reason().trim());
        a.setContactDuringLeave(r.contactDuringLeave() == null || r.contactDuringLeave().isBlank() ? null : r.contactDuringLeave().trim());
        a.setDocumentFileId(r.documentFileId());
    }

    // ===================================================================== helpers

    private void requireView(LeaveApplication a) {
        UserPrincipal me = CurrentUser.get();
        if (a.getEmployeeId().equals(me.getEmployeeId())) return;
        if (me.has(Permission.LEAVE_VIEW)) {
            lookup.requireAccess(a.getEmployeeId());
            return;
        }
        throw ApiException.notFound("Leave application", a.getId());
    }

    private LeaveApplication owned(Long id) {
        LeaveApplication a = app(id);
        if (!a.getEmployeeId().equals(CurrentUser.employeeId())) throw ApiException.notFound("Leave application", id);
        return a;
    }

    LeaveApplication app(Long id) {
        return apps.findById(id).orElseThrow(() -> ApiException.notFound("Leave application", id));
    }

    private LeaveType type(Long id) {
        return types.findById(id).orElseThrow(() -> ApiException.notFound("Leave type", id));
    }

    private Map<Long, LeaveType> typeMap() {
        return types.findAll().stream().collect(Collectors.toMap(LeaveType::getId, Function.identity()));
    }

    private static LeaveApplication.Status parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LeaveApplication.Status.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown status: " + s);
        }
    }

    private static String describe(LeaveApplication a, LeaveType t) {
        return t.getName() + " " + a.getStartDate() + (a.getEndDate().equals(a.getStartDate()) ? "" : " to " + a.getEndDate())
                + " (" + a.getDays().stripTrailingZeros().toPlainString() + " day(s))";
    }

    private static String title(LeaveApplication a, LeaveType t, EmployeeLookup.EmployeeBrief e) {
        return t.getName() + ": " + a.getStartDate().format(D)
                + (a.getEndDate().equals(a.getStartDate()) ? "" : " - " + a.getEndDate().format(D))
                + " (" + a.getDays().stripTrailingZeros().toPlainString() + " d) - " + (e == null ? "" : e.nameWithInitials());
    }

    private static ApplicationView view(LeaveApplication a, LeaveType t, EmployeeLookup.EmployeeBrief e) {
        return new ApplicationView(a.getId(), a.getEmployeeId(), e, a.getLeaveTypeId(), t == null ? null : t.getCode(),
                t == null ? null : t.getName(), t != null && t.isPaid(), a.getLeaveYear(), a.getStartDate(), a.getEndDate(),
                a.isHalfDay(), a.getHalfDaySession(), a.getDays(), a.getReason(), a.getContactDuringLeave(),
                a.getDocumentFileId() != null, a.getStatus().name(), a.getWorkflowInstanceId(),
                a.getCancellationStatus() == null ? null : a.getCancellationStatus().name(), a.getCancellationReason(),
                a.getCancelWorkflowInstanceId(), a.getCreatedAt(), a.getDecidedAt());
    }
}
