package lk.gov.ciaboc.hrms.leave;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** Configurable leave type and its application rules (FR-LEV-001). */
@Getter
@Setter
@Entity
@Table(name = "leave_type")
public class LeaveType extends BaseEntity {
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "name_si", length = 150)
    private String nameSi;

    @Column(name = "name_ta", length = 150)
    private String nameTa;

    @Column(length = 1000)
    private String description;

    /** Unpaid types feed the monthly no-pay calculation (FR-LEV-010). */
    @Column(nullable = false)
    private boolean paid = true;

    /** Whether applications are checked against and deducted from an annual balance. */
    @Column(name = "balance_tracked", nullable = false)
    private boolean balanceTracked = true;

    @Column(name = "requires_reason", nullable = false)
    private boolean requiresReason;

    @Column(name = "requires_document", nullable = false)
    private boolean requiresDocument;

    /** When set, a document is required only if the application exceeds this many days. */
    @Column(name = "document_after_days")
    private Integer documentAfterDays;

    @Column(name = "min_notice_days", nullable = false)
    private int minNoticeDays;

    @Column(name = "max_consecutive_days")
    private Integer maxConsecutiveDays;

    @Column(name = "allow_backdated", nullable = false)
    private boolean allowBackdated;

    @Column(name = "half_day_allowed", nullable = false)
    private boolean halfDayAllowed;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Short leave covers a late arrival or early departure on one day (REQ 13.3); it is not charged against a balance
     * and does not mark the day as leave in attendance.
     */
    @Column(name = "short_leave", nullable = false)
    private boolean shortLeave;

    /** True when a supporting document is mandatory for an application of the given length. */
    public boolean documentRequiredFor(java.math.BigDecimal days) {
        if (!requiresDocument) return false;
        return documentAfterDays == null || days.compareTo(java.math.BigDecimal.valueOf(documentAfterDays)) > 0;
    }
}
