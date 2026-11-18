package lk.gov.ciaboc.hrms.establishment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Approved cadre for a designation within a unit, effective-dated (FR-EST-020, -024). Proposed cadre is held
 * separately as pending CADRE_SET establishment changes (FR-EST-021).
 */
@Getter
@Setter
@Entity
@Table(name = "cadre_allocation")
public class CadreAllocation extends BaseEntity implements EffectiveDated {
    @Column(name = "designation_id", nullable = false)
    private Long designationId;

    @Column(name = "org_unit_code", nullable = false, length = 40)
    private String orgUnitCode;

    @Column(name = "approved_qty", nullable = false)
    private int approvedQty;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "change_id")
    private Long changeId;
}
