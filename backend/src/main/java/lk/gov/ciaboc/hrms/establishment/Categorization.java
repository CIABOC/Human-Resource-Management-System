package lk.gov.ciaboc.hrms.establishment;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** Entities for position categorization. */
public final class Categorization {
    private Categorization() {
    }

    /** A set of category moves submitted together for approval. */
    @Getter
    @Setter
    @Entity(name = "CategorizationBatch")
    @Table(name = "categorization_batch")
    public static class Batch extends BaseEntity {
        public enum Status {PENDING, RETURNED, APPLIED, REJECTED, CANCELLED}

        @Column(name = "effective_date", nullable = false)
        private LocalDate effectiveDate;

        @Column(nullable = false, length = 2000)
        private String reason;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;

        @Column(name = "workflow_instance_id")
        private Long workflowInstanceId;

        @Column(name = "applied_at")
        private Instant appliedAt;
    }

    /** One move: designation from one category to another (null = unassigned). */
    @Getter
    @Setter
    @Entity(name = "CategorizationLine")
    @Table(name = "categorization_line")
    public static class Line {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "batch_id", nullable = false)
        private Long batchId;

        /** DESIGNATION (move a designation between categories) or CATEGORY (re-parent a category). */
        @Column(name = "line_type", nullable = false, length = 20)
        private String lineType = "DESIGNATION";

        @Column(name = "designation_id")
        private Long designationId;

        @Column(name = "from_category_id")
        private Long fromCategoryId;

        @Column(name = "to_category_id")
        private Long toCategoryId;

        @Column(name = "category_id")
        private Long categoryId;

        @Column(name = "from_parent_id")
        private Long fromParentId;

        @Column(name = "to_parent_id")
        private Long toParentId;
    }

    /** Effective-dated membership of a designation in a category. */
    @Getter
    @Setter
    @Entity(name = "DesignationCategoryAssignment")
    @Table(name = "designation_category_assignment")
    public static class Assignment extends BaseEntity implements EffectiveDated {
        @Column(name = "designation_id", nullable = false)
        private Long designationId;

        @Column(name = "category_id", nullable = false)
        private Long categoryId;

        @Column(name = "effective_from", nullable = false)
        private LocalDate effectiveFrom;

        @Column(name = "effective_to")
        private LocalDate effectiveTo;

        @Column(name = "batch_id")
        private Long batchId;
    }
}
