package lk.gov.ciaboc.hrms.recruitment.vacancy;

import lk.gov.ciaboc.hrms.recruitment.vacancy.VacancyEntities.AlertDelivery;
import lk.gov.ciaboc.hrms.recruitment.vacancy.VacancyEntities.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {
    List<AlertRule> findAllByOrderByLeadDaysDescIdAsc();

    List<AlertRule> findByActiveTrueOrderByLeadDaysDesc();
}

interface AlertDeliveryRepository extends JpaRepository<AlertDelivery, Long> {
    List<AlertDelivery> findByVacancyIdOrderByIdDesc(Long vacancyId);

    List<AlertDelivery> findTop500ByOrderByIdDesc();

    boolean existsByVacancyIdAndRuleId(Long vacancyId, Long ruleId);
}
