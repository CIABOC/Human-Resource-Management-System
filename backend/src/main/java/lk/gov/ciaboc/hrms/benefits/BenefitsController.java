package lk.gov.ciaboc.hrms.benefits;

import lk.gov.ciaboc.hrms.benefits.BenefitsEntities.InsuranceScheme;
import lk.gov.ciaboc.hrms.benefits.BenefitsService.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Benefits and insurance API. Management screens need BEN_VIEW / BEN_MANAGE and are limited to the caller's data
 * scope; self-service endpoints act on the caller's own employee record only.
 */
@RestController
@RequestMapping("/api/benefits")
@RequiredArgsConstructor
public class BenefitsController {
    private final BenefitsService service;

    // ---- schemes
    @GetMapping("/schemes")
    @PreAuthorize("hasAnyAuthority('BEN_VIEW','SELF_SERVICE')")
    public List<InsuranceScheme> schemes(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return service.schemes(activeOnly);
    }

    @PostMapping("/schemes")
    @PreAuthorize("hasAuthority('BEN_MANAGE')")
    public InsuranceScheme createScheme(@RequestBody SchemeRequest r) {
        return service.createScheme(r);
    }

    @PutMapping("/schemes/{id}")
    @PreAuthorize("hasAuthority('BEN_MANAGE')")
    public InsuranceScheme updateScheme(@PathVariable Long id, @RequestBody SchemeRequest r) {
        return service.updateScheme(id, r);
    }

    @GetMapping("/eligibility")
    @PreAuthorize("hasAuthority('BEN_VIEW')")
    public List<Eligibility> eligibility(@RequestParam Long employeeId) {
        return service.eligibility(employeeId);
    }

    // ---- coverage / policies
    @GetMapping("/coverages")
    @PreAuthorize("hasAuthority('BEN_VIEW')")
    public List<CoverageView> coverages(@RequestParam(required = false) Long schemeId, @RequestParam(required = false) String status,
                                        @RequestParam(required = false) Long employeeId) {
        return service.coverages(schemeId, status, employeeId);
    }

    @GetMapping("/coverages/expiring")
    @PreAuthorize("hasAuthority('BEN_VIEW')")
    public List<CoverageView> expiring(@RequestParam(defaultValue = "30") int days) {
        return service.expiring(days);
    }

    @PostMapping("/coverages")
    @PreAuthorize("hasAuthority('BEN_MANAGE')")
    public CoverageView createCoverage(@RequestBody CoverageRequest r) {
        return service.createCoverage(r);
    }

    @PutMapping("/coverages/{id}")
    @PreAuthorize("hasAuthority('BEN_MANAGE')")
    public CoverageView updateCoverage(@PathVariable Long id, @RequestBody CoverageRequest r) {
        return service.updateCoverage(id, r);
    }

    @PostMapping("/coverages/{id}/verify")
    @PreAuthorize("hasAuthority('BEN_MANAGE')")
    public ProviderCheck verify(@PathVariable Long id) {
        return service.verifyWithProvider(id);
    }

    @PostMapping("/coverages/expiry-check")
    @PreAuthorize("hasAuthority('BEN_MANAGE')")
    public ExpiryCheckResult expiryCheck() {
        return service.runExpiryCheck();
    }

    // ---- claims
    @GetMapping("/claims")
    @PreAuthorize("hasAuthority('BEN_VIEW')")
    public List<ClaimView> claims(@RequestParam(required = false) String status) {
        return service.claims(status);
    }

    @PostMapping("/claims")
    @PreAuthorize("hasAuthority('BEN_MANAGE')")
    public ClaimView createClaim(@RequestBody ClaimRequest r) {
        return service.createClaim(r, false);
    }

    @PostMapping("/claims/{id}/status")
    @PreAuthorize("hasAuthority('BEN_MANAGE')")
    public ClaimView claimStatus(@PathVariable Long id, @RequestBody ClaimStatusRequest r) {
        return service.updateClaimStatus(id, r);
    }

    // ---- self-service
    @GetMapping("/my")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public MyBenefits mine() {
        return service.mine();
    }

    @PostMapping("/my/claims")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public ClaimView myClaim(@RequestBody ClaimRequest r) {
        return service.createClaim(r, true);
    }
}
