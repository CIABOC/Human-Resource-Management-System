package lk.gov.ciaboc.hrms.benefits;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Default provider client used until a provider API is approved and connected (FR-BEN-006). */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class NoOpInsuranceProviderClient implements InsuranceProviderClient {
    @Override
    public String name() {
        return "none";
    }

    @Override
    public Optional<PolicyStatus> lookupPolicy(String schemeCode, String policyNo) {
        return Optional.empty();
    }
}
