package lk.gov.ciaboc.hrms.extended.letters;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Correspondence and certificates (FR-EXT-005). */
public final class LetterEntities {
    private LetterEntities() {
    }

    /** A letter/certificate template in one language; {{placeholders}} are resolved from employee data at issue. */
    @Getter
    @Setter
    @Entity
    @Table(name = "ext_letter_template")
    public static class LetterTemplate extends BaseEntity {
        public enum Kind {LETTER, CERTIFICATE}

        @Column(nullable = false, length = 40)
        private String code;
        @Column(nullable = false, length = 2)
        private String language;
        @Column(nullable = false, length = 200)
        private String name;
        @Enumerated(EnumType.STRING)
        @Column(name = "document_kind", nullable = false, length = 20)
        private Kind documentKind;
        @Column(nullable = false, length = 300)
        private String title;
        @Column(name = "body_html", nullable = false, columnDefinition = "text")
        private String bodyHtml;
        /** Employees may request this document through self-service. */
        @Column(name = "self_service", nullable = false)
        private boolean selfService = true;
        @Column(nullable = false)
        private boolean active = true;
    }

    @Getter
    @Setter
    @Entity
    @Table(name = "ext_letter_request")
    public static class LetterRequest extends BaseEntity {
        public enum Status {PENDING, RETURNED, APPROVED, ISSUED, REJECTED, CANCELLED}

        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "template_code", nullable = false, length = 40)
        private String templateCode;
        @Column(nullable = false, length = 2)
        private String language;
        @Column(nullable = false, length = 500)
        private String purpose;
        @Column(length = 300)
        private String addressee;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(name = "workflow_instance_id")
        private Long workflowInstanceId;
        @Column(name = "issued_document_id")
        private Long issuedDocumentId;
    }

    /** An issued, immutable document. The verification code is the only key exposed publicly. */
    @Getter
    @Setter
    @Entity
    @Table(name = "ext_issued_document")
    public static class IssuedDocument extends BaseEntity {
        public enum Status {VALID, REVOKED}

        @Column(name = "document_no", nullable = false, unique = true, length = 40)
        private String documentNo;
        @Column(name = "verification_code", nullable = false, unique = true, length = 40)
        private String verificationCode;
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "holder_name", nullable = false, length = 200)
        private String holderName;
        @Column(name = "template_code", nullable = false, length = 40)
        private String templateCode;
        @Column(nullable = false, length = 2)
        private String language;
        @Column(name = "document_kind", nullable = false, length = 20)
        private String documentKind;
        @Column(name = "document_type_name", nullable = false, length = 200)
        private String documentTypeName;
        @Column(nullable = false, length = 300)
        private String title;
        @JsonIgnore
        @Column(name = "body_html", nullable = false, columnDefinition = "text")
        private String bodyHtml;
        @Column(name = "content_sha256", nullable = false, length = 64)
        private String contentSha256;
        @Column(name = "request_id")
        private Long requestId;
        @Column(name = "issued_at", nullable = false)
        private Instant issuedAt;
        @Column(name = "issued_by", nullable = false, length = 100)
        private String issuedBy;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(name = "revoked_at")
        private Instant revokedAt;
        @Column(name = "revoked_by", length = 100)
        private String revokedBy;
        @Column(name = "revoke_reason", length = 500)
        private String revokeReason;
    }
}
