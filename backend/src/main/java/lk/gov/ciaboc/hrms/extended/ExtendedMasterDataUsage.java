package lk.gov.ciaboc.hrms.extended;

import lk.gov.ciaboc.hrms.masterdata.MasterDataUsageChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Master-data codes referenced by extended-scope records (FR-MDM-003). */
@Component
@RequiredArgsConstructor
public class ExtendedMasterDataUsage implements MasterDataUsageChecker {
    private final JdbcTemplate jdbc;

    @Override
    public long usageCount(String category, String code) {
        return switch (category) {
            case "SEPARATION_REASON" -> count("SELECT (SELECT COUNT(*) FROM ext_separation_case WHERE separation_reason = ?) "
                    + "+ (SELECT COUNT(*) FROM ext_separation_type WHERE default_separation_reason = ? AND active)", code, code);
            case "EMPLOYEE_STATUS" -> count("SELECT COUNT(*) FROM ext_separation_type WHERE employee_status = ? AND active", code);
            case "DOCUMENT_TYPE" -> count("SELECT COUNT(*) FROM ext_digitization_item WHERE document_type = ? "
                    + "AND status IN ('UPLOADED','VERIFIED','REJECTED')", code);
            default -> 0;
        };
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
