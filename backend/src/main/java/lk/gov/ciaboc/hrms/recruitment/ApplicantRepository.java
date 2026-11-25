package lk.gov.ciaboc.hrms.recruitment;

import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.Applicant;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.ApplicantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApplicantRepository extends JpaRepository<Applicant, Long> {
    List<Applicant> findByVacancyIdOrderByIdAsc(Long vacancyId);

    List<Applicant> findByVacancyIdAndStatusOrderByIdAsc(Long vacancyId, ApplicantStatus status);

    List<Applicant> findByVacancyIdAndStatusIn(Long vacancyId, Collection<ApplicantStatus> statuses);

    boolean existsByVacancyIdAndNic(Long vacancyId, String nic);

    long countByVacancyIdAndStatus(Long vacancyId, ApplicantStatus status);

    long countByVacancyId(Long vacancyId);

    @Query("select a from RecApplicant a where (:vacancyId is null or a.vacancyId = :vacancyId) and (:status is null or a.status = :status) " +
            "and (:q is null or lower(a.fullName) like lower(concat('%', :q, '%')) or lower(a.applicationNo) like lower(concat('%', :q, '%')) " +
            "or lower(a.nic) like lower(concat('%', :q, '%'))) order by a.id desc")
    List<Applicant> search(@Param("vacancyId") Long vacancyId, @Param("status") ApplicantStatus status, @Param("q") String q);

    /** Rows of [vacancyId, status, count]. */
    @Query("select a.vacancyId, a.status, count(a) from RecApplicant a group by a.vacancyId, a.status")
    List<Object[]> countByVacancyAndStatus();

    @Query("select max(a.id) from RecApplicant a")
    Optional<Long> maxId();
}
