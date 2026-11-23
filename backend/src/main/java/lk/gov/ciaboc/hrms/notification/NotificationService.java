package lk.gov.ciaboc.hrms.notification;

import lk.gov.ciaboc.hrms.common.TemplateRenderer;
import lk.gov.ciaboc.hrms.config.AppProperties;
import lk.gov.ciaboc.hrms.integration.IntegrationService;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Single entry point for HR event notifications (FR-NOT-001..005). Modules pass a template code and variables;
 * callers must only pass variables the recipient is entitled to see (FR-NOT-005).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notifications;
    private final NotificationTemplateRepository templates;
    private final OutboundMessageRepository outbound;
    private final AppUserRepository users;
    private final IntegrationService integrations;
    private final MessageGateways gateways;
    private final AppProperties props;

    @Transactional
    public void notifyUser(Long userId, String templateCode, Map<String, ?> vars, String link) {
        if (userId == null) return;
        users.findById(userId).filter(AppUser::isActive).ifPresent(u -> deliver(u, templateCode, vars, link));
    }

    @Transactional
    public void notifyUsers(Collection<Long> userIds, String templateCode, Map<String, ?> vars, String link) {
        new LinkedHashSet<>(userIds).forEach(id -> notifyUser(id, templateCode, vars, link));
    }

    @Transactional
    public void notifyEmployee(Long employeeId, String templateCode, Map<String, ?> vars, String link) {
        if (employeeId == null) return;
        users.findByEmployeeId(employeeId).filter(AppUser::isActive).ifPresent(u -> deliver(u, templateCode, vars, link));
    }

    @Transactional
    public void notifyRole(String roleCode, String templateCode, Map<String, ?> vars, String link) {
        Set<Long> ids = new LinkedHashSet<>();
        users.findActiveByRole(roleCode).forEach(u -> ids.add(u.getId()));
        notifyUsers(ids, templateCode, vars, link);
    }

    private void deliver(AppUser u, String templateCode, Map<String, ?> vars, String link) {
        NotificationTemplate t = templates.findByCode(templateCode).filter(NotificationTemplate::isActive).orElse(null);
        String lang = u.getPreferredLanguage();
        String title = t == null ? templateCode : TemplateRenderer.render(t.title(lang), vars);
        String body = t == null ? "" : TemplateRenderer.render(t.body(lang), vars);
        if (t == null) log.warn("Notification template {} not found or inactive", templateCode);

        Notification n = new Notification();
        n.setRecipientUserId(u.getId());
        n.setTemplateCode(templateCode);
        n.setTitle(title);
        n.setBody(body);
        n.setLink(link);
        n.setCreatedAt(Instant.now());
        if (u.isNotifySystem()) notifications.save(n);

        if (t != null && t.isSendEmail() && u.isNotifyEmail() && u.getEmail() != null && integrations.isEnabled(IntegrationService.EMAIL)) {
            queue("EMAIL", u.getEmail(), "[CIABOC HRMS] " + title, body, false);
        }
        if (t != null && t.isSendSms() && u.isNotifySms() && u.getMobile() != null && integrations.isEnabled(IntegrationService.SMS)) {
            queue("SMS", u.getMobile(), null, title, false);
        }
    }

    private OutboundMessage queue(String channel, String to, String subject, String body, boolean sensitive) {
        OutboundMessage m = new OutboundMessage();
        m.setChannel(channel);
        m.setRecipient(to);
        m.setSubject(subject);
        m.setBody(body);
        m.setSensitive(sensitive);
        m.setCreatedAt(Instant.now());
        m.setNextAttemptAt(Instant.now());
        return outbound.save(m);
    }

    @Scheduled(fixedDelayString = "${app.notifications.dispatch-interval-ms:30000}", initialDelay = 15000)
    @Transactional
    public void dispatchPending() {
        outbound.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(OutboundMessage.Status.PENDING, Instant.now())
                .forEach(this::dispatch);
    }

    private void dispatch(OutboundMessage m) {
        m.setAttempts(m.getAttempts() + 1);
        try {
            boolean real = gateways.send(m);
            m.setStatus(real ? OutboundMessage.Status.SENT : OutboundMessage.Status.SIMULATED);
            m.setSentAt(Instant.now());
            m.setLastError(null);
        } catch (Exception e) {
            log.warn("Delivery of message {} via {} failed: {}", m.getId(), m.getChannel(), e.getMessage());
            m.setLastError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().substring(0, Math.min(1000, e.getMessage().length())));
            if (m.getAttempts() >= props.notifications().maxAttempts()) {
                m.setStatus(OutboundMessage.Status.FAILED);
            } else {
                m.setNextAttemptAt(Instant.now().plus((long) Math.pow(2, m.getAttempts()), ChronoUnit.MINUTES));
            }
        }
        outbound.save(m);
    }

}
