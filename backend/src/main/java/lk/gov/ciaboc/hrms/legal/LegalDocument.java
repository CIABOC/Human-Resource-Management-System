package lk.gov.ciaboc.hrms.legal;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** An Act, Gazette, regulation or circular. Each upload of a new version is a new row in the same series (FR-EST-033). */
@Getter
@Setter
@Entity
@Table(name = "legal_document")
public class LegalDocument extends BaseEntity {
    public enum DocType {ACT, GAZETTE, REGULATION, CIRCULAR}

    public enum Status {ACTIVE, ARCHIVED, SUPERSEDED}

    public enum Verification {DRAFT, VERIFIED}

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20)
    private DocType docType;

    @Column(name = "doc_number", nullable = false, length = 100)
    private String docNumber;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "issuing_authority", length = 200)
    private String issuingAuthority;

    @Column(name = "publication_date")
    private LocalDate publicationDate;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private Verification verificationStatus = Verification.DRAFT;

    @Column(name = "verified_by", length = 100)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** Id of the first version; all versions of a document share it. */
    @Column(name = "series_id")
    private Long seriesId;

    @Column(name = "version_no", nullable = false)
    private int versionNo = 1;

    @Column(name = "previous_version_id")
    private Long previousVersionId;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(length = 2000)
    private String description;
}
