package lk.gov.ciaboc.hrms.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One approver slot of a step. A single-approver step has one slot; a parallel step has several, completed by ALL or
 * ANY of them. Slots name a role / post / user / division head; the actual people are resolved when they act, so
 * changes of post holder and acting arrangements apply immediately.
 */
@Getter
@Setter
@Entity
@Table(name = "workflow_step_slot")
public class WorkflowStepSlot {
    public enum Status {PENDING, DONE, NOT_REQUIRED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_id")
    private WorkflowInstanceStep step;

    @Column(name = "slot_order", nullable = false)
    private int slotOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_type", nullable = false, length = 30)
    private WorkflowModel.ApproverType approverType;

    @Column(name = "approver_ref", length = 100)
    private String approverRef;

    @Column(length = 250)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "acted_by_user_id")
    private Long actedByUserId;

    @Column(name = "acted_by_name", length = 200)
    private String actedByName;

    @Column(name = "on_behalf_of_user_id")
    private Long onBehalfOfUserId;

    @Column(length = 20)
    private String decision;

    @Column(name = "acted_at")
    private Instant actedAt;
}
