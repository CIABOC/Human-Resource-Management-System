package lk.gov.ciaboc.hrms.appraisal;

import lk.gov.ciaboc.hrms.appraisal.AppraisalEntities.*;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.Json;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.files.StoredFile;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static lk.gov.ciaboc.hrms.appraisal.AppraisalService.blank;
import static lk.gov.ciaboc.hrms.appraisal.AppraisalService.trim;

/**
 * Appeals (screen 7), check-ins and feedback (13), special appraisal cases and KPI exceptions (14), delegation and
 * controlled reopening (15). Requests that change an appraisal take effect only after approval by an appraisal
 * administrator other than the requester; original and adjusted values are both kept.
 */
@Service
@RequiredArgsConstructor
public class AppraisalWorkService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    private final AprAppraisalRepository appraisals;
    private final AprKpiRepository kpis;
    private final AprAppealRepository appeals;
    private final AprCheckinRepository checkins;
    private final AprFeedbackRepository feedback;
    private final AprSpecialCaseRepository cases;
    private final AprDelegationRepository delegations;
    private final AprReopenRepository reopens;
    private final AppraisalAccess access;
    private final AppraisalService service;
    private final EmployeeLookup lookup;
    private final AppUserRepository users;
    private final FileStorageService files;
    private final AuditService audit;

    // ===================================================================== appeals (screen 7)

    public record AppealView(Long id, Long appraisalId, String reference, String periodName, String employeeName, String grounds,
                             List<Long> fileIds, String status, String decision, String decidedBy, Instant decidedAt, Instant submittedAt) {
    }

    @Transactional(readOnly = true)
    public List<AppealView> myAppeals() {
        Long emp = CurrentUser.employeeId();
        if (emp == null) return List.of();
        List<Long> ids = appraisals.findByEmployeeIdOrderByIdDesc(emp).stream().map(Appraisal::getId).toList();
        return ids.isEmpty() ? List.of() : appeals.findByAppraisalIdInOrderBySubmittedAtDesc(ids).stream().map(this::appealView).toList();
    }

    /** Appeals for appraisal administrators to review (within their data scope). */
    @Transactional(readOnly = true)
    public List<AppealView> allAppeals() {
        requireManage();
        return appeals.findAllByOrderBySubmittedAtDesc().stream()
                .filter(x -> access.inScope(service.appraisal(x.getAppraisalId())))
                .map(this::appealView).toList();
    }

    @Transactional
    public AppealView submitAppeal(Long appraisalId, String grounds, List<MultipartFile> attachments) {
        Appraisal a = service.appraisal(appraisalId);
        if (!access.isEmployee(a)) throw ApiException.forbidden("Only the employee may appeal their appraisal");
        if (a.getStatus() != Status.FINALIZED || a.getAcknowledgedAt() == null)
            throw ApiException.rule("APPEAL_NOT_OPEN", "Appeals can be submitted after you acknowledge the finalized appraisal");
        LocalDate closes = service.appealWindowCloses(a);
        if (closes != null && LocalDate.now(ZONE).isAfter(closes)) throw ApiException.rule("APPEAL_WINDOW_CLOSED", "The appeal window closed on " + closes);
        if (blank(grounds)) throw ApiException.badRequest("State the grounds for the appeal");
        boolean open = appeals.findByAppraisalIdInOrderBySubmittedAtDesc(List.of(appraisalId)).stream().anyMatch(x -> x.getStatus() != AppealStatus.DECIDED);
        if (open) throw ApiException.rule("APPEAL_OPEN", "You already have an appeal under review for this appraisal");
        List<Long> fileIds = new ArrayList<>();
        for (MultipartFile f : attachments == null ? List.<MultipartFile>of() : attachments) {
            if (f == null || f.isEmpty()) continue;
            StoredFile sf = files.store(f, "appraisal");
            fileIds.add(sf.getId());
        }
        Appeal ap = new Appeal();
        ap.setAppraisalId(appraisalId);
        ap.setReference(nextRef("APL", appeals.count()));
        ap.setGrounds(grounds.trim());
        ap.setFileIds(fileIds.isEmpty() ? null : fileIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        ap.setSubmittedAt(Instant.now());
        ap = appeals.save(ap);
        service.addTrail(a, "EMPLOYEE", CurrentUser.get().getFullName(), null, "Appeal submitted (" + ap.getReference() + ")", null);
        audit.log(AuditAction.SUBMIT, "AppraisalAppeal", ap.getId(), "Submitted appeal " + ap.getReference() + " on appraisal " + appraisalId);
        return appealView(ap);
    }

    public record AppealDecision(String status, String decision) {
    }

    @Transactional
    public AppealView decideAppeal(Long id, AppealDecision r) {
        requireManage();
        Appeal ap = appeals.findById(id).orElseThrow(() -> ApiException.notFound("Appeal", id));
        Appraisal a = service.appraisal(ap.getAppraisalId());
        if (!access.isHr(a)) throw ApiException.forbidden("You cannot review this appeal");
        AppealStatus st = AppraisalService.parse(AppealStatus.class, r.status(), "appeal status");
        if (ap.getStatus() == AppealStatus.DECIDED) throw ApiException.rule("APPEAL_DECIDED", "The appeal has already been decided");
        if (st == AppealStatus.DECIDED && blank(r.decision())) throw ApiException.badRequest("Record the decision on the appeal");
        ap.setStatus(st);
        if (st == AppealStatus.DECIDED) {
            ap.setDecision(r.decision().trim());
            ap.setDecidedBy(CurrentUser.get().getFullName());
            ap.setDecidedAt(Instant.now());
            service.addTrail(a, "HR", CurrentUser.get().getFullName(), null, "Appeal decided (" + ap.getReference() + ")", r.decision().trim());
            service.notifyEmployee(a, "Appeal decided", "A decision has been recorded on your appeal " + ap.getReference() + ".", "/appraisal/appeals");
        }
        audit.log(AuditAction.UPDATE, "AppraisalAppeal", id, "Appeal " + ap.getReference() + " -> " + st);
        return appealView(appeals.save(ap));
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> appealFile(Long appealId, Long fileId) {
        Appeal ap = appeals.findById(appealId).orElseThrow(() -> ApiException.notFound("Appeal", appealId));
        Appraisal a = service.appraisal(ap.getAppraisalId());
        if (!access.isEmployee(a) && !access.isHr(a)) throw ApiException.notFound("Appeal", appealId);
        if (ap.getFileIds() == null || !Arrays.asList(ap.getFileIds().split(",")).contains(String.valueOf(fileId)))
            throw ApiException.notFound("File", fileId);
        audit.log(AuditAction.DOWNLOAD, "AppraisalAppeal", appealId, "Downloaded appeal document " + fileId);
        return files.download(fileId);
    }

    private AppealView appealView(Appeal ap) {
        Appraisal a = service.appraisal(ap.getAppraisalId());
        String period = access.period(a.getPeriodId()).name();
        List<Long> ids = ap.getFileIds() == null ? List.of() : Arrays.stream(ap.getFileIds().split(",")).map(Long::valueOf).toList();
        return new AppealView(ap.getId(), ap.getAppraisalId(), ap.getReference(), period, service.employeeName(a), ap.getGrounds(), ids,
                ap.getStatus().name(), ap.getDecision(), ap.getDecidedBy(), ap.getDecidedAt(), ap.getSubmittedAt());
    }

    // ===================================================================== check-ins and feedback (screen 13)

    public record CheckinView(Long id, Long employeeId, String employeeName, String supervisorName, LocalDate date,
                              String progressDiscussed, String agreedActions, String status, boolean canUpdate) {
    }

    @Transactional(readOnly = true)
    public List<CheckinView> checkins() {
        UserPrincipal me = CurrentUser.get();
        Map<Long, Checkin> all = new LinkedHashMap<>();
        if (me.getEmployeeId() != null) checkins.findByEmployeeIdOrderByCheckinDateDesc(me.getEmployeeId()).forEach(c -> all.put(c.getId(), c));
        checkins.findBySupervisorUserIdOrderByCheckinDateDesc(me.getId()).forEach(c -> all.put(c.getId(), c));
        Map<Long, EmployeeLookup.EmployeeBrief> b = lookup.briefs(all.values().stream().map(Checkin::getEmployeeId).toList());
        return all.values().stream().sorted(Comparator.comparing(Checkin::getCheckinDate).reversed())
                .map(c -> new CheckinView(c.getId(), c.getEmployeeId(), Optional.ofNullable(b.get(c.getEmployeeId())).map(EmployeeLookup.EmployeeBrief::name).orElse(null),
                        c.getSupervisorName(), c.getCheckinDate(), c.getProgressDiscussed(), c.getAgreedActions(), c.getStatus().name(),
                        c.getSupervisorUserId().equals(me.getId()) && c.getStatus() == CheckinStatus.SCHEDULED))
                .toList();
    }

    public record CheckinRequest(Long employeeId, LocalDate date, String progressDiscussed, String agreedActions) {
    }

    @Transactional
    public CheckinView scheduleCheckin(CheckinRequest r) {
        if (r.employeeId() == null || r.date() == null) throw ApiException.badRequest("Choose the employee and the check-in date");
        requireSupervisorOf(r.employeeId());
        UserPrincipal me = CurrentUser.get();
        Checkin c = new Checkin();
        c.setEmployeeId(r.employeeId());
        c.setSupervisorUserId(me.getId());
        c.setSupervisorName(me.getFullName());
        c.setCheckinDate(r.date());
        c.setProgressDiscussed(trim(r.progressDiscussed()));
        c.setAgreedActions(trim(r.agreedActions()));
        Long savedId = checkins.save(c).getId();
        Long empUser = access.userOfEmployee(r.employeeId());
        service.notifyUser(empUser, "Check-in scheduled", me.getFullName() + " scheduled a performance check-in with you on " + r.date() + ".", "/appraisal/feedback");
        audit.log(AuditAction.CREATE, "AppraisalCheckin", savedId, "Scheduled a check-in with employee " + r.employeeId());
        return checkins().stream().filter(x -> x.id().equals(savedId)).findFirst().orElseThrow();
    }

    @Transactional
    public CheckinView completeCheckin(Long id, CheckinRequest r) {
        Checkin c = checkins.findById(id).orElseThrow(() -> ApiException.notFound("Check-in", id));
        if (!c.getSupervisorUserId().equals(CurrentUser.get().getId())) throw ApiException.forbidden("Only the supervisor records the check-in");
        if (blank(r.progressDiscussed())) throw ApiException.badRequest("Summarize the progress discussed");
        c.setProgressDiscussed(r.progressDiscussed().trim());
        c.setAgreedActions(trim(r.agreedActions()));
        c.setStatus(CheckinStatus.DONE);
        checkins.save(c);
        audit.log(AuditAction.UPDATE, "AppraisalCheckin", id, "Recorded check-in " + id);
        return checkins().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    public record FeedbackView(Long id, Instant date, String fromName, String toName, String type, String body, String status, boolean mine) {
    }

    public record FeedbackLists(List<FeedbackView> history, List<FeedbackView> requests, Long supervisorUserId, String supervisorName) {
    }

    @Transactional(readOnly = true)
    public FeedbackLists feedback() {
        UserPrincipal me = CurrentUser.get();
        Map<Long, Feedback> hist = new LinkedHashMap<>();
        feedback.findByToUserIdOrderByCreatedAtDesc(me.getId()).stream().filter(f -> f.getFeedbackType() != FeedbackType.REQUEST).forEach(f -> hist.put(f.getId(), f));
        if (me.getEmployeeId() != null)
            feedback.findByToEmployeeIdOrderByCreatedAtDesc(me.getEmployeeId()).stream().filter(f -> f.getFeedbackType() != FeedbackType.REQUEST).forEach(f -> hist.put(f.getId(), f));
        feedback.findByFromUserIdOrderByCreatedAtDesc(me.getId()).forEach(f -> hist.put(f.getId(), f));
        List<FeedbackView> history = hist.values().stream().sorted(Comparator.comparing(Feedback::getCreatedAt).reversed())
                .map(f -> fv(f, me.getId())).toList();
        List<FeedbackView> requests = feedback.findByToUserIdOrderByCreatedAtDesc(me.getId()).stream()
                .filter(f -> f.getFeedbackType() == FeedbackType.REQUEST).map(f -> fv(f, me.getId())).toList();
        Long sup = null;
        if (me.getEmployeeId() != null) {
            Long pid = access.currentPeriodId();
            sup = pid == null ? null : appraisals.findByPeriodIdAndEmployeeId(pid, me.getEmployeeId()).map(Appraisal::getSupervisorUserId).orElse(null);
        }
        return new FeedbackLists(history, requests, sup, access.nameOf(sup));
    }

    private static FeedbackView fv(Feedback f, Long me) {
        return new FeedbackView(f.getId(), f.getCreatedAt(), f.getFromName(), f.getToName(), f.getFeedbackType().name(), f.getBody(), f.getStatus(), f.getFromUserId().equals(me));
    }

    public record FeedbackRequest(Long toEmployeeId, String type, String body, Long requestId) {
    }

    @Transactional
    public FeedbackView sendFeedback(FeedbackRequest r) {
        UserPrincipal me = CurrentUser.get();
        if (blank(r.body())) throw ApiException.badRequest("Write the feedback");
        FeedbackType type = AppraisalService.parse(FeedbackType.class, r.type(), "feedback type");
        Feedback f = new Feedback();
        f.setFromUserId(me.getId());
        f.setFromName(me.getFullName());
        f.setFeedbackType(type);
        f.setBody(r.body().trim());
        if (type == FeedbackType.REQUEST) {
            // An employee asks their supervisor for feedback.
            Long pid = access.currentPeriodId();
            Long sup = me.getEmployeeId() == null || pid == null ? null
                    : appraisals.findByPeriodIdAndEmployeeId(pid, me.getEmployeeId()).map(Appraisal::getSupervisorUserId).orElse(null);
            if (sup == null) throw ApiException.rule("NO_SUPERVISOR", "No supervisor is assigned to your appraisal");
            f.setToUserId(sup);
            f.setToName(access.nameOf(sup));
            f.setStatus("OPEN");
            service.notifyUser(sup, "Feedback requested", me.getFullName() + " has asked you for feedback.", "/appraisal/feedback");
        } else {
            if (r.toEmployeeId() == null) throw ApiException.badRequest("Choose the employee");
            requireSupervisorOf(r.toEmployeeId());
            f.setToEmployeeId(r.toEmployeeId());
            f.setToUserId(access.userOfEmployee(r.toEmployeeId()));
            EmployeeLookup.EmployeeBrief b = lookup.brief(r.toEmployeeId());
            f.setToName(b == null ? null : b.name());
            service.notifyUser(f.getToUserId(), type == FeedbackType.RECOGNITION ? "Recognition received" : "Feedback received",
                    me.getFullName() + " sent you feedback.", "/appraisal/feedback");
            if (r.requestId() != null) {
                feedback.findById(r.requestId()).filter(q -> q.getToUserId().equals(me.getId())).ifPresent(q -> {
                    q.setStatus("ANSWERED");
                    feedback.save(q);
                });
            }
        }
        f = feedback.save(f);
        audit.log(AuditAction.CREATE, "AppraisalFeedback", f.getId(), "Sent " + type + " feedback");
        return fv(f, me.getId());
    }

    /** The current user supervises the employee (or acts for their supervisor) in the current period. */
    private void requireSupervisorOf(Long employeeId) {
        Long pid = access.currentPeriodId();
        Appraisal a = pid == null ? null : appraisals.findByPeriodIdAndEmployeeId(pid, employeeId).orElse(null);
        if (a == null || !access.asSupervisor(a).allowed()) throw ApiException.forbidden("You can do this only for members of your team");
    }

    // ===================================================================== special cases and KPI exceptions (screen 14)

    public record CaseView(Long id, String caseType, Long employeeId, String employeeName, Long kpiId, String kpiTitle,
                           LocalDate effectiveFrom, LocalDate effectiveTo, String newPosition, String previousSupervisor,
                           String targetAdjustment, String reason, String justification, BigDecimal originalValue,
                           BigDecimal adjustedValue, Long fileId, String fileName, String status, String requestedBy,
                           Instant requestedAt, String decidedBy, Instant decidedAt, String decisionComment, boolean canDecide) {
    }

    @Transactional(readOnly = true)
    public List<CaseView> cases() {
        UserPrincipal me = CurrentUser.get();
        List<SpecialCase> list = me.has(Permission.APR_MANAGE)
                ? cases.findAllByOrderByCreatedAtDesc().stream().filter(c -> {
            Set<Long> v = lookup.visibleEmployeeIds();
            return v == null || v.contains(c.getEmployeeId()) || c.getRequestedByUserId().equals(me.getId());
        }).toList()
                : cases.findByRequestedByUserIdOrderByCreatedAtDesc(me.getId());
        return caseViews(list);
    }

    private List<CaseView> caseViews(List<SpecialCase> list) {
        UserPrincipal me = CurrentUser.get();
        Map<Long, EmployeeLookup.EmployeeBrief> b = lookup.briefs(list.stream().map(SpecialCase::getEmployeeId).toList());
        Map<Long, String> kpiTitles = kpis.findAllById(list.stream().map(SpecialCase::getKpiId).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Kpi::getId, Kpi::getTitle));
        return list.stream().map(c -> new CaseView(c.getId(), c.getCaseType().name(), c.getEmployeeId(),
                Optional.ofNullable(b.get(c.getEmployeeId())).map(EmployeeLookup.EmployeeBrief::name).orElse(null),
                c.getKpiId(), c.getKpiId() == null ? null : kpiTitles.get(c.getKpiId()), c.getEffectiveFrom(), c.getEffectiveTo(),
                c.getNewPosition(), c.getPreviousSupervisor(), c.getTargetAdjustment(), c.getReason(), c.getJustification(),
                c.getOriginalValue(), c.getAdjustedValue(), c.getFileId(), c.getFileName(), c.getStatus().name(), c.getRequestedByName(),
                c.getCreatedAt(), c.getDecidedByName(), c.getDecidedAt(), c.getDecisionComment(),
                c.getStatus() == RequestStatus.PENDING && me.has(Permission.APR_MANAGE) && !c.getRequestedByUserId().equals(me.getId())
                        && !c.getEmployeeId().equals(me.getEmployeeId())
        )).toList();
    }

    public record CaseRequest(Long employeeId, String caseType, Long kpiId, LocalDate effectiveFrom, LocalDate effectiveTo,
                              String newPosition, String previousSupervisor, String targetAdjustment, String reason,
                              String justification) {
    }

    @Transactional
    public CaseView submitCase(CaseRequest r, MultipartFile attachment) {
        UserPrincipal me = CurrentUser.get();
        CaseType type = AppraisalService.parse(CaseType.class, r.caseType(), "case type");
        if (blank(r.justification())) throw ApiException.badRequest("A justification is required");
        SpecialCase c = new SpecialCase();
        c.setCaseType(type);
        c.setJustification(r.justification().trim());
        c.setReason(trim(r.reason()));
        c.setRequestedByUserId(me.getId());
        c.setRequestedByName(me.getFullName());
        Long pid = access.currentPeriodId();
        if (type == CaseType.KPI_EXCEPTION) {
            if (r.kpiId() == null) throw ApiException.badRequest("Choose the KPI");
            Kpi k = kpis.findById(r.kpiId()).orElseThrow(() -> ApiException.notFound("KPI", r.kpiId()));
            Appraisal a = service.appraisal(k.getAppraisalId());
            if (!access.isEmployee(a) && !access.asSupervisor(a).allowed() && !access.isHr(a))
                throw ApiException.forbidden("You cannot request an exception for this KPI");
            if (blank(r.reason())) throw ApiException.badRequest("Choose the reason the KPI could not be achieved");
            if (attachment == null || attachment.isEmpty()) throw ApiException.badRequest("Attach evidence for the KPI exception");
            c.setEmployeeId(a.getEmployeeId());
            c.setAppraisalId(a.getId());
            c.setKpiId(k.getId());
            c.setOriginalValue(k.getWeight());
            c.setAdjustedValue(BigDecimal.ZERO);
        } else {
            if (r.employeeId() == null || r.effectiveFrom() == null) throw ApiException.badRequest("Choose the employee and the effective date");
            if (r.effectiveTo() != null && r.effectiveTo().isBefore(r.effectiveFrom())) throw ApiException.badRequest("The end date is before the start date");
            Appraisal a = pid == null ? null : appraisals.findByPeriodIdAndEmployeeId(pid, r.employeeId()).orElse(null);
            if (a == null) throw ApiException.rule("NO_APPRAISAL", "The employee has no appraisal in the current period");
            if (!access.asSupervisor(a).allowed() && !access.isHr(a)) throw ApiException.forbidden("Only the supervisor or HR can submit a period adjustment");
            c.setEmployeeId(r.employeeId());
            c.setAppraisalId(a.getId());
            c.setEffectiveFrom(r.effectiveFrom());
            c.setEffectiveTo(r.effectiveTo());
            c.setNewPosition(trim(r.newPosition()));
            c.setPreviousSupervisor(trim(r.previousSupervisor()));
            c.setTargetAdjustment(blank(r.targetAdjustment()) ? "PRO_RATA" : r.targetAdjustment().trim().toUpperCase());
            AppraisalAccess.PeriodInfo p = access.period(a.getPeriodId());
            c.setOriginalValue(BigDecimal.valueOf(100));
            c.setAdjustedValue("PRO_RATA".equals(c.getTargetAdjustment()) ? servedShare(p, r.effectiveFrom(), r.effectiveTo(), type) : BigDecimal.valueOf(100));
        }
        if (attachment != null && !attachment.isEmpty()) {
            StoredFile f = files.store(attachment, "appraisal");
            c.setFileId(f.getId());
            c.setFileName(attachment.getOriginalFilename());
        }
        c = cases.save(c);
        audit.log(AuditAction.SUBMIT, "AppraisalSpecialCase", c.getId(), "Submitted " + type + " for employee " + c.getEmployeeId());
        return caseViews(List.of(c)).get(0);
    }

    /**
     * Share of the period the employee is assessed on: for a partial period or probation the effective dates are the
     * dates served; for leave, transfer, acting or a reporting-line change they are the dates away from the role.
     */
    private static BigDecimal servedShare(AppraisalAccess.PeriodInfo p, LocalDate from, LocalDate to, CaseType type) {
        LocalDate s = from.isBefore(p.startDate()) ? p.startDate() : from;
        LocalDate e = to == null || to.isAfter(p.endDate()) ? p.endDate() : to;
        long total = ChronoUnit.DAYS.between(p.startDate(), p.endDate()) + 1;
        long days = Math.max(0, ChronoUnit.DAYS.between(s, e) + 1);
        boolean served = type == CaseType.PARTIAL_PERIOD || type == CaseType.PROBATION;
        long share = served ? days : total - days;
        return BigDecimal.valueOf(share * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    }

    public record DecisionRequest(boolean approve, String comment) {
    }

    @Transactional
    public CaseView decideCase(Long id, DecisionRequest r) {
        requireManage();
        UserPrincipal me = CurrentUser.get();
        SpecialCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Special case", id));
        if (c.getStatus() != RequestStatus.PENDING) throw ApiException.rule("DECIDED", "This request has already been decided");
        if (c.getRequestedByUserId().equals(me.getId()) || c.getEmployeeId().equals(me.getEmployeeId()))
            throw ApiException.forbidden("Requests are decided by an officer other than the requester or the employee");
        if (!r.approve() && blank(r.comment())) throw ApiException.badRequest("Give a reason for rejecting the request");
        c.setStatus(r.approve() ? RequestStatus.APPROVED : RequestStatus.REJECTED);
        c.setDecidedByName(me.getFullName());
        c.setDecidedAt(Instant.now());
        c.setDecisionComment(trim(r.comment()));
        cases.save(c);
        if (c.getAppraisalId() != null) {
            Appraisal a = service.appraisal(c.getAppraisalId());
            String what = c.getCaseType().name().replace('_', ' ').toLowerCase();
            if (r.approve() && c.getCaseType() == CaseType.KPI_EXCEPTION && c.getKpiId() != null) {
                Kpi k = kpis.findById(c.getKpiId()).orElseThrow();
                k.setExcepted(true);
                kpis.save(k);
                if (a.getStatus() != Status.DRAFT && a.getStatus() != Status.SUBMITTED) {
                    service.recompute(a);
                    appraisals.save(a);
                }
            }
            service.addTrail(a, "HR", me.getFullName(), null, (r.approve() ? "Approved " : "Rejected ") + what, trim(r.comment()));
            service.notifyUser(c.getRequestedByUserId(), "Special case " + (r.approve() ? "approved" : "rejected"),
                    "Your " + what + " request was " + (r.approve() ? "approved." : "rejected."), "/appraisal/special-cases");
        }
        audit.log(r.approve() ? AuditAction.APPROVE : AuditAction.REJECT, "AppraisalSpecialCase", id, "Special case " + id + " " + c.getStatus());
        return caseViews(List.of(c)).get(0);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> caseFile(Long id) {
        SpecialCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Special case", id));
        UserPrincipal me = CurrentUser.get();
        boolean ok = c.getRequestedByUserId().equals(me.getId()) || (me.has(Permission.APR_MANAGE)
                && (lookup.visibleEmployeeIds() == null || lookup.visibleEmployeeIds().contains(c.getEmployeeId())));
        if (!ok || c.getFileId() == null) throw ApiException.notFound("File", id);
        audit.log(AuditAction.DOWNLOAD, "AppraisalSpecialCase", id, "Downloaded special case evidence");
        return files.download(c.getFileId());
    }

    // ===================================================================== delegation (screen 15)

    public record DelegationView(Long id, Long delegatorUserId, String delegatorName, Long delegateUserId, String delegateName,
                                 LocalDate dateFrom, LocalDate dateTo, String scope, String status, boolean mine) {
    }

    @Transactional(readOnly = true)
    public List<DelegationView> delegations() {
        Long me = CurrentUser.get().getId();
        List<Delegation> out = new ArrayList<>(delegations.findByDelegatorUserIdOrderByCreatedAtDesc(me));
        out.addAll(delegations.findByDelegateUserIdAndStatus(me, "ACTIVE"));
        LocalDate today = LocalDate.now(ZONE);
        return out.stream().map(d -> new DelegationView(d.getId(), d.getDelegatorUserId(), d.getDelegatorName(), d.getDelegateUserId(),
                d.getDelegateName(), d.getDateFrom(), d.getDateTo(), d.getScope().name(),
                "ACTIVE".equals(d.getStatus()) && today.isAfter(d.getDateTo()) ? "EXPIRED" : d.getStatus(),
                d.getDelegatorUserId().equals(me))).toList();
    }

    public record Officer(Long userId, String name) {
    }

    /** Authorized officers a responsibility can be delegated to: active users linked to an employee in service. */
    @Transactional(readOnly = true)
    public List<Officer> officers() {
        Long me = CurrentUser.get().getId();
        Set<Long> inService = lookup.inService().stream().map(EmployeeLookup.EmployeeBrief::id).collect(Collectors.toSet());
        return users.findAll().stream()
                .filter(u -> u.isActive() && u.getEmployeeId() != null && inService.contains(u.getEmployeeId()) && !u.getId().equals(me))
                .sorted(Comparator.comparing(AppUser::getFullName))
                .map(u -> new Officer(u.getId(), u.getFullName())).toList();
    }

    public record DelegationRequest(Long delegateUserId, LocalDate dateFrom, LocalDate dateTo, String scope) {
    }

    @Transactional
    public DelegationView delegate(DelegationRequest r) {
        UserPrincipal me = CurrentUser.get();
        if (r.delegateUserId() == null || r.dateFrom() == null || r.dateTo() == null) throw ApiException.badRequest("Choose the officer and the period");
        if (r.dateTo().isBefore(r.dateFrom())) throw ApiException.badRequest("The end date is before the start date");
        if (r.delegateUserId().equals(me.getId())) throw ApiException.badRequest("You cannot delegate to yourself");
        if (officers().stream().noneMatch(o -> o.userId().equals(r.delegateUserId()))) throw ApiException.badRequest("Choose an authorized officer");
        DelegationScope scope = AppraisalService.parse(DelegationScope.class, r.scope(), "scope");
        Delegation d = new Delegation();
        d.setDelegatorUserId(me.getId());
        d.setDelegatorName(me.getFullName());
        d.setDelegateUserId(r.delegateUserId());
        d.setDelegateName(access.nameOf(r.delegateUserId()));
        d.setDateFrom(r.dateFrom());
        d.setDateTo(r.dateTo());
        d.setScope(scope);
        d = delegations.save(d);
        service.notifyUser(r.delegateUserId(), "Appraisal responsibilities delegated to you",
                me.getFullName() + " delegated appraisal responsibilities to you from " + r.dateFrom() + " to " + r.dateTo() + ".", "/appraisal/team");
        audit.log(AuditAction.CREATE, "AppraisalDelegation", d.getId(), "Delegated " + scope + " to user " + r.delegateUserId());
        Long id = d.getId();
        return delegations().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public DelegationView revoke(Long id) {
        Delegation d = delegations.findById(id).orElseThrow(() -> ApiException.notFound("Delegation", id));
        if (!d.getDelegatorUserId().equals(CurrentUser.get().getId())) throw ApiException.forbidden("Only the delegator can revoke a delegation");
        d.setStatus("REVOKED");
        d.setRevokedAt(Instant.now());
        delegations.save(d);
        audit.log(AuditAction.UPDATE, "AppraisalDelegation", id, "Revoked delegation " + id);
        return delegations().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    // ===================================================================== controlled reopening (screen 15)

    public record ReopenView(Long id, String reference, Long appraisalId, String employeeName, String periodName, String reason,
                             String justification, String status, String requestedBy, Instant requestedAt, String approvedBy,
                             Instant decidedAt, boolean canDecide) {
    }

    public record ReopenCandidate(Long appraisalId, String label) {
    }

    /** Finalized appraisals the current user may ask to reopen. */
    @Transactional(readOnly = true)
    public List<ReopenCandidate> reopenCandidates() {
        UserPrincipal me = CurrentUser.get();
        List<Appraisal> list = new ArrayList<>(appraisals.findBySupervisorUserIdIn(access.supervisorIds()));
        if (me.has(Permission.APR_MANAGE)) list.addAll(appraisals.findByStatus(Status.FINALIZED));
        Map<Long, AppraisalAccess.PeriodInfo> periods = access.periods().stream().collect(Collectors.toMap(AppraisalAccess.PeriodInfo::id, p -> p));
        return list.stream().filter(a -> a.getStatus() == Status.FINALIZED).filter(a -> access.asSupervisor(a).allowed() || access.isHr(a))
                .collect(Collectors.toMap(Appraisal::getId, a -> a, (a, b) -> a, LinkedHashMap::new)).values().stream()
                .map(a -> new ReopenCandidate(a.getId(), service.employeeName(a) + ", " + Optional.ofNullable(periods.get(a.getPeriodId())).map(AppraisalAccess.PeriodInfo::name).orElse("")))
                .sorted(Comparator.comparing(ReopenCandidate::label)).toList();
    }

    @Transactional(readOnly = true)
    public List<ReopenView> reopens() {
        UserPrincipal me = CurrentUser.get();
        return reopens.findAllByOrderByCreatedAtDesc().stream()
                .filter(r -> r.getRequestedByUserId().equals(me.getId()) || access.isHr(service.appraisal(r.getAppraisalId())))
                .map(this::reopenView).toList();
    }

    public record ReopenRequest(Long appraisalId, String reason, String justification) {
    }

    @Transactional
    public ReopenView requestReopen(ReopenRequest r) {
        if (r.appraisalId() == null) throw ApiException.badRequest("Choose the appraisal");
        Appraisal a = service.appraisal(r.appraisalId());
        if (!access.asSupervisor(a).allowed() && !access.isHr(a)) throw ApiException.forbidden("Only the supervisor or HR can request reopening");
        if (a.getStatus() != Status.FINALIZED) throw ApiException.rule("NOT_FINALIZED", "Only a finalized appraisal can be reopened");
        if (blank(r.reason()) || blank(r.justification())) throw ApiException.badRequest("A reason and a justification are required");
        if (!reopens.findByAppraisalIdAndStatusIn(a.getId(), List.of(ReopenStatus.PENDING, ReopenStatus.REOPENED)).isEmpty())
            throw ApiException.rule("REOPEN_OPEN", "A reopening request is already open for this appraisal");
        UserPrincipal me = CurrentUser.get();
        Reopen ro = new Reopen();
        ro.setAppraisalId(a.getId());
        ro.setReference(nextRef("RO", reopens.count()));
        ro.setReason(r.reason().trim());
        ro.setJustification(r.justification().trim());
        ro.setRequestedByUserId(me.getId());
        ro.setRequestedByName(me.getFullName());
        ro = reopens.save(ro);
        audit.log(AuditAction.SUBMIT, "AppraisalReopen", ro.getId(), "Requested reopening of appraisal " + a.getId());
        return reopenView(ro);
    }

    @Transactional
    public ReopenView decideReopen(Long id, DecisionRequest r) {
        requireManage();
        UserPrincipal me = CurrentUser.get();
        Reopen ro = reopens.findById(id).orElseThrow(() -> ApiException.notFound("Reopening request", id));
        Appraisal a = service.appraisal(ro.getAppraisalId());
        if (!access.isHr(a)) throw ApiException.forbidden("You cannot decide this request");
        if (ro.getStatus() != ReopenStatus.PENDING) throw ApiException.rule("DECIDED", "This request has already been decided");
        if (ro.getRequestedByUserId().equals(me.getId())) throw ApiException.forbidden("Reopening is approved by an officer other than the requester");
        ro.setApprovedByName(me.getFullName());
        ro.setDecidedAt(Instant.now());
        if (!r.approve()) {
            ro.setStatus(ReopenStatus.REJECTED);
        } else {
            // The earlier version is kept as a snapshot; the appraisal goes back to the supervisor and must be finalized again.
            ro.setSnapshot(Json.write(service.toView(a)));
            ro.setStatus(ReopenStatus.REOPENED);
            a.setStatus(Status.SUBMITTED);
            a.setVerificationCompletedAt(null);
            a.setBehaviourStatus(StepStatus.DRAFT);
            a.setBehaviourSubmittedAt(null);
            a.setApprovedAt(null);
            a.setAcknowledgedAt(null);
            a.setFinalizedAt(null);
            for (Kpi k : kpis.findByAppraisalIdOrderBySortOrderAscIdAsc(a.getId())) {
                if (k.getProgressStatus() == ProgressStatus.VERIFIED) {
                    k.setProgressStatus(ProgressStatus.SUBMITTED);
                    kpis.save(k);
                }
            }
            service.addTrail(a, "HR", me.getFullName(), null, "Reopened (" + ro.getReference() + ")", ro.getReason() + ": " + ro.getJustification());
            appraisals.save(a);
            service.notifyUser(a.getSupervisorUserId(), "Appraisal reopened",
                    "The appraisal of " + service.employeeName(a) + " was reopened and must be finalized again.", "/appraisal/verify/" + a.getId());
        }
        reopens.save(ro);
        audit.log(r.approve() ? AuditAction.APPROVE : AuditAction.REJECT, "AppraisalReopen", id, "Reopening " + ro.getReference() + " " + ro.getStatus());
        return reopenView(ro);
    }

    private ReopenView reopenView(Reopen ro) {
        UserPrincipal me = CurrentUser.get();
        Appraisal a = service.appraisal(ro.getAppraisalId());
        return new ReopenView(ro.getId(), ro.getReference(), a.getId(), service.employeeName(a), access.period(a.getPeriodId()).name(),
                ro.getReason(), ro.getJustification(), ro.getStatus().name(), ro.getRequestedByName(), ro.getCreatedAt(),
                ro.getApprovedByName(), ro.getDecidedAt(),
                ro.getStatus() == ReopenStatus.PENDING && me.has(Permission.APR_MANAGE) && !ro.getRequestedByUserId().equals(me.getId()));
    }

    // ===================================================================== helpers

    private static void requireManage() {
        if (!CurrentUser.get().has(Permission.APR_MANAGE)) throw ApiException.forbidden("Appraisal administrators only");
    }

    private static String nextRef(String prefix, long count) {
        return prefix + "-" + LocalDate.now(ZONE).getYear() + "-" + String.format("%04d", count + 1);
    }
}
