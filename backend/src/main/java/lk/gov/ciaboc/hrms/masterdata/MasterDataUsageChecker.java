package lk.gov.ciaboc.hrms.masterdata;

/**
 * Implemented by modules that reference master-data codes, so a value in use cannot be deactivated without a
 * controlled migration (FR-MDM-003).
 */
public interface MasterDataUsageChecker {
    /** @return number of records using the given category/code (0 if none or category not relevant). */
    long usageCount(String category, String code);
}
