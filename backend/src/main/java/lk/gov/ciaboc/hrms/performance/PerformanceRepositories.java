package lk.gov.ciaboc.hrms.performance;

import lk.gov.ciaboc.hrms.performance.PerformanceEntities.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface EvaluationPeriodRepository extends JpaRepository<EvaluationPeriod, Long> {
    List<EvaluationPeriod> findAllByOrderByStartDateDesc();

    List<EvaluationPeriod> findByStatus(PeriodStatus status);

    boolean existsByCode(String code);
}

interface EvaluationModelRepository extends JpaRepository<EvaluationModel, Long> {
    List<EvaluationModel> findAllByOrderByCodeAsc();

    boolean existsByCode(String code);
}

interface EvaluationCriterionRepository extends JpaRepository<EvaluationCriterion, Long> {
    List<EvaluationCriterion> findByModelIdOrderBySortOrderAscIdAsc(Long modelId);
}

interface CategoryModelRepository extends JpaRepository<CategoryModel, Long> {
    Optional<CategoryModel> findByEmployeeCategory(String category);

    List<CategoryModel> findAllByOrderByEmployeeCategoryAsc();

    long countByModelId(Long modelId);
}

interface EvaluationRepository extends JpaRepository<Evaluation, Long> {
    List<Evaluation> findByPeriodIdOrderByIdAsc(Long periodId);

    List<Evaluation> findByEvaluatorUserIdOrderByIdDesc(Long userId);

    List<Evaluation> findByEmployeeIdOrderByIdDesc(Long employeeId);

    List<Evaluation> findByEmployeeIdAndStatusOrderByIdDesc(Long employeeId, EvaluationStatus status);

    List<Evaluation> findByStatusIn(Collection<EvaluationStatus> statuses);

    List<Evaluation> findAllByOrderByIdDesc();

    boolean existsByPeriodIdAndEmployeeId(Long periodId, Long employeeId);

    long countByModelId(Long modelId);

    long countByEvaluatorUserIdAndStatusIn(Long userId, Collection<EvaluationStatus> statuses);
}

interface EvaluationRatingRepository extends JpaRepository<EvaluationRating, Long> {
    List<EvaluationRating> findByEvaluationId(Long evaluationId);
}
