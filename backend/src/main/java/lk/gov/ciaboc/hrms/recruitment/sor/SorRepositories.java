package lk.gov.ciaboc.hrms.recruitment.sor;

import lk.gov.ciaboc.hrms.recruitment.sor.SorEntities.SelectionMethod;
import lk.gov.ciaboc.hrms.recruitment.sor.SorEntities.SorTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface SorTemplateRepository extends JpaRepository<SorTemplate, Long> {
    Optional<SorTemplate> findByLanguage(String language);

    List<SorTemplate> findAllByOrderByLanguageAsc();
}

interface SelectionMethodRepository extends JpaRepository<SelectionMethod, Long> {
    Optional<SelectionMethod> findByCode(String code);

    List<SelectionMethod> findAllByOrderBySortOrderAscCodeAsc();
}
