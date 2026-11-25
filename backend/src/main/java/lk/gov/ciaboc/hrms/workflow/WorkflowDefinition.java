package lk.gov.ciaboc.hrms.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Administrator-configured approval chain for one business process (FR-WFL-001, -003, -009). */
@Getter
@Setter
@Entity
@Table(name = "workflow_definition")
public class WorkflowDefinition extends BaseEntity {
    @Column(name = "process_code", nullable = false, unique = true, length = 50)
    private String processCode;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Legacy single-chain stages as seeded by module migrations. They are imported as a new published version whenever
     * they change (see {@link WorkflowVersionService#syncLegacyStages}); live routing always uses {@link WorkflowVersion}.
     */
    @JsonIgnore
    @OneToMany(mappedBy = "definition", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("stageOrder ASC")
    private List<WorkflowStage> stages = new ArrayList<>();

    /** Fingerprint of the legacy stage rows last imported. */
    @JsonIgnore
    @Column(name = "legacy_fingerprint", length = 64)
    private String legacyFingerprint;
}
