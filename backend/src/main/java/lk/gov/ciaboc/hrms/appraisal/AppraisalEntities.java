package lk.gov.ciaboc.hrms.appraisal;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Performance appraisal records (wireframes "Performance appraisal", screens 1-15). */
public final class AppraisalEntities {
    private AppraisalEntities() {
    }

    public enum Status {DRAFT, SUBMITTED, VERIFIED, APPROVED, ACKNOWLEDGED, FINALIZED}

    public enum AgreementStatus {DRAFT, SUBMITTED, SUPERVISOR_SIGNED, FINAL}

    public enum StepStatus {NOT_STARTED, DRAFT, SUBMITTED}

    public enum Method {MILESTONE, TASK_COUNT, TIMELINESS, QUALITY, QUALITATIVE}

    public enum ProgressStatus {NOT_STARTED, IN_PROGRESS, SUBMITTED, RETURNED, VERIFIED}

    public enum Decision {VERIFIED, ADJUSTED, RETURNED}

    public enum EvidenceStatus {PENDING, VERIFIED, RETURNED}

    public enum PolicyStatus {DRAFT, PENDING_APPROVAL, APPROVED, SUPERSEDED}

    public enum AppealStatus {SUBMITTED, UNDER_REVIEW, DECIDED}

    public enum CheckinStatus {SCHEDULED, DONE, CANCELLED}

    public enum FeedbackType {RECOGNITION, IMPROVEMENT, REQUEST}

    public enum CaseType {PARTIAL_PERIOD, TRANSFER, PROBATION, EXTENDED_LEAVE, ACTING_APPOINTMENT, REPORTING_LINE_CHANGE, KPI_EXCEPTION}

    public enum RequestStatus {PENDING, APPROVED, REJECTED}

    public enum DelegationScope {VERIFICATION_AND_REVIEW, APPROVAL, ALL}

    public enum ReopenStatus {PENDING, REOPENED, REJECTED, FINALIZED_AGAIN}

    @Getter
    @Setter
    @Entity(name = "AprPolicyVersion")
    @Table(name = "apr_policy_version")
    public static class PolicyVersion extends BaseEntity {
        @Column(name = "version_no", nullable = false, unique = true)
        private int versionNo;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private PolicyStatus status = PolicyStatus.DRAFT;
        @Column(name = "kpi_weight", nullable = false)
        private BigDecimal kpiWeight;
        @Column(name = "behaviour_weight", nullable = false)
        private BigDecimal behaviourWeight;
        @Column(name = "changed_by", length = 200)
        private String changedBy;
        @Column(name = "changed_by_user_id")
        private Long changedByUserId;
        @Column(name = "submitted_at")
        private Instant submittedAt;
        @Column(name = "approved_by", length = 200)
        private String approvedBy;
        @Column(name = "approved_at")
        private Instant approvedAt;
        @Column(length = 1000)
        private String note;
    }

    @Getter
    @Setter
    @Entity(name = "AprRatingBand")
    @Table(name = "apr_rating_band")
    public static class RatingBand extends BaseEntity {
        @Column(name = "policy_version_id", nullable = false)
        private Long policyVersionId;
        @Column(nullable = false, length = 60)
        private String label;
        @Column(name = "rating_value", nullable = false)
        private int ratingValue;
        @Column(nullable = false, length = 80)
        private String category;
        @Column(name = "min_score", nullable = false)
        private BigDecimal minScore;
        @Column(name = "max_score", nullable = false)
        private BigDecimal maxScore;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;
    }

    @Getter
    @Setter
    @Entity(name = "AprBehaviourCriterion")
    @Table(name = "apr_behaviour_criterion")
    public static class BehaviourCriterion extends BaseEntity {
        @Column(nullable = false, unique = true, length = 40)
        private String code;
        @Column(nullable = false, length = 150)
        private String name;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;
        @Column(nullable = false)
        private boolean active = true;
        @Column(name = "descriptor_1", length = 300)
        private String descriptor1;
        @Column(name = "descriptor_2", length = 300)
        private String descriptor2;
        @Column(name = "descriptor_3", length = 300)
        private String descriptor3;
        @Column(name = "descriptor_4", length = 300)
        private String descriptor4;
        @Column(name = "descriptor_5", length = 300)
        private String descriptor5;
    }

