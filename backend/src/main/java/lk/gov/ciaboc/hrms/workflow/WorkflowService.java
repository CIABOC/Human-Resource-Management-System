package lk.gov.ciaboc.hrms.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.ActionType;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.Mode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generic approval engine used by every module. Approval paths are versioned data, not code (REQ 10.4-10.9,
 * FR-WFL-009): when a request is submitted the version in force selects a path through its routing rules and the path
 * is resolved against the organization hierarchy into concrete steps. Requesters can never approve their own requests
 * (FR-WFL-010); delegates may act during approved absences (FR-WFL-007); overdue steps are escalated (FR-WFL-008).
 */
@Slf4j
@Service
public class WorkflowService {
    private static final Set<WorkflowInstance.Status> OPEN = EnumSet.of(WorkflowInstance.Status.PENDING, WorkflowInstance.Status.RETURNED);

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowInstanceRepository instances;
    private final ApprovalActionRepository actions;
    private final WorkflowInstanceStepRepository steps;
    private final AppUserRepository users;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectProvider<WorkflowHandler> handlerProvider;
    private final WorkflowVersionService versionService;
    private final PathResolver pathResolver;
    private final ApproverResolver approverResolver;
    private final ObjectMapper json;
    private volatile Map<String, WorkflowHandler> handlers;

    public WorkflowService(WorkflowDefinitionRepository definitions, WorkflowInstanceRepository instances,
                           ApprovalActionRepository actions, WorkflowInstanceStepRepository steps, AppUserRepository users,
                           NotificationService notifications, AuditService audit, ObjectProvider<WorkflowHandler> handlerProvider,
                           WorkflowVersionService versionService, PathResolver pathResolver, ApproverResolver approverResolver,
                           ObjectMapper json) {
        this.definitions = definitions;
        this.instances = instances;
        this.actions = actions;
        this.steps = steps;
        this.users = users;
        this.notifications = notifications;
        this.audit = audit;
        this.handlerProvider = handlerProvider;
        this.versionService = versionService;
        this.pathResolver = pathResolver;
        this.approverResolver = approverResolver;
        this.json = json;
    }

    // ------------------------------------------------------------------ start / resubmit / cancel

    /**
     * Starts approval for a record. {@code subjectEmployeeId} is the employee the request is about (used for
     * DIVISION_HEAD / supervisor routing); pass null when not applicable.
     */
    @Transactional
    public WorkflowInstance start(String processCode, String entityType, Long entityId, String title, String link,
                                  Long subjectEmployeeId) {
        return start(processCode, entityType, entityId, title, link, subjectEmployeeId, null);
    }

    /**
     * As {@link #start(String, String, Long, String, String, Long)}, with request attributes for routing rules (e.g.
     * {@code days}, {@code amount}) in addition to those of {@link WorkflowHandler#routingAttributes}.
     */
    @Transactional
    public WorkflowInstance start(String processCode, String entityType, Long entityId, String title, String link,
                                  Long subjectEmployeeId, Map<String, Object> requestAttributes) {
        WorkflowDefinition def = activeDefinition(processCode);
        WorkflowVersion version = versionService.effectiveVersion(def.getId(), Instant.now())
                .orElseThrow(() -> ApiException.rule("NO_WORKFLOW", "The approval workflow for '" + processCode
                        + "' has no published version in force. Ask the system administrator to publish one."));
        instances.findByEntityTypeAndEntityIdOrderByIdDesc(entityType, entityId).stream()
                .filter(i -> OPEN.contains(i.getStatus()))
                .findFirst().ifPresent(i -> {
                    throw ApiException.conflict("This record already has an open approval request (#" + i.getId() + ")");
                });
        UserPrincipal me = CurrentUser.get();
        WorkflowInstance wi = new WorkflowInstance();
        wi.setProcessCode(processCode);
        wi.setEntityType(entityType);
        wi.setEntityId(entityId);
        wi.setTitle(title);
        wi.setLink(link);
        wi.setRequesterUserId(me.getId());
        wi.setRequesterName(me.getFullName());
        wi.setRequesterEmployeeId(me.getEmployeeId());
        wi.setSubjectEmployeeId(subjectEmployeeId);
        Long subject = subjectEmployeeId != null ? subjectEmployeeId : me.getEmployeeId();
        wi.setSubjectOrgUnitCode(subject == null ? null : pathResolver.attributesUnit(subject));
        wi.setStatus(WorkflowInstance.Status.PENDING);
        wi.setSubmittedAt(Instant.now());
        wi.setStageEnteredAt(Instant.now());
        wi.setCurrentStage(1);
        wi.setTotalStages(0);
        wi.setRoutingRound(1);
        instances.save(wi);

        Map<String, Object> request = new LinkedHashMap<>();
        if (requestAttributes != null) request.putAll(requestAttributes);
        handler(wi).ifPresent(h -> safeAttributes(h, wi).forEach(request::putIfAbsent));
        wi.setRoutingAttributes(writeAttributes(request));
        PathResolver.ResolvedPath path = resolve(wi, version, request);
        materialize(wi, version, path);
        record(wi, ApprovalAction.Type.SUBMIT, routingNote(wi), null, null, null);
        audit.log(AuditAction.SUBMIT, entityType, entityId, "Submitted for approval: " + title + " (" + routingNote(wi) + ")");
        notifyApprovers(wi);
        return wi;
    }

