package lk.gov.ciaboc.hrms.employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Child records of the employee master (FR-EMP-002, -003). */
public final class EmployeeDetails {
    private EmployeeDetails() {
    }

    @Getter
    @Setter
    @Entity
    @Table(name = "employee_qualification")
    public static class Qualification extends BaseEntity {
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        /** QUALIFICATION_TYPE code: e.g. DEGREE, DIPLOMA, PROFESSIONAL, GCE_AL. */
        @Column(name = "qualification_type", nullable = false, length = 40)
        private String qualificationType;
        @Column(nullable = false, length = 300)
        private String title;
        @Column(length = 300)
        private String institution;
        @Column(name = "year_obtained")
        private Integer yearObtained;
        @Column(length = 100)
        private String result;
        @Column(length = 500)
        private String remarks;
    }

    @Getter
    @Setter
    @Entity
    @Table(name = "employee_previous_employment")
    public static class PreviousEmployment extends BaseEntity {
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(nullable = false, length = 300)
        private String organization;
        @Column(length = 200)
        private String designation;
        @Column(name = "from_date")
        private LocalDate fromDate;
        @Column(name = "to_date")
        private LocalDate toDate;
        @Column(length = 500)
        private String remarks;
    }

    @Getter
    @Setter
    @Entity
    @Table(name = "employee_emergency_contact")
    public static class EmergencyContact extends BaseEntity {
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(nullable = false, length = 200)
        private String name;
        @Column(length = 40)
        private String relationship;
        @Column(nullable = false, length = 30)
        private String phone;
        @Column(length = 500)
        private String address;
    }

    /** Digital personal file entry (FR-EMP-004). */
    @Getter
    @Setter
    @Entity
    @Table(name = "employee_document")
    public static class Document extends BaseEntity {
        public enum Classification {PUBLIC, INTERNAL, CONFIDENTIAL, HIGHLY_CONFIDENTIAL}

        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "document_type", nullable = false, length = 40)
        private String documentType;
        @Column(nullable = false, length = 300)
        private String title;
        @Column(name = "document_date")
        private LocalDate documentDate;
        @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
        @Column(nullable = false, length = 30)
        private Classification classification = Classification.CONFIDENTIAL;
        @Column(name = "file_id", nullable = false)
        private Long fileId;
        @Column(name = "file_name", length = 255)
        private String fileName;
        @Column(length = 500)
        private String remarks;
    }

    /** Service history event (FR-EMP-006, -007). */
    @Getter
    @Setter
    @Entity
    @Table(name = "service_event")
    public static class ServiceEvent extends BaseEntity {
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        /** APPOINTMENT, CONFIRMATION, PROMOTION, TRANSFER, SECONDMENT, INCREMENT, STATUS_CHANGE, SEPARATION, OTHER */
        @Column(name = "event_type", nullable = false, length = 30)
        private String eventType;
        @Column(name = "event_date", nullable = false)
        private LocalDate eventDate;
        @Column(nullable = false, length = 1000)
        private String description;
        @Column(length = 100)
        private String reference;
    }

    /** Self-service or maker-checker change to sensitive fields (FR-EMP-012, -013). */
    @Getter
    @Setter
    @Entity
    @Table(name = "profile_change_request")
    public static class ProfileChangeRequest extends BaseEntity {
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        /** JSON object: field -> {"old": .., "new": ..}. */
        @Column(nullable = false, columnDefinition = "text")
        private String changes;
        @Column(length = 1000)
        private String reason;
        /** SELF or HR. */
        @Column(nullable = false, length = 10)
        private String source;
        /** PENDING, RETURNED, APPROVED, REJECTED, CANCELLED */
        @Column(nullable = false, length = 20)
        private String status;
        @Column(name = "workflow_instance_id")
        private Long workflowInstanceId;
    }
}
