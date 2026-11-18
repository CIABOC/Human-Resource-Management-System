package lk.gov.ciaboc.hrms.masterdata;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Key/value configuration editable by administrators (SYS-01). */
@Getter
@Setter
@Entity
@Table(name = "system_setting")
public class SystemSetting {
    @Id
    @Column(name = "setting_key", length = 100)
    private String key;

    @Column(name = "setting_value", nullable = false, length = 1000)
    private String value;

    @Column(length = 40)
    private String category;

    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType = "STRING";

    @Column(length = 500)
    private String description;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
