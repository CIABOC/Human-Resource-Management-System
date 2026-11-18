package lk.gov.ciaboc.hrms.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Acting arrangement: the delegate may approve on behalf of the delegator between the dates (FR-WFL-007). */
@Getter
@Setter
@Entity
@Table(name = "delegation")
public class Delegation extends BaseEntity {
    @Column(name = "from_user_id", nullable = false)
    private Long fromUserId;

    @Column(name = "from_name", nullable = false, length = 200)
    private String fromName;

    @Column(name = "to_user_id", nullable = false)
    private Long toUserId;

    @Column(name = "to_name", nullable = false, length = 200)
    private String toName;

    /** Null applies to all processes. */
    @Column(name = "process_code", length = 50)
    private String processCode;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private boolean active = true;
}
