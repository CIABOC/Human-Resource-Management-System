package lk.gov.ciaboc.hrms.masterdata;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SettingsService {
    private final SystemSettingRepository repo;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public String get(String key, String defaultValue) {
        return repo.findById(key).map(SystemSetting::getValue).orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        try {
            return new BigDecimal(get(key, defaultValue.toPlainString()).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBool(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)).trim());
    }

    @Transactional(readOnly = true)
    public List<SystemSetting> all() {
        return repo.findAll(Sort.by("category", "key"));
    }

    @Transactional
    public SystemSetting update(String key, String value) {
        SystemSetting s = repo.findById(key).orElseThrow(() -> ApiException.notFound("Setting", key));
        validate(s, value);
        String old = s.getValue();
        s.setValue(value.trim());
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(CurrentUser.username());
        audit.log(AuditAction.CONFIG_CHANGE, "SystemSetting", key, "Setting " + key + " changed", old, value);
        return s;
    }

    private static void validate(SystemSetting s, String value) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("Value is required");
        try {
            switch (s.getValueType()) {
                case "INT" -> Integer.parseInt(value.trim());
                case "DECIMAL" -> new BigDecimal(value.trim());
                case "BOOLEAN" -> {
                    if (!value.trim().equalsIgnoreCase("true") && !value.trim().equalsIgnoreCase("false"))
                        throw new IllegalArgumentException();
                }
                default -> {
                }
            }
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Value must be of type " + s.getValueType());
        }
    }
}
