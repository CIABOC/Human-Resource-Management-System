package lk.gov.ciaboc.hrms.extended.separation;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Retirement and offboarding records (FR-EXT-002). */
public final class SeparationEntities {
    private SeparationEntities() {
    }

    /** Configurable separation type: the employee status and default separation reason it leads to. */
    @Getter
    @Setter
    @Entity
    @Table(name = "ext_separation_type")
    public static class SeparationType extends BaseEntity {
        @Column(nullable = false, unique = true, length = 40)
        private String code;
        @Column(name = "name_en", nullable = false, length = 200)
        private String nameEn;
        @Column(name = "name_si", length = 200)
        private String nameSi;
        @Column(name = "name_ta", length = 200)
        private String nameTa;
        @Column(name = "employee_status", nullable = false, length = 40)
        private String employeeStatus;
        @Column(name = "default_separation_reason", nullable = false, length = 40)
        private String defaultSeparationReason;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;
        @Column(nullable = false)
        private boolean active = true;
    }

    /** A clearance checklist item template; each is cleared by its responsible role. */
    @Getter
    @Setter
    @Entity
    @Table(name = "ext_clearance_item_template")
    public static class ClearanceItemTemplate extends BaseEntity {
        @Column(nullable = false, unique = true, length = 40)
        private String code;
        @Column(name = "name_en", nullable = false, length = 200)
        private String nameEn;
        @Column(name = "name_si", length = 200)
        private String nameSi;
        @Column(name = "name_ta", length = 200)
        private String nameTa;
        @Column(length = 500)
        private String description;
        @Column(name = "responsible_role", nullable = false, length = 50)
        private String responsibleRole;
        /** Comma-separated separation type codes; null applies to every type. */
        @Column(name = "applies_to", length = 300)
        private String appliesTo;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;
        @Column(nullable = false)
        private boolean active = true;

        public boolean appliesTo(String typeCode) {
            if (appliesTo == null || appliesTo.isBlank()) return true;
            for (String s : appliesTo.split(",")) if (s.trim().equalsIgnoreCase(typeCode)) return true;
            return false;
        }
    }

    @Getter
    @Setter
    @Entity
    @Table(name = "ext_separation_case")
    public static class SeparationCase extends BaseEntity {
        public enum Status {OPEN, COMPLETED, CANCELLED}

        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "separation_type", nullable = false, length = 40)
        private String separationType;
        @Column(name = "separation_reason", nullable = false, length = 40)
        private String separationReason;
        @Column(name = "target_status", nullable = false, length = 40)
        private String targetStatus;
        @Column(name = "separation_date", nullable = false)
        private LocalDate separationDate;
        @Column(length = 1000)
        private String remarks;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(name = "settlement_amount", precision = 14, scale = 2)
        private BigDecimal settlementAmount;
        @Column(name = "settlement_notes", columnDefinition = "text")
        private String settlementNotes;
        @Column(name = "settlement_by", length = 100)
        private String settlementBy;
        @Column(name = "settlement_at")
        private Instant settlementAt;
        @Column(name = "completed_by", length = 100)
        private String completedBy;
        @Column(name = "completed_at")
        private Instant completedAt;
        @Column(name = "cancel_reason", length = 500)
        private String cancelReason;
    }

    @Getter
    @Setter
    @Entity
    @Table(name = "ext_separation_clearance")
    public static class Clearance extends BaseEntity {
        public enum Status {PENDING, CLEARED, WAIVED}

        @Column(name = "case_id", nullable = false)
        private Long caseId;
        @Column(name = "item_code", nullable = false, length = 40)
        private String itemCode;
        @Column(name = "item_name", nullable = false, length = 200)
        private String itemName;
        @Column(name = "responsible_role", nullable = false, length = 50)
        private String responsibleRole;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(length = 1000)
        private String remarks;
        @Column(name = "cleared_by", length = 100)
        private String clearedBy;
        @Column(name = "cleared_at")
        private Instant clearedAt;
    }
}
