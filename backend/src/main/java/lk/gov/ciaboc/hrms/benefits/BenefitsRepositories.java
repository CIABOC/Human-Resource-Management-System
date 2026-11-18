package lk.gov.ciaboc.hrms.benefits;

import lk.gov.ciaboc.hrms.benefits.BenefitsEntities.InsuranceClaim;
import lk.gov.ciaboc.hrms.benefits.BenefitsEntities.InsuranceCoverage;
import lk.gov.ciaboc.hrms.benefits.BenefitsEntities.InsuranceScheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

interface InsuranceSchemeRepository extends JpaRepository<InsuranceScheme, Long> {
    List<InsuranceScheme> findAllByOrderByCodeAsc();

    List<InsuranceScheme> findByActiveTrueOrderByCodeAsc();

    boolean existsByCode(String code);

    long countByCategory(String category);
}

interface InsuranceCoverageRepository extends JpaRepository<InsuranceCoverage, Long> {
    List<InsuranceCoverage> findByEmployeeIdOrderByStartDateDesc(Long employeeId);

    List<InsuranceCoverage> findAllByOrderByIdDesc();

    List<InsuranceCoverage> findByEmployeeIdAndSchemeIdAndStatus(Long employeeId, Long schemeId, InsuranceCoverage.Status status);

    /** Active coverages overlapping [from, to]. */
    @Query("select c from InsuranceCoverage c where c.status = lk.gov.ciaboc.hrms.benefits.BenefitsEntities.InsuranceCoverage.Status.ACTIVE " +
            "and c.startDate <= :to and (c.expiryDate is null or c.expiryDate >= :from)")
    List<InsuranceCoverage> activeBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select c from InsuranceCoverage c where c.status = lk.gov.ciaboc.hrms.benefits.BenefitsEntities.InsuranceCoverage.Status.ACTIVE " +
            "and c.expiryDate is not null and c.expiryDate >= :from and c.expiryDate <= :to order by c.expiryDate")
    List<InsuranceCoverage> expiringBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select c from InsuranceCoverage c where c.status = lk.gov.ciaboc.hrms.benefits.BenefitsEntities.InsuranceCoverage.Status.ACTIVE " +
            "and c.expiryDate is not null and c.expiryDate < :d")
    List<InsuranceCoverage> lapsedBefore(@Param("d") LocalDate date);
}

interface InsuranceClaimRepository extends JpaRepository<InsuranceClaim, Long> {
    List<InsuranceClaim> findAllByOrderByIdDesc();

    List<InsuranceClaim> findByEmployeeIdOrderByIdDesc(Long employeeId);
}
