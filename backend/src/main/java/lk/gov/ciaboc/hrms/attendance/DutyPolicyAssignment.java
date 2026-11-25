package lk.gov.ciaboc.hrms.attendance;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Effective-dated assignment of a duty-time policy code (REQ 1.3; table created by V60). Resolution priority:
 * EMPLOYEE, then CATEGORY, then DIVISION (nearest unit up the hierarchy), then ORGANIZATION.
 */
@Getter
@Setter
@Entity
@Table(name = "duty_policy_assignment")
public class DutyPolicyAssignment extends BaseEntity implements EffectiveDated {
    public enum ScopeType {ORGANIZATION, DIVISION, CATEGORY, EMPLOYEE}

    @Column(name = "policy_code", nullable = false, length = 40)
    private String policyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(name = "org_unit_code", length = 40)
    private String orgUnitCode;

    @Column(name = "employee_category", length = 40)
    private String employeeCategory;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "rotation_offset", nullable = false)
    private int rotationOffset;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "authority_ref", length = 200)
    private String authorityRef;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "end_reason", length = 1000)
    private String endReason;
}