    @Getter
    @Setter
    @Entity(name = "AprAppraisal")
    @Table(name = "apr_appraisal")
    public static class Appraisal extends BaseEntity {
        @Column(name = "period_id", nullable = false)
        private Long periodId;
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "policy_version_id")
        private Long policyVersionId;
        @Column(name = "kpi_weight", nullable = false)
        private BigDecimal kpiWeight;
        @Column(name = "behaviour_weight", nullable = false)
        private BigDecimal behaviourWeight;
        @Column(name = "supervisor_user_id")
        private Long supervisorUserId;
        @Column(name = "reviewer_user_id")
        private Long reviewerUserId;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status = Status.DRAFT;
        @Enumerated(EnumType.STRING)
        @Column(name = "agreement_status", nullable = false, length = 30)
        private AgreementStatus agreementStatus = AgreementStatus.DRAFT;
        @Column(name = "agreement_version", nullable = false)
        private int agreementVersion = 1;
        @Column(name = "employee_signed_at")
        private Instant employeeSignedAt;
        @Column(name = "supervisor_signed_at")
        private Instant supervisorSignedAt;
        @Column(name = "supervisor_signed_by", length = 200)
        private String supervisorSignedBy;
        @Column(name = "reviewer_signed_at")
        private Instant reviewerSignedAt;
        @Column(name = "reviewer_signed_by", length = 200)
        private String reviewerSignedBy;
        @Enumerated(EnumType.STRING)
        @Column(name = "self_status", nullable = false, length = 20)
        private StepStatus selfStatus = StepStatus.NOT_STARTED;
        @Column(name = "self_achievements", columnDefinition = "text")
        private String selfAchievements;
        @Column(name = "self_challenges", columnDefinition = "text")
        private String selfChallenges;
        @Column(name = "self_comments", columnDefinition = "text")
        private String selfComments;
        @Column(name = "self_submitted_at")
        private Instant selfSubmittedAt;
        @Enumerated(EnumType.STRING)
        @Column(name = "behaviour_status", nullable = false, length = 20)
        private StepStatus behaviourStatus = StepStatus.NOT_STARTED;
        @Column(columnDefinition = "text")
        private String strengths;
        @Column(columnDefinition = "text")
        private String improvements;
        @Column(name = "behaviour_submitted_at")
        private Instant behaviourSubmittedAt;
        @Column(name = "verification_completed_at")
        private Instant verificationCompletedAt;
        @Column(name = "kpi_score")
        private BigDecimal kpiScore;
        @Column(name = "behaviour_score")
        private BigDecimal behaviourScore;
        @Column(name = "overall_score")
        private BigDecimal overallScore;
        private BigDecimal adjustment;
        @Column(name = "adjustment_justification", columnDefinition = "text")
        private String adjustmentJustification;
        @Column(name = "final_score")
        private BigDecimal finalScore;
        @Column(length = 80)
        private String category;
        @Column(name = "reviewer_comments", columnDefinition = "text")
        private String reviewerComments;
        @Column(name = "approved_at")
        private Instant approvedAt;
        @Column(name = "acknowledged_at")
        private Instant acknowledgedAt;
        @Column(name = "employee_ack_comments", columnDefinition = "text")
        private String employeeAckComments;
        @Column(name = "finalized_at")
        private Instant finalizedAt;
    }

    @Getter
    @Setter
    @Entity(name = "AprKpi")
    @Table(name = "apr_kpi")
    public static class Kpi extends BaseEntity {
        @Column(name = "appraisal_id", nullable = false)
        private Long appraisalId;
        @Column(nullable = false, length = 300)
        private String objective;
        @Column(nullable = false, length = 300)
        private String title;
        @Enumerated(EnumType.STRING)
        @Column(name = "measurement_method", nullable = false, length = 20)
        private Method measurementMethod = Method.QUALITATIVE;
        @Column(length = 300)
        private String target;
        @Column(nullable = false)
        private BigDecimal weight;
        @Column(name = "review_period", length = 100)
        private String reviewPeriod;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;
        /** JSON array of {name, weight}. */
        @Column(columnDefinition = "text")
        private String milestones;
        /** JSON object of the method-specific progress fields. */
        @Column(name = "progress_data", columnDefinition = "text")
        private String progressData;
        @Column(name = "self_progress")
        private BigDecimal selfProgress;
        @Column(name = "self_note", length = 1000)
        private String selfNote;
        @Column(name = "delay_explanation", columnDefinition = "text")
        private String delayExplanation;
        @Enumerated(EnumType.STRING)
        @Column(name = "progress_status", nullable = false, length = 20)
        private ProgressStatus progressStatus = ProgressStatus.NOT_STARTED;
        @Enumerated(EnumType.STRING)
        @Column(length = 20)
        private Decision decision;
        @Column(name = "verified_value")
        private BigDecimal verifiedValue;
        @Column(name = "verifier_comment", length = 1000)
        private String verifierComment;
        @Column(nullable = false)
        private boolean excepted;
        @Column(name = "submission_count", nullable = false)
        private int submissionCount;
    }

    @Getter
    @Setter
    @Entity(name = "AprKpiSubmission")
    @Table(name = "apr_kpi_submission")
    public static class KpiSubmission extends BaseEntity {
        @Column(name = "kpi_id", nullable = false)
        private Long kpiId;
        @Column(name = "version_no", nullable = false)
        private int versionNo;
        @Column(name = "self_progress")
        private BigDecimal selfProgress;
        @Column(name = "progress_data", columnDefinition = "text")
        private String progressData;
        @Column(name = "delay_explanation", columnDefinition = "text")
        private String delayExplanation;
        @Column(nullable = false, length = 20)
        private String status;
        @Column(name = "submitted_at", nullable = false)
        private Instant submittedAt;
    }

    @Getter
    @Setter
    @Entity(name = "AprEvidence")
    @Table(name = "apr_evidence")
    public static class Evidence extends BaseEntity {
        @Column(name = "kpi_id", nullable = false)
        private Long kpiId;
        @Column(name = "file_id", nullable = false)
        private Long fileId;
        @Column(name = "file_name", nullable = false, length = 255)
        private String fileName;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private EvidenceStatus status = EvidenceStatus.PENDING;
    }

    @Getter
    @Setter
    @Entity(name = "AprBehaviourRating")
    @Table(name = "apr_behaviour_rating")
    public static class BehaviourRating extends BaseEntity {
        @Column(name = "appraisal_id", nullable = false)
        private Long appraisalId;
        @Column(name = "criterion_id", nullable = false)
        private Long criterionId;
        private Integer rating;
        @Column(name = "not_applicable", nullable = false)
        private boolean notApplicable;
        @Column(length = 2000)
        private String justification;
    }

    @Getter
    @Setter
    @Entity(name = "AprTrail")
    @Table(name = "apr_trail")
    public static class Trail extends BaseEntity {
        @Column(name = "appraisal_id", nullable = false)
        private Long appraisalId;
        @Column(nullable = false, length = 40)
        private String level;
        @Column(name = "officer_name", length = 200)
        private String officerName;
        @Column(name = "on_behalf_of", length = 200)
        private String onBehalfOf;
        @Column(nullable = false, length = 60)
        private String decision;
        @Column(columnDefinition = "text")
        private String comment;
        @Column(name = "acted_at", nullable = false)
        private Instant actedAt;
    }

    @Getter
    @Setter
    @Entity(name = "AprAgreementHistory")
    @Table(name = "apr_agreement_history")
    public static class AgreementHistory extends BaseEntity {
        @Column(name = "appraisal_id", nullable = false)
        private Long appraisalId;
        @Column(name = "version_no", nullable = false)
        private int versionNo;
        @Column(name = "changed_by", length = 200)
        private String changedBy;
        @Column(name = "changed_at", nullable = false)
        private Instant changedAt;
        @Column(length = 300)
        private String note;
    }

    @Getter
    @Setter
    @Entity(name = "AprAppeal")
    @Table(name = "apr_appeal")
    public static class Appeal extends BaseEntity {
        @Column(name = "appraisal_id", nullable = false)
        private Long appraisalId;
        @Column(nullable = false, unique = true, length = 30)
        private String reference;
        @Column(nullable = false, columnDefinition = "text")
        private String grounds;
        @Column(name = "file_ids", length = 500)
        private String fileIds;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private AppealStatus status = AppealStatus.SUBMITTED;
        @Column(columnDefinition = "text")
        private String decision;
        @Column(name = "decided_by", length = 200)
        private String decidedBy;
        @Column(name = "decided_at")
        private Instant decidedAt;
        @Column(name = "submitted_at", nullable = false)
        private Instant submittedAt;
    }

    @Getter
    @Setter
    @Entity(name = "AprCheckin")
    @Table(name = "apr_checkin")
    public static class Checkin extends BaseEntity {
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "supervisor_user_id", nullable = false)
        private Long supervisorUserId;
        @Column(name = "supervisor_name", length = 200)
        private String supervisorName;
        @Column(name = "checkin_date", nullable = false)
        private LocalDate checkinDate;
        @Column(name = "progress_discussed", columnDefinition = "text")
        private String progressDiscussed;
        @Column(name = "agreed_actions", columnDefinition = "text")
        private String agreedActions;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private CheckinStatus status = CheckinStatus.SCHEDULED;
    }

    @Getter
    @Setter
    @Entity(name = "AprFeedback")
    @Table(name = "apr_feedback")
    public static class Feedback extends BaseEntity {
        @Column(name = "from_user_id", nullable = false)
        private Long fromUserId;
        @Column(name = "from_name", length = 200)
        private String fromName;
        @Column(name = "to_user_id")
        private Long toUserId;
        @Column(name = "to_employee_id")
        private Long toEmployeeId;
        @Column(name = "to_name", length = 200)
        private String toName;
        @Enumerated(EnumType.STRING)
        @Column(name = "feedback_type", nullable = false, length = 20)
        private FeedbackType feedbackType;
        @Column(nullable = false, columnDefinition = "text")
        private String body;
        @Column(nullable = false, length = 20)
        private String status = "SENT";
    }

    @Getter
    @Setter
    @Entity(name = "AprSpecialCase")
    @Table(name = "apr_special_case")
    public static class SpecialCase extends BaseEntity {
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "appraisal_id")
        private Long appraisalId;
        @Enumerated(EnumType.STRING)
        @Column(name = "case_type", nullable = false, length = 30)
        private CaseType caseType;
        @Column(name = "kpi_id")
        private Long kpiId;
        @Column(name = "effective_from")
        private LocalDate effectiveFrom;
        @Column(name = "effective_to")
        private LocalDate effectiveTo;
        @Column(name = "new_position", length = 200)
        private String newPosition;
        @Column(name = "previous_supervisor", length = 200)
        private String previousSupervisor;
        @Column(name = "target_adjustment", length = 30)
        private String targetAdjustment;
        @Column(length = 60)
        private String reason;
        @Column(nullable = false, columnDefinition = "text")
        private String justification;
        @Column(name = "original_value")
        private BigDecimal originalValue;
        @Column(name = "adjusted_value")
        private BigDecimal adjustedValue;
        @Column(name = "file_id")
        private Long fileId;
        @Column(name = "file_name", length = 255)
        private String fileName;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private RequestStatus status = RequestStatus.PENDING;
        @Column(name = "requested_by_user_id", nullable = false)
        private Long requestedByUserId;
        @Column(name = "requested_by_name", length = 200)
        private String requestedByName;
        @Column(name = "decided_by_name", length = 200)
        private String decidedByName;
        @Column(name = "decided_at")
        private Instant decidedAt;
        @Column(name = "decision_comment", length = 1000)
        private String decisionComment;
    }

    @Getter
    @Setter
    @Entity(name = "AprDelegation")
    @Table(name = "apr_delegation")
    public static class Delegation extends BaseEntity {
        @Column(name = "delegator_user_id", nullable = false)
        private Long delegatorUserId;
        @Column(name = "delegator_name", length = 200)
        private String delegatorName;
        @Column(name = "delegate_user_id", nullable = false)
        private Long delegateUserId;
        @Column(name = "delegate_name", length = 200)
        private String delegateName;
        @Column(name = "date_from", nullable = false)
        private LocalDate dateFrom;
        @Column(name = "date_to", nullable = false)
        private LocalDate dateTo;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 30)
        private DelegationScope scope;
        @Column(nullable = false, length = 20)
        private String status = "ACTIVE";
        @Column(name = "revoked_at")
        private Instant revokedAt;
    }

    @Getter
    @Setter
    @Entity(name = "AprReopen")
    @Table(name = "apr_reopen")
    public static class Reopen extends BaseEntity {
        @Column(name = "appraisal_id", nullable = false)
        private Long appraisalId;
        @Column(nullable = false, unique = true, length = 30)
        private String reference;
        @Column(nullable = false, length = 60)
        private String reason;
        @Column(nullable = false, columnDefinition = "text")
        private String justification;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private ReopenStatus status = ReopenStatus.PENDING;
        @Column(name = "requested_by_user_id", nullable = false)
        private Long requestedByUserId;
        @Column(name = "requested_by_name", length = 200)
        private String requestedByName;
        @Column(name = "approved_by_name", length = 200)
        private String approvedByName;
        @Column(name = "decided_at")
        private Instant decidedAt;
        @Column(columnDefinition = "text")
        private String snapshot;
    }

    @Getter
    @Setter
    @Entity(name = "AprSyncModule")
    @Table(name = "apr_sync_module")
    public static class SyncModule extends BaseEntity {
        @Column(name = "module_code", nullable = false, unique = true, length = 20)
        private String moduleCode;
        @Column(name = "module_label", nullable = false, length = 60)
        private String moduleLabel;
        @Column(name = "data_label", nullable = false, length = 100)
        private String dataLabel;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;
        @Column(name = "last_sync_at")
        private Instant lastSyncAt;
        @Column(name = "last_success_at")
        private Instant lastSuccessAt;
        @Column(nullable = false, length = 20)
        private String status = "NEVER";
        @Column(name = "last_message", length = 1000)
        private String lastMessage;
    }

    @Getter
    @Setter
    @Entity(name = "AprSyncFailure")
    @Table(name = "apr_sync_failure")
    public static class SyncFailure extends BaseEntity {
        @Column(name = "module_code", nullable = false, length = 20)
        private String moduleCode;
        @Column(name = "failed_at", nullable = false)
        private Instant failedAt;
        @Column(nullable = false, length = 1000)
        private String reason;
    }
}
