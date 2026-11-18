package lk.gov.ciaboc.hrms.masterdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Generic, trilingual, effective-dated reference value (FR-MDM-001..003). */
@Getter
@Setter
@Entity
@Table(name = "master_data_item", uniqueConstraints = @UniqueConstraint(columnNames = {"category", "code"}))
public class MasterDataItem extends BaseEntity implements EffectiveDated {
    @Column(nullable = false, length = 40)
    private String category;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "name_en", nullable = false, length = 200)
    private String nameEn;

    @Column(name = "name_si", length = 200)
    private String nameSi;

    @Column(name = "name_ta", length = 200)
    private String nameTa;

    @Column(length = 500)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;
}