    @Transactional
    public WorkflowInstance resubmit(Long instanceId, String comment) {
        WorkflowInstance wi = get(instanceId);
        requireRequester(wi);
        if (wi.getStatus() != WorkflowInstance.Status.RETURNED)
            throw ApiException.rule("NOT_RETURNED", "Only requests returned for correction can be resubmitted");
        // In-flight requests finish on the version they started on (REQ 10.9) — the path is re-evaluated within it.
        WorkflowVersion version = instanceVersion(wi);
        Map<String, Object> request = readAttributes(wi.getRoutingAttributes());
        handler(wi).ifPresent(h -> request.putAll(safeAttributes(h, wi)));
        wi.setRoutingAttributes(writeAttributes(request));
        PathResolver.ResolvedPath path = resolve(wi, version, request);
        supersedeOpenSteps(wi);
        wi.setRoutingRound(wi.getRoutingRound() + 1);
        wi.setStatus(WorkflowInstance.Status.PENDING);
        materialize(wi, version, path);
        record(wi, ApprovalAction.Type.RESUBMIT, comment, null, null, null);
        handler(wi).ifPresent(h -> h.onResubmitted(wi));
        audit.log(AuditAction.SUBMIT, wi.getEntityType(), wi.getEntityId(), "Resubmitted: " + wi.getTitle());
        notifyApprovers(wi);
        return wi;
    }

    @Transactional
    public WorkflowInstance cancel(Long instanceId, String comment) {
        WorkflowInstance wi = get(instanceId);
        requireRequester(wi);
        if (!OPEN.contains(wi.getStatus()))
            throw ApiException.rule("NOT_OPEN", "Only open requests can be cancelled");
        wi.setStatus(WorkflowInstance.Status.CANCELLED);
        wi.setCompletedAt(Instant.now());
        wi.setDueAt(null);
        supersedeOpenSteps(wi);
        record(wi, ApprovalAction.Type.CANCEL, comment, null, null, null);
        handler(wi).ifPresent(h -> h.onCancelled(wi));
        audit.log(AuditAction.CANCEL, wi.getEntityType(), wi.getEntityId(), "Cancelled: " + wi.getTitle());
        return wi;
    }

    /**
     * Administrator moves an open request to another published version of its process (REQ 10.9 "unless the
     * administrator chooses otherwise"). A pending request restarts at the first step of the newly resolved path.
     */
    @Transactional
    public WorkflowInstance migrate(Long instanceId, Long targetVersionId, String reason) {
        WorkflowInstance wi = get(instanceId);
        WorkflowVersion target = versionService.version(targetVersionId);
        WorkflowDefinition def = definitions.findByProcessCode(wi.getProcessCode()).orElseThrow();
        if (!target.getDefinitionId().equals(def.getId())) throw ApiException.badRequest("The version belongs to another process");
        if (target.getStatus() != WorkflowVersion.Status.PUBLISHED) throw ApiException.rule("NOT_PUBLISHED", "Only a published version can be used");
        if (!OPEN.contains(wi.getStatus())) throw ApiException.rule("NOT_OPEN", "Request #" + wi.getId() + " is no longer open");
        if (target.getId().equals(wi.getVersionId())) throw ApiException.rule("SAME_VERSION", "Request #" + wi.getId() + " already uses version " + target.getVersionNo());
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to migrate requests");
        Integer from = wi.getVersionNo();
        supersedeOpenSteps(wi);
        wi.setRoutingRound(wi.getRoutingRound() + 1);
        if (wi.getStatus() == WorkflowInstance.Status.PENDING) {
            Map<String, Object> request = readAttributes(wi.getRoutingAttributes());
            handler(wi).ifPresent(h -> request.putAll(safeAttributes(h, wi)));
            wi.setRoutingAttributes(writeAttributes(request));
            materialize(wi, target, resolve(wi, target, request));
        } else {
            wi.setVersionId(target.getId());
            wi.setVersionNo(target.getVersionNo());
        }
        record(wi, ApprovalAction.Type.MIGRATE, "Moved from version " + from + " to version " + target.getVersionNo() + ": " + reason, null, null, null);
        audit.log(AuditAction.CONFIG_CHANGE, "WorkflowInstance", wi.getId(), "Request moved from workflow version " + from
                + " to " + target.getVersionNo() + ": " + reason);
        if (wi.getStatus() == WorkflowInstance.Status.PENDING) notifyApprovers(wi);
        return wi;
    }

