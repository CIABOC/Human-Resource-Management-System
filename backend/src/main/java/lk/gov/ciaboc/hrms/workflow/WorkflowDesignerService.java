package lk.gov.ciaboc.hrms.workflow;

import lk.gov.ciaboc.hrms.workflow.WorkflowController.DefinitionView;
import lk.gov.ciaboc.hrms.workflow.WorkflowController.StageSummary;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.ApproverDef;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.PathDef;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.StageDef;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.WalkUpDef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Builds the list/summary views of workflow definitions for the configuration screens (FR-WFL-001..003, REQ 10.9). */
@Service
@RequiredArgsConstructor
public class WorkflowDesignerService {
    static final List<WorkflowInstance.Status> OPEN = List.of(WorkflowInstance.Status.PENDING, WorkflowInstance.Status.RETURNED);

    private final WorkflowVersionService versionService;
    private final WorkflowVersionRepository versions;
    private final WorkflowInstanceRepository instances;

    /** Process with the version in force, the next scheduled version, the pending draft and the default path's stages. */
    @Transactional(readOnly = true)
    public DefinitionView view(WorkflowDefinition d) {
        Instant now = Instant.now();
        List<WorkflowVersion> all = d.getId() == null ? List.of() : versions.findByDefinitionIdOrderByVersionNoDesc(d.getId());

        Optional<WorkflowVersion> current = d.getId() == null ? Optional.empty() : versionService.effectiveVersion(d.getId(), now);
        Optional<WorkflowVersion> scheduled = all.stream()
                .filter(v -> v.getStatus() == WorkflowVersion.Status.PUBLISHED && v.getEffectiveFrom() != null && v.getEffectiveFrom().isAfter(now))
                .min(Comparator.comparing(WorkflowVersion::getEffectiveFrom).thenComparing(WorkflowVersion::getVersionNo));
        Optional<WorkflowVersion> draft = all.stream().filter(v -> v.getStatus() == WorkflowVersion.Status.DRAFT).findFirst(); // highest no.

        WorkflowModel model = current.map(versionService::model).orElse(null);
        int pathCount = model == null ? 0 : model.paths().size();
        int ruleCount = model == null ? 0 : model.rules().size();
        List<StageSummary> stages = new ArrayList<>();
        PathDef path = model == null ? null : model.defaultPathDef();
        if (path != null && path.stages() != null) {
            int order = 1;
            for (StageDef s : path.stages()) stages.add(summary(order++, s));
        }

        long open = 0;
        if (d.getProcessCode() != null) {
            for (Object[] row : instances.openCountsByVersion(d.getProcessCode(), OPEN)) open += ((Number) row[1]).longValue();
        }

        return new DefinitionView(d.getId(), d.getProcessCode(), d.getName(), d.getDescription(), d.isActive(),
                current.map(WorkflowVersion::getVersionNo).orElse(null), current.map(WorkflowVersion::getId).orElse(null),
                current.map(WorkflowVersion::getEffectiveFrom).orElse(null),
                scheduled.map(WorkflowVersion::getVersionNo).orElse(null), scheduled.map(WorkflowVersion::getEffectiveFrom).orElse(null),
                draft.map(WorkflowVersion::getId).orElse(null), draft.map(WorkflowVersion::getVersionNo).orElse(null),
                pathCount, ruleCount, stages, open);
    }

    private static StageSummary summary(int order, StageDef s) {
        String approverType;
        String approverRef;
        String description;
        WalkUpDef w = s.walkUp();
        if (w != null) {
            approverType = "WALK_UP";
            approverRef = w.value();
            description = "Walk up from " + (w.fromSubject() ? "subject" : "initiator") + " until "
                    + (w.until() == null ? "top" : w.until().name()) + (w.value() == null ? "" : " " + w.value())
                    + " (max " + w.levels() + " levels" + (w.skipsVacant() ? ", skip vacant" : "") + ")";
        } else if (s.approvers().size() == 1) {
            ApproverDef a = s.approvers().get(0);
            approverType = a.type() == null ? null : a.type().name();
            approverRef = a.ref();
            description = null;
        } else if (s.approvers().isEmpty()) {
            approverType = null;
            approverRef = null;
            description = "No approvers configured";
        } else {
            approverType = "MULTIPLE";
            approverRef = null;
            description = s.mode().name() + " of: " + s.approvers().stream()
                    .map(a -> (a.type() == null ? "?" : a.type().name()) + (a.ref() == null ? "" : " " + a.ref()))
                    .collect(Collectors.joining(", "));
        }
        if (s.when() != null && !s.when().isEmpty()) description = (description == null ? "" : description + "; ") + "conditional";
        return new StageSummary(order, s.name(), s.actionType().name(), approverType, approverRef, s.slaHours(),
                s.escalationRoleCode(), description);
    }
}
