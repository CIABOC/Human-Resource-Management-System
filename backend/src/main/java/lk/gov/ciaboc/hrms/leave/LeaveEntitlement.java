package lk.gov.ciaboc.hrms.leave;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Effective-dated annual entitlement of a leave type for an employee category (FR-LEV-002). */
@Getter
@Setter
@Entity
@Table(name = "leave_entitlement")
public class LeaveEntitlement extends BaseEntity implements EffectiveDated {
    @Column(name = "leave_type_id", nullable = false)
    private Long leaveTypeId;

    /** EMPLOYEE_CATEGORY master-data code. */
    @Column(name = "employee_category", nullable = false, length = 40)
    private String employeeCategory;

    @Column(name = "days_per_year", nullable = false, precision = 7, scale = 2)
    private BigDecimal daysPerYear;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(length = 500)
    private String remarks;
}
