package lk.gov.ciaboc.hrms.common.contracts;

import lk.gov.ciaboc.hrms.security.UserPrincipal;

import java.time.LocalDate;
import java.util.List;

/** Feeds for the Establishment dashboard (REQ D-3, D-7). Modules implement these as Spring beans. */
public final class DashboardFeeds {
    private DashboardFeeds() {
    }

    /** level: 90 / 60 / 30 (days ahead) or OPEN. */
    public record VacancyAlert(String vacancyRef, String post, String designation, String orgUnitName, String trigger,
                               LocalDate vacancyDate, String level, String status, String link) {
    }

    /** Upcoming vacancy alerts (D-3); implemented by the vacancy module. */
    public interface VacancyAlertSource {
        List<VacancyAlert> upcomingAlerts(UserPrincipal user, int limit);
    }

    /** type: POLICY_RENEWAL, SECONDMENT_END, APPRAISAL_DEADLINE, CONTRACT_END, INSURANCE_EXPIRY, LIABILITY_END ... */
    public record DueItem(String type, String title, String subject, LocalDate dueDate, String link) {
    }

    /** "Expiring & due soon" items (D-7); any module may contribute. Lead times are configuration. */
    public interface DueSoonSource {
        List<DueItem> dueSoon(UserPrincipal user);
    }
}
