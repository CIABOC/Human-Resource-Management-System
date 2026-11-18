package lk.gov.ciaboc.hrms.leave;

import lk.gov.ciaboc.hrms.dashboard.DashboardContributor;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Leave tiles: my balance (self-service), on leave today and pending applications (scoped), leave taken by type. */
@Component
@RequiredArgsConstructor
public class LeaveDashboard implements DashboardContributor {
    private static final String SCOPE = " AND (:scopeAll OR e.org_unit_code IN (:scopeUnits) OR e.id = :self) AND (:restrictedOk OR e.restricted = FALSE)";

    private final NamedParameterJdbcTemplate jdbc;
    private final EmployeeLookup lookup;
    private final LeaveBalanceService balances;

    @Override
    @Transactional(readOnly = true)
    public List<KpiTile> tiles(UserPrincipal me) {
        List<KpiTile> out = new ArrayList<>();
        if (me.has(Permission.SELF_SERVICE) && me.getEmployeeId() != null) {
            int order = 10;
            for (LeaveBalanceService.BalanceView b : balances.forEmployee(me.getEmployeeId(), Year.now().getValue()).stream().limit(2).toList()) {
                out.add(new KpiTile("leave-balance-" + b.leaveTypeCode(), b.leaveTypeName() + " available",
                        b.available().stripTrailingZeros().toPlainString(), "Days left in " + b.year() + " (pending requests deducted)",
                        "/leave/my", "green", order++));
            }
        }
        if (me.has(Permission.LEAVE_VIEW)) {
            Long onLeave = jdbc.queryForObject("SELECT COUNT(DISTINCT a.employee_id) FROM leave_application a JOIN employee e ON e.id = a.employee_id " +
                    "WHERE a.status = 'APPROVED' AND CURRENT_DATE BETWEEN a.start_date AND a.end_date" + SCOPE, scope(me), Long.class);
            out.add(new KpiTile("on-leave-today", "On leave today", String.valueOf(onLeave), "Approved leave within your scope",
                    "/leave/team-calendar", "navy", 12));
        }
        if (me.has(Permission.LEAVE_MANAGE)) {
            Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM leave_application a JOIN employee e ON e.id = a.employee_id " +
                    "WHERE a.status = 'PENDING'" + SCOPE, scope(me), Long.class);
            out.add(new KpiTile("leave-pending", "Leave applications pending", String.valueOf(pending), "Awaiting recommendation or approval",
                    "/leave/admin", "gold", 13));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Chart> charts(UserPrincipal me) {
        if (!me.has(Permission.LEAVE_VIEW)) return List.of();
        MapSqlParameterSource p = scope(me);
        p.addValue("year", Year.now().getValue());
        List<Map<String, Object>> data = jdbc.queryForList("SELECT t.name AS label, SUM(a.days) AS value FROM leave_application a " +
                "JOIN employee e ON e.id = a.employee_id JOIN leave_type t ON t.id = a.leave_type_id " +
                "WHERE a.status = 'APPROVED' AND a.leave_year = :year" + SCOPE + " GROUP BY t.name, t.sort_order ORDER BY t.sort_order", p);
        return List.of(new Chart("leave-by-type", "Leave taken this year (days)", "bar", data, 5));
    }

    private MapSqlParameterSource scope(UserPrincipal me) {
        Set<String> units = lookup.scopeUnits();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("scopeAll", units == null);
        p.addValue("scopeUnits", units == null || units.isEmpty() ? List.of("__NONE__") : new ArrayList<>(units));
        p.addValue("self", me.getEmployeeId() == null ? -1L : me.getEmployeeId());
        p.addValue("restrictedOk", me.has(Permission.EMP_RESTRICTED));
        return p;
    }
}
