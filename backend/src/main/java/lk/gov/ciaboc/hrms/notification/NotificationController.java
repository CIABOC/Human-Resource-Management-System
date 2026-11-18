package lk.gov.ciaboc.hrms.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.PageResponse;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationRepository notifications;
    private final NotificationTemplateRepository templates;
    private final OutboundMessageRepository outbound;
    private final AuditService audit;

    @GetMapping("/api/notifications")
    public PageResponse<Notification> mine(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        Long uid = CurrentUser.get().getId();
        var pr = PageRequest.of(page, Math.min(size, 100));
        return PageResponse.of(unreadOnly ? notifications.findByRecipientUserIdAndReadFalseOrderByIdDesc(uid, pr)
                : notifications.findByRecipientUserIdOrderByIdDesc(uid, pr));
    }

    @GetMapping("/api/notifications/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notifications.countByRecipientUserIdAndReadFalse(CurrentUser.get().getId()));
    }

    @PostMapping("/api/notifications/{id}/read")
    @Transactional
    public void markRead(@PathVariable Long id) {
        Notification n = notifications.findById(id)
                .filter(x -> x.getRecipientUserId().equals(CurrentUser.get().getId()))
                .orElseThrow(() -> ApiException.notFound("Notification", id));
        if (!n.isRead()) {
            n.setRead(true);
            n.setReadAt(Instant.now());
        }
    }

    @PostMapping("/api/notifications/read-all")
    @Transactional
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", notifications.markAllRead(CurrentUser.get().getId(), Instant.now()));
    }

    // ---- administration ----

    @GetMapping("/api/admin/notification-templates")
    @PreAuthorize("hasAuthority('NOTIF_CONFIG')")
    public List<NotificationTemplate> templates() {
        return templates.findAll(Sort.by("code"));
    }

    public record TemplateRequest(@NotBlank @Size(max = 300) String titleEn, @Size(max = 300) String titleSi,
                                  @Size(max = 300) String titleTa, @NotBlank @Size(max = 2000) String bodyEn,
                                  @Size(max = 2000) String bodySi, @Size(max = 2000) String bodyTa,
                                  boolean sendEmail, boolean sendSms, boolean active) {
    }

    @PutMapping("/api/admin/notification-templates/{id}")
    @PreAuthorize("hasAuthority('NOTIF_CONFIG')")
    @Transactional
    public NotificationTemplate updateTemplate(@PathVariable Long id, @Valid @RequestBody TemplateRequest r) {
        NotificationTemplate t = templates.findById(id).orElseThrow(() -> ApiException.notFound("Template", id));
        Map<String, Object> before = Map.of("titleEn", t.getTitleEn(), "bodyEn", t.getBodyEn(), "active", t.isActive());
        t.setTitleEn(r.titleEn());
        t.setTitleSi(r.titleSi());
        t.setTitleTa(r.titleTa());
        t.setBodyEn(r.bodyEn());
        t.setBodySi(r.bodySi());
        t.setBodyTa(r.bodyTa());
        t.setSendEmail(r.sendEmail());
        t.setSendSms(r.sendSms());
        t.setActive(r.active());
        audit.log(AuditAction.CONFIG_CHANGE, "NotificationTemplate", id, "Template " + t.getCode() + " updated", before, r);
        return t;
    }

    public record OutboundView(Long id, String channel, String recipient, String subject, String status, int attempts,
                               String lastError, Instant createdAt, Instant sentAt) {
    }

    @GetMapping("/api/admin/outbound-messages")
    @PreAuthorize("hasAuthority('NOTIF_CONFIG')")
    public PageResponse<OutboundView> outbound(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(outbound.findAllByOrderByIdDesc(PageRequest.of(page, Math.min(size, 100))),
                m -> new OutboundView(m.getId(), m.getChannel(), m.getRecipient(), m.getSubject(), m.getStatus().name(),
                        m.getAttempts(), m.getLastError(), m.getCreatedAt(), m.getSentAt()));
    }
}
