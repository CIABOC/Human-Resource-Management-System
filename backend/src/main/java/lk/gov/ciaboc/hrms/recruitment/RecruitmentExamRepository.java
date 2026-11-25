package lk.gov.ciaboc.hrms.recruitment;

import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.RecruitmentExam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecruitmentExamRepository extends JpaRepository<RecruitmentExam, Long> {
    List<RecruitmentExam> findByVacancyIdOrderByExamDateAscIdAsc(Long vacancyId);
}
