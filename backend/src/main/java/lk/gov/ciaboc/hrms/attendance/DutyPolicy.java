package lk.gov.ciaboc.hrms.attendance;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Versioned duty-time policy (Spec v2.1 REQ 1.1, 1.4, 1.5; table created by V60). One row per version of a policy
 * code; versions are effective-dated and only APPROVED versions are used for attendance processing.
 */
@Getter
@Setter
@Entity
@Table(name = "duty_policy")
public class DutyPolicy extends BaseEntity implements EffectiveDated {
    public enum PolicyType {FIXED, FLEXIBLE, SHIFT_ROSTER}

    public enum Status {DRAFT, PENDING, RETURNED, APPROVED, REJECTED, WITHDRAWN}

    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_type", nullable = false, length = 20)
    private PolicyType policyType = PolicyType.FIXED;

    @Column(name = "in_time")
    private LocalTime inTime;

    @Column(name = "out_time")
    private LocalTime outTime;

    @Column(name = "core_start")
    private LocalTime coreStart;

    @Column(name = "core_end")
    private LocalTime coreEnd;

    @Column(name = "grace_minutes", nullable = false)
    private int graceMinutes;

    @Column(name = "early_grace_minutes", nullable = false)
    private int earlyGraceMinutes;

    @Column(name = "min_work_minutes", nullable = false)
    private int minWorkMinutes;

    @Column(name = "late_threshold_minutes", nullable = false)
    private int lateThresholdMinutes;

    @Column(name = "early_threshold_minutes", nullable = false)
    private int earlyThresholdMinutes;

    @Column(name = "half_day_minutes", nullable = false)
    private int halfDayMinutes;

    @Column(name = "lunch_start")
    private LocalTime lunchStart;

    @Column(name = "lunch_minutes", nullable = false)
    private int lunchMinutes;

    /** Comma-separated MON..SUN. */
    @Column(name = "working_days", length = 40)
    private String workingDays;

    @Column(name = "rotation_pattern", length = 500)
    private String rotationPattern;

    @Column(name = "rotation_start_date")
    private LocalDate rotationStartDate;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "authority_ref", length = 200)
    private String authorityRef;

    @Column(length = 1000)
    private String reason;

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;
}
