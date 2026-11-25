package lk.gov.ciaboc.hrms.establishment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** A post within a unit, with its reporting line (FR-EST-004, -011, -012). History is kept in the audit log. */
@Getter
@Setter
@Entity
@Table(name = "org_position")
public class Position extends BaseEntity {
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "designation_id", nullable = false)
    private Long designationId;

    @Column(name = "org_unit_code", nullable = false, length = 40)
    private String orgUnitCode;

    @Column(name = "reports_to_position_code", length = 40)
    private String reportsToPositionCode;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "activated_on")
    private LocalDate activatedOn;

    @Column(name = "deactivated_on")
    private LocalDate deactivatedOn;

    /** ACTIVE, FROZEN (vacant post removed from cadre), TO_BE_ABOLISHED (filled excess; abolished on vacation), ABOLISHED. */
    @Column(name = "post_status", nullable = false, length = 20)
    private String postStatus = "ACTIVE";

    /** Cadre change that created or froze this post. */
    @Column(name = "change_id")
    private Long changeId;
}
