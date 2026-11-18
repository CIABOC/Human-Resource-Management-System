package lk.gov.ciaboc.hrms.training;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Training and development records (SRS 4.9). */
public final class TrainingEntities {
    private TrainingEntities() {
    }

    public enum ProgrammeStatus {PLANNED, OPEN, CLOSED, COMPLETED, CANCELLED}

    public enum NominationStatus {PENDING, RETURNED, APPROVED, REJECTED, CANCELLED}

    public enum CompletionStatus {NOT_RECORDED, COMPLETED, NOT_COMPLETED, ABSENT}

    @Getter
    @Setter
    @Entity(name = "TrnTrainingProgramme")
    @Table(name = "trn_programme")
    public static class TrainingProgramme extends BaseEntity {
        @Column(nullable = false, unique = true, length = 40)
        private String code;
        @Column(nullable = false, length = 300)
        private String title;
        @Column(columnDefinition = "text")
        private String description;
        @Column(length = 200)
        private String provider;
        @Column(name = "start_date", nullable = false)
        private LocalDate startDate;
        @Column(name = "end_date", nullable = false)
        private LocalDate endDate;
        @Column(length = 300)
        private String venue;
        @Column(nullable = false)
        private int capacity;
        private BigDecimal cost;
        @Column(name = "nomination_closing_date")
        private LocalDate nominationClosingDate;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private ProgrammeStatus status = ProgrammeStatus.PLANNED;
    }

    @Getter
    @Setter
    @Entity(name = "TrnTrainingNomination")
    @Table(name = "trn_nomination")
    public static class TrainingNomination extends BaseEntity {
        @Column(name = "programme_id", nullable = false)
        private Long programmeId;
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(nullable = false, length = 10)
        private String source;
        @Column(length = 1000)
        private String reason;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private NominationStatus status = NominationStatus.PENDING;
        @Column(name = "workflow_instance_id")
        private Long workflowInstanceId;
        @Enumerated(EnumType.STRING)
        @Column(name = "completion_status", nullable = false, length = 20)
        private CompletionStatus completionStatus = CompletionStatus.NOT_RECORDED;
        @Column(nullable = false)
        private boolean attended;
        @Column(length = 200)
        private String result;
        @Column(name = "completion_remarks", length = 1000)
        private String completionRemarks;
        @Column(name = "completion_recorded_at")
        private Instant completionRecordedAt;
        @Column(name = "certificate_file_id")
        private Long certificateFileId;
        @Column(name = "certificate_file_name", length = 255)
        private String certificateFileName;
    }
}
