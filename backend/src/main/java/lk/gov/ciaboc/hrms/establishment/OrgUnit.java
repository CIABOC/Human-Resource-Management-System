package lk.gov.ciaboc.hrms.establishment;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * One version of an organizational unit. A unit is identified by its stable {@code code}; every approved change
 * closes the current version and opens a new one, so the structure can be viewed as at any past date (FR-EST-006/007).
 * Parent links use the parent's code so they survive re-versioning.
 */
@Getter
@Setter
@Entity
@Table(name = "org_unit", uniqueConstraints = @UniqueConstraint(columnNames = {"code", "effective_from"}))
public class OrgUnit extends BaseEntity implements EffectiveDated {
    public enum UnitType {DIVISION, DEPARTMENT, SECTION, SUB_SECTION}

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_si", length = 200)
    private String nameSi;

    @Column(name = "name_ta", length = 200)
    private String nameTa;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false, length = 20)
    private UnitType unitType;

    @Column(name = "parent_code", length = 40)
    private String parentCode;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "change_id")
    private Long changeId;

    /** LOCATION master-data code: HEAD_OFFICE or REGIONAL (null when the unit has no location split). */
    @Column(length = 20)
    private String location;

    /** Title of the post that heads the unit, e.g. "Director (Investigation)". */
    @Column(name = "head_title", length = 200)
    private String headTitle;

    /** Position code of the unit head (drives reporting lines and workflow routing). */
    @Column(name = "head_position_code", length = 40)
    private String headPositionCode;
}
