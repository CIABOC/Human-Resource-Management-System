package lk.gov.ciaboc.hrms.establishment;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A proposed change to structure, designations, positions or cadre. It takes effect only after approval through the
 * workflow engine (FR-EST-023, FR-EST-060), giving a complete establishment change history.
 */
@Getter
@Setter
@Entity
@Table(name = "establishment_change")
public class EstablishmentChange extends BaseEntity {
    public enum ChangeType {
        ORG_UNIT_CREATE, ORG_UNIT_UPDATE, ORG_UNIT_DEACTIVATE,
        DESIGNATION_CREATE, DESIGNATION_UPDATE, DESIGNATION_DEACTIVATE,
        POSITION_CREATE, POSITION_UPDATE, POSITION_DEACTIVATE,
        CADRE_SET
    }

    public enum Status {PENDING, RETURNED, APPLIED, REJECTED, CANCELLED}

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 30)
    private ChangeType changeType;

    /** Code (org unit) or id (designation/position) of the record being changed; null for creates and cadre. */
    @Column(name = "target_ref", length = 40)
    private String targetRef;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    @Column(name = "applied_at")
    private Instant appliedAt;

    /** For CADRE_SET: the designation/unit concerned, so proposed cadre can be shown next to approved. */
    @Column(name = "cadre_designation_id")
    private Long cadreDesignationId;

    @Column(name = "cadre_org_unit_code", length = 40)
    private String cadreOrgUnitCode;

    @Column(name = "cadre_proposed_qty")
    private Integer cadreProposedQty;
}
