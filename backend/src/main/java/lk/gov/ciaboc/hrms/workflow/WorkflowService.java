package lk.gov.ciaboc.hrms.workflow;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generic approval engine used by every module. Approval chains are data, not code (FR-WFL-009); requesters can
 * never approve their own requests (FR-WFL-010); delegates may act during approved absences (FR-WFL-007).
 */
@Slf4j
@Service
public class WorkflowService {
    private final WorkflowDefinitionRepository definitions;
    private final WorkflowInstanceRepository instances;
    private final ApprovalActionRepository actions;
    private final DelegationRepository delegations;
    private final AppUserRepository users;
    private final OrgDirectory orgDirectory;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectProvider<WorkflowHandler> handlerProvider;
    private final SettingsService settings;
    private volatile Map<String, WorkflowHandler> handlers;

    public WorkflowService(WorkflowDefinitionRepository definitions, WorkflowInstanceRepository instances,
                           ApprovalActionRepository actions, DelegationRepository delegations, AppUserRepository users,
                           @Lazy OrgDirectory orgDirectory, NotificationService notifications, AuditService audit,
                           ObjectProvider<WorkflowHandler> handlerProvider, SettingsService settings) {
        this.settings = settings;
        this.definitions = definitions;
        this.instances = instances;
        this.actions = actions;
        this.delegations = delegations;
        this.users = users;
        this.orgDirectory = orgDirectory;
        this.notifications = notifications;
        this.audit = audit;
        this.handlerProvider = handlerProvider;
    }

    // ------------------------------------------------------------------ start / resubmit / cancel

    /**
     * Starts approval for a record. {@code subjectEmployeeId} is the employee the request is about (used for
     * DIVISION_HEAD routing); pass null when not applicable.
     */
    @Transactional
    public WorkflowInstance start(String processCode, String entityType, Long entityId, String title, String link,
                                  Long subjectEmployeeId) {
        WorkflowDefinition def = activeDefinition(processCode);
        instances.findByEntityTypeAndEntityIdOrderByIdDesc(entityType, entityId).stream()
                .filter(i -> i.getStatus() == WorkflowInstance.Status.PENDING || i.getStatus() == WorkflowInstance.Status.RETURNED)
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
        Long subject = subjectEmployeeId != null ? subjectEmployeeId : me.getEmployeeId();
        wi.setSubjectOrgUnitCode(subject == null ? null : orgDirectory.orgUnitOfEmployee(subject));
        wi.setStatus(WorkflowInstance.Status.PENDING);
        wi.setSubmittedAt(Instant.now());
        wi.setTotalStages(def.getStages().size());
        enterStage(wi, def, 1);
        instances.save(wi);
        record(wi, ApprovalAction.Type.SUBMIT, null, null, null);
        audit.log(AuditAction.SUBMIT, entityType, entityId, "Submitted for approval: " + title);
        notifyApprovers(wi, def);
        return wi;
    }

    @Transactional
    public WorkflowInstance resubmit(Long instanceId, String comment) {
        WorkflowInstance wi = get(instanceId);
        requireRequester(wi);
        if (wi.getStatus() != WorkflowInstance.Status.RETURNED)
            throw ApiException.rule("NOT_RETURNED", "Only requests returned for correction can be resubmitted");
        WorkflowDefinition def = activeDefinition(wi.getProcessCode());
        wi.setStatus(WorkflowInstance.Status.PENDING);
        wi.setTotalStages(def.getStages().size());
        enterStage(wi, def, 1);
        record(wi, ApprovalAction.Type.RESUBMIT, comment, null, null);
        handler(wi).ifPresent(h -> h.onResubmitted(wi));
        audit.log(AuditAction.SUBMIT, wi.getEntityType(), wi.getEntityId(), "Resubmitted: " + wi.getTitle());
        notifyApprovers(wi, def);
        return wi;
    }

    @Transactional
    public WorkflowInstance cancel(Long instanceId, String comment) {
        WorkflowInstance wi = get(instanceId);
        requireRequester(wi);
        if (wi.getStatus() != WorkflowInstance.Status.PENDING && wi.getStatus() != WorkflowInstance.Status.RETURNED)
            throw ApiException.rule("NOT_OPEN", "Only open requests can be cancelled");
        wi.setStatus(WorkflowInstance.Status.CANCELLED);
        wi.setCompletedAt(Instant.now());
        record(wi, ApprovalAction.Type.CANCEL, comment, null, null);
        handler(wi).ifPresent(h -> h.onCancelled(wi));
        audit.log(AuditAction.CANCEL, wi.getEntityType(), wi.getEntityId(), "Cancelled: " + wi.getTitle());
        return wi;
    }

    // ------------------------------------------------------------------ act

