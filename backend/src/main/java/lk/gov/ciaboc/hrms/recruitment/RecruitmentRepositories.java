package lk.gov.ciaboc.hrms.recruitment;

import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    List<Announcement> findAllByOrderByPublishDateDescIdDesc();

    List<Announcement> findByPublishedTrueOrderByPublishDateDesc();
}


interface ApplicantStatusChangeRepository extends JpaRepository<ApplicantStatusChange, Long> {
    List<ApplicantStatusChange> findByApplicantIdOrderByIdAsc(Long applicantId);
}

interface ApplicantDocumentRepository extends JpaRepository<ApplicantDocument, Long> {
    List<ApplicantDocument> findByApplicantIdOrderByIdDesc(Long applicantId);
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
