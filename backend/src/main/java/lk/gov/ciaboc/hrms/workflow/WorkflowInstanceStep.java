package lk.gov.ciaboc.hrms.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A concrete step of a request's resolved path (one per fixed stage, one per hierarchy level of a walk-up stage).
 * Resolved when the request is submitted (or resubmitted / migrated: a new {@code round}); earlier rounds are kept.
 */
@Getter
@Setter
@Entity
@Table(name = "workflow_instance_step")
public class WorkflowInstanceStep {
    public enum Status {WAITING, ACTIVE, DONE, SUPERSEDED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(nullable = false)
    private int round;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(nullable = false, length = 150)
    private String name;

    /** Name of the configured stage this step came from (differs from name for walk-up levels). */
    @Column(name = "stage_name", length = 150)
    private String stageName;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private WorkflowModel.ActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WorkflowModel.Mode mode;

    @Column(name = "sla_hours")
    private Integer slaHours;

    @Column(name = "escalation_role_code", length = 50)
    private String escalationRoleCode;

    /** 1-based hierarchy level for walk-up steps. */
    @Column(name = "walk_level")
    private Integer walkLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "entered_at")
    private Instant enteredAt;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "step", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("slotOrder ASC")
    private List<WorkflowStepSlot> slots = new ArrayList<>();
}
