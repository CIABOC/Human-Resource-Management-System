package lk.gov.ciaboc.hrms.extended;

import lk.gov.ciaboc.hrms.dashboard.DashboardContributor;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.extended.separation.SeparationService;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Extended-scope dashboard tiles: retirements, open clearance cases, letter requests and the user's clearance tasks. */
@Component
@RequiredArgsConstructor
public class ExtendedDashboard implements DashboardContributor {
    private static final String SCOPE = " AND (:scopeAll OR e.org_unit_code IN (:scopeUnits) OR e.id = :self) AND (:restrictedOk OR e.restricted = FALSE)";

    private final NamedParameterJdbcTemplate jdbc;
    private final EmployeeLookup lookup;
    private final SeparationService separations;

    @Override
    @Transactional(readOnly = true)
    public List<KpiTile> tiles(UserPrincipal me) {
        List<KpiTile> out = new ArrayList<>();
        boolean view = me.has(Permission.EXT_VIEW) || me.has(Permission.EXT_MANAGE);
        if (view) {
            MapSqlParameterSource p = scope(me);
            p.addValue("until", LocalDate.now().plusMonths(6));
            Long ret = jdbc.queryForObject("SELECT COUNT(*) FROM employee e WHERE e.status IN ('ACTIVE','PROBATION','ON_LEAVE','SECONDED') " +
                    "AND e.retirement_date BETWEEN CURRENT_DATE AND :until" + SCOPE, p, Long.class);
            out.add(new KpiTile("ext-retirements-6m", "Retirements in 6 months", String.valueOf(ret),
                    "Plan replacements and start offboarding", "/extended/retirements", "gold", 20));
            Long open = jdbc.queryForObject("SELECT COUNT(*) FROM ext_separation_case c JOIN employee e ON e.id = c.employee_id " +
                    "WHERE c.status = 'OPEN'" + SCOPE, scope(me), Long.class);
            out.add(new KpiTile("ext-open-clearance", "Open clearance cases", String.valueOf(open),
                    "Separations awaiting clearance or completion", "/extended/retirements?tab=cases", "navy", 21));
        }
        if (me.has(Permission.EXT_MANAGE)) {
            Long letters = jdbc.queryForObject("SELECT COUNT(*) FROM ext_letter_request r JOIN employee e ON e.id = r.employee_id " +
                    "WHERE r.status IN ('PENDING','APPROVED')" + SCOPE, scope(me), Long.class);
            out.add(new KpiTile("ext-letter-requests", "Letter requests pending", String.valueOf(letters),
                    "Awaiting approval or issue", "/extended/letters", "neutral", 22));
        }
        int tasks = separations.myTasks().size();
        if (tasks > 0) {
            out.add(new KpiTile("ext-my-clearance", "My clearance tasks", String.valueOf(tasks),
                    "Separation clearance items assigned to your role", "/extended/clearance", "red", 23));
        }
        return out;
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
