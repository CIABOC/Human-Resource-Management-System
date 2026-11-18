package lk.gov.ciaboc.hrms.legal;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Records how an amending instrument changed a provision (FR-EST-050..052). */
@Getter
@Setter
@Entity
@Table(name = "legal_amendment")
public class Amendment extends BaseEntity {
    public enum Type {INSERTED, SUBSTITUTED, MODIFIED, REPEALED}

    @Column(name = "original_document_id", nullable = false)
    private Long originalDocumentId;

    /** Provision version that was amended (null for insertions). */
    @Column(name = "section_id")
    private Long sectionId;

    @Column(name = "amending_document_id")
    private Long amendingDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "amendment_type", nullable = false, length = 20)
    private Type amendmentType;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** New provision version created by the amendment (null for repeals). */
    @Column(name = "resulting_section_id")
    private Long resultingSectionId;
}
