package lk.gov.ciaboc.hrms.establishment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface OrgUnitRepository extends JpaRepository<OrgUnit, Long> {
    @Query("select o from OrgUnit o where o.effectiveFrom <= :d and (o.effectiveTo is null or o.effectiveTo > :d) " +
            "order by o.sortOrder, o.name")
    List<OrgUnit> effectiveOn(@Param("d") LocalDate date);

    @Query("select o from OrgUnit o where o.code = :code and o.effectiveFrom <= :d and (o.effectiveTo is null or o.effectiveTo > :d)")
    Optional<OrgUnit> effectiveOn(@Param("code") String code, @Param("d") LocalDate date);

    List<OrgUnit> findByCodeOrderByEffectiveFromDesc(String code);

    boolean existsByCode(String code);
}
