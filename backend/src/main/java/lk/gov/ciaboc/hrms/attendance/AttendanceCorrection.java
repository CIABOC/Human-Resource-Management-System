package lk.gov.ciaboc.hrms.attendance;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

/** Manual attendance correction, applied only after workflow approval (FR-ATT-019..020). */
@Getter
@Setter
@Entity
@Table(name = "attendance_correction")
public class AttendanceCorrection extends BaseEntity {
    public enum Status {PENDING, RETURNED, APPROVED, REJECTED, CANCELLED}

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "check_in")
    private LocalTime checkIn;

    @Column(name = "check_out")
    private LocalTime checkOut;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;
}
