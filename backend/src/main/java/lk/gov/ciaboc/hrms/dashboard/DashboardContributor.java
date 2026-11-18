package lk.gov.ciaboc.hrms.dashboard;

import lk.gov.ciaboc.hrms.security.UserPrincipal;

import java.util.List;
import java.util.Map;

/**
 * Modules contribute KPI tiles and small charts to the dashboard (FR-RPT-004). Implementations must check the
 * principal's permissions and data scope themselves and return nothing the user may not see.
 */
public interface DashboardContributor {

    /** tone: navy | gold | green | red | neutral */
    record KpiTile(String key, String label, String value, String hint, String link, String tone, int order) {
    }

    /** type: bar | pie. Each data point is {"label": .., "value": ..}. */
    record Chart(String key, String title, String type, List<Map<String, Object>> data, int order) {
    }

    default List<KpiTile> tiles(UserPrincipal user) {
        return List.of();
    }

    default List<Chart> charts(UserPrincipal user) {
        return List.of();
    }
}