    public enum Decision {APPROVE, REJECT, RETURN}

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
        WorkflowDefinition def = activeDefinition(wi.getProcessCode());
        Map<Long, Long> eligible = eligibleApprovers(wi, def); // actor -> on-behalf-of (or self)
        Long onBehalf = eligible.get(me.getId());
        if (onBehalf == null) throw ApiException.forbidden("You are not an approver for the current stage of this request");
        if ((decision == Decision.REJECT || decision == Decision.RETURN) && (comment == null || comment.isBlank()))
            throw ApiException.badRequest("A comment is required when rejecting or returning a request");

        Long behalfId = onBehalf.equals(me.getId()) ? null : onBehalf;
        String behalfName = behalfId == null ? null : users.findById(behalfId).map(AppUser::getFullName).orElse(null);

        switch (decision) {
            case APPROVE -> {
                record(wi, ApprovalAction.Type.APPROVE, comment, behalfId, behalfName);
                if (wi.getCurrentStage() >= def.getStages().size()) {
                    wi.setStatus(WorkflowInstance.Status.APPROVED);
                    wi.setCompletedAt(Instant.now());
                    handler(wi).ifPresent(h -> h.onApproved(wi));
                    notifyRequester(wi, "WFL_APPROVED", comment);
                } else {
                    enterStage(wi, def, wi.getCurrentStage() + 1);
                    notifyApprovers(wi, def);
                }
                audit.log(AuditAction.APPROVE, wi.getEntityType(), wi.getEntityId(), "Approved at stage: " + stageName(def, wi));
            }
            case REJECT -> {
                record(wi, ApprovalAction.Type.REJECT, comment, behalfId, behalfName);
                wi.setStatus(WorkflowInstance.Status.REJECTED);
                wi.setCompletedAt(Instant.now());
                handler(wi).ifPresent(h -> h.onRejected(wi));
                notifyRequester(wi, "WFL_REJECTED", comment);
                audit.log(AuditAction.REJECT, wi.getEntityType(), wi.getEntityId(), "Rejected: " + comment);
            }
            case RETURN -> {
                record(wi, ApprovalAction.Type.RETURN, comment, behalfId, behalfName);
                wi.setStatus(WorkflowInstance.Status.RETURNED);
                handler(wi).ifPresent(h -> h.onReturned(wi));
                notifyRequester(wi, "WFL_RETURNED", comment);
                audit.log(AuditAction.RETURN, wi.getEntityType(), wi.getEntityId(), "Returned for correction: " + comment);
            }
        }
        return wi;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<WorkflowInstance> inbox() {
        Long me = CurrentUser.get().getId();
        Map<String, WorkflowDefinition> defs = new HashMap<>();
        return instances.findByStatusOrderBySubmittedAtAsc(WorkflowInstance.Status.PENDING).stream()
                .filter(wi -> {
                    WorkflowDefinition def = defs.computeIfAbsent(wi.getProcessCode(),
                            c -> definitions.findByProcessCode(c).orElse(null));
                    return def != null && eligibleApprovers(wi, def).containsKey(me);
                }).toList();
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

    public record InstanceDetail(WorkflowInstance instance, List<ApprovalAction> actions, Map<String, Object> summary,
                                 List<WorkflowStage> stages, boolean canAct, boolean isRequester) {
    }

    @Transactional(readOnly = true)
    public InstanceDetail detail(Long id) {
        WorkflowInstance wi = get(id);
        UserPrincipal me = CurrentUser.get();
        WorkflowDefinition def = definitions.findByProcessCode(wi.getProcessCode()).orElse(null);
        boolean requester = me.getId().equals(wi.getRequesterUserId());
        boolean canAct = def != null && wi.getStatus() == WorkflowInstance.Status.PENDING && !requester
                && me.has(Permission.WFL_APPROVE) && eligibleApprovers(wi, def).containsKey(me.getId());
        boolean participated = actions.existsByInstanceIdAndActorUserId(id, me.getId());
        if (!requester && !canAct && !participated && !me.has(Permission.WFL_CONFIG) && !me.has(Permission.AUDIT_VIEW)) {
            throw ApiException.forbidden("You do not have access to this request");
        }
        Map<String, Object> summary = handler(wi).map(h -> h.summary(wi)).orElse(Map.of());
        return new InstanceDetail(wi, actions.findByInstanceIdOrderByIdAsc(id), summary,
                def == null ? List.of() : def.getStages(), canAct, requester);
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
            WorkflowDefinition def = definitions.findByProcessCode(wi.getProcessCode()).orElse(null);
            if (def == null || wi.getCurrentStage() > def.getStages().size()) continue;
            WorkflowStage st = def.getStages().get(wi.getCurrentStage() - 1);
            if (st.getSlaHours() == null || wi.getStageEnteredAt().plus(st.getSlaHours(), ChronoUnit.HOURS).isAfter(now)) continue;
            wi.setEscalated(true);
            ActionRecord.system(actions, wi, ApprovalAction.Type.ESCALATE, "Overdue by SLA of " + st.getSlaHours() + "h");
            Map<String, Object> vars = vars(wi, null);
            notifications.notifyUsers(eligibleApprovers(wi, def).keySet(), "WFL_ESCALATED", vars, "/approvals/" + wi.getId());
            if (st.getEscalationRoleCode() != null)
                notifications.notifyRole(st.getEscalationRoleCode(), "WFL_ESCALATED", vars, "/approvals/" + wi.getId());
        }
    }

