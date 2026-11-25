package lk.gov.ciaboc.hrms.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Immutable-once-published version of a process's approval configuration (REQ 10.9, G-2). New requests use the
 * published version whose effective date has been reached (the latest one wins); in-flight requests keep the version
 * they started on. Drafts are editable and never used for live routing (the simulator can preview them).
 */
@Getter
@Setter
@Entity
@Table(name = "workflow_version")
public class WorkflowVersion extends BaseEntity {
    public enum Status {DRAFT, PUBLISHED, WITHDRAWN}

    @Column(name = "definition_id", nullable = false)
    private Long definitionId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    /** JSON of {@link WorkflowModel}. */
    @JsonIgnore
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "change_note", length = 1000)
    private String changeNote;

    /** MIGRATION (converted from the single-chain configuration), LEGACY_IMPORT (seeded stages), DESIGNER, SEED. */
    @Column(nullable = false, length = 20)
    private String source = "DESIGNER";

    @Column(name = "based_on_version_no")
    private Integer basedOnVersionNo;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by", length = 100)
    private String publishedBy;
}
