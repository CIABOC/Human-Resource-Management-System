package lk.gov.ciaboc.hrms.employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Effective-dated placement of an employee in a unit, designation and (optionally) position (FR-EMP-005). */
@Getter
@Setter
@Entity
@Table(name = "employee_assignment")
public class EmployeeAssignment extends BaseEntity implements EffectiveDated {
    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "org_unit_code", nullable = false, length = 40)
    private String orgUnitCode;

    @Column(name = "designation_id", nullable = false)
    private Long designationId;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** APPOINTMENT, TRANSFER, PROMOTION, REASSIGNMENT, SECONDMENT ... */
    @Column(nullable = false, length = 30)
    private String reason;

    @Column(length = 100)
    private String reference;

    @Column(length = 1000)
    private String remarks;

    /** Acting appointment: counts as "Acting" rather than "Filled" in cadre figures. */
    @Column(nullable = false)
    private boolean acting;
}
