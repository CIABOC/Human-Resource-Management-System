package lk.gov.ciaboc.hrms.attendance;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** Raw check-in / check-out event (FR-ATT-001..005, HW-01, INT-04). Events are never edited, only reviewed. */
@Getter
@Setter
@Entity
@Table(name = "attendance_event")
public class AttendanceEvent extends BaseEntity {
    public enum Direction {IN, OUT}

    public enum Source {DEVICE, MANUAL, IMPORT}

    public enum ReviewStatus {ACCEPTED, REJECTED, SUPERSEDED}

    /** Null when the employee number was not recognised. */
    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "employee_no", nullable = false, length = 30)
    private String employeeNo;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    /** Local calendar date of the event. */
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source;

    @Column(name = "device_id", length = 60)
    private String deviceId;

    @Column(name = "batch_ref", length = 60)
    private String batchRef;

    @Column(nullable = false)
    private boolean flagged;

    @Column(name = "flag_reason", length = 500)
    private String flagReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 20)
    private ReviewStatus reviewStatus;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "correction_id")
    private Long correctionId;

    /** Usable for processing: not rejected/superseded, and if flagged then accepted on review. */
    public boolean isUsable() {
        if (employeeId == null) return false;
        if (reviewStatus == ReviewStatus.REJECTED || reviewStatus == ReviewStatus.SUPERSEDED) return false;
        return !flagged || reviewStatus == ReviewStatus.ACCEPTED;
    }
}
