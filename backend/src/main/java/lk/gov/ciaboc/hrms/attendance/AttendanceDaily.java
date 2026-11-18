package lk.gov.ciaboc.hrms.attendance;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** Result of daily attendance processing for one employee and day (FR-ATT-006..009, -014..018). */
@Getter
@Setter
@Entity
@Table(name = "attendance_daily")
public class AttendanceDaily extends BaseEntity {
    public enum Status {PRESENT, LATE, EARLY, ABSENT, ON_LEAVE, HOLIDAY, WEEKEND, NO_SCHEDULE}

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "check_in")
    private Instant checkIn;

    @Column(name = "check_out")
    private Instant checkOut;

    @Column(name = "worked_minutes", nullable = false)
    private int workedMinutes;

    @Column(name = "late_minutes", nullable = false)
    private int lateMinutes;

    @Column(name = "early_minutes", nullable = false)
    private int earlyMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "leave_application_id")
    private Long leaveApplicationId;

    @Column(name = "leave_type_code", length = 40)
    private String leaveTypeCode;

    @Column(name = "leave_half_day", nullable = false)
    private boolean leaveHalfDay;

    @Column(name = "unpaid_leave", nullable = false)
    private boolean unpaidLeave;

    /** True when an approved manual correction contributed to the result. */
    @Column(nullable = false)
    private boolean corrected;

    @Column(name = "exception_note", length = 500)
    private String exceptionNote;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
