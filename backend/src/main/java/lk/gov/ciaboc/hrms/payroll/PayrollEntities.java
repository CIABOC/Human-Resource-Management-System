package lk.gov.ciaboc.hrms.payroll;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Payroll and salary administration records (SRS 4.6). */
public final class PayrollEntities {
    private PayrollEntities() {
    }

    // ===================================================================== configuration

    /** Effective-dated salary scale (FR-PAY-001). Step n pays initial + n x increment, n in [0, maxSteps]. */
    @Getter
    @Setter
    @Entity(name = "SalaryScale")
    @Table(name = "pay_salary_scale")
    public static class SalaryScale extends BaseEntity implements EffectiveDated {
        @Column(nullable = false, length = 40)
        private String code;
        @Column(nullable = false, length = 200)
        private String name;
        @Column(length = 40)
        private String grade;
        @Column(name = "initial_salary", nullable = false, precision = 14, scale = 2)
        private BigDecimal initialSalary;
        @Column(name = "increment_amount", nullable = false, precision = 14, scale = 2)
        private BigDecimal incrementAmount;
        @Column(name = "max_steps", nullable = false)
        private int maxSteps;
        @Column(name = "effective_from", nullable = false)
        private LocalDate effectiveFrom;
        @Column(name = "effective_to")
        private LocalDate effectiveTo;
        @Column(length = 1000)
        private String notes;

        public BigDecimal salaryAtStep(int step) {
            return initialSalary.add(incrementAmount.multiply(BigDecimal.valueOf(step)));
        }

        public BigDecimal getMaxSalary() {
            return initialSalary == null || incrementAmount == null ? null : salaryAtStep(maxSteps);
        }
    }

    @Getter
    @Setter
    @Entity(name = "AllowanceType")
    @Table(name = "pay_allowance_type")
    public static class AllowanceType extends BaseEntity {
        @Column(nullable = false, unique = true, length = 40)
        private String code;
        @Column(nullable = false, length = 200)
        private String name;
        @Column(name = "name_si", length = 200)
        private String nameSi;
        @Column(name = "name_ta", length = 200)
        private String nameTa;
        /** When true every in-service employee receives it; otherwise only employees it is assigned to. */
        @Column(name = "applies_to_all", nullable = false)
        private boolean appliesToAll;
        @Column(nullable = false)
        private boolean active = true;
        @Column(length = 500)
        private String description;
    }

    @Getter
    @Setter
    @Entity(name = "AllowanceRate")
    @Table(name = "pay_allowance_rate")
    public static class AllowanceRate extends BaseEntity implements EffectiveDated {
        @Column(name = "allowance_type_id", nullable = false)
        private Long allowanceTypeId;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private PayrollCalculator.Basis basis;
        @Column(name = "rate_value", nullable = false, precision = 14, scale = 4)
        private BigDecimal rateValue;
        @Column(name = "effective_from", nullable = false)
        private LocalDate effectiveFrom;
        @Column(name = "effective_to")
        private LocalDate effectiveTo;
        @Column(length = 500)
        private String notes;
    }

    @Getter
    @Setter
    @Entity(name = "DeductionType")
    @Table(name = "pay_deduction_type")
    public static class DeductionType extends BaseEntity {
        public enum Category {STATUTORY, SCHEME, OTHER}

        @Column(nullable = false, unique = true, length = 40)
        private String code;
        @Column(nullable = false, length = 200)
        private String name;
        @Column(name = "name_si", length = 200)
        private String nameSi;
        @Column(name = "name_ta", length = 200)
        private String nameTa;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Category category;
        /** When true the deduction applies only to employees with an effective membership (FR-PAY-012). */
        @Column(name = "membership_required", nullable = false)
        private boolean membershipRequired = true;
        @Column(name = "pension_related", nullable = false)
        private boolean pensionRelated;
        @Column(nullable = false)
        private boolean active = true;
        @Column(length = 500)
        private String description;
    }

