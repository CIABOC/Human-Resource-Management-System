package lk.gov.ciaboc.hrms.workflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Immutable approval history entry (FR-WFL-005, -006). */
@Getter
@Setter
@Entity
@Table(name = "approval_action")
public class ApprovalAction {
    public enum Type {SUBMIT, APPROVE, REJECT, RETURN, RESUBMIT, CANCEL, ESCALATE}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "stage_order", nullable = false)
    private int stageOrder;

    @Column(name = "stage_name", length = 150)
    private String stageName;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_name", nullable = false, length = 200)
    private String actorName;

    /** Set when the actor acted as a delegate (FR-WFL-007). */
    @Column(name = "on_behalf_of_user_id")
    private Long onBehalfOfUserId;

    @Column(name = "on_behalf_of_name", length = 200)
    private String onBehalfOfName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type action;

    @Column(name = "comment_text", length = 2000)
    private String comment;

    @Column(name = "acted_at", nullable = false)
    private Instant actedAt;
}
