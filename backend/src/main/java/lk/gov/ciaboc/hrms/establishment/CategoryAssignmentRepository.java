package lk.gov.ciaboc.hrms.establishment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CategoryAssignmentRepository extends JpaRepository<Categorization.Assignment, Long> {
    @Query("select a from DesignationCategoryAssignment a where a.effectiveFrom <= :d and (a.effectiveTo is null or a.effectiveTo > :d)")
    List<Categorization.Assignment> effectiveOn(@Param("d") LocalDate date);

    @Query("select a from DesignationCategoryAssignment a where a.designationId = :des and a.effectiveFrom <= :d " +
            "and (a.effectiveTo is null or a.effectiveTo > :d)")
    Optional<Categorization.Assignment> effectiveOn(@Param("des") Long designationId, @Param("d") LocalDate date);

    /** Assignments starting after a date (future-dated, already approved). */
    @Query("select a from DesignationCategoryAssignment a where a.designationId = :des and a.effectiveFrom > :d")
    List<Categorization.Assignment> startingAfter(@Param("des") Long designationId, @Param("d") LocalDate date);
}
