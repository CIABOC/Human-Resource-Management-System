package lk.gov.ciaboc.hrms.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** One approval level. The approver is a role, a position, a named officer, or the requester's division head (FR-WFL-002). */
@Getter
@Setter
@Entity
@Table(name = "workflow_stage")
public class WorkflowStage {
    public enum ApproverType {ROLE, POSITION, USER, DIVISION_HEAD}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_id")
    private WorkflowDefinition definition;

    @Column(name = "stage_order", nullable = false)
    private int stageOrder;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_type", nullable = false, length = 20)
    private ApproverType approverType;

    /** Role code, position code or user id depending on approverType; unused for DIVISION_HEAD. */
    @Column(name = "approver_ref", length = 100)
    private String approverRef;

    /** Hours allowed before the stage is escalated (FR-WFL-008); null = no escalation. */
    @Column(name = "sla_hours")
    private Integer slaHours;

    @Column(name = "escalation_role_code", length = 50)
    private String escalationRoleCode;
}
