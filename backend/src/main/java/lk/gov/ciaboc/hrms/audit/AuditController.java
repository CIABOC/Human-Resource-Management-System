package lk.gov.ciaboc.hrms.audit;

import lk.gov.ciaboc.hrms.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;

/** Read-only audit history. There are intentionally no update or delete endpoints (FR-AUD-005). */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('AUDIT_VIEW')")
public class AuditController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
    private final AuditService audit;

    @GetMapping
    public PageResponse<AuditLog> search(@RequestParam(required = false) String username,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String entityType,
                                         @RequestParam(required = false) String entityId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size) {
        var result = audit.search(username, action, entityType, entityId,
                from == null ? null : from.atStartOfDay(ZONE).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toInstant(),
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "id")));
        audit.log(AuditAction.SEARCH, "AuditLog", null, "Audit log searched");
        return PageResponse.of(result);
    }

    @GetMapping("/verify")
    public AuditService.ChainVerification verify() {
        return audit.verifyChain();
    }

    @GetMapping("/actions")
    public AuditAction[] actions() {
        return AuditAction.values();
    }
}
