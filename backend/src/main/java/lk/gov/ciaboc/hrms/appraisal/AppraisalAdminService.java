package lk.gov.ciaboc.hrms.appraisal;

import lk.gov.ciaboc.hrms.appraisal.AppraisalEntities.*;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Appraisal configuration (screen 6) and HRMS synchronization (screen 8).
 * <ul>
 *   <li>HR policy: versioned weighting (KPI vs behavioural, must total 100%) and rating bands. Appraisal administrators
 *       draft and submit; an HR policy approver other than the author approves. System administrators can never
 *       approve policy. Changes apply to appraisals started after approval (the next appraisal period).</li>
 *   <li>Sync: refreshes appraisal data from the other HRMS modules. Shows only technical status, never appraisal content.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppraisalAdminService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AprPolicyVersionRepository policies;
    private final AprRatingBandRepository bands;
    private final AprBehaviourCriterionRepository criteria;
    private final AprSyncModuleRepository syncModules;
    private final AprSyncFailureRepository syncFailures;
    private final AppraisalService service;
    private final AppraisalAccess access;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    // ===================================================================== policy

    public record Band(String label, int ratingValue, String category, BigDecimal minScore, BigDecimal maxScore) {
    }

    public record PolicyView(Long id, int versionNo, String status, BigDecimal kpiWeight, BigDecimal behaviourWeight,
                             String changedBy, Instant submittedAt, String approvedBy, Instant approvedAt, String note,
                             Instant updatedAt, List<Band> bands) {
    }

    public record CriterionView(Long id, String code, String name, List<String> descriptors) {
    }

    public record PolicyScreen(PolicyView approved, PolicyView working, List<PolicyView> history, List<CriterionView> criteria,
                               boolean canEdit, boolean canApprove, boolean isSystemAdmin) {
    }

    @Transactional(readOnly = true)
    public PolicyScreen policy() {
        UserPrincipal me = CurrentUser.get();
        if (!me.has(Permission.APR_MANAGE) && !me.has(Permission.APR_POLICY_APPROVE) && !me.has(Permission.APR_ADMIN))
            throw ApiException.forbidden("Appraisal configuration is restricted");
        List<PolicyVersion> all = policies.findAllByOrderByVersionNoDesc();
        PolicyVersion approved = all.stream().filter(p -> p.getStatus() == PolicyStatus.APPROVED).findFirst().orElse(null);
        PolicyVersion working = all.stream().filter(p -> p.getStatus() == PolicyStatus.DRAFT || p.getStatus() == PolicyStatus.PENDING_APPROVAL).findFirst().orElse(null);
        return new PolicyScreen(view(approved), view(working), all.stream().map(this::view).toList(),
                criteria.findByActiveTrueOrderBySortOrderAsc().stream().map(c -> new CriterionView(c.getId(), c.getCode(), c.getName(),
                        Arrays.asList(c.getDescriptor1(), c.getDescriptor2(), c.getDescriptor3(), c.getDescriptor4(), c.getDescriptor5()))).toList(),
                me.has(Permission.APR_MANAGE), canApprove(me), me.hasRole("SYSTEM_ADMIN"));
    }

    private static boolean canApprove(UserPrincipal me) {
        return me.has(Permission.APR_POLICY_APPROVE) && !me.hasRole("SYSTEM_ADMIN");
    }

    public record PolicyRequest(BigDecimal kpiWeight, BigDecimal behaviourWeight, List<Band> bands, String note) {
    }

    @Transactional
    public PolicyView saveDraft(PolicyRequest r) {
        UserPrincipal me = CurrentUser.get();
        if (!me.has(Permission.APR_MANAGE)) throw ApiException.forbidden("Appraisal administrators edit the policy");
        validate(r);
        PolicyVersion p = policies.findAllByOrderByVersionNoDesc().stream()
                .filter(x -> x.getStatus() == PolicyStatus.DRAFT || x.getStatus() == PolicyStatus.PENDING_APPROVAL).findFirst()
                .orElseGet(() -> {
                    PolicyVersion n = new PolicyVersion();
                    n.setVersionNo(policies.findAllByOrderByVersionNoDesc().stream().mapToInt(PolicyVersion::getVersionNo).max().orElse(0) + 1);
                    return n;
                });
        if (p.getStatus() == PolicyStatus.PENDING_APPROVAL) throw ApiException.rule("PENDING", "Version " + p.getVersionNo() + " is waiting for HR approval and cannot be edited");
        p.setStatus(PolicyStatus.DRAFT);
        p.setKpiWeight(r.kpiWeight());
        p.setBehaviourWeight(r.behaviourWeight());
        p.setChangedBy(me.getFullName());
        p.setChangedByUserId(me.getId());
        p.setNote(AppraisalService.trim(r.note()));
        p = policies.save(p);
        bands.deleteByPolicyVersionId(p.getId());
        bands.flush();
        int i = 0;
        for (Band b : r.bands()) {
            RatingBand rb = new RatingBand();
            rb.setPolicyVersionId(p.getId());
            rb.setLabel(b.label().trim());
            rb.setRatingValue(b.ratingValue());
            rb.setCategory(b.category().trim());
            rb.setMinScore(b.minScore());
            rb.setMaxScore(b.maxScore());
            rb.setSortOrder(++i);
            bands.save(rb);
        }
        audit.log(AuditAction.CONFIG_CHANGE, "AppraisalPolicy", p.getId(), "Saved appraisal policy draft v" + p.getVersionNo());
        return view(p);
    }

    private static void validate(PolicyRequest r) {
        if (r.kpiWeight() == null || r.behaviourWeight() == null) throw ApiException.badRequest("Enter both weights");
        if (r.kpiWeight().signum() < 0 || r.behaviourWeight().signum() < 0) throw ApiException.badRequest("Weights cannot be negative");
        if (r.kpiWeight().add(r.behaviourWeight()).compareTo(HUNDRED) != 0) throw ApiException.rule("WEIGHTS_NOT_100", "KPI and behavioural weights must total 100%");
        if (r.bands() == null || r.bands().isEmpty()) throw ApiException.badRequest("Define at least one rating band");
        List<Band> sorted = new ArrayList<>(r.bands());
        for (Band b : sorted) {
            if (AppraisalService.blank(b.label()) || AppraisalService.blank(b.category()) || b.minScore() == null || b.maxScore() == null)
                throw ApiException.badRequest("Every rating band needs a label, a category and a score range");
            if (b.maxScore().compareTo(b.minScore()) < 0) throw ApiException.badRequest("Score range of \"" + b.label() + "\" is reversed");
            if (b.minScore().signum() < 0 || b.maxScore().compareTo(HUNDRED) > 0) throw ApiException.badRequest("Score ranges must lie within 0-100");
        }
        sorted.sort(Comparator.comparing(Band::minScore));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).minScore().compareTo(sorted.get(i - 1).maxScore()) <= 0)
                throw ApiException.rule("BANDS_OVERLAP", "Rating bands \"" + sorted.get(i - 1).label() + "\" and \"" + sorted.get(i).label() + "\" overlap");
        }
    }

    @Transactional
    public PolicyView submit(Long id) {
        UserPrincipal me = CurrentUser.get();
        if (!me.has(Permission.APR_MANAGE)) throw ApiException.forbidden("Appraisal administrators submit the policy");
        PolicyVersion p = policy(id);
        if (p.getStatus() != PolicyStatus.DRAFT) throw ApiException.rule("NOT_DRAFT", "Only a draft can be submitted for HR approval");
        p.setStatus(PolicyStatus.PENDING_APPROVAL);
        p.setSubmittedAt(Instant.now());
        audit.log(AuditAction.SUBMIT, "AppraisalPolicy", id, "Submitted appraisal policy v" + p.getVersionNo() + " for HR approval");
        return view(policies.save(p));
    }

    @Transactional
    public PolicyView approve(Long id) {
        UserPrincipal me = CurrentUser.get();
        if (!canApprove(me)) throw ApiException.forbidden("Only an HR policy approver can approve policy changes; system administrators cannot");
        PolicyVersion p = policy(id);
        if (p.getStatus() != PolicyStatus.PENDING_APPROVAL) throw ApiException.rule("NOT_PENDING", "This version is not waiting for approval");
        if (me.getId().equals(p.getChangedByUserId())) throw ApiException.forbidden("A policy change is approved by someone other than its author");
        for (PolicyVersion old : policies.findByStatusIn(List.of(PolicyStatus.APPROVED))) {
            old.setStatus(PolicyStatus.SUPERSEDED);
            policies.save(old);
        }
        p.setStatus(PolicyStatus.APPROVED);
        p.setApprovedBy(me.getFullName());
        p.setApprovedAt(Instant.now());
        audit.log(AuditAction.APPROVE, "AppraisalPolicy", id, "Approved appraisal policy v" + p.getVersionNo());
        return view(policies.save(p));
    }

    @Transactional
    public PolicyView reject(Long id, String comment) {
        UserPrincipal me = CurrentUser.get();
        if (!canApprove(me)) throw ApiException.forbidden("Only an HR policy approver can return policy changes");
        PolicyVersion p = policy(id);
        if (p.getStatus() != PolicyStatus.PENDING_APPROVAL) throw ApiException.rule("NOT_PENDING", "This version is not waiting for approval");
        p.setStatus(PolicyStatus.DRAFT);
        p.setSubmittedAt(null);
        if (!AppraisalService.blank(comment)) p.setNote("Returned: " + comment.trim());
        audit.log(AuditAction.RETURN, "AppraisalPolicy", id, "Returned appraisal policy v" + p.getVersionNo());
        return view(policies.save(p));
    }

    private PolicyVersion policy(Long id) {
        return policies.findById(id).orElseThrow(() -> ApiException.notFound("Policy version", id));
    }

    private PolicyView view(PolicyVersion p) {
        if (p == null) return null;
        return new PolicyView(p.getId(), p.getVersionNo(), p.getStatus().name(), p.getKpiWeight(), p.getBehaviourWeight(), p.getChangedBy(),
                p.getSubmittedAt(), p.getApprovedBy(), p.getApprovedAt(), p.getNote(), p.getUpdatedAt() != null ? p.getUpdatedAt() : p.getCreatedAt(),
                bands.findByPolicyVersionIdOrderBySortOrderAsc(p.getId()).stream()
                        .map(b -> new Band(b.getLabel(), b.getRatingValue(), b.getCategory(), b.getMinScore(), b.getMaxScore())).toList());
    }

    // ===================================================================== HRMS synchronization (screen 8)

    public record SyncRow(String moduleCode, String moduleLabel, String dataLabel, Instant lastSyncAt, Instant lastSuccessAt,
                          String status, String lastMessage) {
    }

    public record FailureRow(String moduleCode, String moduleLabel, Instant failedAt, String reason) {
    }

    public record SyncScreen(List<SyncRow> modules, List<FailureRow> failures) {
    }

    @Transactional(readOnly = true)
    public SyncScreen syncStatus() {
        requireAdmin();
        List<SyncModule> mods = syncModules.findAllByOrderBySortOrderAsc();
        Map<String, String> labels = new HashMap<>();
        mods.forEach(m -> labels.put(m.getModuleCode(), m.getModuleLabel()));
        return new SyncScreen(mods.stream().map(m -> new SyncRow(m.getModuleCode(), m.getModuleLabel(), m.getDataLabel(), m.getLastSyncAt(),
                m.getLastSuccessAt(), m.getStatus(), m.getLastMessage())).toList(),
                syncFailures.findTop20ByOrderByFailedAtDesc().stream()
                        .map(f -> new FailureRow(f.getModuleCode(), labels.getOrDefault(f.getModuleCode(), f.getModuleCode()), f.getFailedAt(), f.getReason())).toList());
    }

    /** Not transactional: each module's work runs in its own transaction, so one failure does not undo the others. */
    public SyncScreen sync(String code) {
        requireAdmin();
        SyncModule m = syncModules.findByModuleCode(code).orElseThrow(() -> ApiException.notFound("Module", code));
        run(m);
        return syncStatus();
    }

    public SyncScreen syncAll() {
        requireAdmin();
        syncModules.findAllByOrderBySortOrderAsc().forEach(this::run);
        return syncStatus();
    }

    private void run(SyncModule m) {
        Instant now = Instant.now();
        m.setLastSyncAt(now);
        try {
            String message = switch (m.getModuleCode()) {
                case "01" -> {
                    Long pid = access.currentPeriodId();
                    int changed = pid == null ? 0 : service.refreshOfficers(pid);
                    yield "Reporting lines refreshed; " + changed + " appraisal(s) re-assigned";
                }
                case "02" -> {
                    Long pid = access.currentPeriodId();
                    if (pid == null) yield "No open appraisal period";
                    AppraisalService.StartResult r = service.start(pid);
                    yield r.created() + " appraisal(s) created for new employees; " + r.withoutSupervisor() + " without a supervisor";
                }
                case "05_06" -> count("SELECT COUNT(*) FROM employee WHERE status = 'ON_LEAVE'", "employee(s) currently on leave");
                case "09" -> count("SELECT COUNT(*) FROM trn_nomination", "training nomination record(s) read");
                case "12" -> count("SELECT COUNT(*) FROM ext_movement_request WHERE status = 'APPLIED'", "applied transfer/promotion record(s) read");
                default -> throw new IllegalStateException("No synchronization is defined for module " + m.getModuleCode());
            };
            m.setStatus("SUCCESS");
            m.setLastSuccessAt(now);
            m.setLastMessage(message);
        } catch (Exception e) {
            String reason = Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
            reason = reason.length() > 900 ? reason.substring(0, 900) : reason;
            m.setStatus("FAILED");
            m.setLastMessage(reason);
            SyncFailure f = new SyncFailure();
            f.setModuleCode(m.getModuleCode());
            f.setFailedAt(now);
            f.setReason(reason);
            syncFailures.save(f);
        }
        syncModules.save(m);
        audit.log(AuditAction.PROCESS, "AppraisalSync", m.getModuleCode(), "Synchronized " + m.getModuleLabel() + ": " + m.getStatus());
    }

    private String count(String sql, String label) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return (n == null ? 0 : n) + " " + label;
    }

    private static void requireAdmin() {
        if (!CurrentUser.get().has(Permission.APR_ADMIN)) throw ApiException.forbidden("HRMS synchronization is restricted to the system administrator");
    }
}
