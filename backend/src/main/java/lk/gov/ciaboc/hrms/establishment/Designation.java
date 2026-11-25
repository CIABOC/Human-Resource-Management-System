package lk.gov.ciaboc.hrms.establishment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** Designation (job title) with grade; created through the UI without code changes (FR-EST-010, -013). */
@Getter
@Setter
@Entity
@Table(name = "designation")
public class Designation extends BaseEntity {
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_si", length = 200)
    private String nameSi;

    @Column(name = "name_ta", length = 200)
    private String nameTa;

    /** GRADE master-data code. */
    @Column(length = 40)
    private String grade;

    /** SERVICE_CATEGORY master-data code. */
    @Column(name = "service_category", length = 40)
    private String serviceCategory;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    /** Level in the proposed structure (1 = Additional DG ... 13 = Office Assistant; 0 = statutory DG). */
    private Integer level;

    /** Salary scale code (REQ 14.2); links to the payroll salary scales. */
    @Column(name = "salary_code", length = 40)
    private String salaryCode;

    /** Dying cadre: no fresh recruitment, no increase; posts lapse as holders leave (REQ 6.10). */
    @Column(name = "dying_cadre", nullable = false)
    private boolean dyingCadre;

    /** Statutory post (e.g. Director General) with its legal basis (spec 2.4, REQ 10.1). */
    @Column(nullable = false)
    private boolean statutory;

    @Column(name = "legal_basis", length = 1000)
    private String legalBasis;
}
