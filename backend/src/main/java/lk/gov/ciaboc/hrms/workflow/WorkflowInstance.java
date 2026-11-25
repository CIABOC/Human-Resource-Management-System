package lk.gov.ciaboc.hrms.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "workflow_instance")
public class WorkflowInstance extends BaseEntity {
    public enum Status {PENDING, RETURNED, APPROVED, REJECTED, CANCELLED}

    @Column(name = "process_code", nullable = false, length = 50)
    private String processCode;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(nullable = false, length = 300)
    private String title;

    /** Front-end route for the underlying record. */
    @Column(length = 300)
    private String link;

    @Column(name = "requester_user_id", nullable = false)
    private Long requesterUserId;

    @Column(name = "requester_name", nullable = false, length = 200)
    private String requesterName;

    @Column(name = "requester_employee_id")
    private Long requesterEmployeeId;

    /** Org unit used to resolve DIVISION_HEAD stages (the subject employee's unit). */
    @Column(name = "subject_org_unit_code", length = 40)
    private String subjectOrgUnitCode;

    @Column(name = "current_stage", nullable = false)
    private int currentStage;

    @Column(name = "total_stages", nullable = false)
    private int totalStages;

    @Column(name = "current_stage_name", length = 150)
    private String currentStageName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "stage_entered_at", nullable = false)
    private Instant stageEnteredAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(nullable = false)
    private boolean escalated;

    // ---- versioned routing (REQ 10.4-10.9)

    /** Workflow version this request runs on; kept until it completes unless an administrator migrates it. */
    @Column(name = "version_id")
    private Long versionId;

    /** Employee the request is about (null = the requester); used for SUPERVISOR / walk-up routing from the subject. */
    @Column(name = "subject_employee_id")
    private Long subjectEmployeeId;

    @Column(name = "version_no")
    private Integer versionNo;

    @Column(name = "path_code", length = 40)
    private String pathCode;

    @Column(name = "path_name", length = 150)
    private String pathName;

    /** Routing rule that selected the path; null = default path. */
    @Column(name = "matched_rule", length = 150)
    private String matchedRule;

    /** JSON snapshot of the request attributes supplied at submission (re-used on resubmission and migration). */
    @JsonIgnore
    @Column(name = "routing_attributes", columnDefinition = "TEXT")
    private String routingAttributes;

    /** Incremented whenever the path is resolved again (resubmission, migration). */
    @Column(name = "routing_round", nullable = false)
    private int routingRound = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_action_type", length = 20)
    private WorkflowModel.ActionType currentActionType;

    /** When the current stage's SLA expires; null = no SLA. */
    @Column(name = "due_at")
    private Instant dueAt;

    /** Pending and past its stage SLA (D-4). */
    @JsonProperty("overdue")
    public boolean isOverdue() {
        return status == Status.PENDING && dueAt != null && dueAt.isBefore(Instant.now());
    }
}