    // ------------------------------------------------------------------ act

    /** APPROVE also confirms VERIFY and RECORD stages; VERIFY / RECORD are accepted only at those stages. */
    public enum Decision {APPROVE, REJECT, RETURN, VERIFY, RECORD}

    @Transactional
    public WorkflowInstance act(Long instanceId, Decision decision, String comment) {
        WorkflowInstance wi = get(instanceId);
        if (wi.getStatus() != WorkflowInstance.Status.PENDING)
            throw ApiException.rule("NOT_PENDING", "This request is no longer awaiting approval");
        UserPrincipal me = CurrentUser.get();
        if (me.getId().equals(wi.getRequesterUserId()))
            throw ApiException.rule("SEGREGATION_OF_DUTIES", "You cannot approve your own request");
        if (!me.has(Permission.WFL_APPROVE))
            throw ApiException.forbidden("You are not authorized to act on approval requests");
        WorkflowInstanceStep step = currentStep(wi);
        if (step == null) throw ApiException.rule("NO_STEP", "This request has no active step");
        ApproverResolver.Candidate cand = candidates(wi, step).get(me.getId());
        if (cand == null) {
            boolean alreadyActed = step.getSlots().stream().anyMatch(s -> me.getId().equals(s.getActedByUserId()) || me.getId().equals(s.getOnBehalfOfUserId()));
            if (alreadyActed) throw ApiException.rule("ALREADY_ACTED", "You have already acted on this step; another approver must complete it");
            throw ApiException.forbidden("You are not an approver for the current stage of this request");
        }
        ApprovalAction.Type positive = positiveAction(step.getActionType());
        Decision d = decision;
        if (d == Decision.VERIFY && step.getActionType() != ActionType.VERIFY
                || d == Decision.RECORD && step.getActionType() != ActionType.RECORD)
            throw ApiException.rule("INVALID_DECISION", "This stage requires: " + allowedDecisions(step.getActionType()));
        if (d == Decision.REJECT && step.getActionType() != ActionType.APPROVE)
            throw ApiException.rule("INVALID_DECISION", "A " + step.getActionType().name().toLowerCase() + " stage cannot reject; return the request instead");
        if (d == Decision.RETURN && step.getActionType() == ActionType.RECORD)
            throw ApiException.rule("INVALID_DECISION", "A record stage only acknowledges the decision");
        if ((d == Decision.REJECT || d == Decision.RETURN) && (comment == null || comment.isBlank()))
            throw ApiException.badRequest("A comment is required when rejecting or returning a request");

        Long behalfId = cand.onBehalfOfUserId();
        String behalfName = behalfId == null ? null : users.findById(behalfId).map(AppUser::getFullName).orElse(null);
        WorkflowStepSlot slot = step.getSlots().get(cand.slotIndex());
        boolean isPositive = d == Decision.APPROVE || d == Decision.VERIFY || d == Decision.RECORD;
        slot.setActedByUserId(me.getId());
        slot.setActedByName(me.getFullName());
        slot.setOnBehalfOfUserId(behalfId);
        slot.setActedAt(Instant.now());
        slot.setDecision(isPositive ? positive.name() : d.name());

        if (isPositive) {
            slot.setStatus(WorkflowStepSlot.Status.DONE);
            boolean stepDone = step.getMode() == Mode.ANY
                    || step.getSlots().stream().allMatch(s -> s.getStatus() != WorkflowStepSlot.Status.PENDING);
            String progress = "";
            if (step.getMode() == Mode.ALL && step.getSlots().size() > 1) {
                long done = step.getSlots().stream().filter(s -> s.getStatus() == WorkflowStepSlot.Status.DONE).count();
                progress = " (" + done + " of " + step.getSlots().size() + ")";
            }
            record(wi, positive, comment, behalfId, behalfName, step, slot.getLabel());
            if (stepDone) {
                step.getSlots().stream().filter(s -> s.getStatus() == WorkflowStepSlot.Status.PENDING)
                        .forEach(s -> s.setStatus(WorkflowStepSlot.Status.NOT_REQUIRED));
                step.setStatus(WorkflowInstanceStep.Status.DONE);
                step.setCompletedAt(Instant.now());
                List<WorkflowInstanceStep> round = roundSteps(wi);
                WorkflowInstanceStep next = round.stream().filter(s -> s.getStepOrder() == step.getStepOrder() + 1).findFirst().orElse(null);
                if (next == null) {
                    wi.setStatus(WorkflowInstance.Status.APPROVED);
                    wi.setCompletedAt(Instant.now());
                    wi.setDueAt(null);
                    handler(wi).ifPresent(h -> h.onApproved(wi));
                    notifyRequester(wi, "WFL_APPROVED", comment);
                } else {
                    enterStep(wi, next, round.size());
                    notifyApprovers(wi);
                }
            }
            audit.log(AuditAction.APPROVE, wi.getEntityType(), wi.getEntityId(),
                    positive.name().charAt(0) + positive.name().substring(1).toLowerCase() + (positive == ApprovalAction.Type.RECORD ? "ed" : "d")
                            + " at stage: " + step.getName() + progress);
            return wi;
        }
        slot.setStatus(WorkflowStepSlot.Status.DONE);
        if (d == Decision.REJECT) {
            record(wi, ApprovalAction.Type.REJECT, comment, behalfId, behalfName, step, slot.getLabel());
            wi.setStatus(WorkflowInstance.Status.REJECTED);
            wi.setCompletedAt(Instant.now());
            wi.setDueAt(null);
            step.setCompletedAt(Instant.now());
            handler(wi).ifPresent(h -> h.onRejected(wi));
            notifyRequester(wi, "WFL_REJECTED", comment);
            audit.log(AuditAction.REJECT, wi.getEntityType(), wi.getEntityId(), "Rejected: " + comment);
        } else {
            record(wi, ApprovalAction.Type.RETURN, comment, behalfId, behalfName, step, slot.getLabel());
            wi.setStatus(WorkflowInstance.Status.RETURNED);
            wi.setDueAt(null);
            handler(wi).ifPresent(h -> h.onReturned(wi));
            notifyRequester(wi, "WFL_RETURNED", comment);
            audit.log(AuditAction.RETURN, wi.getEntityType(), wi.getEntityId(), "Returned for correction: " + comment);
        }
        return wi;
    }

