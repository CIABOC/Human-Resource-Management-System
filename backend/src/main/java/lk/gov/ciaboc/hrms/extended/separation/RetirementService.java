package lk.gov.ciaboc.hrms.extended.separation;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeRepository;
import lk.gov.ciaboc.hrms.extended.ExtSupport;
import lk.gov.ciaboc.hrms.extended.separation.SeparationEntities.SeparationCase;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Retirement forecasting and advance notices to HR (FR-EXT-002). Horizon, lead times and the notified role are
 * system settings; each (employee, retirement date, lead time) notice is sent once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetirementService {
    private final EmployeeRepository employees;
    private final EmployeeLookup lookup;
    private final SeparationCaseRepository cases;
    private final ExtSupport support;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public record ForecastRow(EmployeeLookup.EmployeeBrief employee, LocalDate dateOfBirth, LocalDate retirementDate,
                              long monthsRemaining, long daysRemaining, Long openCaseId) {
    }

    @Transactional(readOnly = true)
    public List<ForecastRow> forecast(Integer months) {
        int horizon = months == null ? settings.getInt("ext.retirement.forecast-months", 12) : months;
        if (horizon < 1 || horizon > 120) throw ApiException.badRequest("Forecast horizon must be between 1 and 120 months");
        LocalDate today = LocalDate.now();
        LocalDate until = today.plusMonths(horizon);
        List<Employee> due = employees.findByStatusIn(EmployeeLookup.IN_SERVICE).stream()
                .filter(e -> e.getRetirementDate() != null && !e.getRetirementDate().isBefore(today) && !e.getRetirementDate().isAfter(until))
                .filter(lookup::canAccess)
                .sorted(Comparator.comparing(Employee::getRetirementDate))
                .toList();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = support.briefs(due.stream().map(Employee::getId).toList());
        Map<Long, Long> openCases = new HashMap<>();
        cases.findByStatus(SeparationCase.Status.OPEN).forEach(c -> openCases.put(c.getEmployeeId(), c.getId()));
        return due.stream().map(e -> new ForecastRow(briefs.get(e.getId()), e.getDateOfBirth(), e.getRetirementDate(),
                ChronoUnit.MONTHS.between(today, e.getRetirementDate()), ChronoUnit.DAYS.between(today, e.getRetirementDate()),
                openCases.get(e.getId()))).toList();
    }

    /** Daily job; the schedule is configurable through {@code app.extended.retirement-notice-cron}. */
    @Scheduled(cron = "${app.extended.retirement-notice-cron:0 30 6 * * *}")
    public void scheduled() {
        try {
            int n = sendNotices(LocalDate.now());
            if (n > 0) log.info("Sent {} retirement advance notice(s)", n);
        } catch (Exception e) {
            log.error("Retirement notice job failed", e);
        }
    }

    /**
     * Notifies the configured role about employees whose retirement falls within a configured lead time. For each
     * employee only the nearest lead time reached is notified, so a first run does not send a burst of older notices.
     */
    @Transactional
    public int sendNotices(LocalDate today) {
        List<Integer> leads = leadMonths();
        if (leads.isEmpty()) return 0;
        String role = settings.get("ext.retirement.notify-role", "HR_ADMIN");
        int maxLead = leads.get(leads.size() - 1);
        int sent = 0;
        for (Employee e : employees.findByStatusIn(EmployeeLookup.IN_SERVICE)) {
            LocalDate r = e.getRetirementDate();
            if (r == null || r.isBefore(today) || r.isAfter(today.plusMonths(maxLead))) continue;
            Integer lead = leads.stream().filter(l -> !r.isAfter(today.plusMonths(l))).findFirst().orElse(null);
            if (lead == null) continue;
            int inserted = jdbc.update("INSERT INTO ext_retirement_notice (employee_id, retirement_date, lead_months, sent_at) " +
                            "VALUES (?, ?, ?, ?) ON CONFLICT (employee_id, retirement_date, lead_months) DO NOTHING",
                    e.getId(), r, lead, Timestamp.from(Instant.now()));
            if (inserted == 0) continue;
            Map<String, Object> vars = new HashMap<>();
            vars.put("employeeNo", e.getEmployeeNo());
            vars.put("employeeName", e.getNameWithInitials());
            vars.put("designation", support.designationName(e.getDesignationId()));
            vars.put("retirementDate", r);
            vars.put("months", Math.max(1, ChronoUnit.MONTHS.between(today, r)));
            notifications.notifyRole(role, "EXT_RETIREMENT_DUE", vars, "/extended/retirements");
            sent++;
        }
        if (sent > 0) audit.log(AuditAction.PROCESS, "RetirementNotice", null, sent + " retirement advance notice(s) sent to " + role);
        return sent;
    }

    List<Integer> leadMonths() {
        String raw = settings.get("ext.retirement.notice-lead-months", "12,6,3,1");
        TreeSet<Integer> out = new TreeSet<>();
        for (String s : raw.split(",")) {
            try {
                int v = Integer.parseInt(s.trim());
                if (v > 0 && v <= 120) out.add(v);
            } catch (NumberFormatException ignored) {
                // invalid entries are skipped; the setting is free text
            }
        }
        return new ArrayList<>(out);
    }
}
