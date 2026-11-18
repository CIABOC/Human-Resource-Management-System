package lk.gov.ciaboc.hrms.leave;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** History of HR balance initialisations and adjustments (FR-LEV-006, audit). */
@Getter
@Setter
@Entity
@Table(name = "leave_balance_adjustment")
public class LeaveBalanceAdjustment extends BaseEntity {
    public enum Kind {INITIALISE, ADJUST}

    @Column(name = "balance_id", nullable = false)
    private Long balanceId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "leave_type_id", nullable = false)
    private Long leaveTypeId;

    @Column(name = "leave_year", nullable = false)
    private int leaveYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal days;

    @Column(nullable = false, length = 1000)
    private String reason;
}
