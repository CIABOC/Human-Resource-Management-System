package lk.gov.ciaboc.hrms.recruitment;

import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.Vacancy;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.VacancyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VacancyRepository extends JpaRepository<Vacancy, Long> {
    List<Vacancy> findAllByOrderByIdDesc();

    List<Vacancy> findByStatusOrderByClosingDateAsc(VacancyStatus status);

    List<Vacancy> findByDesignationIdAndOrgUnitCodeAndStatusIn(Long designationId, String orgUnitCode, Collection<VacancyStatus> statuses);

    List<Vacancy> findByStatusIn(Collection<VacancyStatus> statuses);

    List<Vacancy> findByStatusInOrderByExpectedDateAscIdAsc(Collection<VacancyStatus> statuses);

    List<Vacancy> findByVacatedByEmployeeIdAndStatusIn(Long employeeId, Collection<VacancyStatus> statuses);

    List<Vacancy> findByPositionIdAndStatusIn(Long positionId, Collection<VacancyStatus> statuses);

    List<Vacancy> findBySorId(Long sorId);

    Optional<Vacancy> findBySourceKey(String sourceKey);

    Optional<Vacancy> findByReferenceNo(String referenceNo);

    boolean existsByReferenceNo(String referenceNo);

    long countByStatus(VacancyStatus status);
}
