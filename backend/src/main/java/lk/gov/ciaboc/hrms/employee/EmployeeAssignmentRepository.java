package lk.gov.ciaboc.hrms.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EmployeeAssignmentRepository extends JpaRepository<EmployeeAssignment, Long> {
    List<EmployeeAssignment> findByEmployeeIdOrderByEffectiveFromDesc(Long employeeId);

    @Query("select a from EmployeeAssignment a where a.employeeId = :emp and a.effectiveFrom <= :d " +
            "and (a.effectiveTo is null or a.effectiveTo > :d)")
    Optional<EmployeeAssignment> effectiveOn(@Param("emp") Long employeeId, @Param("d") LocalDate date);

    Optional<EmployeeAssignment> findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(Long employeeId);

    /** Occupied cadre as at a date: rows of [designationId, orgUnitCode, count]. */
    @Query("select a.designationId, a.orgUnitCode, count(a) from EmployeeAssignment a where a.effectiveFrom <= :d " +
            "and (a.effectiveTo is null or a.effectiveTo > :d) group by a.designationId, a.orgUnitCode")
    List<Object[]> occupancy(@Param("d") LocalDate date);

    @Query("select count(a) from EmployeeAssignment a where a.orgUnitCode = :unit and a.effectiveFrom <= :d " +
            "and (a.effectiveTo is null or a.effectiveTo > :d)")
    long countInUnitOn(@Param("unit") String orgUnitCode, @Param("d") LocalDate date);

    @Query("select count(a) from EmployeeAssignment a where a.designationId = :des and a.effectiveFrom <= :d " +
            "and (a.effectiveTo is null or a.effectiveTo > :d)")
    long countWithDesignationOn(@Param("des") Long designationId, @Param("d") LocalDate date);

    @Query("select count(a) from EmployeeAssignment a where a.positionId = :pos and a.effectiveFrom <= :d " +
            "and (a.effectiveTo is null or a.effectiveTo > :d)")
    long countInPositionOn(@Param("pos") Long positionId, @Param("d") LocalDate date);
}
