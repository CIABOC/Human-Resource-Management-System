package lk.gov.ciaboc.hrms.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** One row per external interface (INT-01..INT-11); each is individually switchable (INT-12). */
@Getter
@Setter
@Entity
@Table(name = "integration_config")
public class IntegrationConfig extends BaseEntity {
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "ref_id", length = 10)
    private String refId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String purpose;

    @Column(length = 1)
    private String priority;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "endpoint_url", length = 500)
    private String endpointUrl;

    @Column(length = 1000)
    private String notes;
}