    static ApprovalAction.Type positiveAction(ActionType t) {
        return switch (t) {
            case APPROVE -> ApprovalAction.Type.APPROVE;
            case VERIFY -> ApprovalAction.Type.VERIFY;
            case RECORD -> ApprovalAction.Type.RECORD;
        };
    }

    static List<String> allowedDecisions(ActionType t) {
        return switch (t) {
            case APPROVE -> List.of("APPROVE", "RETURN", "REJECT");
            case VERIFY -> List.of("VERIFY", "RETURN");
            case RECORD -> List.of("RECORD");
        };
    }

    // ------------------------------------------------------------------ queries

    @Transactional
    public List<WorkflowInstance> inbox() {
        Long me = CurrentUser.get().getId();
        return instances.findByStatusOrderBySubmittedAtAsc(WorkflowInstance.Status.PENDING).stream()
                .filter(wi -> {
                    WorkflowInstanceStep st = currentStep(wi);
                    return st != null && candidates(wi, st).containsKey(me);
                }).toList();
    }

    public record ProcessCount(String processCode, String processName, long count, long overdue) {
    }

    /** "Awaiting my approval" summary for dashboards (D-4). */
    public record InboxSummary(long total, long overdue, long dueWithin24h, Instant oldestSubmittedAt, List<ProcessCount> byProcess,
                               List<WorkflowInstance> items) {
    }

    @Transactional
    public InboxSummary inboxSummary(int limit) {
        List<WorkflowInstance> mine = inbox();
        Instant soon = Instant.now().plus(24, ChronoUnit.HOURS);
        Map<String, String> names = definitions.findAll().stream()
                .collect(Collectors.toMap(WorkflowDefinition::getProcessCode, WorkflowDefinition::getName, (a, b) -> a));
        Map<String, List<WorkflowInstance>> byProc = mine.stream()
                .collect(Collectors.groupingBy(WorkflowInstance::getProcessCode, TreeMap::new, Collectors.toList()));
        List<ProcessCount> counts = byProc.entrySet().stream().map(e -> new ProcessCount(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                e.getValue().size(), e.getValue().stream().filter(WorkflowInstance::isOverdue).count())).toList();
        List<WorkflowInstance> items = mine.stream()
                .sorted(Comparator.comparing(WorkflowInstance::isOverdue).reversed()
                        .thenComparing(w -> w.getDueAt() == null ? Instant.MAX : w.getDueAt()))
                .limit(Math.max(0, Math.min(limit, 100))).toList();
        return new InboxSummary(mine.size(), mine.stream().filter(WorkflowInstance::isOverdue).count(),
                mine.stream().filter(w -> !w.isOverdue() && w.getDueAt() != null && w.getDueAt().isBefore(soon)).count(),
                mine.stream().map(WorkflowInstance::getSubmittedAt).min(Comparator.naturalOrder()).orElse(null), counts, items);
    }

    @Transactional(readOnly = true)
    public List<WorkflowInstance> myRequests() {
        return instances.findByRequesterUserIdOrderByIdDesc(CurrentUser.get().getId());
    }

