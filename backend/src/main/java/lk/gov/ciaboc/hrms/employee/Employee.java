package lk.gov.ciaboc.hrms.employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * The single employee master record (DR-02). Other modules reference it by id and never copy its core data.
 * Current placement fields are a convenience mirror of the open {@link EmployeeAssignment}.
 */
@Getter
@Setter
@Entity
@Table(name = "employee")
public class Employee extends BaseEntity {
    @Column(name = "employee_no", nullable = false, unique = true, length = 30)
    private String employeeNo;

    @Column(length = 20)
    private String title;

    @Column(name = "full_name", nullable = false, length = 300)
    private String fullName;

    @Column(name = "name_with_initials", nullable = false, length = 200)
    private String nameWithInitials;

    @Column(name = "name_si", length = 300)
    private String nameSi;

    @Column(name = "name_ta", length = 300)
    private String nameTa;

    @Column(nullable = false, unique = true, length = 12)
    private String nic;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(length = 20)
    private String gender;

    @Column(name = "civil_status", length = 20)
    private String civilStatus;

    @Column(length = 200)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(length = 30)
    private String mobile;

    @Column(name = "permanent_address", length = 500)
    private String permanentAddress;

    @Column(name = "residential_address", length = 500)
    private String residentialAddress;

    /** EMPLOYEE_STATUS master-data code. */
    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "employment_type", nullable = false, length = 40)
    private String employmentType;

    @Column(name = "employee_category", nullable = false, length = 40)
    private String employeeCategory;

    @Column(name = "service_category", length = 40)
    private String serviceCategory;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Column(name = "confirmation_date")
    private LocalDate confirmationDate;

    @Column(name = "retirement_date")
    private LocalDate retirementDate;

    /** Month (1-12) in which the annual increment falls. */
    @Column(name = "increment_month")
    private Integer incrementMonth;

    @Column(name = "separation_date")
    private LocalDate separationDate;

    @Column(name = "separation_reason", length = 40)
    private String separationReason;

    @Column(name = "separation_remarks", length = 1000)
    private String separationRemarks;

    @Column(name = "seconded_from", length = 300)
    private String secondedFrom;

    @Column(name = "secondment_end_date")
    private LocalDate secondmentEndDate;

    @Column(name = "org_unit_code", length = 40)
    private String orgUnitCode;

    @Column(name = "designation_id")
    private Long designationId;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "bank_code", length = 40)
    private String bankCode;

    @Column(name = "bank_branch", length = 100)
    private String bankBranch;

    @Column(name = "bank_account_no", length = 40)
    private String bankAccountNo;

    /** Restricted record class: visible only to holders of EMP_RESTRICTED (NFR-SEC-10). */
    @Column(nullable = false)
    private boolean restricted;
}
