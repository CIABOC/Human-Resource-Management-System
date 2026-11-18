package lk.gov.ciaboc.hrms.benefits;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Insurance schemes, employee coverage and claims (SRS 4.7). */
public final class BenefitsEntities {
    private BenefitsEntities() {
    }

    @Getter
    @Setter
    @Entity(name = "InsuranceScheme")
    @Table(name = "ben_insurance_scheme")
    public static class InsuranceScheme extends BaseEntity {
        public enum Type {AGRAHARA, OTHER}

        @Column(nullable = false, unique = true, length = 40)
        private String code;
        @Column(nullable = false, length = 200)
        private String name;
        @Column(name = "name_si", length = 200)
        private String nameSi;
        @Column(name = "name_ta", length = 200)
        private String nameTa;
        @Enumerated(EnumType.STRING)
        @Column(name = "scheme_type", nullable = false, length = 20)
        private Type schemeType;
        /** INSURANCE_CATEGORY master-data code. */
        @Column(length = 40)
        private String category;
        @Column(length = 300)
        private String provider;
        @Column(name = "employee_premium", nullable = false, precision = 14, scale = 2)
        private BigDecimal employeePremium = BigDecimal.ZERO;
        @Column(name = "employer_premium", nullable = false, precision = 14, scale = 2)
        private BigDecimal employerPremium = BigDecimal.ZERO;
        @Column(name = "coverage_amount", precision = 14, scale = 2)
        private BigDecimal coverageAmount;
        /** Employee premium is recovered through payroll as an approved benefit deduction. */
        @Column(name = "payroll_deduction", nullable = false)
        private boolean payrollDeduction = true;
        @Column(name = "claims_enabled", nullable = false)
        private boolean claimsEnabled = true;
        /** Comma-separated EMPLOYMENT_TYPE codes; blank = all. */
        @Column(name = "eligible_employment_types", length = 500)
        private String eligibleEmploymentTypes;
        @Column(name = "min_service_months", nullable = false)
        private int minServiceMonths;
        @Column(name = "eligibility_notes", columnDefinition = "text")
        private String eligibilityNotes;
        @Column(nullable = false)
        private boolean active = true;
    }

    @Getter
    @Setter
    @Entity(name = "InsuranceCoverage")
    @Table(name = "ben_insurance_coverage")
    public static class InsuranceCoverage extends BaseEntity {
        public enum Status {ACTIVE, EXPIRED, CANCELLED}

        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "scheme_id", nullable = false)
        private Long schemeId;
        @Column(name = "policy_no", length = 100)
        private String policyNo;
        @Column(name = "start_date", nullable = false)
        private LocalDate startDate;
        @Column(name = "expiry_date")
        private LocalDate expiryDate;
        @Column(name = "employee_premium", nullable = false, precision = 14, scale = 2)
        private BigDecimal employeePremium = BigDecimal.ZERO;
        @Column(name = "employer_premium", nullable = false, precision = 14, scale = 2)
        private BigDecimal employerPremium = BigDecimal.ZERO;
        @Column(name = "coverage_amount", precision = 14, scale = 2)
        private BigDecimal coverageAmount;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(name = "expiry_notified_on")
        private LocalDate expiryNotifiedOn;
        @Column(length = 1000)
        private String remarks;
    }

    @Getter
    @Setter
    @Entity(name = "InsuranceClaim")
    @Table(name = "ben_insurance_claim")
    public static class InsuranceClaim extends BaseEntity {
        public enum Status {SUBMITTED, IN_REVIEW, APPROVED, REJECTED, PAID}

        @Column(name = "coverage_id", nullable = false)
        private Long coverageId;
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "claim_date", nullable = false)
        private LocalDate claimDate;
        @Column(name = "incident_date")
        private LocalDate incidentDate;
        @Column(name = "claim_type", nullable = false, length = 100)
        private String claimType;
        @Column(name = "amount_claimed", nullable = false, precision = 14, scale = 2)
        private BigDecimal amountClaimed;
        @Column(name = "amount_approved", precision = 14, scale = 2)
        private BigDecimal amountApproved;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(name = "provider_ref", length = 100)
        private String providerRef;
        @Column(length = 2000)
        private String description;
        @Column(length = 1000)
        private String remarks;
    }
}
