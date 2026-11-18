package lk.gov.ciaboc.hrms.recruitment;

import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface VacancyRepository extends JpaRepository<Vacancy, Long> {
    List<Vacancy> findAllByOrderByIdDesc();

    List<Vacancy> findByStatusOrderByClosingDateAsc(VacancyStatus status);

    List<Vacancy> findByDesignationIdAndOrgUnitCodeAndStatusIn(Long designationId, String orgUnitCode, Collection<VacancyStatus> statuses);

    List<Vacancy> findByStatusIn(Collection<VacancyStatus> statuses);

    boolean existsByReferenceNo(String referenceNo);

    long countByStatus(VacancyStatus status);
}

interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    List<Announcement> findAllByOrderByPublishDateDescIdDesc();

    List<Announcement> findByPublishedTrueOrderByPublishDateDesc();
}

interface ApplicantRepository extends JpaRepository<Applicant, Long> {
    List<Applicant> findByVacancyIdOrderByIdAsc(Long vacancyId);

    List<Applicant> findByVacancyIdAndStatusOrderByIdAsc(Long vacancyId, ApplicantStatus status);

    List<Applicant> findByVacancyIdAndStatusIn(Long vacancyId, Collection<ApplicantStatus> statuses);

    boolean existsByVacancyIdAndNic(Long vacancyId, String nic);

    long countByVacancyIdAndStatus(Long vacancyId, ApplicantStatus status);

    @Query("select a from RecApplicant a where (:vacancyId is null or a.vacancyId = :vacancyId) and (:status is null or a.status = :status) " +
            "and (:q is null or lower(a.fullName) like lower(concat('%', :q, '%')) or lower(a.applicationNo) like lower(concat('%', :q, '%')) " +
            "or lower(a.nic) like lower(concat('%', :q, '%'))) order by a.id desc")
    List<Applicant> search(@Param("vacancyId") Long vacancyId, @Param("status") ApplicantStatus status, @Param("q") String q);

    @Query("select max(a.id) from RecApplicant a")
    Optional<Long> maxId();
}

interface ApplicantStatusChangeRepository extends JpaRepository<ApplicantStatusChange, Long> {
    List<ApplicantStatusChange> findByApplicantIdOrderByIdAsc(Long applicantId);
}

interface ApplicantDocumentRepository extends JpaRepository<ApplicantDocument, Long> {
    List<ApplicantDocument> findByApplicantIdOrderByIdDesc(Long applicantId);
}

interface RecruitmentExamRepository extends JpaRepository<RecruitmentExam, Long> {
    List<RecruitmentExam> findByVacancyIdOrderByExamDateAscIdAsc(Long vacancyId);
}

interface ExamResultRepository extends JpaRepository<ExamResult, Long> {
    List<ExamResult> findByExamId(Long examId);

    List<ExamResult> findByApplicantId(Long applicantId);

    Optional<ExamResult> findByExamIdAndApplicantId(Long examId, Long applicantId);
}

interface InterviewRepository extends JpaRepository<Interview, Long> {
    List<Interview> findByVacancyIdOrderByInterviewDateAscInterviewTimeAsc(Long vacancyId);

    List<Interview> findByApplicantIdOrderByInterviewDateAsc(Long applicantId);
}

interface SelectionListRepository extends JpaRepository<SelectionList, Long> {
    List<SelectionList> findByVacancyIdOrderByIdDesc(Long vacancyId);
}

interface SelectionItemRepository extends JpaRepository<SelectionItem, Long> {
    List<SelectionItem> findBySelectionIdOrderByRankNoAsc(Long selectionId);

    void deleteBySelectionId(Long selectionId);
}

interface RecruitmentLetterTemplateRepository extends JpaRepository<LetterTemplate, Long> {
    List<LetterTemplate> findAllByOrderByCategoryAscCodeAscLanguageAsc();

    Optional<LetterTemplate> findByCodeAndLanguage(String code, String language);
}

interface CorrespondenceLogRepository extends JpaRepository<CorrespondenceLog, Long> {
    List<CorrespondenceLog> findByApplicantIdOrderByIdDesc(Long applicantId);

    List<CorrespondenceLog> findByVacancyIdOrderByIdDesc(Long vacancyId);
}
