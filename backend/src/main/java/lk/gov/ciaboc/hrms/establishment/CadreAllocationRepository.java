package lk.gov.ciaboc.hrms.establishment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CadreAllocationRepository extends JpaRepository<CadreAllocation, Long> {
    @Query("select c from CadreAllocation c where c.effectiveFrom <= :d and (c.effectiveTo is null or c.effectiveTo > :d)")
    List<CadreAllocation> effectiveOn(@Param("d") LocalDate date);

    @Query("select c from CadreAllocation c where c.designationId = :des and c.orgUnitCode = :unit " +
            "and c.effectiveFrom <= :d and (c.effectiveTo is null or c.effectiveTo > :d)")
    Optional<CadreAllocation> effectiveOn(@Param("des") Long designationId, @Param("unit") String orgUnitCode,
                                          @Param("d") LocalDate date);

    List<CadreAllocation> findByDesignationIdAndOrgUnitCodeOrderByEffectiveFromDesc(Long designationId, String orgUnitCode);
}
