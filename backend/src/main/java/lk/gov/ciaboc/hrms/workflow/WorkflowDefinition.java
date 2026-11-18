package lk.gov.ciaboc.hrms.workflow;

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

    @OneToMany(mappedBy = "definition", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("stageOrder ASC")
    private List<WorkflowStage> stages = new ArrayList<>();
}
