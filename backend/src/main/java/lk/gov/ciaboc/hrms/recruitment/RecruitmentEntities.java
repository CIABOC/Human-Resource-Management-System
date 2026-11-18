package lk.gov.ciaboc.hrms.recruitment;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Recruitment and appointment records (SRS 4.3). */
public final class RecruitmentEntities {
    private RecruitmentEntities() {
    }

    public enum VacancyStatus {DRAFT, OPEN, CLOSED, FILLED, CANCELLED}

    /**
     * Applicant pipeline. RECOMMENDED / SELECTED / NOT_SELECTED are only set by the selection approval workflow, never
     * manually, so that every selection decision is approved by an accountable person (C-05).
     */
    public enum ApplicantStatus {RECEIVED, SHORTLISTED, REJECTED, WITHDRAWN, RECOMMENDED, SELECTED, NOT_SELECTED, APPOINTED}

    public enum SelectionStatus {DRAFT, PENDING, RETURNED, APPROVED, REJECTED, CANCELLED}

    public enum LetterCategory {APPOINTMENT, EXAM_CALL, INTERVIEW_CALL, REGRET, OTHER}

    @Getter
    @Setter
    @Entity(name = "RecVacancy")
    @Table(name = "rec_vacancy")
    public static class Vacancy extends BaseEntity {
        @Column(name = "reference_no", nullable = false, unique = true, length = 40)
        private String referenceNo;
        @Column(nullable = false, length = 200)
        private String title;
        @Column(name = "designation_id", nullable = false)
        private Long designationId;
        @Column(name = "org_unit_code", nullable = false, length = 40)
        private String orgUnitCode;
        @Column(name = "num_posts", nullable = false)
        private int numPosts;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private VacancyStatus status = VacancyStatus.DRAFT;
        @Column(name = "opening_date")
        private LocalDate openingDate;
        @Column(name = "closing_date")
        private LocalDate closingDate;
        @Column(columnDefinition = "text")
        private String description;
        @Column(columnDefinition = "text")
        private String requirements;
        @Column(name = "exam_weight", nullable = false)
        private BigDecimal examWeight;
        @Column(name = "interview_weight", nullable = false)
        private BigDecimal interviewWeight;
    }

    @Getter
    @Setter
    @Entity(name = "RecAnnouncement")
    @Table(name = "rec_announcement")
    public static class Announcement extends BaseEntity {
        @Column(name = "vacancy_id")
        private Long vacancyId;
        @Column(nullable = false, length = 300)
        private String title;
        @Column(nullable = false, columnDefinition = "text")
        private String content;
        @Column(name = "publish_date", nullable = false)
        private LocalDate publishDate;
        @Column(name = "closing_date")
        private LocalDate closingDate;
        @Column(nullable = false)
        private boolean published;
        @Column(name = "published_at")
        private Instant publishedAt;
        @Column(name = "published_by", length = 100)
        private String publishedBy;
    }

    @Getter
    @Setter
    @Entity(name = "RecApplicant")
    @Table(name = "rec_applicant")
    public static class Applicant extends BaseEntity {
        @Column(name = "vacancy_id", nullable = false)
        private Long vacancyId;
        @Column(name = "application_no", nullable = false, unique = true, length = 40)
        private String applicationNo;
        @Column(length = 20)
        private String title;
        @Column(name = "full_name", nullable = false, length = 300)
        private String fullName;
        @Column(name = "name_with_initials", nullable = false, length = 200)
        private String nameWithInitials;
        @Column(nullable = false, length = 12)
        private String nic;
        @Column(name = "date_of_birth", nullable = false)
        private LocalDate dateOfBirth;
        @Column(length = 20)
        private String gender;
        @Column(name = "civil_status", length = 20)
        private String civilStatus;
        @Column(length = 150)
        private String email;
        @Column(length = 20)
        private String mobile;
        @Column(length = 500)
        private String address;
        @Column(columnDefinition = "text")
        private String qualifications;
        @Column(name = "received_date", nullable = false)
        private LocalDate receivedDate;
        @Column(nullable = false, length = 20)
        private String source = "HR_ENTERED";
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private ApplicantStatus status = ApplicantStatus.RECEIVED;
        @Column(length = 1000)
        private String remarks;
        @Column(name = "employee_id")
        private Long employeeId;
    }

    @Getter
    @Setter
    @Entity(name = "RecApplicantStatusChange")
    @Table(name = "rec_applicant_status")
    public static class ApplicantStatusChange extends BaseEntity {
        @Column(name = "applicant_id", nullable = false)
        private Long applicantId;
        @Column(name = "from_status", length = 20)
        private String fromStatus;
        @Column(name = "to_status", nullable = false, length = 20)
        private String toStatus;
        @Column(name = "changed_at", nullable = false)
        private Instant changedAt;
        @Column(name = "changed_by", nullable = false, length = 100)
        private String changedBy;
        @Column(length = 1000)
        private String remarks;
    }

    @Getter
    @Setter
    @Entity(name = "RecApplicantDocument")
    @Table(name = "rec_applicant_document")
    public static class ApplicantDocument extends BaseEntity {
        @Column(name = "applicant_id", nullable = false)
        private Long applicantId;
        @Column(name = "document_type", nullable = false, length = 60)
        private String documentType;
        @Column(nullable = false, length = 200)
        private String title;
        @Column(name = "file_id", nullable = false)
        private Long fileId;
        @Column(name = "file_name", nullable = false, length = 255)
        private String fileName;
    }

