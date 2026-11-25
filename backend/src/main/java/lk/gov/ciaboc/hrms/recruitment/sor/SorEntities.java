package lk.gov.ciaboc.hrms.recruitment.sor;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Scheme of Recruitment records (spec v2.1 REQ 8.1-8.3). */
public final class SorEntities {
    private SorEntities() {
    }

    public enum SorStatus {DRAFT, PENDING, RETURNED, APPROVED, REJECTED, SUPERSEDED}

    /**
     * One version of a Scheme of Recruitment. Versions of the same scheme share {@code seriesId} and {@code sorNo}; an
     * approved version is locked and is changed only by creating a new version with a reason (REQ 8.3).
     */
    @Getter
    @Setter
    @Entity(name = "RecSor")
    @Table(name = "rec_sor")
    public static class Sor extends BaseEntity {
        @Column(name = "series_id")
        private Long seriesId;
        @Column(name = "sor_no", nullable = false, length = 40)
        private String sorNo;
        @Column(name = "version_no", nullable = false)
        private int versionNo;
        @Column(name = "designation_id", nullable = false)
        private Long designationId;
        @Column(length = 40)
        private String grade;
        @Column(name = "salary_code", length = 40)
        private String salaryCode;
        @Column(nullable = false, length = 300)
        private String title;
        @Column(name = "service_reference", length = 500)
        private String serviceReference;
        @Column(name = "educational_qualifications", columnDefinition = "text")
        private String educationalQualifications;
        @Column(columnDefinition = "text")
        private String experience;
        @Column(name = "age_min")
        private Integer ageMin;
        @Column(name = "age_max")
        private Integer ageMax;
        @Column(name = "age_reference", length = 200)
        private String ageReference;
        @Column(columnDefinition = "text")
        private String competencies;
        @Column(name = "selection_method", length = 40)
        private String selectionMethod;
        @Column(name = "selection_details", columnDefinition = "text")
        private String selectionDetails;
        @Column(name = "other_conditions", columnDefinition = "text")
        private String otherConditions;
        /** JSON: {"si": {"title": .., "educationalQualifications": ..}, "ta": {...}}. */
        @Column(columnDefinition = "text")
        private String translations;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private SorStatus status = SorStatus.DRAFT;
        @Column(nullable = false)
        private boolean locked;
        @Column(name = "change_reason", length = 1000)
        private String changeReason;
        @Column(name = "workflow_instance_id")
        private Long workflowInstanceId;
        @Column(name = "submitted_at")
        private Instant submittedAt;
        @Column(name = "approved_at")
        private Instant approvedAt;
        @Column(name = "approved_by", length = 100)
        private String approvedBy;
    }

    @Getter
    @Setter
    @Entity(name = "RecSorTemplate")
    @Table(name = "rec_sor_template")
    public static class SorTemplate extends BaseEntity {
        @Column(nullable = false, unique = true, length = 2)
        private String language;
        @Column(nullable = false, length = 200)
        private String name;
        @Column(nullable = false, columnDefinition = "text")
        private String body;
        @Column(nullable = false)
        private boolean active = true;
    }

    @Getter
    @Setter
    @Entity(name = "RecSorSelectionMethod")
    @Table(name = "rec_sor_selection_method")
    public static class SelectionMethod extends BaseEntity {
        @Column(nullable = false, unique = true, length = 40)
        private String code;
        @Column(name = "name_en", nullable = false, length = 200)
        private String nameEn;
        @Column(name = "name_si", length = 200)
        private String nameSi;
        @Column(name = "name_ta", length = 200)
        private String nameTa;
        @Column(nullable = false)
        private boolean active = true;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;

        public String label(String lang) {
            String v = "si".equals(lang) ? nameSi : "ta".equals(lang) ? nameTa : nameEn;
            return v == null || v.isBlank() ? nameEn : v;
        }
    }
}
