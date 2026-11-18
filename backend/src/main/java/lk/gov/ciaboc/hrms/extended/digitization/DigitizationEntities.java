package lk.gov.ciaboc.hrms.extended.digitization;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.employee.EmployeeDetails;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** Controlled digitization of legacy personal files (FR-EXT-011). */
public final class DigitizationEntities {
    private DigitizationEntities() {
    }

    @Getter
    @Setter
    @Entity
    @Table(name = "ext_digitization_batch")
    public static class Batch extends BaseEntity {
        public enum Status {OPEN, IN_QA, COMPLETED}

        @Column(name = "batch_no", nullable = false, unique = true, length = 40)
        private String batchNo;
        @Column(nullable = false, length = 300)
        private String title;
        @Column(name = "source_location", length = 300)
        private String sourceLocation;
        @Column(length = 1000)
        private String remarks;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(name = "submitted_by", length = 100)
        private String submittedBy;
        @Column(name = "submitted_at")
        private Instant submittedAt;
        @Column(name = "completed_at")
        private Instant completedAt;
    }

    @Getter
    @Setter
    @Entity
    @Table(name = "ext_digitization_item")
    public static class Item extends BaseEntity {
        public enum Status {UPLOADED, VERIFIED, REJECTED, FILED, WITHDRAWN}

        @Column(name = "batch_id", nullable = false)
        private Long batchId;
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "employee_no", nullable = false, length = 30)
        private String employeeNo;
        @Column(name = "document_type", nullable = false, length = 40)
        private String documentType;
        @Column(nullable = false, length = 300)
        private String title;
        @Column(name = "document_date")
        private LocalDate documentDate;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 30)
        private EmployeeDetails.Document.Classification classification;
        @Column(name = "file_id", nullable = false)
        private Long fileId;
        @Column(name = "file_name", length = 255)
        private String fileName;
        @Column(name = "file_sha256", length = 64)
        private String fileSha256;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(name = "uploaded_by", nullable = false, length = 100)
        private String uploadedBy;
        @Column(name = "qa_by", length = 100)
        private String qaBy;
        @Column(name = "qa_at")
        private Instant qaAt;
        @Column(name = "qa_remarks", length = 1000)
        private String qaRemarks;
        @Column(name = "employee_document_id")
        private Long employeeDocumentId;
        @Column(name = "filed_by", length = 100)
        private String filedBy;
        @Column(name = "filed_at")
        private Instant filedAt;
    }
}
