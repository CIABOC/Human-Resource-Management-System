package lk.gov.ciaboc.hrms.appraisal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.gov.ciaboc.hrms.appraisal.AppraisalAccess.Acting;
import lk.gov.ciaboc.hrms.appraisal.AppraisalAccess.PeriodInfo;
import lk.gov.ciaboc.hrms.appraisal.AppraisalEntities.*;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.Json;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.files.StoredFile;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * The appraisal of one employee for one period: agreement and sign-off (screen 9), KPI progress with evidence
 * (screen 2), self-assessment (10), KPI verification (11), behavioural assessment (4), reviewing officer approval (12)
 * and acknowledgement. Status trail: Draft > Submitted > Verified > Approved > Acknowledged > Finalized.
 * Scores are always calculated by the system and cannot be edited by hand; only the reviewing officer may moderate,
 * with a required justification.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppraisalService {
    static final String ENTITY = "PerformanceAppraisal";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    private final AprAppraisalRepository appraisals;
    private final AprKpiRepository kpis;
    private final AprKpiSubmissionRepository submissions;
    private final AprEvidenceRepository evidence;
    private final AprBehaviourCriterionRepository criteria;
    private final AprBehaviourRatingRepository ratings;
    private final AprTrailRepository trail;
    private final AprAgreementHistoryRepository history;
    private final AprPolicyVersionRepository policies;
    private final AprRatingBandRepository bands;
    private final AprReopenRepository reopens;
    private final AppraisalAccess access;
    private final EmployeeLookup lookup;
    private final FileStorageService files;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final AuditService audit;
    private final ObjectMapper mapper;

    // ===================================================================== views

    public record Milestone(String name, BigDecimal weight) {
    }

    public record EvidenceView(Long id, String fileName, String status, Instant uploadedAt) {
    }

    public record SubmissionView(Long kpiId, int versionNo, Instant submittedAt, String status, BigDecimal selfProgress) {
    }

    public record KpiView(Long id, String objective, String title, String method, String target, BigDecimal weight,
                          String reviewPeriod, int sortOrder, List<Milestone> milestones, Map<String, Object> progressData,
                          BigDecimal selfProgress, String selfNote, String delayExplanation, String progressStatus,
                          String decision, BigDecimal verifiedValue, String verifierComment, boolean excepted,
                          boolean behindTarget, String evidenceStatus, String chip, List<EvidenceView> evidence,
                          List<SubmissionView> submissions) {
    }

    public record CriterionRating(Long criterionId, String code, String name, List<String> descriptors, Integer rating,
                                  boolean notApplicable, String justification) {
    }

    public record TrailView(String level, String officerName, String onBehalfOf, String decision, String comment, Instant actedAt) {
    }

    public record HistoryView(int versionNo, String changedBy, Instant changedAt, String note) {
    }

    public record Flags(boolean employee, boolean supervisor, boolean reviewer, boolean hr, String actingFor,
                        boolean canEditAgreement, boolean canSignEmployee, boolean canSignSupervisor, boolean canSignReviewer,
                        boolean canReturnAgreement, boolean canUpdateProgress, boolean canSelfAssess, boolean canVerify,
                        boolean canAssess, boolean canApprove, boolean canAcknowledge, boolean canAppeal,
                        boolean canRequestReopen, boolean canSeeBehaviour, boolean canAssignOfficers) {
    }

    public record AppraisalView(Long id, PeriodInfo period, EmployeeLookup.EmployeeBrief employee,
                                Long supervisorUserId, String supervisorName, Long reviewerUserId, String reviewerName,
                                String status, String agreementStatus, int agreementVersion,
                                Instant employeeSignedAt, Instant supervisorSignedAt, String supervisorSignedBy,
                                Instant reviewerSignedAt, String reviewerSignedBy,
                                String selfStatus, String selfAchievements, String selfChallenges, String selfComments,
                                Instant selfSubmittedAt, String behaviourStatus, String strengths, String improvements,
                                Instant behaviourSubmittedAt, Instant verificationCompletedAt,
                                BigDecimal kpiWeight, BigDecimal behaviourWeight, BigDecimal totalKpiWeight,
                                BigDecimal selfScore, BigDecimal verifiedScore, BigDecimal kpiScore, BigDecimal behaviourScore,
                                BigDecimal overallScore, BigDecimal adjustment, String adjustmentJustification,
                                BigDecimal finalScore, String category, String reviewerComments, Instant approvedAt,
                                Instant acknowledgedAt, String employeeAckComments, Instant finalizedAt,
                                LocalDate selfAssessmentCloses, LocalDate appealWindowCloses,
                                List<KpiView> kpis, List<CriterionRating> behaviour, List<TrailView> trail,
                                List<HistoryView> agreementHistory, Flags flags) {
    }

    public record ActionItem(String action, LocalDate due, String link) {
    }

    public record TrendPoint(String label, BigDecimal value) {
    }

    public record MyDashboard(AppraisalView appraisal, List<ActionItem> actions, List<TrendPoint> trend) {
    }

    // ===================================================================== reads

    @Transactional(readOnly = true)
    public AppraisalView view(Long id) {
        Appraisal a = appraisal(id);
        access.requireView(a);
        if (!access.isEmployee(a)) audit.log(AuditAction.VIEW, ENTITY, id, "Viewed performance appraisal " + id);
        return toView(a);
    }

    @Transactional(readOnly = true)
    public MyDashboard my(Long periodId) {
        Long emp = CurrentUser.employeeId();
        if (emp == null) return new MyDashboard(null, List.of(), List.of());
        Long pid = periodId != null ? periodId : access.currentPeriodId();
        Appraisal a = pid == null ? null : appraisals.findByPeriodIdAndEmployeeId(pid, emp).orElse(null);
        AppraisalView v = a == null ? null : toView(a);
        return new MyDashboard(v, v == null ? List.of() : actionsFor(a, v), trend(emp));
    }

    private List<TrendPoint> trend(Long employeeId) {
        Map<Long, PeriodInfo> periods = access.periods().stream().collect(Collectors.toMap(PeriodInfo::id, p -> p));
        return appraisals.findByEmployeeIdOrderByIdDesc(employeeId).stream()
                .filter(x -> x.getStatus() == Status.FINALIZED && x.getFinalScore() != null && periods.containsKey(x.getPeriodId()))
                .sorted(Comparator.comparing(x -> periods.get(x.getPeriodId()).startDate()))
                .map(x -> new TrendPoint(periods.get(x.getPeriodId()).name(), x.getFinalScore()))
                .toList();
    }

    private List<ActionItem> actionsFor(Appraisal a, AppraisalView v) {
        List<ActionItem> out = new ArrayList<>();
        Map<String, LocalDate> stages = access.stageEnds(a.getPeriodId());
        PeriodInfo p = v.period();
        if (a.getAgreementStatus() == AgreementStatus.DRAFT) {
            out.add(new ActionItem("Agree KPIs and sign off the performance agreement",
                    stages.getOrDefault("OBJECTIVE_SETTING", p.startDate().plusDays(30)), "/appraisal/agreement"));
        }
        if (a.getAgreementStatus() == AgreementStatus.FINAL && a.getStatus() == Status.DRAFT) {
            boolean returned = a.getStatus() == Status.DRAFT && v.kpis().stream().anyMatch(k -> "RETURNED".equals(k.progressStatus()));
            if (returned) out.add(new ActionItem("Revise KPI progress returned by your supervisor", v.selfAssessmentCloses(), "/appraisal/kpis"));
            if (v.kpis().stream().anyMatch(k -> !k.excepted() && List.of("NOT_STARTED", "IN_PROGRESS").contains(k.progressStatus())))
                out.add(new ActionItem("Update KPI progress", stages.getOrDefault("MID_YEAR_REVIEW", p.endDate()), "/appraisal/kpis"));
            if (a.getSelfStatus() != StepStatus.SUBMITTED)
                out.add(new ActionItem("Complete self-assessment", v.selfAssessmentCloses(), "/appraisal/self-assessment"));
        }
        if (a.getStatus() == Status.APPROVED) {
            LocalDate due = a.getApprovedAt() == null ? null : LocalDate.ofInstant(a.getApprovedAt(), ZONE).plusDays(14);
            out.add(new ActionItem("Acknowledge appraisal", due, "/appraisal/my"));
        }
        return out;
    }

    // ===================================================================== agreement (screen 9)

    public record KpiDef(Long id, String objective, String title, String method, String target, BigDecimal weight,
                         String reviewPeriod, List<Milestone> milestones) {
    }

    @Transactional
    public AppraisalView saveAgreement(Long id, List<KpiDef> defs) {
        Appraisal a = appraisal(id);
        requireView(a);
        if (!canEditAgreement(a)) throw ApiException.rule("AGREEMENT_LOCKED", "The agreement can be changed only while it is a draft, by the employee or the supervisor");
        List<Kpi> existing = kpis.findByAppraisalIdOrderBySortOrderAscIdAsc(id);
        Map<Long, Kpi> byId = existing.stream().collect(Collectors.toMap(Kpi::getId, k -> k));
        Set<Long> kept = new HashSet<>();
        int order = 0;
        for (KpiDef d : defs == null ? List.<KpiDef>of() : defs) {
            if (blank(d.title()) || blank(d.objective())) throw ApiException.badRequest("Every KPI needs an objective and a title");
            BigDecimal w = d.weight() == null ? BigDecimal.ZERO : d.weight();
            if (w.signum() < 0 || w.compareTo(HUNDRED) > 0) throw ApiException.badRequest("KPI weights must be between 0 and 100");
            Method m = parse(Method.class, d.method(), "measurement method");
            Kpi k = d.id() != null && byId.containsKey(d.id()) ? byId.get(d.id()) : new Kpi();
            k.setAppraisalId(id);
            k.setObjective(d.objective().trim());
            k.setTitle(d.title().trim());
            k.setMeasurementMethod(m);
            k.setTarget(trim(d.target()));
            k.setWeight(w);
            k.setReviewPeriod(trim(d.reviewPeriod()));
            k.setSortOrder(order++);
            if (m == Method.MILESTONE) {
                List<Milestone> ms = d.milestones() == null ? List.of() : d.milestones().stream().filter(x -> !blank(x.name())).toList();
                k.setMilestones(Json.write(ms));
            } else {
                k.setMilestones(null);
            }
            k = kpis.save(k);
            kept.add(k.getId());
        }
        for (Kpi k : existing) if (!kept.contains(k.getId())) kpis.delete(k);
        audit.log(AuditAction.UPDATE, ENTITY, id, "Updated objectives and KPIs of appraisal " + id);
        return toView(a);
    }

    @Transactional
    public AppraisalView signAgreement(Long id) {
        Appraisal a = appraisal(id);
        requireView(a);
        UserPrincipal me = CurrentUser.get();
        List<Kpi> list = kpis.findByAppraisalIdOrderBySortOrderAscIdAsc(id);
        switch (a.getAgreementStatus()) {
            case DRAFT -> {
                if (!access.isEmployee(a)) throw ApiException.forbidden("The employee signs off the agreement first");
                validateAgreement(list);
                a.setAgreementStatus(AgreementStatus.SUBMITTED);
                a.setEmployeeSignedAt(Instant.now());
                addHistory(a, "Signed off by the employee and submitted");
                addTrail(a, "EMPLOYEE", me.getFullName(), null, "Agreement signed off", null);
                notifyUser(a.getSupervisorUserId(), "Performance agreement to sign off",
                        employeeName(a) + " has signed off the performance agreement. Please review and sign off.", "/appraisal/agreement?appraisal=" + id);
            }
            case SUBMITTED -> {
                Acting s = access.asSupervisor(a);
                if (!s.allowed()) throw ApiException.forbidden("Only the supervisor signs off at this stage");
                a.setSupervisorSignedAt(Instant.now());
                a.setSupervisorSignedBy(me.getFullName());
                addTrail(a, "SUPERVISOR", me.getFullName(), s.onBehalfOf(), "Agreement signed off", null);
                if (a.getReviewerUserId() == null) {
                    finalizeAgreement(a, me.getFullName(), null);
                } else {
                    a.setAgreementStatus(AgreementStatus.SUPERVISOR_SIGNED);
                    notifyUser(a.getReviewerUserId(), "Performance agreement to approve",
                            "The performance agreement of " + employeeName(a) + " is waiting for your approval.", "/appraisal/agreement?appraisal=" + id);
                }
            }
            case SUPERVISOR_SIGNED -> {
                Acting r = access.asReviewer(a);
                if (!r.allowed()) throw ApiException.forbidden("Only the reviewing officer approves at this stage");
                finalizeAgreement(a, me.getFullName(), r.onBehalfOf());
            }
            case FINAL -> throw ApiException.rule("AGREEMENT_FINAL", "The agreement is already final");
        }
        audit.log(AuditAction.APPROVE, ENTITY, id, "Agreement of appraisal " + id + " signed: " + a.getAgreementStatus());
        return toView(appraisals.save(a));
    }

    private void finalizeAgreement(Appraisal a, String name, String onBehalfOf) {
        a.setAgreementStatus(AgreementStatus.FINAL);
        a.setReviewerSignedAt(Instant.now());
        a.setReviewerSignedBy(name);
        addHistory(a, "Final agreement");
        addTrail(a, a.getReviewerUserId() == null ? "SUPERVISOR" : "REVIEWING_OFFICER", name, onBehalfOf, "Agreement approved - final", null);
        notifyEmployee(a, "Performance agreement is final", "Your performance agreement is final. You can now record progress on your KPIs.", "/appraisal/kpis");
    }

    public record CommentRequest(String comment) {
    }

    @Transactional
    public AppraisalView returnAgreement(Long id, String comment) {
        Appraisal a = appraisal(id);
        requireView(a);
        Acting acting = a.getAgreementStatus() == AgreementStatus.SUBMITTED ? access.asSupervisor(a)
                : a.getAgreementStatus() == AgreementStatus.SUPERVISOR_SIGNED ? access.asReviewer(a) : Acting.NO;
        if (!acting.allowed()) throw ApiException.forbidden("You cannot return this agreement at its current stage");
        if (blank(comment)) throw ApiException.badRequest("Give a reason for returning the agreement");
        String level = a.getAgreementStatus() == AgreementStatus.SUBMITTED ? "SUPERVISOR" : "REVIEWING_OFFICER";
        a.setAgreementStatus(AgreementStatus.DRAFT);
        a.setAgreementVersion(a.getAgreementVersion() + 1);
        a.setEmployeeSignedAt(null);
        a.setSupervisorSignedAt(null);
        a.setSupervisorSignedBy(null);
        addHistory(a, "Returned for revision: " + abbreviate(comment, 200));
        addTrail(a, level, CurrentUser.get().getFullName(), acting.onBehalfOf(), "Agreement returned", comment);
        notifyEmployee(a, "Performance agreement returned", "Your performance agreement was returned for revision: " + comment, "/appraisal/agreement");
        audit.log(AuditAction.RETURN, ENTITY, id, "Agreement of appraisal " + id + " returned");
        return toView(appraisals.save(a));
    }

    private void validateAgreement(List<Kpi> list) {
        if (list.isEmpty()) throw ApiException.rule("NO_KPIS", "Add at least one objective and KPI");
        BigDecimal total = list.stream().map(Kpi::getWeight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(HUNDRED) != 0)
            throw ApiException.rule("WEIGHTS_NOT_100", "KPI weights must total 100% before the agreement is submitted (currently " + total.stripTrailingZeros().toPlainString() + "%)");
        for (Kpi k : list) {
            if (k.getMeasurementMethod() == Method.MILESTONE) {
                List<Milestone> ms = milestones(k);
                BigDecimal mw = ms.stream().map(m -> m.weight() == null ? BigDecimal.ZERO : m.weight()).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (ms.isEmpty() || mw.compareTo(HUNDRED) != 0)
                    throw ApiException.rule("MILESTONES", "The milestones of \"" + k.getTitle() + "\" must total 100%");
            }
        }
    }

    // ===================================================================== KPI progress & evidence (screen 2)

    public record ProgressRequest(List<Boolean> milestonesDone, Integer completedTasks, Integer totalTasks,
                                  LocalDate actualDate, String qualityMeasure, String description,
                                  BigDecimal selfProgress, String delayExplanation, String note) {
    }

    @Transactional
    public AppraisalView saveProgress(Long kpiId, ProgressRequest r) {
        Kpi k = kpi(kpiId);
        Appraisal a = appraisal(k.getAppraisalId());
        requireProgressEditable(a, k);
        Map<String, Object> data = new LinkedHashMap<>();
        BigDecimal progress;
        switch (k.getMeasurementMethod()) {
            case MILESTONE -> {
                List<Milestone> ms = milestones(k);
                List<Boolean> done = r.milestonesDone() == null ? List.of() : r.milestonesDone();
                BigDecimal sum = BigDecimal.ZERO;
                for (int i = 0; i < ms.size(); i++) {
                    if (i < done.size() && Boolean.TRUE.equals(done.get(i)) && ms.get(i).weight() != null) sum = sum.add(ms.get(i).weight());
                }
                data.put("milestonesDone", done.subList(0, Math.min(done.size(), ms.size())));
                progress = sum;
            }
            case TASK_COUNT -> {
                int total = r.totalTasks() == null ? 0 : r.totalTasks();
                int completed = r.completedTasks() == null ? 0 : r.completedTasks();
                if (total <= 0 || completed < 0 || completed > total) throw ApiException.badRequest("Enter completed and total tasks (completed cannot exceed total)");
                data.put("completedTasks", completed);
                data.put("totalTasks", total);
                progress = BigDecimal.valueOf(completed).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            }
            default -> {
                progress = percent(r.selfProgress(), "self-reported progress");
                if (k.getMeasurementMethod() == Method.TIMELINESS && r.actualDate() != null) data.put("actualDate", r.actualDate().toString());
                if (k.getMeasurementMethod() == Method.QUALITY && !blank(r.qualityMeasure())) data.put("qualityMeasure", r.qualityMeasure().trim());
                if (k.getMeasurementMethod() == Method.QUALITATIVE && !blank(r.description())) data.put("description", r.description().trim());
            }
        }
        k.setProgressData(Json.write(data));
        k.setSelfProgress(progress.min(HUNDRED));
        k.setDelayExplanation(trim(r.delayExplanation()));
        if (r.note() != null) k.setSelfNote(trim(r.note()));
        k.setProgressStatus(ProgressStatus.IN_PROGRESS);
        kpis.save(k);
        if (a.getSelfStatus() == StepStatus.NOT_STARTED) {
            a.setSelfStatus(StepStatus.DRAFT);
            appraisals.save(a);
        }
        audit.log(AuditAction.UPDATE, ENTITY, a.getId(), "Updated progress of KPI " + kpiId);
        return toView(a);
    }

    @Transactional
    public AppraisalView uploadEvidence(Long kpiId, MultipartFile file) {
        Kpi k = kpi(kpiId);
        Appraisal a = appraisal(k.getAppraisalId());
        requireProgressEditable(a, k);
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Choose a file to upload");
        StoredFile f = files.store(file, "appraisal");
        Evidence e = new Evidence();
        e.setKpiId(kpiId);
        e.setFileId(f.getId());
        e.setFileName(Optional.ofNullable(file.getOriginalFilename()).orElse("evidence"));
        evidence.save(e);
        audit.log(AuditAction.UPLOAD, ENTITY, a.getId(), "Uploaded evidence for KPI " + kpiId);
        return toView(a);
    }

    @Transactional
    public AppraisalView deleteEvidence(Long evidenceId) {
        Evidence e = evidence.findById(evidenceId).orElseThrow(() -> ApiException.notFound("Evidence", evidenceId));
        Kpi k = kpi(e.getKpiId());
        Appraisal a = appraisal(k.getAppraisalId());
        requireProgressEditable(a, k);
        if (e.getStatus() == EvidenceStatus.VERIFIED) throw ApiException.rule("EVIDENCE_VERIFIED", "Verified evidence cannot be removed");
        evidence.delete(e);
        audit.log(AuditAction.DELETE, ENTITY, a.getId(), "Removed evidence " + evidenceId);
        return toView(a);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> downloadEvidence(Long evidenceId) {
        Evidence e = evidence.findById(evidenceId).orElseThrow(() -> ApiException.notFound("Evidence", evidenceId));
        Appraisal a = appraisal(kpi(e.getKpiId()).getAppraisalId());
        requireView(a);
        audit.log(AuditAction.DOWNLOAD, ENTITY, a.getId(), "Downloaded evidence " + evidenceId);
        return files.download(e.getFileId());
    }

    @Transactional
    public AppraisalView submitKpi(Long kpiId) {
        Kpi k = kpi(kpiId);
        Appraisal a = appraisal(k.getAppraisalId());
        requireProgressEditable(a, k);
        if (k.getSelfProgress() == null) throw ApiException.rule("NO_PROGRESS", "Save your progress before submitting it for verification");
        PeriodInfo p = access.period(a.getPeriodId());
        if (behind(k, p) && blank(k.getDelayExplanation()))
            throw ApiException.rule("EXPLANATION_REQUIRED", "Explanation is required because \"" + k.getTitle() + "\" is behind its target");
        boolean hasEvidence = evidence.findByKpiId(kpiId).stream().anyMatch(e -> e.getStatus() != EvidenceStatus.RETURNED);
        if (!hasEvidence) throw ApiException.rule("EVIDENCE_REQUIRED", "Upload the required evidence before submitting for verification");
        k.setSubmissionCount(k.getSubmissionCount() + 1);
        k.setProgressStatus(ProgressStatus.SUBMITTED);
        k.setDecision(null);
        kpis.save(k);
        KpiSubmission s = new KpiSubmission();
        s.setKpiId(kpiId);
        s.setVersionNo(k.getSubmissionCount());
        s.setSelfProgress(k.getSelfProgress());
        s.setProgressData(k.getProgressData());
        s.setDelayExplanation(k.getDelayExplanation());
        s.setStatus("SUBMITTED");
        s.setSubmittedAt(Instant.now());
        submissions.save(s);
        notifyUser(a.getSupervisorUserId(), "KPI progress to verify",
                employeeName(a) + " submitted progress on \"" + k.getTitle() + "\" for verification.", "/appraisal/verify/" + a.getId());
        audit.log(AuditAction.SUBMIT, ENTITY, a.getId(), "Submitted KPI " + kpiId + " for verification (v" + s.getVersionNo() + ")");
        return toView(a);
    }

    private void requireProgressEditable(Appraisal a, Kpi k) {
        if (!access.isEmployee(a)) throw ApiException.forbidden("Only the employee records KPI progress");
        if (a.getAgreementStatus() != AgreementStatus.FINAL) throw ApiException.rule("AGREEMENT_NOT_FINAL", "Progress can be recorded once the performance agreement is final");
        if (a.getStatus() != Status.DRAFT) throw ApiException.rule("APPRAISAL_SUBMITTED", "Progress cannot be changed after the self-assessment is submitted");
        if (k.isExcepted()) throw ApiException.rule("KPI_EXCEPTED", "This KPI has an approved exception");
        if (k.getProgressStatus() == ProgressStatus.SUBMITTED || k.getProgressStatus() == ProgressStatus.VERIFIED)
            throw ApiException.rule("KPI_SUBMITTED", "This KPI is with your supervisor for verification");
    }

    // ===================================================================== self-assessment (screen 10)

    public record SelfLine(Long kpiId, BigDecimal selfProgress, String note) {
    }

    public record SelfAssessmentRequest(List<SelfLine> lines, String achievements, String challenges, String comments, boolean submit) {
    }

    @Transactional
    public AppraisalView saveSelfAssessment(Long id, SelfAssessmentRequest r) {
        Appraisal a = appraisal(id);
        if (!access.isEmployee(a)) throw ApiException.forbidden("Only the employee completes the self-assessment");
        if (a.getAgreementStatus() != AgreementStatus.FINAL) throw ApiException.rule("AGREEMENT_NOT_FINAL", "The self-assessment opens once the performance agreement is final");
        if (a.getSelfStatus() == StepStatus.SUBMITTED || a.getStatus() != Status.DRAFT)
            throw ApiException.rule("SELF_SUBMITTED", "The self-assessment cannot be edited after submission");
        LocalDate closes = selfAssessmentCloses(a, access.period(a.getPeriodId()));
        if (closes != null && LocalDate.now(ZONE).isAfter(closes)) throw ApiException.rule("SELF_CLOSED", "The self-assessment closed on " + closes);
        Map<Long, Kpi> byId = kpis.findByAppraisalIdOrderBySortOrderAscIdAsc(id).stream().collect(Collectors.toMap(Kpi::getId, k -> k));
        for (SelfLine l : r.lines() == null ? List.<SelfLine>of() : r.lines()) {
            Kpi k = byId.get(l.kpiId());
            if (k == null || k.isExcepted()) continue;
            if (l.selfProgress() != null) k.setSelfProgress(percent(l.selfProgress(), "self-reported achievement"));
            k.setSelfNote(trim(l.note()));
            if (k.getProgressStatus() == ProgressStatus.NOT_STARTED && k.getSelfProgress() != null) k.setProgressStatus(ProgressStatus.IN_PROGRESS);
            kpis.save(k);
        }
        a.setSelfAchievements(trim(r.achievements()));
        a.setSelfChallenges(trim(r.challenges()));
        a.setSelfComments(trim(r.comments()));
        a.setSelfStatus(StepStatus.DRAFT);
        if (r.submit()) {
            List<Kpi> missing = byId.values().stream().filter(k -> !k.isExcepted() && k.getSelfProgress() == null).toList();
            if (!missing.isEmpty()) throw ApiException.rule("SELF_INCOMPLETE", "Enter your self-reported achievement for every KPI");
            a.setSelfStatus(StepStatus.SUBMITTED);
            a.setSelfSubmittedAt(Instant.now());
            a.setStatus(Status.SUBMITTED);
            addTrail(a, "EMPLOYEE", CurrentUser.get().getFullName(), null, "Self-assessment submitted", null);
            notifyUser(a.getSupervisorUserId(), "Self-assessment received",
                    employeeName(a) + " has submitted the self-assessment. Verify the KPIs and complete the behavioural assessment.", "/appraisal/verify/" + id);
            audit.log(AuditAction.SUBMIT, ENTITY, id, "Submitted self-assessment of appraisal " + id);
        } else {
            audit.log(AuditAction.UPDATE, ENTITY, id, "Saved self-assessment draft of appraisal " + id);
        }
        return toView(appraisals.save(a));
    }

    // ===================================================================== KPI verification (screen 11)

    public record VerifyLine(Long kpiId, String decision, BigDecimal verifiedValue, String comment) {
    }

    public record VerificationRequest(List<VerifyLine> lines, boolean complete) {
    }

    @Transactional
    public AppraisalView saveVerification(Long id, VerificationRequest r) {
        Appraisal a = appraisal(id);
        Acting s = access.asSupervisor(a);
        if (!s.allowed()) throw ApiException.forbidden("Only the supervisor (or a delegate) verifies KPIs");
        if (a.getAgreementStatus() != AgreementStatus.FINAL || (a.getStatus() != Status.DRAFT && a.getStatus() != Status.SUBMITTED))
            throw ApiException.rule("NOT_VERIFIABLE", "KPIs can be verified only while the appraisal is in progress");
        List<Kpi> list = kpis.findByAppraisalIdOrderBySortOrderAscIdAsc(id);
        Map<Long, Kpi> byId = list.stream().collect(Collectors.toMap(Kpi::getId, k -> k));
        for (VerifyLine l : r.lines() == null ? List.<VerifyLine>of() : r.lines()) {
            Kpi k = byId.get(l.kpiId());
            if (k == null || k.isExcepted()) continue;
            Decision d = blank(l.decision()) ? null : parse(Decision.class, l.decision(), "decision");
            k.setDecision(d);
            k.setVerifierComment(trim(l.comment()));
            if (d == Decision.VERIFIED) k.setVerifiedValue(l.verifiedValue() != null ? percent(l.verifiedValue(), "verified value") : k.getSelfProgress());
            else if (d == Decision.ADJUSTED) k.setVerifiedValue(l.verifiedValue() == null ? null : percent(l.verifiedValue(), "verified value"));
            else k.setVerifiedValue(null);
            kpis.save(k);
        }
        if (r.complete()) {
            if (a.getStatus() != Status.SUBMITTED) throw ApiException.rule("SELF_NOT_SUBMITTED", "Verification can be completed after the employee submits the self-assessment");
            for (Kpi k : list) {
                if (k.isExcepted()) continue;
                if (k.getDecision() == null) throw ApiException.rule("DECISION_MISSING", "Every KPI needs a decision before verification is completed");
                if (k.getDecision() == Decision.RETURNED) throw ApiException.rule("KPI_RETURNED", "\"" + k.getTitle() + "\" is marked Returned - use Return to employee instead");
                if (k.getDecision() == Decision.ADJUSTED && (k.getVerifiedValue() == null || blank(k.getVerifierComment())))
                    throw ApiException.rule("ADJUSTED_INCOMPLETE", "Adjusted decisions need a verified value and a comment (\"" + k.getTitle() + "\")");
                if (k.getVerifiedValue() == null) throw ApiException.rule("VALUE_MISSING", "Enter the verified value of \"" + k.getTitle() + "\"");
            }
            for (Kpi k : list) {
                if (k.isExcepted()) continue;
                k.setProgressStatus(ProgressStatus.VERIFIED);
                kpis.save(k);
                for (Evidence e : evidence.findByKpiId(k.getId())) {
                    if (e.getStatus() == EvidenceStatus.PENDING) {
                        e.setStatus(EvidenceStatus.VERIFIED);
                        evidence.save(e);
                    }
                }
                submissions.findFirstByKpiIdOrderByVersionNoDesc(k.getId()).ifPresent(sub -> {
                    sub.setStatus(k.getDecision().name());
                    submissions.save(sub);
                });
            }
            a.setVerificationCompletedAt(Instant.now());
            a.setKpiScore(kpiScore(list));
            addTrail(a, "SUPERVISOR", CurrentUser.get().getFullName(), s.onBehalfOf(), "KPIs verified", null);
            audit.log(AuditAction.APPROVE, ENTITY, id, "Completed KPI verification of appraisal " + id);
            maybeAdvance(a, s);
        } else {
            audit.log(AuditAction.UPDATE, ENTITY, id, "Saved KPI verification of appraisal " + id);
        }
        return toView(appraisals.save(a));
    }

    @Transactional
    public AppraisalView returnToEmployee(Long id, String comment) {
        Appraisal a = appraisal(id);
        Acting s = access.asSupervisor(a);
        if (!s.allowed()) throw ApiException.forbidden("Only the supervisor (or a delegate) can return the submission");
        if (a.getStatus() != Status.SUBMITTED && a.getStatus() != Status.DRAFT) throw ApiException.rule("NOT_RETURNABLE", "The appraisal is not with the supervisor");
        if (blank(comment)) throw ApiException.badRequest("Give a reason for returning the submission");
        for (Kpi k : kpis.findByAppraisalIdOrderBySortOrderAscIdAsc(id)) {
            if (k.getDecision() != Decision.RETURNED) continue;
            k.setProgressStatus(ProgressStatus.RETURNED);
            kpis.save(k);
            for (Evidence e : evidence.findByKpiId(k.getId())) {
                if (e.getStatus() == EvidenceStatus.PENDING) {
                    e.setStatus(EvidenceStatus.RETURNED);
                    evidence.save(e);
                }
            }
            submissions.findFirstByKpiIdOrderByVersionNoDesc(k.getId()).ifPresent(sub -> {
                sub.setStatus("RETURNED");
                submissions.save(sub);
            });
        }
        a.setStatus(Status.DRAFT);
        if (a.getSelfStatus() == StepStatus.SUBMITTED) a.setSelfStatus(StepStatus.DRAFT);
        a.setSelfSubmittedAt(null);
        a.setVerificationCompletedAt(null);
        addTrail(a, "SUPERVISOR", CurrentUser.get().getFullName(), s.onBehalfOf(), "Returned to employee", comment);
        notifyEmployee(a, "Appraisal returned for revision", "Your supervisor returned your KPI submission: " + comment, "/appraisal/kpis");
        audit.log(AuditAction.RETURN, ENTITY, id, "Returned appraisal " + id + " to the employee");
        return toView(appraisals.save(a));
    }

    // ===================================================================== behavioural assessment (screen 4)

    public record RatingLine(Long criterionId, Integer rating, boolean notApplicable, String justification) {
    }

    public record BehaviourRequest(List<RatingLine> ratings, String strengths, String improvements, boolean submit) {
    }

    @Transactional
    public AppraisalView saveBehaviour(Long id, BehaviourRequest r) {
        Appraisal a = appraisal(id);
        Acting s = access.asSupervisor(a);
        if (!s.allowed()) throw ApiException.forbidden("Only the supervisor (or a delegate) completes the behavioural assessment");
        if (a.getBehaviourStatus() == StepStatus.SUBMITTED) throw ApiException.rule("BEHAVIOUR_SUBMITTED", "The behavioural assessment has been submitted");
        if (a.getStatus() != Status.DRAFT && a.getStatus() != Status.SUBMITTED) throw ApiException.rule("NOT_EDITABLE", "The appraisal is no longer with the supervisor");
        Map<Long, BehaviourCriterion> active = criteria.findByActiveTrueOrderBySortOrderAsc().stream().collect(Collectors.toMap(BehaviourCriterion::getId, c -> c));
        Map<Long, BehaviourRating> existing = ratings.findByAppraisalId(id).stream().collect(Collectors.toMap(BehaviourRating::getCriterionId, x -> x, (x, y) -> x));
        for (RatingLine l : r.ratings() == null ? List.<RatingLine>of() : r.ratings()) {
            if (!active.containsKey(l.criterionId())) continue;
            if (l.rating() != null && (l.rating() < 1 || l.rating() > 5)) throw ApiException.badRequest("Ratings run from 1 (Very Bad) to 5 (Very Good)");
            BehaviourRating br = existing.getOrDefault(l.criterionId(), new BehaviourRating());
            br.setAppraisalId(id);
            br.setCriterionId(l.criterionId());
            br.setNotApplicable(l.notApplicable());
            br.setRating(l.notApplicable() ? null : l.rating());
            br.setJustification(trim(l.justification()));
            existing.put(l.criterionId(), ratings.save(br));
        }
        a.setStrengths(trim(r.strengths()));
        a.setImprovements(trim(r.improvements()));
        a.setBehaviourStatus(StepStatus.DRAFT);
        if (r.submit()) {
            int rated = 0;
            List<String> missing = new ArrayList<>();
            for (BehaviourCriterion c : active.values()) {
                BehaviourRating br = existing.get(c.getId());
                if (br != null && br.isNotApplicable()) continue;
                if (br == null || br.getRating() == null || blank(br.getJustification())) missing.add(c.getName());
                else rated++;
            }
            if (!missing.isEmpty())
                throw ApiException.rule("JUSTIFICATION_REQUIRED", missing.size() + " criteria need a rating and a justification before you can submit: " + String.join(", ", missing));
            if (rated == 0) throw ApiException.rule("NO_RATINGS", "Rate at least one criterion");
            a.setBehaviourStatus(StepStatus.SUBMITTED);
            a.setBehaviourSubmittedAt(Instant.now());
            a.setBehaviourScore(behaviourScore(ratings.findByAppraisalId(id)));
            addTrail(a, "SUPERVISOR", CurrentUser.get().getFullName(), s.onBehalfOf(), "Behavioural assessment submitted", null);
            audit.log(AuditAction.SUBMIT, ENTITY, id, "Submitted behavioural assessment of appraisal " + id);
            maybeAdvance(a, s);
        } else {
            audit.log(AuditAction.UPDATE, ENTITY, id, "Saved behavioural assessment draft of appraisal " + id);
        }
        return toView(appraisals.save(a));
    }

    /** The review goes to the reviewing officer only after every KPI has a decision and the behavioural assessment is in. */
    private void maybeAdvance(Appraisal a, Acting s) {
        if (a.getStatus() != Status.SUBMITTED || a.getSelfStatus() != StepStatus.SUBMITTED
                || a.getVerificationCompletedAt() == null || a.getBehaviourStatus() != StepStatus.SUBMITTED) return;
        recompute(a);
        a.setStatus(Status.VERIFIED);
        addTrail(a, "SUPERVISOR", CurrentUser.get().getFullName(), s.onBehalfOf(), "Verified", null);
        notifyUser(a.getReviewerUserId(), "Appraisal to approve",
                "The appraisal of " + employeeName(a) + " is ready for your approval.", "/appraisal/approvals/" + a.getId());
    }

    // ===================================================================== reviewing officer (screen 12)

    public record ApprovalRequest(BigDecimal adjustment, String justification, String comments) {
    }

    @Transactional
    public AppraisalView approve(Long id, ApprovalRequest r) {
        Appraisal a = appraisal(id);
        Acting rv = access.asReviewer(a);
        if (!rv.allowed()) throw ApiException.forbidden("Only the reviewing officer (or a delegate) approves the appraisal");
        if (a.getStatus() != Status.VERIFIED) throw ApiException.rule("NOT_VERIFIED", "The appraisal can be approved after the supervisor has verified it");
        BigDecimal adj = r.adjustment() == null ? BigDecimal.ZERO : r.adjustment();
        if (adj.abs().compareTo(HUNDRED) > 0) throw ApiException.badRequest("The adjustment must be between -100 and 100");
        if (adj.signum() != 0 && blank(r.justification())) throw ApiException.rule("JUSTIFICATION_REQUIRED", "A justification is required when the score is adjusted");
        a.setAdjustment(adj.signum() == 0 ? null : adj);
        a.setAdjustmentJustification(adj.signum() == 0 ? null : r.justification().trim());
        a.setReviewerComments(trim(r.comments()));
        recompute(a);
        a.setStatus(Status.APPROVED);
        a.setApprovedAt(Instant.now());
        addTrail(a, "REVIEWING_OFFICER", CurrentUser.get().getFullName(), rv.onBehalfOf(),
                adj.signum() == 0 ? "Approved" : "Approved with adjustment (" + adj.stripTrailingZeros().toPlainString() + ")", trim(r.comments()));
        notifyEmployee(a, "Appraisal approved", "Your appraisal has been approved. Please review and acknowledge it.", "/appraisal/my");
        audit.log(AuditAction.APPROVE, ENTITY, id, "Approved appraisal " + id + " - final score " + a.getFinalScore());
        return toView(appraisals.save(a));
    }

    @Transactional
    public AppraisalView returnForCorrection(Long id, String comment) {
        Appraisal a = appraisal(id);
        Acting rv = access.asReviewer(a);
        if (!rv.allowed()) throw ApiException.forbidden("Only the reviewing officer (or a delegate) can return the appraisal");
        if (a.getStatus() != Status.VERIFIED) throw ApiException.rule("NOT_VERIFIED", "Only a verified appraisal can be returned for correction");
        if (blank(comment)) throw ApiException.badRequest("Give a reason for returning the appraisal");
        a.setStatus(Status.SUBMITTED);
        a.setVerificationCompletedAt(null);
        a.setBehaviourStatus(StepStatus.DRAFT);
        a.setBehaviourSubmittedAt(null);
        for (Kpi k : kpis.findByAppraisalIdOrderBySortOrderAscIdAsc(id)) {
            if (k.getProgressStatus() == ProgressStatus.VERIFIED) {
                k.setProgressStatus(ProgressStatus.SUBMITTED);
                kpis.save(k);
            }
        }
        addTrail(a, "REVIEWING_OFFICER", CurrentUser.get().getFullName(), rv.onBehalfOf(), "Returned for correction", comment);
        notifyUser(a.getSupervisorUserId(), "Appraisal returned for correction",
                "The reviewing officer returned the appraisal of " + employeeName(a) + ": " + comment, "/appraisal/verify/" + id);
        audit.log(AuditAction.RETURN, ENTITY, id, "Reviewing officer returned appraisal " + id);
        return toView(appraisals.save(a));
    }

    // ===================================================================== acknowledgement & finalization

    @Transactional
    public AppraisalView acknowledge(Long id, String comments) {
        Appraisal a = appraisal(id);
        if (!access.isEmployee(a)) throw ApiException.forbidden("Only the employee acknowledges the appraisal");
        if (a.getStatus() != Status.APPROVED) throw ApiException.rule("NOT_APPROVED", "The appraisal can be acknowledged after it is approved");
        Instant now = Instant.now();
        a.setAcknowledgedAt(now);
        a.setEmployeeAckComments(trim(comments));
        a.setStatus(Status.ACKNOWLEDGED);
        addTrail(a, "EMPLOYEE", CurrentUser.get().getFullName(), null, "Acknowledged", trim(comments));
        a.setStatus(Status.FINALIZED);
        a.setFinalizedAt(now);
        addTrail(a, "SYSTEM", "HRMS", null, "Finalized", null);
        for (Reopen ro : reopens.findByAppraisalIdAndStatus(id, ReopenStatus.REOPENED)) {
            ro.setStatus(ReopenStatus.FINALIZED_AGAIN);
            reopens.save(ro);
        }
        audit.log(AuditAction.APPROVE, ENTITY, id, "Appraisal " + id + " acknowledged and finalized");
        return toView(appraisals.save(a));
    }

    // ===================================================================== HR: officers and starting a period

    public record OfficersRequest(Long supervisorUserId, Long reviewerUserId) {
    }

    @Transactional
    public AppraisalView setOfficers(Long id, OfficersRequest r) {
        Appraisal a = appraisal(id);
        if (!access.isHr(a)) throw ApiException.forbidden("Appraisal administrators assign officers");
        Long empUser = access.userOfEmployee(a.getEmployeeId());
        if (r.supervisorUserId() != null && r.supervisorUserId().equals(empUser)) throw ApiException.badRequest("An employee cannot supervise their own appraisal");
        if (r.reviewerUserId() != null && r.reviewerUserId().equals(empUser)) throw ApiException.badRequest("An employee cannot review their own appraisal");
        if (r.reviewerUserId() != null && r.reviewerUserId().equals(r.supervisorUserId())) throw ApiException.badRequest("The supervisor and the reviewing officer must be different people");
        String old = a.getSupervisorUserId() + "/" + a.getReviewerUserId();
        a.setSupervisorUserId(r.supervisorUserId());
        a.setReviewerUserId(r.reviewerUserId());
        addTrail(a, "HR", CurrentUser.get().getFullName(), null, "Officers assigned",
                "Supervisor: " + Objects.toString(access.nameOf(r.supervisorUserId()), "-") + "; reviewing officer: " + Objects.toString(access.nameOf(r.reviewerUserId()), "-"));
        audit.log(AuditAction.UPDATE, ENTITY, id, "Assigned appraisal officers", old, r.supervisorUserId() + "/" + r.reviewerUserId());
        return toView(appraisals.save(a));
    }

    public record StartResult(int created, int skipped, int withoutSupervisor) {
    }

    /** Creates appraisals for every in-service employee who does not yet have one in the period (Module 02 sync). */
    @Transactional
    public StartResult start(Long periodId) {
        PeriodInfo p = access.period(periodId);
        if (!"OPEN".equals(p.status())) throw ApiException.rule("PERIOD_CLOSED", "Appraisals can be started only for an open period");
        PolicyVersion policy = policies.findFirstByStatusOrderByVersionNoDesc(PolicyStatus.APPROVED)
                .orElseThrow(() -> ApiException.rule("NO_POLICY", "There is no approved appraisal policy"));
        int created = 0, skipped = 0, noSup = 0;
        for (EmployeeLookup.EmployeeBrief e : lookup.inService()) {
            if (appraisals.existsByPeriodIdAndEmployeeId(periodId, e.id())) {
                skipped++;
                continue;
            }
            AppraisalAccess.Officers o = access.resolve(e.id());
            Appraisal a = new Appraisal();
            a.setPeriodId(periodId);
            a.setEmployeeId(e.id());
            a.setPolicyVersionId(policy.getId());
            a.setKpiWeight(policy.getKpiWeight());
            a.setBehaviourWeight(policy.getBehaviourWeight());
            a.setSupervisorUserId(o.supervisorUserId());
            a.setReviewerUserId(o.reviewerUserId());
            a = appraisals.save(a);
            addHistory(a, "Agreement created");
            if (o.supervisorUserId() == null) noSup++;
            created++;
        }
        audit.log(AuditAction.PROCESS, ENTITY, periodId, "Started appraisals for period " + p.code() + ": " + created + " created");
        return new StartResult(created, skipped, noSup);
    }

    /** Re-resolves supervisor and reviewing officer of appraisals still with the employee or the supervisor (Module 01 sync). */
    @Transactional
    public int refreshOfficers(Long periodId) {
        int changed = 0;
        for (Appraisal a : appraisals.findByPeriodId(periodId)) {
            if (a.getStatus() != Status.DRAFT) continue;
            AppraisalAccess.Officers o = access.resolve(a.getEmployeeId());
            if (!Objects.equals(o.supervisorUserId(), a.getSupervisorUserId()) || !Objects.equals(o.reviewerUserId(), a.getReviewerUserId())) {
                if (o.supervisorUserId() == null) continue;
                a.setSupervisorUserId(o.supervisorUserId());
                a.setReviewerUserId(o.reviewerUserId());
                appraisals.save(a);
                changed++;
            }
        }
        return changed;
    }

    // ===================================================================== scoring

    /** Overall KPI achievement = verified achievement of each KPI x its weight, added together (KPI exceptions excluded). */
    static BigDecimal kpiScore(List<Kpi> list) {
        return weighted(list, k -> k.getDecision() == Decision.VERIFIED || k.getDecision() == Decision.ADJUSTED ? k.getVerifiedValue() : null, false);
    }

    static BigDecimal selfScore(List<Kpi> list) {
        return weighted(list, Kpi::getSelfProgress, false);
    }

    private static BigDecimal weighted(List<Kpi> list, java.util.function.Function<Kpi, BigDecimal> value, boolean skipMissing) {
        BigDecimal weights = BigDecimal.ZERO, sum = BigDecimal.ZERO;
        boolean any = false;
        for (Kpi k : list) {
            if (k.isExcepted() || k.getWeight() == null) continue;
            BigDecimal v = value.apply(k);
            if (v == null && skipMissing) continue;
            weights = weights.add(k.getWeight());
            if (v != null) {
                any = true;
                sum = sum.add(v.multiply(k.getWeight()));
            }
        }
        if (!any || weights.signum() == 0) return null;
        return sum.divide(weights, 2, RoundingMode.HALF_UP);
    }

    /** Behavioural result: average of the applicable ratings on 1-5, expressed as 0-100. N/A criteria are left out. */
    static BigDecimal behaviourScore(List<BehaviourRating> list) {
        List<Integer> r = list.stream().filter(x -> !x.isNotApplicable() && x.getRating() != null).map(BehaviourRating::getRating).toList();
        if (r.isEmpty()) return null;
        BigDecimal avg = BigDecimal.valueOf(r.stream().mapToInt(Integer::intValue).sum()).divide(BigDecimal.valueOf(r.size()), 6, RoundingMode.HALF_UP);
        return avg.subtract(BigDecimal.ONE).multiply(HUNDRED).divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP);
    }

    /** Final score = KPI result x KPI weight + behavioural result x behavioural weight (+ moderation), clamped to 0-100. */
    void recompute(Appraisal a) {
        List<Kpi> list = kpis.findByAppraisalIdOrderBySortOrderAscIdAsc(a.getId());
        a.setKpiScore(kpiScore(list));
        a.setBehaviourScore(behaviourScore(ratings.findByAppraisalId(a.getId())));
        BigDecimal k = a.getKpiScore() == null ? BigDecimal.ZERO : a.getKpiScore();
        BigDecimal b = a.getBehaviourScore() == null ? BigDecimal.ZERO : a.getBehaviourScore();
        BigDecimal overall = k.multiply(a.getKpiWeight()).add(b.multiply(a.getBehaviourWeight())).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        a.setOverallScore(overall);
        BigDecimal fin = overall.add(a.getAdjustment() == null ? BigDecimal.ZERO : a.getAdjustment()).max(BigDecimal.ZERO).min(HUNDRED);
        a.setFinalScore(fin);
        a.setCategory(category(a.getPolicyVersionId(), fin));
    }

    String category(Long policyVersionId, BigDecimal score) {
        if (policyVersionId == null || score == null) return null;
        BigDecimal s = score.setScale(2, RoundingMode.HALF_UP);
        return bands.findByPolicyVersionIdOrderBySortOrderAsc(policyVersionId).stream()
                .filter(b -> s.compareTo(b.getMinScore()) >= 0 && s.compareTo(b.getMaxScore()) <= 0)
                .map(RatingBand::getCategory).findFirst().orElse(null);
    }

    /** Expected progress by now is the elapsed share of the period; a KPI below it (and below 100%) is behind target. */
    static boolean behind(Kpi k, PeriodInfo p) {
        if (k.getSelfProgress() == null || k.getSelfProgress().compareTo(HUNDRED) >= 0) return false;
        long total = ChronoUnit.DAYS.between(p.startDate(), p.endDate()) + 1;
        long elapsed = ChronoUnit.DAYS.between(p.startDate(), LocalDate.now(ZONE)) + 1;
        if (total <= 0) return false;
        double expected = Math.max(0, Math.min(100, elapsed * 100.0 / total));
        return k.getSelfProgress().doubleValue() + 0.01 < expected;
    }

    LocalDate selfAssessmentCloses(Appraisal a, PeriodInfo p) {
        LocalDate stage = access.stageEnds(a.getPeriodId()).get("SELF_ASSESSMENT");
        if (stage != null && !stage.isBefore(p.endDate())) return stage;
        if (p.dueDate() != null) return p.dueDate();
        return p.endDate().plusDays(settings.getInt("appraisal.self-assessment-days", 30));
    }

    LocalDate appealWindowCloses(Appraisal a) {
        if (a.getFinalizedAt() == null) return null;
        return LocalDate.ofInstant(a.getFinalizedAt(), ZONE).plusDays(settings.getInt("appraisal.appeal-window-days", 14));
    }

    // ===================================================================== mapping

    AppraisalView toView(Appraisal a) {
        PeriodInfo p = access.period(a.getPeriodId());
        List<Kpi> list = kpis.findByAppraisalIdOrderBySortOrderAscIdAsc(a.getId());
        List<Long> ids = list.stream().map(Kpi::getId).toList();
        Map<Long, List<Evidence>> ev = ids.isEmpty() ? Map.of() : evidence.findByKpiIdInOrderByIdAsc(ids).stream().collect(Collectors.groupingBy(Evidence::getKpiId));
        Map<Long, List<KpiSubmission>> subs = ids.isEmpty() ? Map.of() : submissions.findByKpiIdInOrderByVersionNoDesc(ids).stream().collect(Collectors.groupingBy(KpiSubmission::getKpiId));
        boolean emp = access.isEmployee(a);
        Acting sup = access.asSupervisor(a);
        Acting rev = access.asReviewer(a);
        boolean hr = access.isHr(a);
        boolean seeBehaviour = sup.allowed() || rev.allowed() || hr || (emp && a.getStatus() == Status.FINALIZED);

        List<KpiView> kv = list.stream().map(k -> {
            List<Evidence> es = ev.getOrDefault(k.getId(), List.of());
            String evStatus = es.isEmpty() ? "Not submitted"
                    : es.stream().anyMatch(e -> e.getStatus() == EvidenceStatus.RETURNED) ? "Returned"
                    : es.stream().allMatch(e -> e.getStatus() == EvidenceStatus.VERIFIED) ? "Verified" : "Pending";
            return new KpiView(k.getId(), k.getObjective(), k.getTitle(), k.getMeasurementMethod().name(), k.getTarget(), k.getWeight(),
                    k.getReviewPeriod(), k.getSortOrder(), milestones(k), progressData(k), k.getSelfProgress(), k.getSelfNote(),
                    k.getDelayExplanation(), k.getProgressStatus().name(), k.getDecision() == null ? null : k.getDecision().name(),
                    k.getVerifiedValue(), k.getVerifierComment(), k.isExcepted(), behind(k, p), evStatus, chip(k),
                    es.stream().map(e -> new EvidenceView(e.getId(), e.getFileName(), e.getStatus().name(), e.getCreatedAt())).toList(),
                    subs.getOrDefault(k.getId(), List.of()).stream()
                            .map(s -> new SubmissionView(k.getId(), s.getVersionNo(), s.getSubmittedAt(), s.getStatus(), s.getSelfProgress())).toList());
        }).toList();

        List<CriterionRating> behaviour = List.of();
        if (seeBehaviour) {
            Map<Long, BehaviourRating> rs = ratings.findByAppraisalId(a.getId()).stream().collect(Collectors.toMap(BehaviourRating::getCriterionId, x -> x, (x, y) -> x));
            behaviour = criteria.findByActiveTrueOrderBySortOrderAsc().stream().map(c -> {
                BehaviourRating r = rs.get(c.getId());
                return new CriterionRating(c.getId(), c.getCode(), c.getName(),
                        Arrays.asList(c.getDescriptor1(), c.getDescriptor2(), c.getDescriptor3(), c.getDescriptor4(), c.getDescriptor5()),
                        r == null ? null : r.getRating(), r != null && r.isNotApplicable(), r == null ? null : r.getJustification());
            }).toList();
        }
        // Scores calculated from the supervisor's assessment are not shown to the employee before finalization.
        boolean seeScores = seeBehaviour || (emp && (a.getStatus() == Status.APPROVED || a.getStatus() == Status.FINALIZED));
        Map<Long, String> names = access.namesOf(Arrays.asList(a.getSupervisorUserId(), a.getReviewerUserId()));

        LocalDate closes = selfAssessmentCloses(a, p);
        LocalDate appealCloses = appealWindowCloses(a);
        boolean draftAgreement = a.getAgreementStatus() == AgreementStatus.DRAFT && a.getStatus() == Status.DRAFT;
        Flags flags = new Flags(emp, sup.allowed(), rev.allowed(), hr,
                sup.onBehalfOf() != null ? sup.onBehalfOf() : rev.onBehalfOf(),
                draftAgreement && (emp || sup.allowed()),
                emp && a.getAgreementStatus() == AgreementStatus.DRAFT,
                sup.allowed() && a.getAgreementStatus() == AgreementStatus.SUBMITTED,
                rev.allowed() && a.getAgreementStatus() == AgreementStatus.SUPERVISOR_SIGNED,
                (sup.allowed() && a.getAgreementStatus() == AgreementStatus.SUBMITTED) || (rev.allowed() && a.getAgreementStatus() == AgreementStatus.SUPERVISOR_SIGNED),
                emp && a.getAgreementStatus() == AgreementStatus.FINAL && a.getStatus() == Status.DRAFT,
                emp && a.getAgreementStatus() == AgreementStatus.FINAL && a.getStatus() == Status.DRAFT && a.getSelfStatus() != StepStatus.SUBMITTED,
                sup.allowed() && a.getAgreementStatus() == AgreementStatus.FINAL && (a.getStatus() == Status.DRAFT || a.getStatus() == Status.SUBMITTED),
                sup.allowed() && a.getBehaviourStatus() != StepStatus.SUBMITTED && (a.getStatus() == Status.DRAFT || a.getStatus() == Status.SUBMITTED),
                rev.allowed() && a.getStatus() == Status.VERIFIED,
                emp && a.getStatus() == Status.APPROVED,
                emp && a.getStatus() == Status.FINALIZED && appealCloses != null && !LocalDate.now(ZONE).isAfter(appealCloses),
                a.getStatus() == Status.FINALIZED && (sup.allowed() || hr),
                seeBehaviour, hr);

        return new AppraisalView(a.getId(), p, lookup.brief(a.getEmployeeId()),
                a.getSupervisorUserId(), names.get(a.getSupervisorUserId()), a.getReviewerUserId(), names.get(a.getReviewerUserId()),
                a.getStatus().name(), a.getAgreementStatus().name(), a.getAgreementVersion(),
                a.getEmployeeSignedAt(), a.getSupervisorSignedAt(), a.getSupervisorSignedBy(), a.getReviewerSignedAt(), a.getReviewerSignedBy(),
                a.getSelfStatus().name(), a.getSelfAchievements(), a.getSelfChallenges(), a.getSelfComments(), a.getSelfSubmittedAt(),
                a.getBehaviourStatus().name(), seeBehaviour ? a.getStrengths() : null, seeBehaviour ? a.getImprovements() : null,
                a.getBehaviourSubmittedAt(), a.getVerificationCompletedAt(),
                a.getKpiWeight(), a.getBehaviourWeight(), list.stream().map(Kpi::getWeight).reduce(BigDecimal.ZERO, BigDecimal::add),
                selfScore(list), kpiScore(list),
                seeScores ? a.getKpiScore() : null, seeScores ? a.getBehaviourScore() : null, seeScores ? a.getOverallScore() : null,
                seeScores ? a.getAdjustment() : null, seeScores ? a.getAdjustmentJustification() : null,
                seeScores ? a.getFinalScore() : null, seeScores ? a.getCategory() : null, seeScores ? a.getReviewerComments() : null,
                a.getApprovedAt(), a.getAcknowledgedAt(), a.getEmployeeAckComments(), a.getFinalizedAt(), closes, appealCloses,
                kv, behaviour,
                trail.findByAppraisalIdOrderByActedAtAscIdAsc(a.getId()).stream()
                        .map(t -> new TrailView(t.getLevel(), t.getOfficerName(), t.getOnBehalfOf(), t.getDecision(), t.getComment(), t.getActedAt())).toList(),
                history.findByAppraisalIdOrderByVersionNoDesc(a.getId()).stream()
                        .map(h -> new HistoryView(h.getVersionNo(), h.getChangedBy(), h.getChangedAt(), h.getNote())).toList(),
                flags);
    }

    private static String chip(Kpi k) {
        if (k.isExcepted()) return "Exception approved";
        if (k.getProgressStatus() == ProgressStatus.RETURNED) return "Returned";
        if (k.getProgressStatus() == ProgressStatus.VERIFIED && k.getVerifiedValue() != null && k.getVerifiedValue().compareTo(HUNDRED) >= 0) return "Completed";
        if (k.getSelfProgress() == null || k.getSelfProgress().signum() == 0) return k.getProgressStatus() == ProgressStatus.NOT_STARTED ? "Not started" : "In progress";
        return "In progress";
    }

    List<Milestone> milestones(Kpi k) {
        if (blank(k.getMilestones())) return List.of();
        try {
            return mapper.readValue(k.getMilestones(), new TypeReference<List<Milestone>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> progressData(Kpi k) {
        if (blank(k.getProgressData())) return Map.of();
        try {
            return mapper.readValue(k.getProgressData(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ===================================================================== helpers

    Appraisal appraisal(Long id) {
        return appraisals.findById(id).orElseThrow(() -> ApiException.notFound("Appraisal", id));
    }

    private Kpi kpi(Long id) {
        return kpis.findById(id).orElseThrow(() -> ApiException.notFound("KPI", id));
    }

    private void requireView(Appraisal a) {
        access.requireView(a);
    }

    private boolean canEditAgreement(Appraisal a) {
        return a.getAgreementStatus() == AgreementStatus.DRAFT && a.getStatus() == Status.DRAFT
                && (access.isEmployee(a) || access.asSupervisor(a).allowed());
    }

    void addTrail(Appraisal a, String level, String officer, String onBehalfOf, String decision, String comment) {
        Trail t = new Trail();
        t.setAppraisalId(a.getId());
        t.setLevel(level);
        t.setOfficerName(officer);
        t.setOnBehalfOf(onBehalfOf);
        t.setDecision(decision);
        t.setComment(comment);
        t.setActedAt(Instant.now());
        trail.save(t);
    }

    private void addHistory(Appraisal a, String note) {
        AgreementHistory h = new AgreementHistory();
        h.setAppraisalId(a.getId());
        h.setVersionNo(a.getAgreementVersion());
        h.setChangedBy(CurrentUser.find().map(UserPrincipal::getFullName).orElse("System"));
        h.setChangedAt(Instant.now());
        h.setNote(note);
        history.save(h);
    }

    String employeeName(Appraisal a) {
        EmployeeLookup.EmployeeBrief b = lookup.brief(a.getEmployeeId());
        return b == null ? "An employee" : b.name();
    }

    void notifyUser(Long userId, String title, String message, String link) {
        if (userId == null) return;
        try {
            notifications.notifyUser(userId, "APR_ACTION", Map.of("title", title, "message", message), link);
        } catch (Exception e) {
            log.warn("Appraisal notification failed: {}", e.getMessage());
        }
    }

    void notifyEmployee(Appraisal a, String title, String message, String link) {
        try {
            notifications.notifyEmployee(a.getEmployeeId(), "APR_ACTION", Map.of("title", title, "message", message), link);
        } catch (Exception e) {
            log.warn("Appraisal notification failed: {}", e.getMessage());
        }
    }

    static BigDecimal percent(BigDecimal v, String what) {
        if (v == null) throw ApiException.badRequest("Enter the " + what + " (0-100%)");
        if (v.signum() < 0 || v.compareTo(HUNDRED) > 0) throw ApiException.badRequest("The " + what + " must be between 0 and 100%");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    static <E extends Enum<E>> E parse(Class<E> type, String v, String what) {
        try {
            return Enum.valueOf(type, v.trim().toUpperCase());
        } catch (Exception e) {
            throw ApiException.badRequest("Unknown " + what + ": " + v);
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String trim(String s) {
        return blank(s) ? null : s.trim();
    }

    static String abbreviate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
