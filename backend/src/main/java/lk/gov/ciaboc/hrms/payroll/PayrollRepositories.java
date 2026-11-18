package lk.gov.ciaboc.hrms.payroll;

import lk.gov.ciaboc.hrms.payroll.PayrollEntities.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface SalaryScaleRepository extends JpaRepository<SalaryScale, Long> {
    List<SalaryScale> findByCodeOrderByEffectiveFromDesc(String code);

    @Query("select s from SalaryScale s where s.effectiveFrom <= :d and (s.effectiveTo is null or s.effectiveTo > :d) order by s.code")
    List<SalaryScale> effectiveOn(@Param("d") LocalDate date);

    @Query("select s from SalaryScale s where s.code = :code and s.effectiveFrom <= :d and (s.effectiveTo is null or s.effectiveTo > :d)")
    Optional<SalaryScale> effectiveOn(@Param("code") String code, @Param("d") LocalDate date);

    boolean existsByCode(String code);
}

interface AllowanceTypeRepository extends JpaRepository<AllowanceType, Long> {
    List<AllowanceType> findAllByOrderByCodeAsc();

    boolean existsByCode(String code);
}

interface AllowanceRateRepository extends JpaRepository<AllowanceRate, Long> {
    List<AllowanceRate> findByAllowanceTypeIdOrderByEffectiveFromDesc(Long allowanceTypeId);

    @Query("select r from AllowanceRate r where r.effectiveFrom <= :d and (r.effectiveTo is null or r.effectiveTo > :d)")
    List<AllowanceRate> effectiveOn(@Param("d") LocalDate date);
}

interface DeductionTypeRepository extends JpaRepository<DeductionType, Long> {
    List<DeductionType> findAllByOrderByCategoryAscCodeAsc();

    boolean existsByCode(String code);
}

interface DeductionRateRepository extends JpaRepository<DeductionRate, Long> {
    List<DeductionRate> findByDeductionTypeIdOrderByEffectiveFromDesc(Long deductionTypeId);

    @Query("select r from DeductionRate r where r.effectiveFrom <= :d and (r.effectiveTo is null or r.effectiveTo > :d)")
    List<DeductionRate> effectiveOn(@Param("d") LocalDate date);
}

interface EmployeeSalaryRepository extends JpaRepository<EmployeeSalary, Long> {
    List<EmployeeSalary> findByEmployeeIdOrderByEffectiveFromDesc(Long employeeId);

    @Query("select s from EmployeeSalary s where s.effectiveFrom <= :d and (s.effectiveTo is null or s.effectiveTo > :d)")
    List<EmployeeSalary> effectiveOn(@Param("d") LocalDate date);

    @Query("select s from EmployeeSalary s where s.employeeId = :e and s.effectiveFrom <= :d and (s.effectiveTo is null or s.effectiveTo > :d)")
    Optional<EmployeeSalary> effectiveOn(@Param("e") Long employeeId, @Param("d") LocalDate date);

    @Query("select s from EmployeeSalary s where s.employeeId = :e and s.effectiveFrom > :d")
    List<EmployeeSalary> startingAfter(@Param("e") Long employeeId, @Param("d") LocalDate date);

    @Query("select s from EmployeeSalary s where s.scaleCode = :code and (s.effectiveTo is null or s.effectiveTo > :d)")
    List<EmployeeSalary> openOnScale(@Param("code") String scaleCode, @Param("d") LocalDate date);
}

interface EmployeeAllowanceRepository extends JpaRepository<EmployeeAllowance, Long> {
    List<EmployeeAllowance> findByEmployeeIdOrderByEffectiveFromDesc(Long employeeId);

    @Query("select a from EmployeeAllowance a where a.effectiveFrom <= :d and (a.effectiveTo is null or a.effectiveTo > :d)")
    List<EmployeeAllowance> effectiveOn(@Param("d") LocalDate date);
}

interface DeductionMembershipRepository extends JpaRepository<DeductionMembership, Long> {
    List<DeductionMembership> findByEmployeeIdOrderByEffectiveFromDesc(Long employeeId);

    List<DeductionMembership> findByDeductionTypeIdOrderByEmployeeIdAscEffectiveFromDesc(Long deductionTypeId);

    @Query("select m from DeductionMembership m where m.effectiveFrom <= :d and (m.effectiveTo is null or m.effectiveTo > :d)")
    List<DeductionMembership> effectiveOn(@Param("d") LocalDate date);
}

