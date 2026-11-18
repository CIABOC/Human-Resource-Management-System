package lk.gov.ciaboc.hrms.performance;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Performance evaluation records (SRS 4.8). */
public final class PerformanceEntities {
    private PerformanceEntities() {
    }

    public enum PeriodStatus {OPEN, CLOSED}

    public enum CriterionType {CRITERION, KPI, OBJECTIVE}

    public enum EvaluationStatus {DRAFT, SUBMITTED, RETURNED, REJECTED, FINALIZED}

    @Getter
    @Setter
    @Entity(name = "PerEvaluationPeriod")
    @Table(name = "per_period")
    public static class EvaluationPeriod extends BaseEntity {
        @Column(nullable = false, unique = true, length = 40)
        private String code;
        @Column(nullable = false, length = 200)
        private String name;
        @Column(name = "start_date", nullable = false)
        private LocalDate startDate;
        @Column(name = "end_date", nullable = false)
        private LocalDate endDate;
        @Column(name = "due_date")
        private LocalDate dueDate;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private PeriodStatus status = PeriodStatus.OPEN;
    }

    @Getter
    @Setter
    @Entity(name = "PerEvaluationModel")
    @Table(name = "per_model")
    public static class EvaluationModel extends BaseEntity {
        @Column(nullable = false, unique = true, length = 40)
        private String code;
        @Column(nullable = false, length = 200)
        private String name;
        @Column(length = 1000)
        private String description;
        @Column(name = "rating_min", nullable = false)
        private BigDecimal ratingMin;
        @Column(name = "rating_max", nullable = false)
        private BigDecimal ratingMax;
        /** Sample models seeded for demonstration; to be confirmed with Administration (SRS 11.1 #12). */
        @Column(nullable = false)
        private boolean sample;
        @Column(nullable = false)
        private boolean active = true;
    }

    @Getter
    @Setter
    @Entity(name = "PerEvaluationCriterion")
    @Table(name = "per_criterion")
    public static class EvaluationCriterion extends BaseEntity {
        @Column(name = "model_id", nullable = false)
        private Long modelId;
        @Enumerated(EnumType.STRING)
        @Column(name = "criterion_type", nullable = false, length = 20)
        private CriterionType criterionType = CriterionType.CRITERION;
        @Column(nullable = false, length = 300)
        private String title;
        @Column(length = 1000)
        private String description;
        @Column(nullable = false)
        private BigDecimal weight;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;
    }

    @Getter
    @Setter
    @Entity(name = "PerCategoryModel")
    @Table(name = "per_category_model")
    public static class CategoryModel extends BaseEntity {
        @Column(name = "employee_category", nullable = false, unique = true, length = 40)
        private String employeeCategory;
        @Column(name = "model_id", nullable = false)
        private Long modelId;
    }

    @Getter
    @Setter
    @Entity(name = "PerEvaluation")
    @Table(name = "per_evaluation")
    public static class Evaluation extends BaseEntity {
        @Column(name = "period_id", nullable = false)
        private Long periodId;
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "model_id", nullable = false)
        private Long modelId;
        @Column(name = "evaluator_user_id")
        private Long evaluatorUserId;
        @Column(name = "evaluator_name", length = 200)
        private String evaluatorName;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private EvaluationStatus status = EvaluationStatus.DRAFT;
        @Column(name = "overall_score")
        private BigDecimal overallScore;
        @Column(name = "overall_percent")
        private BigDecimal overallPercent;
        @Column(name = "evaluator_comments", columnDefinition = "text")
        private String evaluatorComments;
        @Column(name = "employee_comments", columnDefinition = "text")
        private String employeeComments;
        @Column(name = "submitted_at")
        private Instant submittedAt;
        @Column(name = "finalized_at")
        private Instant finalizedAt;
        @Column(name = "acknowledged_at")
        private Instant acknowledgedAt;
        @Column(name = "workflow_instance_id")
        private Long workflowInstanceId;
        @JsonIgnore
        @Column(name = "last_reminder_at")
        private Instant lastReminderAt;
    }

    @Getter
    @Setter
    @Entity(name = "PerEvaluationRating")
    @Table(name = "per_rating")
    public static class EvaluationRating extends BaseEntity {
        @Column(name = "evaluation_id", nullable = false)
        private Long evaluationId;
        @Column(name = "criterion_id", nullable = false)
        private Long criterionId;
        @Column(nullable = false)
        private BigDecimal rating;
        @Column(length = 2000)
        private String comment;
    }
}
