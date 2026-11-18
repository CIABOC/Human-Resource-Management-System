package lk.gov.ciaboc.hrms.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowController {
    private final WorkflowService service;
    private final WorkflowDefinitionRepository definitions;
    private final DelegationRepository delegations;
    private final AppUserRepository users;
    private final AuditService audit;

    // ---- approver views ----

    @GetMapping("/inbox")
    @PreAuthorize("hasAuthority('WFL_APPROVE')")
    public List<WorkflowInstance> inbox() {
        return service.inbox();
    }

    @GetMapping("/my-requests")
    public List<WorkflowInstance> myRequests() {
        return service.myRequests();
    }

    @GetMapping("/acted")
    @PreAuthorize("hasAuthority('WFL_APPROVE')")
    public List<WorkflowInstance> acted() {
        return service.actedOnByMe();
    }

    @GetMapping("/instances/{id}")
    public WorkflowService.InstanceDetail detail(@PathVariable Long id) {
        return service.detail(id);
    }

    /** Approval trail of a record, filtered to instances the caller may see. */
    @GetMapping("/history")
    public List<WorkflowInstance> history(@RequestParam String entityType, @RequestParam Long entityId) {
        return service.historyFor(entityType, entityId).stream().filter(service::canView).toList();
    }

    public record ActRequest(@NotNull WorkflowService.Decision decision, @Size(max = 2000) String comment) {
    }

    @PostMapping("/instances/{id}/act")
    public WorkflowInstance act(@PathVariable Long id, @Valid @RequestBody ActRequest r) {
        return service.act(id, r.decision(), r.comment());
    }

    public record CommentRequest(@Size(max = 2000) String comment) {
    }

    @PostMapping("/instances/{id}/resubmit")
    public WorkflowInstance resubmit(@PathVariable Long id, @RequestBody(required = false) CommentRequest r) {
        return service.resubmit(id, r == null ? null : r.comment());
    }

    @PostMapping("/instances/{id}/cancel")
    public WorkflowInstance cancel(@PathVariable Long id, @RequestBody(required = false) CommentRequest r) {
        return service.cancel(id, r == null ? null : r.comment());
    }

    // ---- configuration (FR-WFL-001..003, 009) ----

    @GetMapping("/definitions")
    @PreAuthorize("hasAuthority('WFL_CONFIG')")
    public List<WorkflowDefinition> definitions() {
        return definitions.findAll(Sort.by("processCode"));
    }

    public record StageRequest(@NotBlank @Size(max = 150) String name, @NotNull WorkflowStage.ApproverType approverType,
                               @Size(max = 100) String approverRef, @Min(1) Integer slaHours,
                               @Size(max = 50) String escalationRoleCode) {
    }

    public record DefinitionRequest(@NotBlank @Size(max = 150) String name, @Size(max = 500) String description,
                                    boolean active, @NotEmpty List<@Valid StageRequest> stages) {
    }

    @PutMapping("/definitions/{id}")
    @PreAuthorize("hasAuthority('WFL_CONFIG')")
    @Transactional
    public WorkflowDefinition update(@PathVariable Long id, @Valid @RequestBody DefinitionRequest r) {
        WorkflowDefinition d = definitions.findById(id).orElseThrow(() -> ApiException.notFound("Workflow", id));
        Map<String, Object> before = Map.of("stages", d.getStages().stream()
                .map(s -> s.getStageOrder() + ":" + s.getName() + ":" + s.getApproverType() + ":" + s.getApproverRef()).toList());
        d.setName(r.name());
        d.setDescription(r.description());
        d.setActive(r.active());
        d.getStages().clear();
        definitions.flush();
        List<WorkflowStage> stages = new ArrayList<>();
        int order = 1;
        for (StageRequest s : r.stages()) {
            if (s.approverType() != WorkflowStage.ApproverType.DIVISION_HEAD && (s.approverRef() == null || s.approverRef().isBlank()))
                throw ApiException.badRequest("Stage '" + s.name() + "' needs an approver reference");
            WorkflowStage st = new WorkflowStage();
            st.setDefinition(d);
            st.setStageOrder(order++);
            st.setName(s.name());
            st.setApproverType(s.approverType());
            st.setApproverRef(s.approverType() == WorkflowStage.ApproverType.DIVISION_HEAD ? null : s.approverRef().trim());
            st.setSlaHours(s.slaHours());
            st.setEscalationRoleCode(s.escalationRoleCode());
            stages.add(st);
        }
        d.getStages().addAll(stages);
        audit.log(AuditAction.CONFIG_CHANGE, "WorkflowDefinition", id, "Workflow " + d.getProcessCode() + " reconfigured", before,
                Map.of("stages", stages.stream().map(s -> s.getStageOrder() + ":" + s.getName() + ":" + s.getApproverType() + ":" + s.getApproverRef()).toList()));
        return d;
    }

    // ---- delegations (FR-WFL-007) ----

    @GetMapping("/delegations")
    public List<Delegation> myDelegations(@RequestParam(defaultValue = "false") boolean all) {
        UserPrincipal me = CurrentUser.get();
        if (all && me.has(Permission.WFL_CONFIG)) return delegations.findAllByOrderByStartDateDesc();
        return delegations.findByFromUserIdOrToUserIdOrderByStartDateDesc(me.getId(), me.getId());
    }

    public record DelegationRequest(Long fromUserId, @NotNull Long toUserId, @Size(max = 50) String processCode,
                                    @NotNull LocalDate startDate, @NotNull LocalDate endDate, @Size(max = 500) String reason) {
    }

    @PostMapping("/delegations")
    @PreAuthorize("hasAuthority('WFL_APPROVE') or hasAuthority('WFL_CONFIG')")
    @Transactional
    public Delegation delegate(@Valid @RequestBody DelegationRequest r) {
        UserPrincipal me = CurrentUser.get();
        Long from = r.fromUserId() == null ? me.getId() : r.fromUserId();
        if (!from.equals(me.getId()) && !me.has(Permission.WFL_CONFIG))
            throw ApiException.forbidden("You can only delegate your own approvals");
        if (from.equals(r.toUserId())) throw ApiException.badRequest("Cannot delegate to the same person");
        if (r.endDate().isBefore(r.startDate())) throw ApiException.badRequest("End date must not be before start date");
        AppUser fromUser = users.findById(from).orElseThrow(() -> ApiException.notFound("User", from));
        AppUser toUser = users.findById(r.toUserId()).filter(AppUser::isActive)
                .orElseThrow(() -> ApiException.notFound("Active user", r.toUserId()));
        Delegation d = new Delegation();
        d.setFromUserId(from);
        d.setFromName(fromUser.getFullName());
        d.setToUserId(toUser.getId());
        d.setToName(toUser.getFullName());
        d.setProcessCode(r.processCode() == null || r.processCode().isBlank() ? null : r.processCode());
        d.setStartDate(r.startDate());
        d.setEndDate(r.endDate());
        d.setReason(r.reason());
        delegations.save(d);
        audit.log(AuditAction.CREATE, "Delegation", d.getId(), "Approvals delegated from " + d.getFromName() + " to "
                + d.getToName() + " (" + d.getStartDate() + " to " + d.getEndDate() + ")");
        return d;
    }

    @PostMapping("/delegations/{id}/revoke")
    @Transactional
    public Delegation revoke(@PathVariable Long id) {
        UserPrincipal me = CurrentUser.get();
        Delegation d = delegations.findById(id).orElseThrow(() -> ApiException.notFound("Delegation", id));
        if (!d.getFromUserId().equals(me.getId()) && !me.has(Permission.WFL_CONFIG))
            throw ApiException.forbidden("You can only revoke your own delegations");
        d.setActive(false);
        audit.log(AuditAction.DEACTIVATE, "Delegation", id, "Delegation revoked");
        return d;
    }

    /** Small user directory for picking delegates and named approvers. */
    public record UserOption(Long id, String fullName, String username) {
    }

    @GetMapping("/user-options")
    @PreAuthorize("hasAuthority('WFL_APPROVE') or hasAuthority('WFL_CONFIG')")
    public List<UserOption> userOptions() {
        return users.findAll(Sort.by("fullName")).stream().filter(AppUser::isActive)
                .map(u -> new UserOption(u.getId(), u.getFullName(), u.getUsername())).toList();
    }
}