interface EmployeeLoanRepository extends JpaRepository<EmployeeLoan, Long> {
    List<EmployeeLoan> findByEmployeeIdOrderByStartDateDesc(Long employeeId);

    List<EmployeeLoan> findAllByOrderByStatusAscStartDateDesc();

    List<EmployeeLoan> findByStatusOrderByStartDateDesc(EmployeeLoan.Status status);

    long countByLoanType(String loanType);
}

interface LoanTransactionRepository extends JpaRepository<LoanTransaction, Long> {
    List<LoanTransaction> findByLoanIdOrderByTxnDateAscIdAsc(Long loanId);
}

interface LoanAdjustmentRepository extends JpaRepository<LoanAdjustment, Long> {
    List<LoanAdjustment> findByLoanIdOrderByIdDesc(Long loanId);

    List<LoanAdjustment> findAllByOrderByIdDesc();

    boolean existsByLoanIdAndStatusIn(Long loanId, Collection<LoanAdjustment.Status> statuses);
}

interface PayrollRunRepository extends JpaRepository<PayrollRun, Long> {
    List<PayrollRun> findAllByOrderByPeriodDesc();

    Optional<PayrollRun> findByPeriod(String period);

    boolean existsByStatusNot(PayrollRun.Status status);

    Optional<PayrollRun> findTopByStatusOrderByPeriodDesc(PayrollRun.Status status);

    Optional<PayrollRun> findTopByOrderByPeriodDesc();
}

interface PayrollLineRepository extends JpaRepository<PayrollLine, Long> {
    List<PayrollLine> findByRunIdOrderByEmployeeNoAsc(Long runId);

    @Query("select l from PayrollLine l, PayrollRun r where r.id = l.runId and r.status = lk.gov.ciaboc.hrms.payroll.PayrollEntities.PayrollRun.Status.FINALIZED " +
            "and l.employeeId = :e order by r.period desc")
    List<PayrollLine> finalizedForEmployee(@Param("e") Long employeeId);

    @Modifying
    @Query("delete from PayrollLine l where l.runId = :run")
    int deleteByRun(@Param("run") Long runId);
}

interface PayrollLineItemRepository extends JpaRepository<PayrollLineItem, Long> {
    List<PayrollLineItem> findByLineIdOrderBySortOrderAsc(Long lineId);

    List<PayrollLineItem> findByLineIdIn(Collection<Long> lineIds);

    @Query("select count(i) > 0 from PayrollLineItem i, PayrollLine l, PayrollRun r where i.lineId = l.id and l.runId = r.id " +
            "and r.status = lk.gov.ciaboc.hrms.payroll.PayrollEntities.PayrollRun.Status.SUBMITTED " +
            "and i.sourceType = lk.gov.ciaboc.hrms.payroll.PayrollCalculator.Source.LOAN and i.sourceId = :loan")
    boolean loanInSubmittedRun(@Param("loan") Long loanId);

    /** Deduction and employer-contribution history of an employee from finalized runs (FR-PAY-011). */
    @Query("select r.period, i from PayrollLineItem i, PayrollLine l, PayrollRun r where i.lineId = l.id and l.runId = r.id " +
            "and r.status = lk.gov.ciaboc.hrms.payroll.PayrollEntities.PayrollRun.Status.FINALIZED and l.employeeId = :e " +
            "and i.componentType <> lk.gov.ciaboc.hrms.payroll.PayrollCalculator.ComponentType.EARNING order by r.period desc, i.sortOrder")
    List<Object[]> deductionHistory(@Param("e") Long employeeId);

    @Modifying
    @Query("delete from PayrollLineItem i where i.lineId in (select l.id from PayrollLine l where l.runId = :run)")
    int deleteByRun(@Param("run") Long runId);
}

interface PayrollAdjustmentRepository extends JpaRepository<PayrollAdjustment, Long> {
    List<PayrollAdjustment> findAllByOrderByIdDesc();

    List<PayrollAdjustment> findByStatusOrderByIdDesc(PayrollAdjustment.Status status);

    @Query("select a from PayrollAdjustment a where a.status = lk.gov.ciaboc.hrms.payroll.PayrollEntities.PayrollAdjustment.Status.OPEN " +
            "and a.targetPeriod <= :period")
    List<PayrollAdjustment> openUpTo(@Param("period") String period);
}
