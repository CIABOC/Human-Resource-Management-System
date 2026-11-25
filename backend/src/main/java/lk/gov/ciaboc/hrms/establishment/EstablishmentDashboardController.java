package lk.gov.ciaboc.hrms.establishment;

import lk.gov.ciaboc.hrms.common.contracts.DashboardFeeds;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Establishment dashboard (spec §4.1, D-1, D-2, D-3, D-6, D-7). D-4 (awaiting my approval) comes from the workflow
 * inbox and D-5 (Configuration Center) is a set of links built by the UI. Feeds from other modules are optional beans,
 * so the dashboard still works when a module is absent or fails.
 */
@Slf4j
@RestController
@RequestMapping("/api/establishment/dashboard")
@RequiredArgsConstructor
public class EstablishmentDashboardController {
    private final EstablishmentService establishment;
    private final CategorizationBatchRepository batches;
    private final ObjectProvider<DashboardFeeds.VacancyAlertSource> vacancyAlerts;
    private final ObjectProvider<DashboardFeeds.DueSoonSource> dueSoon;

    public record DivisionHealth(String code, String name, int approved, long filled, long acting) {
    }

    public record Dashboard(LocalDate asOf, int approved, long filled, long acting, long vacant, long excess, int forecast,
                            long pendingChanges, long draftChanges, long pendingCategorizations,
                            List<DivisionHealth> divisions, List<DashboardFeeds.VacancyAlert> vacancyAlerts,
                            List<DashboardFeeds.DueItem> dueSoon) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('EST_VIEW')")
    public Dashboard dashboard() {
        LocalDate today = LocalDate.now();
        UserPrincipal me = CurrentUser.get();
        List<EstablishmentService.CadreRow> rows = establishment.cadreSummary(today, null);
        int approved = rows.stream().mapToInt(EstablishmentService.CadreRow::approved).sum();
        long filled = rows.stream().mapToLong(EstablishmentService.CadreRow::filled).sum();
        long acting = rows.stream().mapToLong(EstablishmentService.CadreRow::acting).sum();
        long vacant = rows.stream().mapToLong(EstablishmentService.CadreRow::vacant).sum();
        long excess = rows.stream().mapToLong(EstablishmentService.CadreRow::excess).sum();

        // D-6: the divisions are the units directly below the root (Director General's office).
        List<DivisionHealth> divisions = new ArrayList<>();
        for (EstablishmentService.OrgNode root : establishment.orgChart(today)) {
            List<EstablishmentService.OrgNode> level = root.children().isEmpty() ? List.of(root) : root.children();
            for (EstablishmentService.OrgNode n : level)
                divisions.add(new DivisionHealth(n.code(), n.name(), n.rollupApproved(), n.rollupFilled(), n.rollupActing()));
        }

        List<DashboardFeeds.VacancyAlert> alerts = new ArrayList<>();
        vacancyAlerts.orderedStream().forEach(s -> {
            try {
                alerts.addAll(s.upcomingAlerts(me, 20));
            } catch (RuntimeException e) {
                log.warn("Vacancy alert feed failed: {}", e.getMessage());
            }
        });
        List<DashboardFeeds.DueItem> due = new ArrayList<>();
        dueSoon.orderedStream().forEach(s -> {
            try {
                due.addAll(s.dueSoon(me));
            } catch (RuntimeException e) {
                log.warn("Due-soon feed failed: {}", e.getMessage());
            }
        });
        due.sort(Comparator.comparing(DashboardFeeds.DueItem::dueDate, Comparator.nullsLast(Comparator.naturalOrder())));

        long pending = establishment.changes(EstablishmentChange.Status.PENDING).size();
        long drafts = establishment.changes(EstablishmentChange.Status.DRAFT).size();
        long pendingCat = batches.findAll().stream().filter(b -> "PENDING".equals(String.valueOf(b.getStatus()))).count();
        return new Dashboard(today, approved, filled, acting, vacant, excess, alerts.size(), pending, drafts, pendingCat,
                divisions, alerts.stream().limit(20).toList(), due.stream().limit(30).toList());
    }
}
