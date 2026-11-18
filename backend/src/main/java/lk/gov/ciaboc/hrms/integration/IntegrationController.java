package lk.gov.ciaboc.hrms.integration;

import jakarta.validation.constraints.Size;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/integrations")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SYS_CONFIG')")
public class IntegrationController {
    private final IntegrationConfigRepository repo;
    private final AuditService audit;

    @GetMapping
    public List<IntegrationConfig> list() {
        return repo.findAll(Sort.by("refId"));
    }

    public record UpdateIntegration(boolean enabled, @Size(max = 500) String endpointUrl, @Size(max = 1000) String notes) {
    }

    @PutMapping("/{id}")
    @Transactional
    public IntegrationConfig update(@PathVariable Long id, @RequestBody UpdateIntegration req) {
        IntegrationConfig c = repo.findById(id).orElseThrow(() -> ApiException.notFound("Integration", id));
        Map<String, Object> before = Map.of("enabled", c.isEnabled(), "endpointUrl", String.valueOf(c.getEndpointUrl()));
        c.setEnabled(req.enabled());
        c.setEndpointUrl(req.endpointUrl());
        c.setNotes(req.notes());
        audit.log(AuditAction.CONFIG_CHANGE, "IntegrationConfig", id, "Integration " + c.getCode() + " updated", before,
                Map.of("enabled", c.isEnabled(), "endpointUrl", String.valueOf(c.getEndpointUrl())));
        return c;
    }
}
