package lk.gov.ciaboc.hrms.dashboard;

import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final List<DashboardContributor> contributors;

    public record Dashboard(List<DashboardContributor.KpiTile> tiles, List<DashboardContributor.Chart> charts) {
    }

    @GetMapping
    public Dashboard dashboard() {
        UserPrincipal me = CurrentUser.get();
        List<DashboardContributor.KpiTile> tiles = new ArrayList<>();
        List<DashboardContributor.Chart> charts = new ArrayList<>();
        for (DashboardContributor c : contributors) {
            try {
                tiles.addAll(c.tiles(me));
                charts.addAll(c.charts(me));
            } catch (RuntimeException e) {
                // One failing module must not blank the whole dashboard.
                log.warn("Dashboard contributor {} failed: {}", c.getClass().getSimpleName(), e.getMessage());
            }
        }
        tiles.sort(Comparator.comparingInt(DashboardContributor.KpiTile::order));
        charts.sort(Comparator.comparingInt(DashboardContributor.Chart::order));
        return new Dashboard(tiles, charts);
    }
}
