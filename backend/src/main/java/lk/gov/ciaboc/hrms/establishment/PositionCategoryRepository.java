package lk.gov.ciaboc.hrms.establishment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PositionCategoryRepository extends JpaRepository<PositionCategory, Long> {
    List<PositionCategory> findAllByOrderBySortOrderAscNameAsc();

    Optional<PositionCategory> findByCode(String code);
}