    // ------------------------------------------------------------------ internals

    /** Maps each eligible actor to the user they act for (themselves, or a delegator). Excludes the requester. */
    Map<Long, Long> eligibleApprovers(WorkflowInstance wi, WorkflowDefinition def) {
        if (wi.getCurrentStage() < 1 || wi.getCurrentStage() > def.getStages().size()) return Map.of();
        WorkflowStage st = def.getStages().get(wi.getCurrentStage() - 1);
        Set<Long> base = new LinkedHashSet<>();
        switch (st.getApproverType()) {
            case ROLE -> users.findActiveByRole(st.getApproverRef()).forEach(u -> base.add(u.getId()));
            case USER -> {
                try {
                    users.findById(Long.parseLong(st.getApproverRef())).filter(AppUser::isActive).ifPresent(u -> base.add(u.getId()));
                } catch (NumberFormatException e) {
                    log.warn("Workflow stage {} has invalid user reference {}", st.getId(), st.getApproverRef());
                }
            }
            case POSITION -> base.addAll(orgDirectory.userIdsHoldingPosition(st.getApproverRef()));
            case DIVISION_HEAD -> {
                if (wi.getSubjectOrgUnitCode() != null)
                    base.addAll(orgDirectory.divisionHeadUserIdsFor(wi.getSubjectOrgUnitCode(), wi.getRequesterUserId()));
            }
        }
        base.remove(wi.getRequesterUserId());
        // Never leave a request without an approver: fall back to the stage's escalation role, then the configured
        // default role (e.g. unit without a division head, or the only eligible approver is the requester).
        if (base.isEmpty()) {
            String fallback = st.getEscalationRoleCode() != null ? st.getEscalationRoleCode()
                    : settings.get("workflow.fallback-role", "HR_ADMIN");
            users.findActiveByRole(fallback).forEach(u -> base.add(u.getId()));
            base.remove(wi.getRequesterUserId());
        }
        Map<Long, Long> result = new LinkedHashMap<>();
        base.forEach(id -> result.put(id, id));
        for (Delegation d : delegations.activeOn(LocalDate.now())) {
            if (base.contains(d.getFromUserId()) && (d.getProcessCode() == null || d.getProcessCode().equals(wi.getProcessCode()))) {
                result.putIfAbsent(d.getToUserId(), d.getFromUserId());
            }
        }
        result.remove(wi.getRequesterUserId());
        return result;
    }

    private void enterStage(WorkflowInstance wi, WorkflowDefinition def, int stage) {
        wi.setCurrentStage(stage);
        wi.setCurrentStageName(def.getStages().get(stage - 1).getName());
        wi.setStageEnteredAt(Instant.now());
        wi.setEscalated(false);
    }

    private WorkflowDefinition activeDefinition(String processCode) {
        WorkflowDefinition def = definitions.findByProcessCode(processCode).filter(WorkflowDefinition::isActive)
                .orElseThrow(() -> ApiException.rule("NO_WORKFLOW", "No active approval workflow is configured for '"
                        + processCode + "'. Ask the system administrator to configure one."));
        if (def.getStages().isEmpty())
            throw ApiException.rule("NO_WORKFLOW", "The approval workflow for '" + processCode + "' has no stages.");
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
        if (handlers == null) {
            handlers = handlerProvider.orderedStream()
                    .collect(Collectors.toMap(WorkflowHandler::processCode, Function.identity()));
        }
        return Optional.ofNullable(handlers.get(wi.getProcessCode()));
    }

    private static String stageName(WorkflowDefinition def, WorkflowInstance wi) {
        int idx = Math.min(wi.getCurrentStage(), def.getStages().size()) - 1;
        return idx >= 0 ? def.getStages().get(idx).getName() : "";
    }

    private void record(WorkflowInstance wi, ApprovalAction.Type type, String comment, Long behalfId, String behalfName) {
        UserPrincipal me = CurrentUser.get();
        ApprovalAction a = new ApprovalAction();
        a.setInstanceId(wi.getId());
        a.setStageOrder(wi.getCurrentStage());
        a.setStageName(wi.getCurrentStageName());
        a.setActorUserId(me.getId());
        a.setActorName(me.getFullName());
        a.setOnBehalfOfUserId(behalfId);
        a.setOnBehalfOfName(behalfName);
        a.setAction(type);
        a.setComment(comment);
        a.setActedAt(Instant.now());
        actions.save(a);
    }

    private void notifyApprovers(WorkflowInstance wi, WorkflowDefinition def) {
        notifications.notifyUsers(eligibleApprovers(wi, def).keySet(), "WFL_PENDING", vars(wi, null), "/approvals/" + wi.getId());
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
            repo.save(a);
        }
    }
}
