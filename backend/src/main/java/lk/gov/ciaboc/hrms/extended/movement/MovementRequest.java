package lk.gov.ciaboc.hrms.extended.movement;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** A transfer, promotion or confirmation request (FR-EXT-001). Applied to the employee record only after approval. */
@Getter
@Setter
@Entity
@Table(name = "ext_movement_request")
public class MovementRequest extends BaseEntity {
    public enum Type {TRANSFER, PROMOTION, CONFIRMATION}

    public enum Status {PENDING, RETURNED, APPLIED, REJECTED, CANCELLED}

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20)
    private Type requestType;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "from_org_unit_code", length = 40)
    private String fromOrgUnitCode;

    @Column(name = "from_designation_id")
    private Long fromDesignationId;

    @Column(name = "from_position_id")
    private Long fromPositionId;

    @Column(name = "target_org_unit_code", length = 40)
    private String targetOrgUnitCode;

    @Column(name = "target_designation_id")
    private Long targetDesignationId;

    @Column(name = "target_position_id")
    private Long targetPositionId;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(length = 100)
    private String reference;

    @Column(name = "cadre_warning", length = 500)
    private String cadreWarning;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    @Column(name = "applied_at")
    private Instant appliedAt;
}
