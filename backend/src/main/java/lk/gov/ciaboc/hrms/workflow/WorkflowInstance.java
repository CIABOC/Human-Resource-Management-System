package lk.gov.ciaboc.hrms.workflow;

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
}
