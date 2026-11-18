package lk.gov.ciaboc.hrms.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IntegrationConfigRepository extends JpaRepository<IntegrationConfig, Long> {
    Optional<IntegrationConfig> findByCode(String code);
}
