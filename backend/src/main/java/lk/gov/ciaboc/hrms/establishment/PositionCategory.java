package lk.gov.ciaboc.hrms.establishment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** A grouping of designations shown as a column on the organization chart. Closed categories accept no new members. */
@Getter
@Setter
@Entity
@Table(name = "position_category")
public class PositionCategory extends BaseEntity {
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "name_si", length = 150)
    private String nameSi;

    @Column(name = "name_ta", length = 150)
    private String nameTa;

    @Column(nullable = false, length = 7)
    private String color = "#0B1F4B";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean closed;

    @Column(length = 500)
    private String description;
}
