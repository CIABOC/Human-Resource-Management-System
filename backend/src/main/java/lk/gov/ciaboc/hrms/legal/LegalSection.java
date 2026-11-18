package lk.gov.ciaboc.hrms.legal;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Part / Chapter / Section / Subsection / Clause of a legal document (FR-EST-040). Amendments close a version and open a
 * new one so original text and history are preserved (FR-EST-043, -052).
 */
@Getter
@Setter
@Entity
@Table(name = "legal_section")
public class LegalSection extends BaseEntity implements EffectiveDated {
    public enum Level {PART, CHAPTER, SECTION, SUBSECTION, CLAUSE}

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** Stable key of the parent provision (its sectionKey). */
    @Column(name = "parent_key", length = 36)
    private String parentKey;

    /** Stable identity of the provision across versions. */
    @Column(name = "section_key", nullable = false, length = 36)
    private String sectionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Level level;

    @Column(nullable = false, length = 40)
    private String number;

    @Column(length = 500)
    private String heading;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private LegalDocument.Verification verificationStatus = LegalDocument.Verification.DRAFT;

    @Column(name = "version_no", nullable = false)
    private int versionNo = 1;

    @Column(name = "previous_version_id")
    private Long previousVersionId;

    /** LEGAL_TOPIC master-data codes, e.g. APPOINTMENT, POWERS_FUNCTIONS (FR-EST-042). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "legal_section_tag", joinColumns = @JoinColumn(name = "section_id"))
    @Column(name = "tag", length = 40)
    private Set<String> tags = new HashSet<>();

    /** Designations this provision applies to (FR-EST-041). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "legal_section_designation", joinColumns = @JoinColumn(name = "section_id"))
    @Column(name = "designation_id")
    private Set<Long> designationIds = new HashSet<>();
}
