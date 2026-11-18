package lk.gov.ciaboc.hrms.establishment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DesignationRepository extends JpaRepository<Designation, Long> {
    Optional<Designation> findByCode(String code);

    List<Designation> findAllByOrderByNameAsc();

    List<Designation> findByActiveTrueOrderByNameAsc();

    long countByGradeAndActiveTrue(String grade);

    long countByServiceCategoryAndActiveTrue(String serviceCategory);
}
