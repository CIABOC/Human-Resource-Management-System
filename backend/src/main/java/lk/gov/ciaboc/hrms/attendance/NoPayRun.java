package lk.gov.ciaboc.hrms.attendance;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Monthly no-pay calculation; released to payroll only once APPROVED (FR-ATT-021..023). */
@Getter
@Setter
@Entity
@Table(name = "nopay_run")
public class NoPayRun extends BaseEntity {
    public enum Status {DRAFT, PENDING, RETURNED, APPROVED, REJECTED}

    /** YYYY-MM. */
    @Column(nullable = false, unique = true, length = 7)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "calculated_by", length = 100)
    private String calculatedBy;

    @Column(name = "rules_snapshot", length = 1000)
    private String rulesSnapshot;

    /** Working days of the period without any processed attendance when calculated. */
    @Column(name = "unprocessed_days", nullable = false)
    private int unprocessedDays;

    @Column(name = "total_days", nullable = false, precision = 9, scale = 2)
    private BigDecimal totalDays = BigDecimal.ZERO;

    @Column(name = "employee_count", nullable = false)
    private int employeeCount;

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    @Column(name = "approved_at")
    private Instant approvedAt;
}
