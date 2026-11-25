package lk.gov.ciaboc.hrms.recruitment.vacancy;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.contracts.DashboardFeeds;
import lk.gov.ciaboc.hrms.common.contracts.EstablishmentEvents;
import lk.gov.ciaboc.hrms.establishment.Designation;
import lk.gov.ciaboc.hrms.establishment.DesignationRepository;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.establishment.OrgUnit;
import lk.gov.ciaboc.hrms.establishment.Position;
import lk.gov.ciaboc.hrms.establishment.PositionRepository;
import lk.gov.ciaboc.hrms.integration.IntegrationService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.Notification;
import lk.gov.ciaboc.hrms.notification.NotificationRepository;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.notification.OutboundMessage;
import lk.gov.ciaboc.hrms.notification.OutboundMessageRepository;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.Vacancy;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.VacancyStatus;
import lk.gov.ciaboc.hrms.recruitment.VacancyRepository;
import lk.gov.ciaboc.hrms.recruitment.vacancy.VacancyEntities.AlertDelivery;
import lk.gov.ciaboc.hrms.recruitment.vacancy.VacancyEntities.AlertRule;
import lk.gov.ciaboc.hrms.recruitment.vacancy.VacancyEntities.TriggerConfig;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Early vacancy alerts (spec v2.1 REQ 5.2, 5.3) and the D-3 dashboard feed. For each FORECAST / OPEN vacancy the
 * closest due alert rule (lead time ≥ days remaining) is sent once to its recipients in-app (NotificationService),
 * by official e-mail and optionally SMS (shared outbound queue). Delivery status per recipient: READ / UNREAD for
 * in-app (from the notification read flag), DELIVERED / BOUNCED / PENDING for e-mail and SMS. Filled, frozen and
 * cancelled vacancies get no further alerts (REQ 5.7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VacancyAlertService implements DashboardFeeds.VacancyAlertSource {
    static final String TEMPLATE = "VACANCY_ALERT";
    private static final Set<VacancyStatus> ALERTABLE = EnumSet.of(VacancyStatus.FORECAST, VacancyStatus.OPEN);

    private final VacancyRepository vacancies;
    private final AlertRuleRepository rules;
    private final AlertDeliveryRepository deliveries;
    private final TriggerConfigRepository triggers;
    private final VacancyAutomationService automation;
    private final AppUserRepository users;
    private final NotificationService notifications;
    private final NotificationRepository notificationRepo;
    private final OutboundMessageRepository outbound;
    private final IntegrationService integrations;
    private final SettingsService settings;
    private final EstablishmentService establishment;
    private final DesignationRepository designations;
    private final PositionRepository positions;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    // ===================================================================== scheduling and events

    /** Daily: forecast scan, then alerts at the configured lead times. */
    @Scheduled(cron = "${app.recruitment.vacancy-scan-cron:0 15 6 * * *}", zone = "${app.timezone:Asia/Colombo}")
    public void daily() {
        try {
            automation.scan();
            dispatchDue();
        } catch (Exception e) {
            log.error("Vacancy scan failed", e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void afterCadreChanged(EstablishmentEvents.CadreChanged e) {
        safeDispatch();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void afterSeparated(EstablishmentEvents.EmployeeSeparated e) {
        safeDispatch();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void afterPlacement(EstablishmentEvents.EmployeePlacementChanged e) {
        safeDispatch();
    }

    private void safeDispatch() {
        try {
            dispatchDue();
        } catch (Exception ex) {
            // Alerts must never break the business change that already committed; the daily run retries.
            log.warn("Vacancy alert dispatch failed: {}", ex.getMessage());
        }
    }

    // ===================================================================== dispatch

    /** Sends every due alert that has not been sent yet. Returns the number of (vacancy, rule) alerts sent. */
    @Transactional
    public int dispatchDue() {
        if (!settings.getBool("vacancy.alerts.enabled", true)) return 0;
        List<AlertRule> active = rules.findByActiveTrueOrderByLeadDaysDesc();
        if (active.isEmpty()) return 0;
        LocalDate today = LocalDate.now();
        int sent = 0;
        for (Vacancy v : vacancies.findByStatusInOrderByExpectedDateAscIdAsc(ALERTABLE)) {
            if (v.getExpectedDate() == null) continue;
            long daysLeft = Math.max(0, ChronoUnit.DAYS.between(today, v.getExpectedDate()));
            if (v.getStatus() == VacancyStatus.OPEN) daysLeft = 0;
            final long left = daysLeft;
            List<AlertRule> applicable = active.stream()
                    .filter(r -> r.getTriggerCode() == null || r.getTriggerCode().equals(v.getTriggerCode())).toList();
            // The closest due level only: a vacancy found 20 days ahead gets the 30-day alert, not 90/60/30 at once.
            Optional<AlertRule> due = applicable.stream().filter(r -> r.getLeadDays() >= left)
                    .min(Comparator.comparingInt(AlertRule::getLeadDays));
            if (due.isEmpty()) continue;
            AlertRule r = due.get();
            if (deliveries.existsByVacancyIdAndRuleId(v.getId(), r.getId())) continue;
            boolean closerAlreadySent = applicable.stream().filter(o -> o.getLeadDays() < r.getLeadDays())
                    .anyMatch(o -> deliveries.existsByVacancyIdAndRuleId(v.getId(), o.getId()));
            if (closerAlreadySent) continue;
            send(v, r, left);
            sent++;
        }
        return sent;
    }

    private void send(Vacancy v, AlertRule r, long daysLeft) {
        String level = level(r.getLeadDays());
        Map<String, Object> vars = vars(v, level);
        String link = "/recruitment/vacancies/" + v.getId();
        Map<Long, String> recipients = resolve(r, v);
        Instant now = Instant.now();
        boolean emailOn = integrations.isEnabled(IntegrationService.EMAIL);
        boolean smsOn = integrations.isEnabled(IntegrationService.SMS);
        for (Map.Entry<Long, String> rec : recipients.entrySet()) {
            AppUser u = users.findById(rec.getKey()).filter(AppUser::isActive).orElse(null);
            if (u == null) continue;
            if (r.isInApp()) {
                notifications.notifyUser(u.getId(), TEMPLATE, vars, link);
                Long nid = notificationRepo.findByRecipientUserIdOrderByIdDesc(u.getId(), PageRequest.of(0, 1)).stream()
                        .filter(n -> TEMPLATE.equals(n.getTemplateCode()) && link.equals(n.getLink()))
                        .map(Notification::getId).findFirst().orElse(null);
                record(v, r, level, u, rec.getValue(), "IN_APP", u.getUsername(), nid, null, "SENT", null, now);
            }
            if (r.isEmail()) {
                if (u.getEmail() == null || u.getEmail().isBlank()) {
                    record(v, r, level, u, rec.getValue(), "EMAIL", null, null, null, "SKIPPED", "No official e-mail address on the user account", now);
                } else if (!emailOn) {
                    record(v, r, level, u, rec.getValue(), "EMAIL", u.getEmail(), null, null, "SKIPPED", "E-mail integration is disabled", now);
                } else {
                    OutboundMessage m = queue("EMAIL", u.getEmail(), "[CIABOC HRMS] Vacancy alert (" + level + "): " + vars.get("post"),
                            body(vars, daysLeft));
                    record(v, r, level, u, rec.getValue(), "EMAIL", u.getEmail(), null, m.getId(), "QUEUED", null, now);
                }
            }
            if (r.isSms()) {
                if (u.getMobile() == null || u.getMobile().isBlank()) {
                    record(v, r, level, u, rec.getValue(), "SMS", null, null, null, "SKIPPED", "No mobile number on the user account", now);
                } else if (!smsOn) {
                    record(v, r, level, u, rec.getValue(), "SMS", u.getMobile(), null, null, "SKIPPED", "SMS integration is disabled", now);
                } else {
                    OutboundMessage m = queue("SMS", u.getMobile(), null, "CIABOC HRMS: vacancy " + vars.get("vacancyRef") + " ("
                            + vars.get("designation") + ", " + vars.get("unit") + ") expected " + vars.get("expectedDate"));
                    record(v, r, level, u, rec.getValue(), "SMS", u.getMobile(), null, m.getId(), "QUEUED", null, now);
                }
            }
        }
        if (recipients.isEmpty()) {
            log.warn("Vacancy alert rule {} resolved no recipients for vacancy {}", r.getId(), v.getReferenceNo());
        }
        audit.log(AuditAction.PROCESS, "Vacancy", v.getId(), "Vacancy alert '" + r.getName() + "' (" + level + ") sent for "
                + v.getReferenceNo() + " to " + recipients.size() + " recipient(s)");
    }

    private void record(Vacancy v, AlertRule r, String level, AppUser u, String basis, String channel, String address, Long nid,
                        Long mid, String status, String detail, Instant now) {
        AlertDelivery d = new AlertDelivery();
        d.setVacancyId(v.getId());
        d.setRuleId(r.getId());
        d.setLevel(level);
        d.setLeadDays(r.getLeadDays());
        d.setRecipientUserId(u.getId());
        d.setRecipientName(u.getFullName());
        d.setRecipientBasis(basis.length() > 120 ? basis.substring(0, 120) : basis);
        d.setChannel(channel);
        d.setAddress(address);
        d.setNotificationId(nid);
        d.setOutboundMessageId(mid);
        d.setStatus(status);
        d.setDetail(detail);
        d.setSentAt(now);
        deliveries.save(d);
    }

    private OutboundMessage queue(String channel, String to, String subject, String body) {
        OutboundMessage m = new OutboundMessage();
        m.setChannel(channel);
        m.setRecipient(to);
        m.setSubject(subject == null ? null : subject.length() > 300 ? subject.substring(0, 300) : subject);
        m.setBody(body);
        m.setSensitive(false);
        m.setCreatedAt(Instant.now());
        m.setNextAttemptAt(Instant.now());
        return outbound.save(m);
    }

    private static String body(Map<String, Object> vars, long daysLeft) {
        return "Vacancy " + vars.get("vacancyRef") + "\nPost: " + vars.get("post") + "\nDesignation: " + vars.get("designation")
                + "\nDivision / unit: " + vars.get("unit") + "\nTrigger: " + vars.get("trigger") + "\nVacancy date: " + vars.get("expectedDate")
                + (daysLeft > 0 ? " (in " + daysLeft + " days)" : "") + "\nStatus: " + vars.get("status")
                + "\n\nPlease review the vacancy in the CIABOC HRMS (Establishment → Vacancies & Alerts).";
    }

    private Map<String, Object> vars(Vacancy v, String level) {
        Map<String, Object> m = new HashMap<>();
        String des = designations.findById(v.getDesignationId()).map(Designation::getName).orElse("");
        String post = v.getPositionId() == null ? des : positions.findById(v.getPositionId()).map(Position::getCode).orElse(des);
        m.put("vacancyRef", v.getReferenceNo());
        m.put("post", post);
        m.put("designation", des);
        m.put("unit", establishment.orgUnit(v.getOrgUnitCode(), LocalDate.now()).map(OrgUnit::getName).orElse(v.getOrgUnitCode()));
        m.put("trigger", triggers.findByCode(v.getTriggerCode()).map(TriggerConfig::getName).orElse(v.getTriggerCode()));
        m.put("expectedDate", String.valueOf(v.getExpectedDate()));
        m.put("status", v.getStatus().name());
        m.put("level", "OPEN".equals(level) ? "Open" : level + "-day");
        return m;
    }

    static String level(int leadDays) {
        return leadDays == 0 ? "OPEN" : String.valueOf(leadDays);
    }

    // ===================================================================== recipients

    /** Resolves ROLE:, USER:, EMPLOYEE: and DIVISION_HEAD entries to active users, with the basis of each. */
    Map<Long, String> resolve(AlertRule r, Vacancy v) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (String entry : VacancyAutomationService.split(r.getRecipients())) {
            int colon = entry.indexOf(':');
            String type = colon < 0 ? entry : entry.substring(0, colon);
            String ref = colon < 0 ? null : r.getRecipients().substring(0, 0) + entry.substring(colon + 1);
            switch (type) {
                case "ROLE" -> users.findActiveByRole(ref).forEach(u -> out.putIfAbsent(u.getId(), "Role " + ref));
                case "USER" -> users.findByUsernameIgnoreCase(ref).filter(AppUser::isActive)
                        .ifPresent(u -> out.putIfAbsent(u.getId(), "User " + u.getUsername()));
                case "EMPLOYEE" -> jdbc.queryForList("SELECT id FROM employee WHERE upper(employee_no) = ?", Long.class, ref).stream().findFirst()
                        .flatMap(users::findByEmployeeId).filter(AppUser::isActive)
                        .ifPresent(u -> out.putIfAbsent(u.getId(), "Employee " + ref));
                case "DIVISION_HEAD" -> divisionHead(v.getOrgUnitCode())
                        .ifPresent(u -> out.putIfAbsent(u.getId(), "Head of the division"));
                default -> log.warn("Unknown vacancy alert recipient '{}' in rule {}", entry, r.getId());
            }
        }
        return out;
    }

    /** Holder of the unit head post, walking up to the parent unit when the head post is vacant. */
    Optional<AppUser> divisionHead(String unitCode) {
        LocalDate today = LocalDate.now();
        for (String code : establishment.unitAndAncestors(unitCode, today)) {
            String head = establishment.orgUnit(code, today).map(OrgUnit::getHeadPositionCode).orElse(null);
            if (head == null) continue;
            Optional<Long> emp = jdbc.queryForList("SELECT a.employee_id FROM employee_assignment a JOIN org_position p ON p.id = a.position_id "
                    + "JOIN employee e ON e.id = a.employee_id WHERE p.code = ? AND a.effective_from <= CURRENT_DATE "
                    + "AND (a.effective_to IS NULL OR a.effective_to > CURRENT_DATE) AND e.status IN ('ACTIVE','ON_LEAVE','SECONDED','PROBATION') "
                    + "ORDER BY a.acting, a.id", Long.class, head).stream().findFirst();
            Optional<AppUser> u = emp.flatMap(users::findByEmployeeId).filter(AppUser::isActive);
            if (u.isPresent()) return u;
        }
        return Optional.empty();
    }

    // ===================================================================== views

    public record DeliveryView(Long id, Long vacancyId, String vacancyRef, String level, String ruleName, String recipientName,
                               String recipientBasis, String channel, String address, String status, String deliveryStatus,
                               String detail, Instant sentAt, Instant readAt) {
    }

    @Transactional(readOnly = true)
    public List<DeliveryView> deliveries(Long vacancyId) {
        List<AlertDelivery> list = vacancyId == null ? deliveries.findTop500ByOrderByIdDesc() : deliveries.findByVacancyIdOrderByIdDesc(vacancyId);
        Map<Long, String> refs = new HashMap<>();
        Map<Long, String> ruleNames = rules.findAll().stream().collect(Collectors.toMap(AlertRule::getId, AlertRule::getName));
        Map<Long, Notification> notes = notificationRepo.findAllById(list.stream().map(AlertDelivery::getNotificationId)
                .filter(Objects::nonNull).toList()).stream().collect(Collectors.toMap(Notification::getId, n -> n));
        Map<Long, OutboundMessage> msgs = outbound.findAllById(list.stream().map(AlertDelivery::getOutboundMessageId)
                .filter(Objects::nonNull).toList()).stream().collect(Collectors.toMap(OutboundMessage::getId, m -> m));
        return list.stream().map(d -> {
            String ref = refs.computeIfAbsent(d.getVacancyId(), id -> vacancies.findById(id).map(Vacancy::getReferenceNo).orElse(null));
            Notification n = d.getNotificationId() == null ? null : notes.get(d.getNotificationId());
            OutboundMessage m = d.getOutboundMessageId() == null ? null : msgs.get(d.getOutboundMessageId());
            return new DeliveryView(d.getId(), d.getVacancyId(), ref, d.getLevel(), ruleNames.get(d.getRuleId()), d.getRecipientName(),
                    d.getRecipientBasis(), d.getChannel(), d.getAddress(), d.getStatus(), deliveryStatus(d, n, m),
                    m != null && m.getLastError() != null ? m.getLastError() : d.getDetail(), d.getSentAt(), n == null ? null : n.getReadAt());
        }).toList();
    }

    /** DELIVERED / READ / UNREAD / BOUNCED (REQ 5.3), plus PENDING while queued and NOT_SENT when skipped. */
    static String deliveryStatus(AlertDelivery d, Notification n, OutboundMessage m) {
        if ("SKIPPED".equals(d.getStatus())) return "NOT_SENT";
        if ("IN_APP".equals(d.getChannel())) return n == null ? "DELIVERED" : n.isRead() ? "READ" : "UNREAD";
        if (m == null) return "PENDING";
        return switch (m.getStatus()) {
            case SENT, SIMULATED -> "DELIVERED";
            case FAILED -> "BOUNCED";
            case SKIPPED -> "NOT_SENT";
            case PENDING -> "PENDING";
        };
    }

    // ===================================================================== D-3 dashboard feed

    @Override
    @Transactional(readOnly = true)
    public List<DashboardFeeds.VacancyAlert> upcomingAlerts(UserPrincipal user, int limit) {
        if (user == null || !(user.has(Permission.REC_VIEW) || user.has(Permission.EST_VIEW))) return List.of();
        List<Integer> leads = rules.findByActiveTrueOrderByLeadDaysDesc().stream().map(AlertRule::getLeadDays)
                .filter(l -> l > 0).sorted().toList();
        LocalDate today = LocalDate.now();
        Map<Long, String> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, Designation::getName));
        Map<String, String> units = establishment.orgUnits(today).stream().collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getName, (a, b) -> a));
        Map<String, String> trig = triggers.findAll().stream().collect(Collectors.toMap(TriggerConfig::getCode, TriggerConfig::getName));
        List<DashboardFeeds.VacancyAlert> out = new ArrayList<>();
        for (Vacancy v : vacancies.findByStatusInOrderByExpectedDateAscIdAsc(ALERTABLE)) {
            if (out.size() >= Math.max(1, limit)) break;
            long left = v.getExpectedDate() == null ? 0 : ChronoUnit.DAYS.between(today, v.getExpectedDate());
            String level;
            if (v.getStatus() == VacancyStatus.OPEN || left <= 0) level = "OPEN";
            else level = leads.stream().filter(l -> l >= left).findFirst().map(String::valueOf).orElse(null);
            if (level == null) continue; // beyond the longest lead time
            String post = v.getPositionId() == null ? des.get(v.getDesignationId())
                    : positions.findById(v.getPositionId()).map(Position::getCode).orElse(null);
            out.add(new DashboardFeeds.VacancyAlert(v.getReferenceNo(), post, des.get(v.getDesignationId()),
                    units.getOrDefault(v.getOrgUnitCode(), v.getOrgUnitCode()), trig.getOrDefault(v.getTriggerCode(), v.getTriggerCode()),
                    v.getExpectedDate(), level, v.getStatus().name(), "/recruitment/vacancies/" + v.getId()));
        }
        return out;
    }
}