    @Getter
    @Setter
    @Entity(name = "RecRecruitmentExam")
    @Table(name = "rec_exam")
    public static class RecruitmentExam extends BaseEntity {
        public enum ExamType {WRITTEN, PRACTICAL}

        public enum Status {SCHEDULED, COMPLETED, CANCELLED}

        @Column(name = "vacancy_id", nullable = false)
        private Long vacancyId;
        @Column(nullable = false, length = 200)
        private String title;
        @Enumerated(EnumType.STRING)
        @Column(name = "exam_type", nullable = false, length = 20)
        private ExamType examType;
        @Column(name = "exam_date", nullable = false)
        private LocalDate examDate;
        @Column(name = "start_time")
        private LocalTime startTime;
        @Column(length = 300)
        private String venue;
        @Column(name = "max_marks", nullable = false)
        private BigDecimal maxMarks;
        @Column(name = "pass_marks")
        private BigDecimal passMarks;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status = Status.SCHEDULED;
    }

    @Getter
    @Setter
    @Entity(name = "RecExamResult")
    @Table(name = "rec_exam_result")
    public static class ExamResult extends BaseEntity {
        @Column(name = "exam_id", nullable = false)
        private Long examId;
        @Column(name = "applicant_id", nullable = false)
        private Long applicantId;
        private BigDecimal marks;
        @Column(nullable = false)
        private boolean absent;
        @Column(length = 1000)
        private String remarks;
    }

    @Getter
    @Setter
    @Entity(name = "RecInterview")
    @Table(name = "rec_interview")
    public static class Interview extends BaseEntity {
        public enum Status {SCHEDULED, COMPLETED, ABSENT, CANCELLED}

        @Column(name = "vacancy_id", nullable = false)
        private Long vacancyId;
        @Column(name = "applicant_id", nullable = false)
        private Long applicantId;
        @Column(name = "interview_date", nullable = false)
        private LocalDate interviewDate;
        @Column(name = "interview_time")
        private LocalTime interviewTime;
        @Column(length = 300)
        private String venue;
        @Column(nullable = false, length = 1000)
        private String panel;
        @Column(name = "max_marks", nullable = false)
        private BigDecimal maxMarks;
        private BigDecimal marks;
        @Column(length = 2000)
        private String remarks;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status = Status.SCHEDULED;
    }

    @Getter
    @Setter
    @Entity(name = "RecSelectionList")
    @Table(name = "rec_selection")
    public static class SelectionList extends BaseEntity {
        @Column(name = "vacancy_id", nullable = false)
        private Long vacancyId;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private SelectionStatus status = SelectionStatus.DRAFT;
        @Column(length = 2000)
        private String remarks;
        @Column(name = "workflow_instance_id")
        private Long workflowInstanceId;
        @Column(name = "submitted_at")
        private Instant submittedAt;
        @Column(name = "decided_at")
        private Instant decidedAt;
    }

    @Getter
    @Setter
    @Entity(name = "RecSelectionItem")
    @Table(name = "rec_selection_item")
    public static class SelectionItem extends BaseEntity {
        @Column(name = "selection_id", nullable = false)
        private Long selectionId;
        @Column(name = "applicant_id", nullable = false)
        private Long applicantId;
        @Column(name = "rank_no", nullable = false)
        private int rankNo;
        @Column(name = "exam_score")
        private BigDecimal examScore;
        @Column(name = "interview_score")
        private BigDecimal interviewScore;
        @Column(name = "total_score", nullable = false)
        private BigDecimal totalScore;
        @Column(nullable = false)
        private boolean recommended;
        @Column(length = 1000)
        private String remarks;
    }

    @Getter
    @Setter
    @Entity(name = "RecLetterTemplate")
    @Table(name = "rec_letter_template")
    public static class LetterTemplate extends BaseEntity {
        @Column(nullable = false, length = 40)
        private String code;
        @Column(nullable = false, length = 2)
        private String language;
        @Column(nullable = false, length = 200)
        private String name;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 30)
        private LetterCategory category;
        @Column(nullable = false, length = 300)
        private String subject;
        @Column(nullable = false, columnDefinition = "text")
        private String body;
        @Column(nullable = false)
        private boolean active = true;
    }

    @Getter
    @Setter
    @Entity(name = "RecCorrespondenceLog")
    @Table(name = "rec_correspondence")
    public static class CorrespondenceLog extends BaseEntity {
        @Column(name = "applicant_id", nullable = false)
        private Long applicantId;
        @Column(name = "vacancy_id", nullable = false)
        private Long vacancyId;
        @Column(name = "template_id")
        private Long templateId;
        @Column(name = "template_code", nullable = false, length = 40)
        private String templateCode;
        @Column(nullable = false, length = 30)
        private String category;
        @Column(nullable = false, length = 2)
        private String language;
        @Column(nullable = false, length = 300)
        private String subject;
        @Column(nullable = false, columnDefinition = "text")
        private String body;
        @Column(nullable = false, length = 10)
        private String channel;
        @Column(length = 200)
        private String recipient;
        @Column(nullable = false, length = 20)
        private String status;
        @Column(name = "outbound_message_id")
        private Long outboundMessageId;
        @Column(name = "generated_at", nullable = false)
        private Instant generatedAt;
        @Column(name = "generated_by", nullable = false, length = 100)
        private String generatedBy;
    }
}
