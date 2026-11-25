package lk.gov.ciaboc.hrms.establishment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Control total from the source cadre tables (organization, unit or designation) for reconciliation (REQ 6.11). */
@Getter
@Setter
@Entity
@Table(name = "cadre_control_total")
public class CadreControlTotal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ORGANIZATION, UNIT or DESIGNATION. */
    @Column(nullable = false, length = 20)
    private String scope;

    @Column(name = "ref_code", nullable = false, length = 40)
    private String refCode;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(name = "approved_total", nullable = false)
    private int approvedTotal;

    @Column(length = 200)
    private String source;
}