    @Transactional(readOnly = true)
    public List<WorkflowInstance> actedOnByMe() {
        List<Long> ids = instances.instanceIdsActedOnBy(CurrentUser.get().getId());
        return ids.isEmpty() ? List.of() : instances.findByIdInOrderByIdDesc(ids);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowInstance> latestFor(String entityType, Long entityId) {
        return instances.findByEntityTypeAndEntityIdOrderByIdDesc(entityType, entityId).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<WorkflowInstance> historyFor(String entityType, Long entityId) {
        return instances.findByEntityTypeAndEntityIdOrderByIdDesc(entityType, entityId);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return instances.countByStatus(WorkflowInstance.Status.PENDING);
    }

    public record SlotView(int slotOrder, String approverType, String approverRef, String label, String status,
                           String actedByName, String decision, Instant actedAt) {
    }

    /** A step of the resolved path; {@code approverType/approverRef} mirror the first slot for older screens. */
    public record StepView(Long id, int stageOrder, String name, String stageName, String actionType, String mode,
                           String approverType, String approverRef, String status, Integer slaHours, Instant enteredAt,
                           Instant dueAt, Instant completedAt, Integer walkLevel, List<SlotView> slots) {
    }

    public record InstanceDetail(WorkflowInstance instance, List<ApprovalAction> actions, Map<String, Object> summary,
                                 List<StepView> stages, boolean canAct, boolean isRequester, List<String> allowedDecisions,
                                 Map<String, Object> routingAttributes) {
    }

    @Transactional
    public InstanceDetail detail(Long id) {
        WorkflowInstance wi = get(id);
        UserPrincipal me = CurrentUser.get();
        boolean requester = me.getId().equals(wi.getRequesterUserId());
        WorkflowInstanceStep step = wi.getStatus() == WorkflowInstance.Status.PENDING ? currentStep(wi) : null;
        boolean canAct = step != null && !requester && me.has(Permission.WFL_APPROVE) && candidates(wi, step).containsKey(me.getId());
        boolean participated = actions.existsByInstanceIdAndActorUserId(id, me.getId());
        if (!requester && !canAct && !participated && !me.has(Permission.WFL_CONFIG) && !me.has(Permission.AUDIT_VIEW)) {
            throw ApiException.forbidden("You do not have access to this request");
        }
        Map<String, Object> summary = handler(wi).map(h -> h.summary(wi)).orElse(Map.of());
        if (steps.countByInstanceId(wi.getId()) == 0) ensureSteps(wi);
        List<StepView> views = roundSteps(wi).stream().map(WorkflowService::view).toList();
        Map<String, Object> attrs = me.has(Permission.WFL_CONFIG) || me.has(Permission.AUDIT_VIEW) ? readAttributes(wi.getRoutingAttributes()) : Map.of();
        return new InstanceDetail(wi, actions.findByInstanceIdOrderByIdAsc(id), summary, views, canAct, requester,
                canAct ? allowedDecisions(step.getActionType()) : List.of(), attrs);
    }

    static StepView view(WorkflowInstanceStep s) {
        WorkflowStepSlot first = s.getSlots().isEmpty() ? null : s.getSlots().get(0);
        return new StepView(s.getId(), s.getStepOrder(), s.getName(), s.getStageName(), s.getActionType().name(), s.getMode().name(),
                first == null ? null : first.getApproverType().name(), first == null ? null : first.getApproverRef(),
                s.getStatus().name(), s.getSlaHours(), s.getEnteredAt(), s.getDueAt(), s.getCompletedAt(), s.getWalkLevel(),
                s.getSlots().stream().map(x -> new SlotView(x.getSlotOrder(), x.getApproverType().name(), x.getApproverRef(), x.getLabel(),
                        x.getStatus().name(), x.getActedByName(), x.getDecision(), x.getActedAt())).toList());
    }

    /** Whether the current user may see the approval trail of an entity (used by module screens). */
    public boolean canView(WorkflowInstance wi) {
        UserPrincipal me = CurrentUser.get();
        return me.getId().equals(wi.getRequesterUserId()) || me.has(Permission.WFL_CONFIG) || me.has(Permission.AUDIT_VIEW)
                || actions.existsByInstanceIdAndActorUserId(wi.getId(), me.getId());
    }

    @Transactional(readOnly = true)
    public List<ApprovalAction> actionsFor(Long instanceId) {
        return actions.findByInstanceIdOrderByIdAsc(instanceId);
    }

    // ------------------------------------------------------------------ escalation (FR-WFL-008)

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void escalateOverdue() {
        Instant now = Instant.now();
        for (WorkflowInstance wi : instances.findByStatusOrderBySubmittedAtAsc(WorkflowInstance.Status.PENDING)) {
            if (wi.isEscalated()) continue;
            WorkflowInstanceStep st = currentStep(wi);
            if (st == null || st.getDueAt() == null || st.getDueAt().isAfter(now)) continue;
            wi.setEscalated(true);
            ActionRecord.system(actions, wi, ApprovalAction.Type.ESCALATE, "Overdue by SLA of " + st.getSlaHours() + "h");
            Map<String, Object> vars = vars(wi, null);
            notifications.notifyUsers(candidates(wi, st).keySet(), "WFL_ESCALATED", vars, "/approvals/" + wi.getId());
            if (st.getEscalationRoleCode() != null)
                notifications.notifyRole(st.getEscalationRoleCode(), "WFL_ESCALATED", vars, "/approvals/" + wi.getId());
        }
    }

    // ------------------------------------------------------------------ internals

    /** Who may act on the step now (actor → candidate). Excludes the requester and anyone who already acted on the step. */
    Map<Long, ApproverResolver.Candidate> candidates(WorkflowInstance wi, WorkflowInstanceStep step) {
        List<ApproverResolver.SlotRef> refs = step.getSlots().stream()
                .map(s -> new ApproverResolver.SlotRef(s.getApproverType(), s.getApproverRef())).toList();
        List<Integer> pending = new ArrayList<>();
        Set<Long> excluded = new HashSet<>();
        for (int i = 0; i < step.getSlots().size(); i++) {
            WorkflowStepSlot s = step.getSlots().get(i);
            if (s.getStatus() == WorkflowStepSlot.Status.PENDING) pending.add(i);
            if (s.getActedByUserId() != null) excluded.add(s.getActedByUserId());
            if (s.getOnBehalfOfUserId() != null) excluded.add(s.getOnBehalfOfUserId());
        }
        return approverResolver.candidates(refs, pending, step.getEscalationRoleCode(), wi.getSubjectOrgUnitCode(),
                wi.getRequesterUserId(), wi.getProcessCode(), excluded);
    }

    /** Kept for callers inside the package: actor → user acted for. */
    Map<Long, Long> eligibleApprovers(WorkflowInstance wi) {
        WorkflowInstanceStep st = currentStep(wi);
        if (st == null) return Map.of();
        Map<Long, Long> out = new LinkedHashMap<>();
        candidates(wi, st).forEach((k, c) -> out.put(k, c.onBehalfOfUserId() == null ? k : c.onBehalfOfUserId()));
        return out;
    }

    private PathResolver.ResolvedPath resolve(WorkflowInstance wi, WorkflowVersion version, Map<String, Object> request) {
        Map<String, Object> attrs = pathResolver.attributes(request, wi.getRequesterUserId(), wi.getRequesterEmployeeId(), wi.getSubjectEmployeeId());
        PathResolver.Context ctx = new PathResolver.Context(wi.getRequesterUserId(), wi.getRequesterEmployeeId(), wi.getSubjectEmployeeId(),
                wi.getSubjectOrgUnitCode(), attrs);
        return pathResolver.resolve(versionService.model(version), ctx);
    }

    /** Persists the resolved steps as the current round and activates the first one. */
    private void materialize(WorkflowInstance wi, WorkflowVersion version, PathResolver.ResolvedPath path) {
        wi.setVersionId(version.getId());
        wi.setVersionNo(version.getVersionNo());
        wi.setPathCode(path.pathCode());
        wi.setPathName(truncate(path.pathName(), 150));
        wi.setMatchedRule(truncate(path.matchedRule(), 150));
        List<WorkflowInstanceStep> created = new ArrayList<>();
        int order = 1;
        for (PathResolver.ResolvedStep rs : path.steps()) {
            WorkflowInstanceStep st = new WorkflowInstanceStep();
            st.setInstanceId(wi.getId());
            st.setRound(wi.getRoutingRound());
            st.setStepOrder(order++);
            st.setName(truncate(rs.name(), 150));
            st.setStageName(truncate(rs.stageName(), 150));
            st.setActionType(rs.actionType());
            st.setMode(rs.mode());
            st.setSlaHours(rs.slaHours());
            st.setEscalationRoleCode(rs.escalationRoleCode());
            st.setWalkLevel(rs.walkLevel());
            st.setStatus(WorkflowInstanceStep.Status.WAITING);
            int slotOrder = 1;
            for (PathResolver.ResolvedSlot sl : rs.slots()) {
                WorkflowStepSlot slot = new WorkflowStepSlot();
                slot.setStep(st);
                slot.setSlotOrder(slotOrder++);
                slot.setApproverType(sl.type());
                slot.setApproverRef(sl.ref());
                slot.setLabel(truncate(sl.label(), 250));
                slot.setStatus(WorkflowStepSlot.Status.PENDING);
                st.getSlots().add(slot);
            }
            created.add(steps.save(st));
        }
        enterStep(wi, created.get(0), created.size());
    }

    private void enterStep(WorkflowInstance wi, WorkflowInstanceStep st, int total) {
        Instant now = Instant.now();
        st.setStatus(WorkflowInstanceStep.Status.ACTIVE);
        st.setEnteredAt(now);
        st.setDueAt(st.getSlaHours() == null ? null : now.plus(st.getSlaHours(), ChronoUnit.HOURS));
        wi.setCurrentStage(st.getStepOrder());
        wi.setTotalStages(total);
        wi.setCurrentStageName(st.getName());
        wi.setCurrentActionType(st.getActionType());
        wi.setStageEnteredAt(now);
        wi.setDueAt(st.getDueAt());
        wi.setEscalated(false);
    }

    private void supersedeOpenSteps(WorkflowInstance wi) {
        for (WorkflowInstanceStep s : steps.findByInstanceIdAndRoundOrderByStepOrderAsc(wi.getId(), wi.getRoutingRound())) {
            if (s.getStatus() != WorkflowInstanceStep.Status.DONE) s.setStatus(WorkflowInstanceStep.Status.SUPERSEDED);
        }
    }

    private List<WorkflowInstanceStep> roundSteps(WorkflowInstance wi) {
        return steps.findByInstanceIdAndRoundOrderByStepOrderAsc(wi.getId(), wi.getRoutingRound());
    }

    /** The active step; requests created before versioned routing get their steps built on first use. */
    WorkflowInstanceStep currentStep(WorkflowInstance wi) {
        if (wi.getStatus() != WorkflowInstance.Status.PENDING) return null;
        List<WorkflowInstanceStep> round = roundSteps(wi);
        if (round.isEmpty()) {
            ensureSteps(wi);
            round = roundSteps(wi);
        }
        return round.stream().filter(s -> s.getStepOrder() == wi.getCurrentStage()).findFirst().orElse(null);
    }

    /** Builds steps for a request that predates versioned routing, keeping its progress. */
    private void ensureSteps(WorkflowInstance wi) {
        if (steps.countByInstanceId(wi.getId()) > 0) return;
        WorkflowVersion version;
        try {
            version = instanceVersion(wi);
        } catch (ApiException e) {
            log.warn("Request #{} has no workflow version to build its steps from", wi.getId());
            return;
        }
        Map<String, Object> request = readAttributes(wi.getRoutingAttributes());
        PathResolver.ResolvedPath path = resolve(wi, version, request);
        int current = wi.getCurrentStage();
        Instant entered = wi.getStageEnteredAt();
        boolean escalated = wi.isEscalated();
        materialize(wi, version, path);
        List<WorkflowInstanceStep> round = roundSteps(wi);
        int target = Math.max(1, Math.min(current, round.size()));
        for (WorkflowInstanceStep s : round) {
            if (s.getStepOrder() < target || wi.getStatus() == WorkflowInstance.Status.APPROVED) {
                s.setStatus(WorkflowInstanceStep.Status.DONE);
                s.getSlots().forEach(x -> x.setStatus(WorkflowStepSlot.Status.NOT_REQUIRED));
            } else if (s.getStepOrder() == target && wi.getStatus() == WorkflowInstance.Status.PENDING) {
                enterStep(wi, s, round.size());
                s.setEnteredAt(entered);
                s.setDueAt(s.getSlaHours() == null || entered == null ? null : entered.plus(s.getSlaHours(), ChronoUnit.HOURS));
                wi.setStageEnteredAt(entered);
                wi.setDueAt(s.getDueAt());
                wi.setEscalated(escalated);
            } else {
                s.setStatus(WorkflowInstanceStep.Status.WAITING);
            }
        }
    }

    private WorkflowVersion instanceVersion(WorkflowInstance wi) {
        if (wi.getVersionId() != null) return versionService.version(wi.getVersionId());
        WorkflowDefinition def = definitions.findByProcessCode(wi.getProcessCode())
                .orElseThrow(() -> ApiException.rule("NO_WORKFLOW", "No workflow is configured for '" + wi.getProcessCode() + "'"));
        Instant at = wi.getSubmittedAt() == null ? Instant.now() : wi.getSubmittedAt();
        return versionService.effectiveVersion(def.getId(), at)
                .or(() -> versionService.effectiveVersion(def.getId(), Instant.now()))
                .orElseThrow(() -> ApiException.rule("NO_WORKFLOW", "No published version for '" + wi.getProcessCode() + "'"));
    }

    private WorkflowDefinition activeDefinition(String processCode) {
        WorkflowDefinition def = definitions.findByProcessCode(processCode).filter(WorkflowDefinition::isActive)
                .orElseThrow(() -> ApiException.rule("NO_WORKFLOW", "No active approval workflow is configured for '"
                        + processCode + "'. Ask the system administrator to configure one."));
        versionService.syncLegacyStages(def);
        return def;
    }

    private WorkflowInstance get(Long id) {
        return instances.findById(id).orElseThrow(() -> ApiException.notFound("Approval request", id));
    }

    private void requireRequester(WorkflowInstance wi) {
        if (!CurrentUser.get().getId().equals(wi.getRequesterUserId()))
            throw ApiException.forbidden("Only the requester can do this");
    }

    /** Handlers are resolved lazily so modules can depend on WorkflowService without a bean cycle. */
    private Optional<WorkflowHandler> handler(WorkflowInstance wi) {
        return handler(wi.getProcessCode());
    }

    Optional<WorkflowHandler> handler(String processCode) {
        if (handlers == null) {
            handlers = handlerProvider.orderedStream()
                    .collect(Collectors.toMap(WorkflowHandler::processCode, Function.identity(), (a, b) -> a));
        }
        return Optional.ofNullable(handlers.get(processCode));
    }

    private Map<String, Object> safeAttributes(WorkflowHandler h, WorkflowInstance wi) {
        try {
            Map<String, Object> m = h.routingAttributes(wi);
            return m == null ? Map.of() : m;
        } catch (RuntimeException e) {
            log.warn("Routing attributes of {} #{} unavailable: {}", wi.getProcessCode(), wi.getEntityId(), e.getMessage());
            return Map.of();
        }
    }

    String writeAttributes(Map<String, Object> m) {
        try {
            Map<String, Object> clean = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (k != null && (v == null || v instanceof Number || v instanceof Boolean || v instanceof CharSequence || v instanceof Collection<?>
                        || v instanceof Enum<?> || v instanceof java.time.temporal.Temporal))
                    clean.put(k, v instanceof Enum<?> e ? e.name() : v instanceof java.time.temporal.Temporal t ? t.toString() : v);
            });
            String s = json.writeValueAsString(clean);
            return s.length() > 20000 ? "{}" : s;
        } catch (Exception e) {
            return "{}";
        }
    }

