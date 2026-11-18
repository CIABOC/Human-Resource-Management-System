package lk.gov.ciaboc.hrms.dashboard;

import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/** Headcount, cadre, approvals and retirement indicators. */
@Component
@RequiredArgsConstructor
public class CoreDashboard implements DashboardContributor {
    private final NamedParameterJdbcTemplate jdbc;
    private final EmployeeLookup lookup;
    private final EstablishmentService establishment;
    private final WorkflowService workflow;

    @Override
    @Transactional(readOnly = true)
    public List<KpiTile> tiles(UserPrincipal me) {
        List<KpiTile> out = new ArrayList<>();
        if (me.has(Permission.WFL_APPROVE)) {
            out.add(new KpiTile("approvals", "Awaiting my approval", String.valueOf(workflow.inbox().size()),
                    "Requests at a stage you can act on", "/approvals", "gold", 1));
        }
        long myOpen = workflow.myRequests().stream()
                .filter(i -> i.getStatus() == WorkflowInstance.Status.PENDING || i.getStatus() == WorkflowInstance.Status.RETURNED).count();
        out.add(new KpiTile("my-requests", "My open requests", String.valueOf(myOpen), "Pending or returned for correction",
                "/approvals?tab=mine", "navy", 2));
        if (me.has(Permission.EMP_VIEW)) {
            MapSqlParameterSource p = scope();
            Long head = jdbc.queryForObject("SELECT COUNT(*) FROM employee e WHERE e.status IN ('ACTIVE','PROBATION','ON_LEAVE','SECONDED')" + SCOPE, p, Long.class);
            out.add(new KpiTile("headcount", "Employees in service", String.valueOf(head), "Within your data scope", "/employees", "navy", 3));
            p.addValue("until", LocalDate.now().plusMonths(12));
            Long ret = jdbc.queryForObject("SELECT COUNT(*) FROM employee e WHERE e.status IN ('ACTIVE','PROBATION','ON_LEAVE','SECONDED') " +
                    "AND e.retirement_date BETWEEN CURRENT_DATE AND :until" + SCOPE, p, Long.class);
            out.add(new KpiTile("retirements", "Retirements in 12 months", String.valueOf(ret), "Based on recorded retirement dates",
                    "/reports", "neutral", 6));
        }
        if (me.has(Permission.EST_VIEW)) {
            var rows = establishment.cadreSummary(LocalDate.now(), null);
            int approved = rows.stream().mapToInt(EstablishmentService.CadreRow::approved).sum();
            long vacant = rows.stream().mapToLong(EstablishmentService.CadreRow::vacant).sum();
            out.add(new KpiTile("cadre", "Approved cadre", String.valueOf(approved), "Across all units", "/establishment/cadre", "navy", 4));
            out.add(new KpiTile("vacancies", "Vacant posts", String.valueOf(vacant), "Approved minus occupied", "/establishment/cadre", "red", 5));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Chart> charts(UserPrincipal me) {
        List<Chart> out = new ArrayList<>();
        if (me.has(Permission.EMP_VIEW)) {
            List<Map<String, Object>> byUnit = jdbc.queryForList(
                    "SELECT COALESCE(u.name, e.org_unit_code) AS label, COUNT(*) AS value FROM employee e " +
                            "LEFT JOIN org_unit u ON u.code = e.org_unit_code AND u.effective_from <= CURRENT_DATE " +
                            "AND (u.effective_to IS NULL OR u.effective_to > CURRENT_DATE) " +
                            "WHERE e.status IN ('ACTIVE','PROBATION','ON_LEAVE','SECONDED')" + SCOPE +
                            " GROUP BY 1 ORDER BY 2 DESC LIMIT 12", scope());
            out.add(new Chart("headcount-by-unit", "Headcount by unit", "bar", byUnit, 1));
            List<Map<String, Object>> byStatus = jdbc.queryForList(
                    "SELECT e.status AS label, COUNT(*) AS value FROM employee e WHERE 1=1" + SCOPE + " GROUP BY 1 ORDER BY 2 DESC", scope());
            out.add(new Chart("employees-by-status", "Employees by status", "pie", byStatus, 3));
        }
        if (me.has(Permission.EST_VIEW)) {
            Map<String, long[]> agg = new TreeMap<>();
            for (var r : establishment.cadreSummary(LocalDate.now(), null)) {
                long[] v = agg.computeIfAbsent(r.orgUnitName(), k -> new long[2]);
                v[0] += r.occupied();
                v[1] += r.vacant();
            }
            List<Map<String, Object>> data = new ArrayList<>();
            agg.forEach((k, v) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", k);
                m.put("value", v[0]);
                m.put("vacant", v[1]);
                data.add(m);
            });
            out.add(new Chart("cadre-by-unit", "Occupied vs vacant cadre", "bar", data, 2));
        }
        return out;
    }

    private static final String SCOPE = " AND (:scopeAll OR e.org_unit_code IN (:scopeUnits) OR e.id = :self) AND (:restrictedOk OR e.restricted = FALSE)";

    private MapSqlParameterSource scope() {
        Set<String> units = lookup.scopeUnits();
        var me = lk.gov.ciaboc.hrms.security.CurrentUser.get();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("scopeAll", units == null);
        p.addValue("scopeUnits", units == null || units.isEmpty() ? List.of("__NONE__") : new ArrayList<>(units));
        p.addValue("self", me.getEmployeeId() == null ? -1L : me.getEmployeeId());
        p.addValue("restrictedOk", me.has(Permission.EMP_RESTRICTED));
        return p;
    }
}
