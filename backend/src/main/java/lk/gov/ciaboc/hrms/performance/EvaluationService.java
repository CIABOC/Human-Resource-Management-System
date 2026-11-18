package lk.gov.ciaboc.hrms.performance;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.performance.PerformanceEntities.*;
import lk.gov.ciaboc.hrms.security.*;
import lk.gov.ciaboc.hrms.workflow.OrgDirectory;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Performance evaluations (FR-PER-004..009). Strict confidentiality (FR-PER-009): an evaluation is visible only to the
 * evaluated employee (after finalization), the assigned evaluator, users who acted on its approval workflow, and PER_VIEW
 * holders within their data scope. Every view is audit-logged. The system computes the weighted score; the evaluator and
 * the HR reviewer remain accountable for the assessment (C-05).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService implements WorkflowHandler {
    public static final String PROCESS = "PERFORMANCE_EVALUATION";
    public static final String ENTITY = "PerformanceEvaluation";
    private static final Set<EvaluationStatus> EDITABLE = EnumSet.of(EvaluationStatus.DRAFT, EvaluationStatus.RETURNED, EvaluationStatus.REJECTED);

    private final EvaluationRepository evaluations;
    private final EvaluationRatingRepository ratings;
    private final PerformanceConfigService config;
    private final EmployeeLookup lookup;
    private final EstablishmentService establishment;
    private final AppUserRepository users;

    private final OrgDirectory orgDirectory;
    private final WorkflowService workflow;
    private final NotificationService notifications;
    private final SettingsService settings;
    private final AuditService audit;

    // ===================================================================== views

    public record EvaluationListItem(Long id, Long periodId, String periodName, Long employeeId, String employeeNo,
                                     String employeeName, String designation, String orgUnitName, String employeeCategory,
                                     String modelCode, Long evaluatorUserId, String evaluatorName, String status,
                                     BigDecimal overallScore, BigDecimal overallPercent, Instant submittedAt,
                                     Instant finalizedAt, Instant acknowledgedAt) {
    }

    public record CriterionLine(Long criterionId, String criterionType, String title, String description, BigDecimal weight,
                                BigDecimal rating, String comment) {
    }

    public record EvaluationDetail(Long id, EvaluationPeriod period, EmployeeLookup.EmployeeBrief employee,
                                   EvaluationModel model, Long evaluatorUserId, String evaluatorName, String status,
                                   BigDecimal overallScore, BigDecimal overallPercent, String evaluatorComments,
                                   String employeeComments, Instant submittedAt, Instant finalizedAt,
                                   Instant acknowledgedAt, Long workflowInstanceId, List<CriterionLine> lines,
                                   boolean canEdit, boolean canAcknowledge, boolean canReassign) {
    }

    /** HR list (PER_VIEW), limited to employees in the caller's data scope; the caller's own unfinished evaluation is hidden. */
    @Transactional(readOnly = true)
    public List<EvaluationListItem> list(Long periodId, String status, String orgUnitCode) {
        UserPrincipal me = CurrentUser.get();
        Set<Long> visible = lookup.visibleEmployeeIds();
        Set<String> units = blank(orgUnitCode) ? null : establishment.unitAndDescendants(orgUnitCode, LocalDate.now());
        EvaluationStatus st = parseStatus(status);
        List<Evaluation> rows = (periodId == null ? evaluations.findAllByOrderByIdDesc() : evaluations.findByPeriodIdOrderByIdAsc(periodId))
                .stream()
                .filter(e -> visible == null || visible.contains(e.getEmployeeId()))
                .filter(e -> !e.getEmployeeId().equals(me.getEmployeeId()) || e.getStatus() == EvaluationStatus.FINALIZED)
                .filter(e -> st == null || e.getStatus() == st)
                .toList();
        List<EvaluationListItem> items = items(rows);
        if (units != null) {
            Map<Long, EmployeeLookup.EmployeeBrief> b = lookup.briefs(rows.stream().map(Evaluation::getEmployeeId).toList());
            items = items.stream().filter(i -> {
                EmployeeLookup.EmployeeBrief e = b.get(i.employeeId());
                return e != null && units.contains(e.orgUnitCode());
            }).toList();
        }
        audit.log(AuditAction.SEARCH, ENTITY, periodId, "Listed performance evaluations" + (periodId == null ? "" : " of period " + periodId));
        return items;
    }

    /** Evaluations assigned to the current user as evaluator. */
    @Transactional(readOnly = true)
    public List<EvaluationListItem> assignedToMe() {
        return items(evaluations.findByEvaluatorUserIdOrderByIdDesc(CurrentUser.get().getId()));
    }

    /** The employee's own finalized evaluations (FR-PER-007). */
    @Transactional(readOnly = true)
    public List<EvaluationListItem> mine() {
        return items(evaluations.findByEmployeeIdAndStatusOrderByIdDesc(CurrentUser.employeeId(), EvaluationStatus.FINALIZED));
    }

    /** Finalized evaluation history of an employee (self, or PER_VIEW within scope). */
    @Transactional(readOnly = true)
    public List<EvaluationListItem> history(Long employeeId) {
        UserPrincipal me = CurrentUser.get();
        if (!employeeId.equals(me.getEmployeeId())) {
            if (!me.has(Permission.PER_VIEW)) throw ApiException.notFound("Employee", employeeId);
            lookup.requireAccess(employeeId);
        }
        audit.log(AuditAction.VIEW, ENTITY, null, "Viewed evaluation history of employee " + employeeId);
        return items(evaluations.findByEmployeeIdAndStatusOrderByIdDesc(employeeId, EvaluationStatus.FINALIZED));
    }

    @Transactional(readOnly = true)
    public EvaluationDetail detail(Long id) {
        Evaluation ev = evaluation(id);
        requireView(ev);
        audit.log(AuditAction.VIEW, ENTITY, id, "Viewed performance evaluation " + id);
        return detail(ev);
    }

    private EvaluationDetail detail(Evaluation ev) {
        UserPrincipal me = CurrentUser.get();
        EvaluationModel model = config.model(ev.getModelId());
        Map<Long, EvaluationRating> byCriterion = ratings.findByEvaluationId(ev.getId()).stream()
                .collect(Collectors.toMap(EvaluationRating::getCriterionId, r -> r, (a, b) -> a));
        List<CriterionLine> lines = config.criteriaOf(model.getId()).stream().map(c -> {
            EvaluationRating r = byCriterion.get(c.getId());
            return new CriterionLine(c.getId(), c.getCriterionType().name(), c.getTitle(), c.getDescription(), c.getWeight(),
                    r == null ? null : r.getRating(), r == null ? null : r.getComment());
        }).toList();
        EvaluationPeriod p = config.period(ev.getPeriodId());
        boolean isEvaluator = me.getId().equals(ev.getEvaluatorUserId());
        boolean canEdit = isEvaluator && EDITABLE.contains(ev.getStatus()) && p.getStatus() == PeriodStatus.OPEN;
        boolean canAck = ev.getEmployeeId().equals(me.getEmployeeId()) && ev.getStatus() == EvaluationStatus.FINALIZED && ev.getAcknowledgedAt() == null;
        boolean canReassign = me.has(Permission.PER_MANAGE) && EDITABLE.contains(ev.getStatus()) && !ev.getEmployeeId().equals(me.getEmployeeId());
        return new EvaluationDetail(ev.getId(), p, lookup.brief(ev.getEmployeeId()), model, ev.getEvaluatorUserId(),
                ev.getEvaluatorName(), ev.getStatus().name(), ev.getOverallScore(), ev.getOverallPercent(),
                ev.getEvaluatorComments(), ev.getEmployeeComments(), ev.getSubmittedAt(), ev.getFinalizedAt(),
                ev.getAcknowledgedAt(), ev.getWorkflowInstanceId(), lines, canEdit, canAck, canReassign);
    }

    private List<EvaluationListItem> items(List<Evaluation> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long, EmployeeLookup.EmployeeBrief> b = lookup.briefs(rows.stream().map(Evaluation::getEmployeeId).toList());
        Map<Long, EvaluationPeriod> ps = new HashMap<>();
        Map<Long, String> modelCodes = new HashMap<>();
        return rows.stream().map(e -> {
            EvaluationPeriod p = ps.computeIfAbsent(e.getPeriodId(), config::period);
            String mc = modelCodes.computeIfAbsent(e.getModelId(), id -> config.model(id).getCode());
            EmployeeLookup.EmployeeBrief emp = b.get(e.getEmployeeId());
            return new EvaluationListItem(e.getId(), p.getId(), p.getName(), e.getEmployeeId(), emp == null ? null : emp.employeeNo(),
                    emp == null ? null : emp.nameWithInitials(), emp == null ? null : emp.designation(),
                    emp == null ? null : emp.orgUnitName(), emp == null ? null : emp.employeeCategory(), mc,
                    e.getEvaluatorUserId(), e.getEvaluatorName(), e.getStatus().name(), e.getOverallScore(),
                    e.getOverallPercent(), e.getSubmittedAt(), e.getFinalizedAt(), e.getAcknowledgedAt());
        }).toList();
    }

    // ===================================================================== confidentiality (FR-PER-009)

    boolean canView(Evaluation ev) {
        UserPrincipal me = CurrentUser.get();
        if (ev.getEmployeeId().equals(me.getEmployeeId())) return ev.getStatus() == EvaluationStatus.FINALIZED;
        if (me.getId().equals(ev.getEvaluatorUserId())) return true;
        for (WorkflowInstance wi : workflow.historyFor(ENTITY, ev.getId())) {
            if (workflow.actionsFor(wi.getId()).stream().anyMatch(a -> me.getId().equals(a.getActorUserId()))) return true;
        }
        if (me.has(Permission.PER_VIEW)) return lookup.canAccess(lookup.require(ev.getEmployeeId()));
        return false;
    }

    private void requireView(Evaluation ev) {
        // 404 rather than 403 so the existence of an evaluation is not disclosed.
        if (!canView(ev)) throw ApiException.notFound("Evaluation", ev.getId());
    }

    // ===================================================================== creation & assignment (FR-PER-003, -004)

    public record CreateRequest(Long periodId, Long employeeId, Long evaluatorUserId) {
    }

    @Transactional
    public EvaluationDetail create(CreateRequest r) {
        if (r.periodId() == null || r.employeeId() == null) throw ApiException.badRequest("Period and employee are required");
        EvaluationPeriod p = config.period(r.periodId());
        if (p.getStatus() != PeriodStatus.OPEN) throw ApiException.rule("PERIOD_CLOSED", "The evaluation period is closed");
        Employee e = lookup.requireAccess(r.employeeId());
        if (e.getId().equals(CurrentUser.get().getEmployeeId())) throw ApiException.rule("SELF", "You cannot set up your own evaluation");
        if (!EmployeeLookup.IN_SERVICE.contains(e.getStatus())) throw ApiException.rule("NOT_IN_SERVICE", "Employee is not in service");
        if (evaluations.existsByPeriodIdAndEmployeeId(p.getId(), e.getId()))
            throw ApiException.conflict("An evaluation already exists for this employee in " + p.getName());
        EvaluationModel m = config.modelForCategory(e.getEmployeeCategory())
                .orElseThrow(() -> ApiException.rule("NO_MODEL", "No active evaluation model is assigned to employee category " + e.getEmployeeCategory()));
        config.requireWeights(m);
        Long evaluator = r.evaluatorUserId() != null ? r.evaluatorUserId() : defaultEvaluator(e);
        Evaluation ev = new Evaluation();
        ev.setPeriodId(p.getId());
        ev.setEmployeeId(e.getId());
        ev.setModelId(m.getId());
        ev.setStatus(EvaluationStatus.DRAFT);
        if (evaluator != null) setEvaluator(ev, e, evaluator);
        evaluations.save(ev);
        audit.log(AuditAction.CREATE, ENTITY, ev.getId(), "Evaluation for " + e.getEmployeeNo() + " in " + p.getCode()
                + " (model " + m.getCode() + ", evaluator " + ev.getEvaluatorName() + ")");
        if (evaluator != null) notifyAssigned(ev, e, p);
        return detail(ev);
    }

    public record GenerateResult(int created, int existing, List<String> skipped, int withoutEvaluator) {
    }

    /** Creates draft evaluations for every in-service employee in scope whose category has a model (FR-PER-004). */
    @Transactional
    public GenerateResult generate(Long periodId) {
        EvaluationPeriod p = config.period(periodId);
        if (p.getStatus() != PeriodStatus.OPEN) throw ApiException.rule("PERIOD_CLOSED", "The evaluation period is closed");
        Set<Long> visible = lookup.visibleEmployeeIds();
        Long self = CurrentUser.get().getEmployeeId();
        int created = 0, existing = 0, noEvaluator = 0;
        List<String> skipped = new ArrayList<>();
        Map<Long, Integer> perEvaluator = new LinkedHashMap<>();
        for (EmployeeLookup.EmployeeBrief b : lookup.inService()) {
            if (visible != null && !visible.contains(b.id())) continue;
            if (b.id().equals(self)) continue; // segregation: nobody sets up their own evaluation
            if (evaluations.existsByPeriodIdAndEmployeeId(periodId, b.id())) {
                existing++;
                continue;
            }
            Optional<EvaluationModel> m = config.modelForCategory(b.employeeCategory());
            if (m.isEmpty()) {
                skipped.add(b.employeeNo() + ": no model for category " + b.employeeCategory());
                continue;
            }
            try {
                config.requireWeights(m.get());
            } catch (ApiException ex) {
                skipped.add(b.employeeNo() + ": " + ex.getMessage());
                continue;
            }
            Employee e = lookup.require(b.id());
            Evaluation ev = new Evaluation();
            ev.setPeriodId(periodId);
            ev.setEmployeeId(b.id());
            ev.setModelId(m.get().getId());
            ev.setStatus(EvaluationStatus.DRAFT);
            Long evaluator = defaultEvaluator(e);
            if (evaluator != null) {
                setEvaluator(ev, e, evaluator);
                perEvaluator.merge(evaluator, 1, Integer::sum);
            } else noEvaluator++;
            evaluations.save(ev);
            created++;
        }
        perEvaluator.forEach((uid, n) -> notifications.notifyUser(uid, "PER_REMINDER_EVALUATOR",
                vars("count", n, "period", p.getName(), "dueDate", p.getDueDate() == null ? "-" : p.getDueDate()), "/performance/assigned"));
        audit.log(AuditAction.PROCESS, "EvaluationPeriod", periodId, "Generated " + created + " evaluations for " + p.getCode()
                + " (" + skipped.size() + " skipped, " + noEvaluator + " without evaluator)");
        return new GenerateResult(created, existing, skipped, noEvaluator);
    }

    @Transactional
    public EvaluationDetail reassign(Long id, Long evaluatorUserId) {
        Evaluation ev = evaluation(id);
        Employee e = lookup.requireAccess(ev.getEmployeeId());
        if (e.getId().equals(CurrentUser.get().getEmployeeId())) throw ApiException.rule("SELF", "You cannot change the evaluator of your own evaluation");
        if (!EDITABLE.contains(ev.getStatus())) throw ApiException.rule("NOT_EDITABLE", "The evaluator can only be changed before submission");
        if (evaluatorUserId == null) throw ApiException.badRequest("Evaluator is required");
        String before = ev.getEvaluatorName();
        setEvaluator(ev, e, evaluatorUserId);
        audit.log(AuditAction.UPDATE, ENTITY, id, "Evaluator of evaluation " + id + " changed", before, ev.getEvaluatorName());
        notifyAssigned(ev, e, config.period(ev.getPeriodId()));
        return detail(ev);
    }

    private void setEvaluator(Evaluation ev, Employee e, Long userId) {
        AppUser u = users.findById(userId).filter(AppUser::isActive)
                .orElseThrow(() -> ApiException.rule("INVALID_EVALUATOR", "Evaluator must be an active user"));
        if (e.getId().equals(u.getEmployeeId())) throw ApiException.rule("INVALID_EVALUATOR", "An employee cannot evaluate themselves");
        if (!holds(u, Permission.PER_EVALUATE) && !holds(u, Permission.PER_MANAGE))
            throw ApiException.rule("INVALID_EVALUATOR", u.getFullName() + " is not authorized to evaluate employees");
        ev.setEvaluatorUserId(u.getId());
        ev.setEvaluatorName(u.getFullName());
    }

    /** Default evaluator: the nearest division head above the employee who is not the employee themself. */
    Long defaultEvaluator(Employee e) {
        if (e.getOrgUnitCode() == null) return null;
        for (String unit : establishment.unitAndAncestors(e.getOrgUnitCode(), LocalDate.now())) {
            for (Long uid : orgDirectory.divisionHeadUserIdsFor(unit)) {
                AppUser u = users.findById(uid).orElse(null);
                if (u != null && u.isActive() && !e.getId().equals(u.getEmployeeId())) return uid;
            }
        }
        return null;
    }

    public record EvaluatorOption(Long userId, String name, Long employeeId) {
    }

    @Transactional(readOnly = true)
    public List<EvaluatorOption> evaluators() {
        return users.findAll().stream().filter(AppUser::isActive)
                .filter(u -> holds(u, Permission.PER_EVALUATE) || holds(u, Permission.PER_MANAGE))
                .sorted(Comparator.comparing(AppUser::getFullName))
                .map(u -> new EvaluatorOption(u.getId(), u.getFullName(), u.getEmployeeId())).toList();
    }

    private static boolean holds(AppUser u, Permission p) {
        return u.getRoles().stream().filter(Role::isActive).anyMatch(r -> r.getPermissions().contains(p.name()));
    }

    // ===================================================================== rating & submission (FR-PER-004, -005)

    public record RatingEntry(Long criterionId, BigDecimal rating, String comment) {
    }

    public record SaveRequest(List<RatingEntry> ratings, String evaluatorComments) {
    }

    @Transactional
    public EvaluationDetail save(Long id, SaveRequest r) {
        Evaluation ev = evaluation(id);
        requireEvaluatorEditing(ev);
        EvaluationModel m = config.model(ev.getModelId());
        Map<Long, EvaluationCriterion> cs = config.criteriaOf(m.getId()).stream()
                .collect(Collectors.toMap(EvaluationCriterion::getId, c -> c));
        Map<Long, EvaluationRating> existing = ratings.findByEvaluationId(id).stream()
                .collect(Collectors.toMap(EvaluationRating::getCriterionId, x -> x, (a, b) -> a));
        String before = ev.getOverallScore() + " | " + existing.values().stream().map(x -> x.getCriterionId() + "=" + x.getRating())
                .sorted().collect(Collectors.joining(","));
        for (RatingEntry e : r.ratings() == null ? List.<RatingEntry>of() : r.ratings()) {
            if (!cs.containsKey(e.criterionId())) throw ApiException.badRequest("Criterion " + e.criterionId() + " is not part of this evaluation model");
            if (e.rating() == null) {
                EvaluationRating old = existing.remove(e.criterionId());
                if (old != null) ratings.delete(old);
                continue;
            }
            if (e.rating().compareTo(m.getRatingMin()) < 0 || e.rating().compareTo(m.getRatingMax()) > 0)
                throw ApiException.badRequest("Ratings must be between " + m.getRatingMin() + " and " + m.getRatingMax());
            if (e.comment() != null && e.comment().length() > 2000) throw ApiException.badRequest("Comments are limited to 2000 characters");
            EvaluationRating rt = existing.computeIfAbsent(e.criterionId(), k -> new EvaluationRating());
            rt.setEvaluationId(id);
            rt.setCriterionId(e.criterionId());
            rt.setRating(e.rating());
            rt.setComment(blank(e.comment()) ? null : e.comment().trim());
            ratings.save(rt);
        }
        ev.setEvaluatorComments(r.evaluatorComments());
        recompute(ev, m, cs.values(), existing.values());
        audit.log(AuditAction.UPDATE, ENTITY, id, "Evaluation " + id + " ratings saved", before, ev.getOverallScore() + " | "
                + existing.values().stream().map(x -> x.getCriterionId() + "=" + x.getRating()).sorted().collect(Collectors.joining(",")));
        return detail(ev);
    }

    private void recompute(Evaluation ev, EvaluationModel m, Collection<EvaluationCriterion> cs, Collection<EvaluationRating> rs) {
        Map<Long, BigDecimal> weights = cs.stream().collect(Collectors.toMap(EvaluationCriterion::getId, EvaluationCriterion::getWeight));
        List<PerformanceScoring.WeightedRating> list = rs.stream()
                .map(x -> new PerformanceScoring.WeightedRating(weights.get(x.getCriterionId()), x.getRating())).toList();
        PerformanceScoring.Result res = PerformanceScoring.weighted(list, m.getRatingMin(), m.getRatingMax());
        ev.setOverallScore(res.score());
        ev.setOverallPercent(res.percent());
    }

    @Transactional
    public EvaluationDetail submit(Long id) {
        Evaluation ev = evaluation(id);
        requireEvaluatorEditing(ev);
        EvaluationModel m = config.model(ev.getModelId());
        List<EvaluationCriterion> cs = config.criteriaOf(m.getId());
        List<EvaluationRating> rs = ratings.findByEvaluationId(id);
        Set<Long> rated = rs.stream().map(EvaluationRating::getCriterionId).collect(Collectors.toSet());
        List<String> missing = cs.stream().filter(c -> !rated.contains(c.getId())).map(EvaluationCriterion::getTitle).toList();
        if (!missing.isEmpty()) throw ApiException.rule("INCOMPLETE", "Rate every criterion before submitting. Missing: " + String.join(", ", missing));
        if (blank(ev.getEvaluatorComments())) throw ApiException.badRequest("Overall evaluator comments are required");
        recompute(ev, m, cs, rs);
        Employee e = lookup.require(ev.getEmployeeId());
        EvaluationPeriod p = config.period(ev.getPeriodId());
        boolean resubmit = ev.getStatus() == EvaluationStatus.RETURNED && ev.getWorkflowInstanceId() != null;
        ev.setStatus(EvaluationStatus.SUBMITTED);
        ev.setSubmittedAt(Instant.now());
        if (resubmit) {
            workflow.resubmit(ev.getWorkflowInstanceId(), "Evaluation revised");
        } else {
            WorkflowInstance wi = workflow.start(PROCESS, ENTITY, ev.getId(), "Performance evaluation: " + e.getEmployeeNo()
                    + " — " + p.getName(), "/performance/evaluations/" + ev.getId(), e.getId());
            ev.setWorkflowInstanceId(wi.getId());
        }
        audit.log(AuditAction.SUBMIT, ENTITY, id, "Evaluation " + id + " submitted for HR review (score " + ev.getOverallScore() + ")");
        return detail(ev);
    }

    private void requireEvaluatorEditing(Evaluation ev) {
        UserPrincipal me = CurrentUser.get();
        if (!me.getId().equals(ev.getEvaluatorUserId())) {
            requireView(ev);
            throw ApiException.forbidden("Only the assigned evaluator can rate this evaluation");
        }
        if (!EDITABLE.contains(ev.getStatus())) throw ApiException.rule("NOT_EDITABLE", "This evaluation has been submitted");
        if (config.period(ev.getPeriodId()).getStatus() != PeriodStatus.OPEN)
            throw ApiException.rule("PERIOD_CLOSED", "The evaluation period is closed");
    }

    // ===================================================================== acknowledgement (FR-PER-007)

    @Transactional
    public EvaluationDetail acknowledge(Long id, String comments) {
        Evaluation ev = evaluation(id);
        UserPrincipal me = CurrentUser.get();
        if (!ev.getEmployeeId().equals(me.getEmployeeId()) || ev.getStatus() != EvaluationStatus.FINALIZED)
            throw ApiException.notFound("Evaluation", id);
        if (ev.getAcknowledgedAt() != null) throw ApiException.rule("ALREADY_ACKNOWLEDGED", "You have already acknowledged this evaluation");
        if (comments != null && comments.length() > 4000) throw ApiException.badRequest("Comments are limited to 4000 characters");
        ev.setEmployeeComments(blank(comments) ? null : comments.trim());
        ev.setAcknowledgedAt(Instant.now());
        audit.log(AuditAction.UPDATE, ENTITY, id, "Evaluation " + id + " acknowledged by the employee");
        return detail(ev);
    }

    // ===================================================================== workflow callbacks

    @Override
    public String processCode() {
        return PROCESS;
    }

    /** The evaluated employee may never decide on their own evaluation, even if they hold the reviewer role. */
    private void requireNotSubject(Evaluation ev) {
        Long mine = CurrentUser.find().map(UserPrincipal::getEmployeeId).orElse(null);
        if (mine != null && mine.equals(ev.getEmployeeId()))
            throw ApiException.rule("SEGREGATION_OF_DUTIES", "You cannot review your own performance evaluation");
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        Evaluation ev = evaluation(wi.getEntityId());
        requireNotSubject(ev);
        ev.setStatus(EvaluationStatus.FINALIZED);
        ev.setFinalizedAt(Instant.now());
        EvaluationPeriod p = config.period(ev.getPeriodId());
        notifications.notifyEmployee(ev.getEmployeeId(), "PER_FINALIZED", vars("period", p.getName()), "/performance/evaluations/" + ev.getId());
        audit.log(AuditAction.APPROVE, ENTITY, ev.getId(), "Evaluation " + ev.getId() + " finalized");
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        Evaluation ev = evaluation(wi.getEntityId());
        requireNotSubject(ev);
        ev.setStatus(EvaluationStatus.REJECTED);
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        Evaluation ev = evaluation(wi.getEntityId());
        requireNotSubject(ev);
        ev.setStatus(EvaluationStatus.RETURNED);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        Evaluation ev = evaluation(wi.getEntityId());
        if (ev.getStatus() == EvaluationStatus.SUBMITTED) return; // set by submit()
        if (ratings.findByEvaluationId(ev.getId()).size() < config.criteriaOf(ev.getModelId()).size())
            throw ApiException.rule("INCOMPLETE", "Rate every criterion before resubmitting");
        ev.setStatus(EvaluationStatus.SUBMITTED);
        ev.setSubmittedAt(Instant.now());
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        Evaluation ev = evaluation(wi.getEntityId());
        ev.setStatus(EvaluationStatus.DRAFT);
        ev.setWorkflowInstanceId(null);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        Evaluation ev = evaluation(wi.getEntityId());
        EvaluationDetail d = detail(ev);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Employee", d.employee().employeeNo() + " — " + d.employee().nameWithInitials() + " (" + d.employee().designation() + ")");
        m.put("Period", d.period().getName());
        m.put("Model", d.model().getName() + (d.model().isSample() ? " [SAMPLE]" : "") + " — scale " + d.model().getRatingMin() + "–" + d.model().getRatingMax());
        m.put("Evaluator", d.evaluatorName());
        for (CriterionLine l : d.lines())
            m.put(l.title() + " (" + l.weight().stripTrailingZeros().toPlainString() + "%)", l.rating() + (l.comment() == null ? "" : " — " + l.comment()));
        m.put("Weighted overall score", d.overallScore() + " (" + d.overallPercent() + "%)");
        m.put("Evaluator comments", d.evaluatorComments());
        return m;
    }

    // ===================================================================== reminders (FR-PER-008)

    public record ReminderResult(int evaluatorsReminded, int evaluationsPending, int employeesReminded) {
    }

    @Scheduled(cron = "${app.performance.reminder-cron:0 30 7 * * *}")
    @Transactional
    public void scheduledReminders() {
        try {
            ReminderResult r = sendReminders(null, false);
            if (r.evaluatorsReminded() + r.employeesReminded() > 0) log.info("Performance reminders sent: {}", r);
        } catch (Exception e) {
            log.warn("Performance reminders failed: {}", e.getMessage());
        }
    }

    /**
     * Reminds evaluators of unfinished evaluations (within the configured window before the due date) and employees of
     * finalized evaluations they have not acknowledged. {@code manual} sends regardless of the window/interval.
     */
    @Transactional
    public ReminderResult sendReminders(Long periodId, boolean manual) {
        LocalDate today = LocalDate.now();
        int daysBefore = settings.getInt("performance.reminder-days-before", 14);
        int interval = settings.getInt("performance.reminder-interval-days", 3);
        Instant cutoff = Instant.now().minus(interval, ChronoUnit.DAYS);
        Map<Long, EvaluationPeriod> ps = new HashMap<>();
        Map<Long, List<Evaluation>> byEvaluator = new LinkedHashMap<>();
        int employees = 0;
        for (Evaluation ev : evaluations.findByStatusIn(EnumSet.of(EvaluationStatus.DRAFT, EvaluationStatus.RETURNED,
                EvaluationStatus.REJECTED, EvaluationStatus.FINALIZED))) {
            if (periodId != null && !periodId.equals(ev.getPeriodId())) continue;
            EvaluationPeriod p = ps.computeIfAbsent(ev.getPeriodId(), config::period);
            boolean due = manual || ev.getLastReminderAt() == null || ev.getLastReminderAt().isBefore(cutoff);
            if (!due) continue;
            if (ev.getStatus() == EvaluationStatus.FINALIZED) {
                if (ev.getAcknowledgedAt() != null) continue;
                if (!manual && ev.getFinalizedAt() != null && ev.getFinalizedAt().isAfter(cutoff)) continue;
                notifications.notifyEmployee(ev.getEmployeeId(), "PER_REMINDER_ACK", vars("period", p.getName()),
                        "/performance/evaluations/" + ev.getId());
                ev.setLastReminderAt(Instant.now());
                employees++;
            } else {
                if (p.getStatus() != PeriodStatus.OPEN || ev.getEvaluatorUserId() == null) continue;
                if (!manual && (p.getDueDate() == null || today.isBefore(p.getDueDate().minusDays(daysBefore)))) continue;
                byEvaluator.computeIfAbsent(ev.getEvaluatorUserId(), k -> new ArrayList<>()).add(ev);
            }
        }
        int pending = 0;
        for (var en : byEvaluator.entrySet()) {
            List<Evaluation> list = en.getValue();
            EvaluationPeriod p = ps.get(list.get(0).getPeriodId());
            notifications.notifyUser(en.getKey(), "PER_REMINDER_EVALUATOR", vars("count", list.size(), "period", p.getName(),
                    "dueDate", p.getDueDate() == null ? "-" : p.getDueDate()), "/performance/assigned");
            list.forEach(ev -> ev.setLastReminderAt(Instant.now()));
            pending += list.size();
        }
        ReminderResult res = new ReminderResult(byEvaluator.size(), pending, employees);
        if (manual || res.evaluatorsReminded() + res.employeesReminded() > 0)
            audit.log(AuditAction.PROCESS, ENTITY, periodId, "Performance reminders sent: " + byEvaluator.size() + " evaluators ("
                    + pending + " evaluations), " + employees + " employees");
        return res;
    }

    private void notifyAssigned(Evaluation ev, Employee e, EvaluationPeriod p) {
        notifications.notifyUser(ev.getEvaluatorUserId(), "PER_EVALUATION_ASSIGNED", vars("employee", e.getEmployeeNo() + " "
                + e.getNameWithInitials(), "period", p.getName(), "dueDate", p.getDueDate() == null ? "-" : p.getDueDate()),
                "/performance/evaluations/" + ev.getId());
    }

    // ===================================================================== helpers

    Evaluation evaluation(Long id) {
        return evaluations.findById(id).orElseThrow(() -> ApiException.notFound("Evaluation", id));
    }

    private static EvaluationStatus parseStatus(String s) {
        if (blank(s)) return null;
        try {
            return EvaluationStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown evaluation status " + s);
        }
    }

    private static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