    Map<String, Object> readAttributes(String s) {
        if (s == null || s.isBlank()) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(json.readValue(s, new TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String routingNote(WorkflowInstance wi) {
        return "Workflow version " + wi.getVersionNo() + ", path " + wi.getPathName()
                + (wi.getMatchedRule() == null ? " (default)" : " (rule: " + wi.getMatchedRule() + ")");
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private void record(WorkflowInstance wi, ApprovalAction.Type type, String comment, Long behalfId, String behalfName,
                        WorkflowInstanceStep step) {
        record(wi, type, comment, behalfId, behalfName, step, null);
    }

    private void record(WorkflowInstance wi, ApprovalAction.Type type, String comment, Long behalfId, String behalfName,
                        WorkflowInstanceStep step, String slotLabel) {
        UserPrincipal me = CurrentUser.get();
        ApprovalAction a = new ApprovalAction();
        a.setInstanceId(wi.getId());
        a.setStageOrder(step != null ? step.getStepOrder() : wi.getCurrentStage());
        a.setStageName(step != null ? step.getName() : wi.getCurrentStageName());
        a.setActorUserId(me.getId());
        a.setActorName(me.getFullName());
        a.setOnBehalfOfUserId(behalfId);
        a.setOnBehalfOfName(behalfName);
        a.setAction(type);
        a.setComment(truncate(comment, 2000));
        a.setActedAt(Instant.now());
        a.setStepId(step == null ? null : step.getId());
        a.setStageActionType(step == null ? null : step.getActionType().name());
        a.setSlotLabel(slotLabel);
        a.setWorkflowVersionNo(wi.getVersionNo());
        actions.save(a);
    }

    private void notifyApprovers(WorkflowInstance wi) {
        WorkflowInstanceStep st = currentStep(wi);
        if (st == null) return;
        notifications.notifyUsers(candidates(wi, st).keySet(), "WFL_PENDING", vars(wi, null), "/approvals/" + wi.getId());
    }

    private void notifyRequester(WorkflowInstance wi, String template, String comment) {
        notifications.notifyUser(wi.getRequesterUserId(), template, vars(wi, comment),
                wi.getLink() != null ? wi.getLink() : "/approvals/" + wi.getId());
    }

    private static Map<String, Object> vars(WorkflowInstance wi, String comment) {
        Map<String, Object> v = new HashMap<>();
        v.put("title", wi.getTitle());
        v.put("requester", wi.getRequesterName());
        v.put("stage", wi.getCurrentStageName());
        v.put("reference", "#" + wi.getId());
        v.put("comment", comment == null ? "" : comment);
        return v;
    }

    /** System-generated history rows (no authenticated actor). */
    static final class ActionRecord {
        private ActionRecord() {
        }

        static void system(ApprovalActionRepository repo, WorkflowInstance wi, ApprovalAction.Type type, String comment) {
            ApprovalAction a = new ApprovalAction();
            a.setInstanceId(wi.getId());
            a.setStageOrder(wi.getCurrentStage());
            a.setStageName(wi.getCurrentStageName());
            a.setActorName("System");
            a.setAction(type);
            a.setComment(comment);
            a.setActedAt(Instant.now());
            a.setStageActionType(wi.getCurrentActionType() == null ? null : wi.getCurrentActionType().name());
            a.setWorkflowVersionNo(wi.getVersionNo());
            repo.save(a);
        }
    }
}
