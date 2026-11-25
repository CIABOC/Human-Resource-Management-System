package lk.gov.ciaboc.hrms.recruitment.vacancy;

import lk.gov.ciaboc.hrms.recruitment.vacancy.VacancyEntities.TriggerConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TriggerConfigRepository extends JpaRepository<TriggerConfig, Long> {
    Optional<TriggerConfig> findByCode(String code);

    List<TriggerConfig> findAllByOrderBySortOrderAscCodeAsc();
}
