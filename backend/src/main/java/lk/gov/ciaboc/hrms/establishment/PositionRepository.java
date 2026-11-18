package lk.gov.ciaboc.hrms.establishment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PositionRepository extends JpaRepository<Position, Long> {
    Optional<Position> findByCode(String code);

    List<Position> findAllByOrderByCodeAsc();

    List<Position> findByOrgUnitCodeAndActiveTrue(String orgUnitCode);

    long countByDesignationIdAndActiveTrue(Long designationId);

    long countByOrgUnitCodeAndActiveTrue(String orgUnitCode);
}