    @Getter
    @Setter
    @Entity(name = "DeductionRate")
    @Table(name = "pay_deduction_rate")
    public static class DeductionRate extends BaseEntity implements EffectiveDated {
        @Column(name = "deduction_type_id", nullable = false)
        private Long deductionTypeId;
        @Enumerated(EnumType.STRING)
        @Column(name = "employee_basis", nullable = false, length = 20)
        private PayrollCalculator.Basis employeeBasis;
        @Column(name = "employee_value", nullable = false, precision = 14, scale = 4)
        private BigDecimal employeeValue = BigDecimal.ZERO;
        @Enumerated(EnumType.STRING)
        @Column(name = "employer_basis", nullable = false, length = 20)
        private PayrollCalculator.Basis employerBasis;
        @Column(name = "employer_value", nullable = false, precision = 14, scale = 4)
        private BigDecimal employerValue = BigDecimal.ZERO;
        @Column(name = "effective_from", nullable = false)
        private LocalDate effectiveFrom;
        @Column(name = "effective_to")
        private LocalDate effectiveTo;
        @Column(name = "to_be_confirmed", nullable = false)
        private boolean toBeConfirmed;
        @Column(length = 500)
        private String notes;
    }

    // ===================================================================== employee placement

    @Getter
    @Setter
    @Entity(name = "EmployeeSalary")
    @Table(name = "pay_employee_salary")
    public static class EmployeeSalary extends BaseEntity implements EffectiveDated {
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "scale_code", nullable = false, length = 40)
        private String scaleCode;
        @Column(nullable = false)
        private int step;
        @Column(name = "basic_salary", nullable = false, precision = 14, scale = 2)
        private BigDecimal basicSalary;
        @Column(name = "effective_from", nullable = false)
        private LocalDate effectiveFrom;
        @Column(name = "effective_to")
        private LocalDate effectiveTo;
        /** INITIAL, INCREMENT, REVISION, PROMOTION, CORRECTION */
        @Column(nullable = false, length = 30)
        private String reason;
        @Column(length = 100)
        private String reference;
        @Column(length = 1000)
        private String remarks;
    }

    @Getter
    @Setter
    @Entity(name = "EmployeeAllowance")
    @Table(name = "pay_employee_allowance")
    public static class EmployeeAllowance extends BaseEntity implements EffectiveDated {
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "allowance_type_id", nullable = false)
        private Long allowanceTypeId;
        @Column(name = "amount_override", precision = 14, scale = 2)
        private BigDecimal amountOverride;
        @Column(name = "effective_from", nullable = false)
        private LocalDate effectiveFrom;
        @Column(name = "effective_to")
        private LocalDate effectiveTo;
        @Column(length = 500)
        private String remarks;
    }

    @Getter
    @Setter
    @Entity(name = "DeductionMembership")
    @Table(name = "pay_deduction_membership")
    public static class DeductionMembership extends BaseEntity implements EffectiveDated {
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "deduction_type_id", nullable = false)
        private Long deductionTypeId;
        @Column(name = "member_no", length = 60)
        private String memberNo;
        @Column(name = "employee_value_override", precision = 14, scale = 4)
        private BigDecimal employeeValueOverride;
        @Column(name = "employer_value_override", precision = 14, scale = 4)
        private BigDecimal employerValueOverride;
        @Column(name = "effective_from", nullable = false)
        private LocalDate effectiveFrom;
        @Column(name = "effective_to")
        private LocalDate effectiveTo;
        @Column(length = 500)
        private String remarks;
    }

    // ===================================================================== loans

    @Getter
    @Setter
    @Entity(name = "EmployeeLoan")
    @Table(name = "pay_employee_loan")
    public static class EmployeeLoan extends BaseEntity {
        public enum Status {ACTIVE, SETTLED, TRANSFERRED_OUT}

        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        /** LOAN_TYPE master-data code. */
        @Column(name = "loan_type", nullable = false, length = 40)
        private String loanType;
        @Column(nullable = false, length = 300)
        private String lender;
        @Column(name = "previous_workplace", length = 300)
        private String previousWorkplace;
        @Column(name = "reference_no", length = 100)
        private String referenceNo;
        @Column(name = "original_amount", nullable = false, precision = 14, scale = 2)
        private BigDecimal originalAmount;
        @Column(name = "outstanding_balance", nullable = false, precision = 14, scale = 2)
        private BigDecimal outstandingBalance;
        @Column(name = "monthly_instalment", nullable = false, precision = 14, scale = 2)
        private BigDecimal monthlyInstalment;
        @Column(name = "start_date", nullable = false)
        private LocalDate startDate;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(name = "closed_on")
        private LocalDate closedOn;
        @Column(length = 1000)
        private String remarks;
    }

    @Getter
    @Setter
    @Entity(name = "LoanTransaction")
    @Table(name = "pay_loan_transaction")
    public static class LoanTransaction extends BaseEntity {
        public enum Type {OPENING, DEDUCTION, SETTLEMENT, INSTALMENT_CHANGE, TRANSFER_OUT}

        @Column(name = "loan_id", nullable = false)
        private Long loanId;
        @Column(name = "txn_date", nullable = false)
        private LocalDate txnDate;
        @Enumerated(EnumType.STRING)
        @Column(name = "txn_type", nullable = false, length = 30)
        private Type txnType;
        @Column(nullable = false, precision = 14, scale = 2)
        private BigDecimal amount;
        @Column(name = "balance_after", nullable = false, precision = 14, scale = 2)
        private BigDecimal balanceAfter;
        @Column(name = "payroll_run_id")
        private Long payrollRunId;
        @Column(length = 7)
        private String period;
        @Column(length = 500)
        private String remarks;
    }

    @Getter
    @Setter
    @Entity(name = "LoanAdjustment")
    @Table(name = "pay_loan_adjustment")
    public static class LoanAdjustment extends BaseEntity {
        public enum Type {EARLY_SETTLEMENT, INSTALMENT_CHANGE, TRANSFER_OUT}

        public enum Status {PENDING, RETURNED, APPLIED, REJECTED, CANCELLED}

        @Column(name = "loan_id", nullable = false)
        private Long loanId;
        @Enumerated(EnumType.STRING)
        @Column(name = "adjustment_type", nullable = false, length = 30)
        private Type adjustmentType;
        @Column(name = "new_instalment", precision = 14, scale = 2)
        private BigDecimal newInstalment;
        @Column(name = "settlement_amount", precision = 14, scale = 2)
        private BigDecimal settlementAmount;
        @Column(name = "effective_date", nullable = false)
        private LocalDate effectiveDate;
        @Column(nullable = false, length = 1000)
        private String reason;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(name = "workflow_instance_id")
        private Long workflowInstanceId;
        @Column(name = "applied_at")
        private Instant appliedAt;
    }

    // ===================================================================== payroll runs

    @Getter
    @Setter
    @Entity(name = "PayrollRun")
    @Table(name = "pay_payroll_run")
    public static class PayrollRun extends BaseEntity {
        public enum Status {DRAFT, SUBMITTED, RETURNED, FINALIZED}

        @Column(nullable = false, unique = true, length = 7)
        private String period;
        @Column(name = "pay_date")
        private LocalDate payDate;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(nullable = false)
        private boolean locked;
        @Column(name = "calculated_at")
        private Instant calculatedAt;
        @Column(name = "calculated_by", length = 100)
        private String calculatedBy;
        @Column(name = "calculated_by_user_id")
        private Long calculatedByUserId;
        @Column(name = "submitted_at")
        private Instant submittedAt;
        @Column(name = "submitted_by", length = 100)
        private String submittedBy;
        @Column(name = "finalized_at")
        private Instant finalizedAt;
        @Column(name = "workflow_instance_id")
        private Long workflowInstanceId;
        @Column(name = "employee_count", nullable = false)
        private int employeeCount;
        @Column(name = "total_gross", nullable = false, precision = 14, scale = 2)
        private BigDecimal totalGross = BigDecimal.ZERO;
        @Column(name = "total_deductions", nullable = false, precision = 14, scale = 2)
        private BigDecimal totalDeductions = BigDecimal.ZERO;
        @Column(name = "total_net", nullable = false, precision = 14, scale = 2)
        private BigDecimal totalNet = BigDecimal.ZERO;
        @Column(name = "total_employer", nullable = false, precision = 14, scale = 2)
        private BigDecimal totalEmployer = BigDecimal.ZERO;
        @Column(name = "warning_count", nullable = false)
        private int warningCount;
        @Column(length = 1000)
        private String notes;
    }

    @Getter
    @Setter
    @Entity(name = "PayrollLine")
    @Table(name = "pay_payroll_line")
    public static class PayrollLine extends BaseEntity {
        @Column(name = "run_id", nullable = false)
        private Long runId;
        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Column(name = "employee_no", nullable = false, length = 30)
        private String employeeNo;
        @Column(name = "employee_name", nullable = false, length = 300)
        private String employeeName;
        @Column(length = 200)
        private String designation;
        @Column(name = "org_unit_code", length = 40)
        private String orgUnitCode;
        @Column(name = "bank_code", length = 40)
        private String bankCode;
        @Column(name = "bank_branch", length = 100)
        private String bankBranch;
        @Column(name = "bank_account_no", length = 40)
        private String bankAccountNo;
        @Column(name = "scale_code", length = 40)
        private String scaleCode;
        private Integer step;
        @Column(name = "basic_salary", nullable = false, precision = 14, scale = 2)
        private BigDecimal basicSalary;
        @Column(name = "gross_pay", nullable = false, precision = 14, scale = 2)
        private BigDecimal grossPay;
        @Column(name = "total_deductions", nullable = false, precision = 14, scale = 2)
        private BigDecimal totalDeductions;
        @Column(name = "net_pay", nullable = false, precision = 14, scale = 2)
        private BigDecimal netPay;
        @Column(name = "employer_contributions", nullable = false, precision = 14, scale = 2)
        private BigDecimal employerContributions;
        @Column(name = "no_pay_days", nullable = false, precision = 6, scale = 2)
        private BigDecimal noPayDays = BigDecimal.ZERO;
        @Column(length = 1000)
        private String warnings;
    }

    @Getter
    @Setter
    @Entity(name = "PayrollLineItem")
    @Table(name = "pay_payroll_line_item")
    public static class PayrollLineItem extends BaseEntity {
        @Column(name = "line_id", nullable = false)
        private Long lineId;
        @Enumerated(EnumType.STRING)
        @Column(name = "component_type", nullable = false, length = 20)
        private PayrollCalculator.ComponentType componentType;
        @Enumerated(EnumType.STRING)
        @Column(name = "source_type", nullable = false, length = 20)
        private PayrollCalculator.Source sourceType;
        @Column(name = "source_id")
        private Long sourceId;
        @Column(nullable = false, length = 40)
        private String code;
        @Column(nullable = false, length = 300)
        private String description;
        @Column(nullable = false, precision = 14, scale = 2)
        private BigDecimal amount;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;
    }

    @Getter
    @Setter
    @Entity(name = "PayrollAdjustment")
    @Table(name = "pay_payroll_adjustment")
    public static class PayrollAdjustment extends BaseEntity {
        public enum Type {EARNING, DEDUCTION}

        public enum Status {OPEN, APPLIED, CANCELLED}

        @Column(name = "employee_id", nullable = false)
        private Long employeeId;
        @Enumerated(EnumType.STRING)
        @Column(name = "adjustment_type", nullable = false, length = 20)
        private Type adjustmentType;
        @Column(nullable = false, length = 40)
        private String code;
        @Column(nullable = false, length = 300)
        private String description;
        @Column(nullable = false, precision = 14, scale = 2)
        private BigDecimal amount;
        @Column(name = "target_period", nullable = false, length = 7)
        private String targetPeriod;
        @Column(name = "related_run_id")
        private Long relatedRunId;
        @Column(nullable = false, length = 1000)
        private String reason;
        @Enumerated(EnumType.STRING)
        @Column(nullable = false, length = 20)
        private Status status;
        @Column(name = "applied_run_id")
        private Long appliedRunId;
    }
}
