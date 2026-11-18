package lk.gov.ciaboc.hrms.benefits;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Future integration with external insurance providers (FR-BEN-006, INT-06). The default implementation
 * ({@link NoOpInsuranceProviderClient}) does nothing; a real client is added as another bean with a higher priority
 * and is only called when integration_config 'INSURANCE_PROVIDER' is enabled. Only the minimum necessary data
 * (scheme code and policy number) is sent to the provider.
 */
public interface InsuranceProviderClient {

    record PolicyStatus(String policyNo, String status, LocalDate expiryDate, BigDecimal coverageAmount, String message) {
    }

    record ClaimStatus(String providerRef, String status, BigDecimal amountApproved, String message) {
    }

    /** Short name used in logs and on screens. */
    String name();

    /** Looks up a policy with the provider; empty when the provider has no information. */
    Optional<PolicyStatus> lookupPolicy(String schemeCode, String policyNo);

    /** Looks up a claim with the provider; empty when not supported. */
    default Optional<ClaimStatus> lookupClaim(String schemeCode, String providerRef) {
        return Optional.empty();
    }
}
