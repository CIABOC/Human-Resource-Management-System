package lk.gov.ciaboc.hrms.integration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IntegrationService {
    public static final String EMAIL = "EMAIL";
    public static final String SMS = "SMS";
    public static final String ATTENDANCE_DEVICE = "ATTENDANCE_DEVICE";
    public static final String INSURANCE_PROVIDER = "INSURANCE_PROVIDER";

    private final IntegrationConfigRepository repo;

    @Transactional(readOnly = true)
    public boolean isEnabled(String code) {
        return repo.findByCode(code).map(IntegrationConfig::isEnabled).orElse(false);
    }
}
