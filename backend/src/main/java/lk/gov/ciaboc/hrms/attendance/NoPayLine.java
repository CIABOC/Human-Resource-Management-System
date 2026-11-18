package lk.gov.ciaboc.hrms.attendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "nopay_line")
public class NoPayLine extends BaseEntity {
    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "absence_days", nullable = false, precision = 7, scale = 2)
    private BigDecimal absenceDays = BigDecimal.ZERO;

    @Column(name = "unpaid_leave_days", nullable = false, precision = 7, scale = 2)
    private BigDecimal unpaidLeaveDays = BigDecimal.ZERO;

    @Column(name = "late_count", nullable = false)
    private int lateCount;

    @Column(name = "late_days", nullable = false, precision = 7, scale = 2)
    private BigDecimal lateDays = BigDecimal.ZERO;

    @Column(name = "calculated_days", nullable = false, precision = 7, scale = 2)
    private BigDecimal calculatedDays = BigDecimal.ZERO;

    /** Reviewer override; when set it replaces the calculated value. */
    @Column(name = "adjusted_days", precision = 7, scale = 2)
    private BigDecimal adjustedDays;

    @Column(name = "adjustment_reason", length = 1000)
    private String adjustmentReason;

    public BigDecimal effectiveDays() {
        return adjustedDays != null ? adjustedDays : calculatedDays;
    }
}
