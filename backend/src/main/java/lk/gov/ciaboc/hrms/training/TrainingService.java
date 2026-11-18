package lk.gov.ciaboc.hrms.training;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.files.StoredFile;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lk.gov.ciaboc.hrms.training.TrainingEntities.*;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Training programmes, nominations (approved through TRAINING_NOMINATION: division head, then HR), capacity control,
 * participation/completion and employee training history (FR-TRN-001..004).
 */
@Service
@RequiredArgsConstructor
public class TrainingService implements WorkflowHandler {
    public static final String PROCESS = "TRAINING_NOMINATION";
    public static final String ENTITY = "TrainingNomination";
    /** Nominations that hold a seat. */
    static final Set<NominationStatus> LIVE = EnumSet.of(NominationStatus.PENDING, NominationStatus.RETURNED, NominationStatus.APPROVED);
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{1,40}$");

    private final TrainingProgrammeRepository programmes;
    private final TrainingNominationRepository nominations;
    private final EmployeeLookup lookup;
    private final WorkflowService workflow;
    private final NotificationService notifications;
    private final FileStorageService files;
    private final SettingsService settings;
    private final AuditService audit;

    // ===================================================================== programmes (FR-TRN-001)

    public record ProgrammeRequest(String code, String title, String description, String provider, LocalDate startDate,
                                   LocalDate endDate, String venue, Integer capacity, BigDecimal cost,
                                   LocalDate nominationClosingDate) {
    }

    public record ProgrammeView(TrainingProgramme programme, long seatsTaken, long approved, long pending, long completed,
                                long seatsAvailable) {
    }

    @Transactional(readOnly = true)
    public List<ProgrammeView> programmes(String status) {
        ProgrammeStatus s = parse(ProgrammeStatus.class, status, "programme status");
        return programmes.findAllByOrderByStartDateDesc().stream().filter(p -> s == null || p.getStatus() == s).map(this::view).toList();
    }

