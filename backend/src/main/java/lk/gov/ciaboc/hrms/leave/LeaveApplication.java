package lk.gov.ciaboc.hrms.leave;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** An employee's leave application and, if requested, its cancellation (FR-LEV-003..009). */
@Getter
@Setter
@Entity
@Table(name = "leave_application")
public class LeaveApplication extends BaseEntity {
    public enum Status {PENDING, RETURNED, APPROVED, REJECTED, WITHDRAWN, CANCELLED}

    public enum CancellationStatus {PENDING, APPROVED, REJECTED, WITHDRAWN}

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "leave_type_id", nullable = false)
    private Long leaveTypeId;

    @Column(name = "leave_year", nullable = false)
    private int leaveYear;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "half_day", nullable = false)
    private boolean halfDay;

    /** AM or PM for half-day leave. */
    @Column(name = "half_day_session", length = 2)
    private String halfDaySession;

    /** Working days charged (weekends and holidays excluded). */
    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal days;

    @Column(length = 1000)
    private String reason;

    @Column(name = "contact_during_leave", length = 300)
    private String contactDuringLeave;

    @Column(name = "document_file_id")
    private Long documentFileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_status", length = 20)
    private CancellationStatus cancellationStatus;

    @Column(name = "cancellation_reason", length = 1000)
    private String cancellationReason;

    @Column(name = "cancel_workflow_instance_id")
    private Long cancelWorkflowInstanceId;

    /** Whether the application currently holds days (pending reservation or approved use). */
    public boolean isActive() {
        return status == Status.PENDING || status == Status.RETURNED || status == Status.APPROVED;
    }
}
