package lk.gov.ciaboc.hrms.leave;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Leave balance per employee, type and calendar year, maintained automatically (FR-LEV-006). */
@Getter
@Setter
@Entity
@Table(name = "leave_balance")
public class LeaveBalance extends BaseEntity {
    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "leave_type_id", nullable = false)
    private Long leaveTypeId;

    @Column(name = "leave_year", nullable = false)
    private int leaveYear;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal entitled = BigDecimal.ZERO;

    /** Net manual adjustments by HR (carried forward days, corrections). */
    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal adjusted = BigDecimal.ZERO;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal used = BigDecimal.ZERO;

    /** Days reserved by applications awaiting approval. */
    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal pending = BigDecimal.ZERO;

    public BigDecimal available() {
        return entitled.add(adjusted).subtract(used).subtract(pending);
    }
}