    /** Programmes open for nomination, for self-service. */
    @Transactional(readOnly = true)
    public List<ProgrammeView> openProgrammes() {
        LocalDate today = LocalDate.now();
        return programmes.findByStatusOrderByStartDateAsc(ProgrammeStatus.OPEN).stream()
                .filter(p -> p.getNominationClosingDate() == null || !p.getNominationClosingDate().isBefore(today))
                .filter(p -> !p.getStartDate().isBefore(today))
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ProgrammeView programmeView(Long id) {
        return view(programme(id));
    }

    @Transactional
    public ProgrammeView saveProgramme(Long id, ProgrammeRequest r) {
        TrainingProgramme p = id == null ? new TrainingProgramme() : programme(id);
        if (p.getStatus() == ProgrammeStatus.COMPLETED || p.getStatus() == ProgrammeStatus.CANCELLED)
            throw ApiException.rule("NOT_EDITABLE", "A completed or cancelled programme cannot be changed");
        String code = r.code() == null ? null : r.code().trim().toUpperCase();
        if (code == null || !CODE.matcher(code).matches()) throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits, '-' or '_'");
        if ((id == null || !code.equals(p.getCode())) && programmes.existsByCode(code)) throw ApiException.conflict("Programme " + code + " already exists");
        require(r.title(), "Title");
        if (r.startDate() == null || r.endDate() == null) throw ApiException.badRequest("Start and end dates are required");
        if (r.endDate().isBefore(r.startDate())) throw ApiException.badRequest("End date cannot be before the start date");
        if (r.capacity() == null || r.capacity() < 1) throw ApiException.badRequest("Capacity must be at least 1");
        if (r.cost() != null && r.cost().signum() < 0) throw ApiException.badRequest("Cost cannot be negative");
        if (r.nominationClosingDate() != null && r.nominationClosingDate().isAfter(r.startDate()))
            throw ApiException.badRequest("Nominations must close on or before the start date");
        if (id != null) {
            long taken = nominations.countByProgrammeIdAndStatusIn(id, LIVE);
            if (r.capacity() < taken) throw ApiException.rule("CAPACITY", "Capacity cannot be below the " + taken + " seats already nominated");
        }
        String before = id == null ? null : describe(p);
        p.setCode(code);
        p.setTitle(r.title().trim());
        p.setDescription(r.description());
        p.setProvider(blankToNull(r.provider()));
        p.setStartDate(r.startDate());
        p.setEndDate(r.endDate());
        p.setVenue(blankToNull(r.venue()));
        p.setCapacity(r.capacity());
        p.setCost(r.cost());
        p.setNominationClosingDate(r.nominationClosingDate());
        programmes.save(p);
        audit.log(id == null ? AuditAction.CREATE : AuditAction.UPDATE, "TrainingProgramme", p.getId(), "Programme " + code + " saved", before, describe(p));
        return view(p);
    }

    @Transactional
    public ProgrammeView changeStatus(Long id, String status) {
        TrainingProgramme p = programme(id);
        ProgrammeStatus to = parse(ProgrammeStatus.class, status, "programme status");
        if (to == null) throw ApiException.badRequest("Status is required");
        ProgrammeStatus from = p.getStatus();
        boolean ok = switch (from) {
            case PLANNED -> to == ProgrammeStatus.OPEN || to == ProgrammeStatus.CANCELLED;
            case OPEN -> to == ProgrammeStatus.CLOSED || to == ProgrammeStatus.CANCELLED;
            case CLOSED -> to == ProgrammeStatus.OPEN || to == ProgrammeStatus.COMPLETED || to == ProgrammeStatus.CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
        if (!ok) throw ApiException.rule("INVALID_TRANSITION", "A " + from + " programme cannot be changed to " + to);
        p.setStatus(to);
        audit.log(AuditAction.UPDATE, "TrainingProgramme", id, "Programme " + p.getCode() + " " + from + " → " + to, from.name(), to.name());
        return view(p);
    }

    private ProgrammeView view(TrainingProgramme p) {
        Map<NominationStatus, Long> c = nominations.findByProgrammeIdOrderByIdAsc(p.getId()).stream()
                .collect(Collectors.groupingBy(TrainingNomination::getStatus, Collectors.counting()));
        long completed = nominations.findByProgrammeIdOrderByIdAsc(p.getId()).stream()
                .filter(n -> n.getCompletionStatus() == CompletionStatus.COMPLETED).count();
        long taken = LIVE.stream().mapToLong(s -> c.getOrDefault(s, 0L)).sum();
        long pending = c.getOrDefault(NominationStatus.PENDING, 0L) + c.getOrDefault(NominationStatus.RETURNED, 0L);
        return new ProgrammeView(p, taken, c.getOrDefault(NominationStatus.APPROVED, 0L), pending, completed, Math.max(0, p.getCapacity() - taken));
    }

    private static String describe(TrainingProgramme p) {
        return p.getTitle() + " | " + p.getStartDate() + "→" + p.getEndDate() + " | cap " + p.getCapacity() + " | cost " + p.getCost() + " | " + p.getVenue();
    }

    TrainingProgramme programme(Long id) {
        return programmes.findById(id).orElseThrow(() -> ApiException.notFound("Training programme", id));
    }

    // ===================================================================== nominations (FR-TRN-002)

    public record NominationRequest(Long programmeId, Long employeeId, String reason) {
    }

    public record NominationView(Long id, Long programmeId, String programmeCode, String programmeTitle, LocalDate startDate,
                                 LocalDate endDate, String venue, String provider, Long employeeId, String employeeNo,
                                 String employeeName, String designation, String orgUnitName, String source, String reason,
                                 String status, Long workflowInstanceId, String completionStatus, boolean attended,
                                 String result, String completionRemarks, String certificateFileName, boolean hasCertificate,
                                 Instant createdAt) {
    }

    /** Nomination by HR / training managers. */
    @Transactional
    public NominationView nominate(NominationRequest r) {
        if (r.employeeId() == null) throw ApiException.badRequest("Employee is required");
        lookup.requireAccess(r.employeeId());
        if (r.employeeId().equals(CurrentUser.get().getEmployeeId()))
            throw ApiException.rule("SELF", "Use self-service to nominate yourself");
        return create(r.programmeId(), r.employeeId(), "HR", r.reason());
    }

    /** Self-nomination by the employee (FR-TRN-002). */
    @Transactional
    public NominationView selfNominate(Long programmeId, String reason) {
        if (!settings.getBool("training.self-nomination.enabled", true))
            throw ApiException.rule("DISABLED", "Self-nomination is not enabled");
        TrainingProgramme p = programme(programmeId == null ? -1L : programmeId);
        if (p.getStatus() != ProgrammeStatus.OPEN) throw ApiException.rule("NOT_OPEN", "This programme is not open for nominations");
        if (p.getNominationClosingDate() != null && p.getNominationClosingDate().isBefore(LocalDate.now()))
            throw ApiException.rule("CLOSED", "Nominations for this programme have closed");
        require(reason, "Reason for the nomination");
        return create(programmeId, CurrentUser.employeeId(), "SELF", reason);
    }

    private NominationView create(Long programmeId, Long employeeId, String source, String reason) {
        if (programmeId == null) throw ApiException.badRequest("Programme is required");
        TrainingProgramme p = programme(programmeId);
        if (p.getStatus() != ProgrammeStatus.OPEN && p.getStatus() != ProgrammeStatus.PLANNED)
            throw ApiException.rule("NOT_OPEN", "Nominations are not accepted for a " + p.getStatus() + " programme");
        Employee e = lookup.require(employeeId);
        if (!EmployeeLookup.IN_SERVICE.contains(e.getStatus())) throw ApiException.rule("NOT_IN_SERVICE", "Employee is not in service");
        if (nominations.existsByProgrammeIdAndEmployeeIdAndStatusIn(programmeId, employeeId, LIVE))
            throw ApiException.conflict(e.getEmployeeNo() + " is already nominated for this programme");
        // Capacity check (FR-TRN-002): seats held by pending, returned and approved nominations.
        long taken = nominations.countByProgrammeIdAndStatusIn(programmeId, LIVE);
        if (taken >= p.getCapacity())
            throw ApiException.rule("CAPACITY_FULL", "Programme " + p.getCode() + " is full (" + p.getCapacity() + " seats)");
        if (reason != null && reason.length() > 1000) throw ApiException.badRequest("Reason is limited to 1000 characters");
        TrainingNomination n = new TrainingNomination();
        n.setProgrammeId(programmeId);
        n.setEmployeeId(employeeId);
        n.setSource(source);
        n.setReason(blankToNull(reason));
        n.setStatus(NominationStatus.PENDING);
        nominations.save(n);
        WorkflowInstance wi = workflow.start(PROCESS, ENTITY, n.getId(), "Training nomination: " + e.getEmployeeNo() + " → " + p.getTitle(),
                "SELF".equals(source) ? "/training/my" : "/training/programmes/" + p.getId(), employeeId);
        n.setWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.CREATE, ENTITY, n.getId(), e.getEmployeeNo() + " nominated (" + source + ") for " + p.getCode());
        return views(List.of(n)).get(0);
    }

    /**
     * Withdraws a nomination. Open requests are cancelled through the workflow by the requester; an approved nomination
     * can be withdrawn by a training manager (or the employee) before the programme starts.
     */
    @Transactional
    public NominationView cancel(Long id, String reason) {
        TrainingNomination n = nomination(id);
        UserPrincipal me = CurrentUser.get();
        boolean self = n.getEmployeeId().equals(me.getEmployeeId());
        if (!self && !me.has(Permission.TRN_MANAGE)) throw ApiException.notFound("Nomination", id);
        if (!self) lookup.requireAccess(n.getEmployeeId());
        if (n.getStatus() == NominationStatus.PENDING || n.getStatus() == NominationStatus.RETURNED) {
            workflow.cancel(n.getWorkflowInstanceId(), reason);
        } else if (n.getStatus() == NominationStatus.APPROVED) {
            require(reason, "Reason");
            TrainingProgramme p = programme(n.getProgrammeId());
            if (!p.getStartDate().isAfter(LocalDate.now())) throw ApiException.rule("STARTED", "The programme has already started");
            n.setStatus(NominationStatus.CANCELLED);
            audit.log(AuditAction.CANCEL, ENTITY, id, "Approved nomination withdrawn: " + reason);
        } else {
            throw ApiException.rule("NOT_OPEN", "This nomination is already " + n.getStatus());
        }
        return views(List.of(n)).get(0);
    }

    @Transactional(readOnly = true)
    public List<NominationView> forProgramme(Long programmeId) {
        programme(programmeId);
        Set<Long> visible = lookup.visibleEmployeeIds();
        return views(nominations.findByProgrammeIdOrderByIdAsc(programmeId).stream()
                .filter(n -> visible == null || visible.contains(n.getEmployeeId())).toList());
    }

    @Transactional(readOnly = true)
    public List<NominationView> mine() {
        return views(nominations.findByEmployeeIdOrderByIdDesc(CurrentUser.employeeId()));
    }

    /** Training history of an employee (FR-TRN-004): self, or TRN_VIEW within scope. */
    @Transactional(readOnly = true)
    public List<NominationView> history(Long employeeId) {
        UserPrincipal me = CurrentUser.get();
        if (!employeeId.equals(me.getEmployeeId())) {
            if (!me.has(Permission.TRN_VIEW)) throw ApiException.notFound("Employee", employeeId);
            lookup.requireAccess(employeeId);
            audit.log(AuditAction.VIEW, ENTITY, null, "Viewed training history of employee " + employeeId);
        }
        return views(nominations.findByEmployeeIdOrderByIdDesc(employeeId));
    }

    // ===================================================================== participation & completion (FR-TRN-003)

    public record CompletionRequest(String completionStatus, String result, String remarks) {
    }

    @Transactional
    public NominationView recordCompletion(Long id, CompletionRequest r) {
        TrainingNomination n = nomination(id);
        lookup.requireAccess(n.getEmployeeId());
        if (n.getStatus() != NominationStatus.APPROVED) throw ApiException.rule("NOT_APPROVED", "Only approved nominations can be completed");
        TrainingProgramme p = programme(n.getProgrammeId());
        if (p.getStartDate().isAfter(LocalDate.now())) throw ApiException.rule("NOT_STARTED", "The programme has not started yet");
        CompletionStatus cs = parse(CompletionStatus.class, r.completionStatus(), "completion status");
        if (cs == null || cs == CompletionStatus.NOT_RECORDED) throw ApiException.badRequest("Completion status is required");
        String before = n.getCompletionStatus() + " " + n.getResult();
        n.setCompletionStatus(cs);
        n.setAttended(cs != CompletionStatus.ABSENT);
        n.setResult(blankToNull(r.result()));
        n.setCompletionRemarks(blankToNull(r.remarks()));
        n.setCompletionRecordedAt(Instant.now());
        audit.log(AuditAction.UPDATE, ENTITY, id, "Participation recorded for nomination " + id, before, cs + " " + n.getResult());
        return views(List.of(n)).get(0);
    }

    @Transactional
    public NominationView uploadCertificate(Long id, MultipartFile file) {
        TrainingNomination n = nomination(id);
        lookup.requireAccess(n.getEmployeeId());
        if (n.getCompletionStatus() != CompletionStatus.COMPLETED)
            throw ApiException.rule("NOT_COMPLETED", "Record the programme as completed before attaching a certificate");
        StoredFile f = files.store(file, "TRAINING");
        n.setCertificateFileId(f.getId());
        n.setCertificateFileName(f.getOriginalName());
        audit.log(AuditAction.UPLOAD, ENTITY, id, "Certificate uploaded for nomination " + id);
        return views(List.of(n)).get(0);
    }

    @Transactional(readOnly = true)
    public Long certificateForDownload(Long id) {
        TrainingNomination n = nomination(id);
        UserPrincipal me = CurrentUser.get();
        if (!n.getEmployeeId().equals(me.getEmployeeId())) {
            if (!me.has(Permission.TRN_VIEW)) throw ApiException.notFound("Nomination", id);
            lookup.requireAccess(n.getEmployeeId());
        }
        if (n.getCertificateFileId() == null) throw ApiException.notFound("Certificate", id);
        audit.log(AuditAction.DOWNLOAD, ENTITY, id, "Downloaded training certificate of nomination " + id);
        return n.getCertificateFileId();
    }

    // ===================================================================== workflow callbacks

    @Override
    public String processCode() {
        return PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        TrainingNomination n = nomination(wi.getEntityId());
        TrainingProgramme p = programme(n.getProgrammeId());
        if (p.getStatus() == ProgrammeStatus.CANCELLED) throw ApiException.rule("CANCELLED", "The programme has been cancelled");
        long approved = nominations.countByProgrammeIdAndStatusIn(p.getId(), EnumSet.of(NominationStatus.APPROVED));
        if (approved >= p.getCapacity()) throw ApiException.rule("CAPACITY_FULL", "Programme " + p.getCode() + " is already full");
        n.setStatus(NominationStatus.APPROVED);
        notifications.notifyEmployee(n.getEmployeeId(), "TRN_NOMINATION_APPROVED", vars(p), "/training/my");
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        TrainingNomination n = nomination(wi.getEntityId());
        n.setStatus(NominationStatus.REJECTED);
        if (!"SELF".equals(n.getSource()))
            notifications.notifyEmployee(n.getEmployeeId(), "TRN_NOMINATION_REJECTED", vars(programme(n.getProgrammeId())), "/training/my");
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        nomination(wi.getEntityId()).setStatus(NominationStatus.RETURNED);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        nomination(wi.getEntityId()).setStatus(NominationStatus.PENDING);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        nomination(wi.getEntityId()).setStatus(NominationStatus.CANCELLED);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        TrainingNomination n = nomination(wi.getEntityId());
        TrainingProgramme p = programme(n.getProgrammeId());
        EmployeeLookup.EmployeeBrief e = lookup.brief(n.getEmployeeId());
        ProgrammeView v = view(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Employee", e.employeeNo() + " — " + e.nameWithInitials() + " (" + e.designation() + ", " + e.orgUnitName() + ")");
        m.put("Programme", p.getCode() + " — " + p.getTitle());
        m.put("Provider", p.getProvider());
        m.put("Dates", p.getStartDate() + " to " + p.getEndDate());
        m.put("Venue", p.getVenue());
        m.put("Cost per programme", p.getCost());
        m.put("Seats", v.approved() + " approved, " + v.pending() + " pending of " + p.getCapacity());
        m.put("Nominated by", "SELF".equals(n.getSource()) ? "Employee (self-nomination)" : "HR / training");
        m.put("Reason", n.getReason());
        return m;
    }

    // ===================================================================== helpers

    private List<NominationView> views(List<TrainingNomination> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, EmployeeLookup.EmployeeBrief> b = lookup.briefs(list.stream().map(TrainingNomination::getEmployeeId).toList());
        Map<Long, TrainingProgramme> ps = new HashMap<>();
        return list.stream().map(n -> {
            TrainingProgramme p = ps.computeIfAbsent(n.getProgrammeId(), this::programme);
            EmployeeLookup.EmployeeBrief e = b.get(n.getEmployeeId());
            return new NominationView(n.getId(), p.getId(), p.getCode(), p.getTitle(), p.getStartDate(), p.getEndDate(), p.getVenue(),
                    p.getProvider(), n.getEmployeeId(), e == null ? null : e.employeeNo(), e == null ? null : e.nameWithInitials(),
                    e == null ? null : e.designation(), e == null ? null : e.orgUnitName(), n.getSource(), n.getReason(),
                    n.getStatus().name(), n.getWorkflowInstanceId(), n.getCompletionStatus().name(), n.isAttended(), n.getResult(),
                    n.getCompletionRemarks(), n.getCertificateFileName(), n.getCertificateFileId() != null, n.getCreatedAt());
        }).toList();
    }

    private static Map<String, Object> vars(TrainingProgramme p) {
        Map<String, Object> m = new HashMap<>();
        m.put("programme", p.getTitle());
        m.put("startDate", p.getStartDate());
        m.put("endDate", p.getEndDate());
        m.put("venue", p.getVenue() == null ? "-" : p.getVenue());
        return m;
    }

    TrainingNomination nomination(Long id) {
        return nominations.findById(id).orElseThrow(() -> ApiException.notFound("Nomination", id));
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown " + label + ": " + value);
        }
    }

    private static void require(String v, String label) {
        if (v == null || v.isBlank()) throw ApiException.badRequest(label + " is required");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
