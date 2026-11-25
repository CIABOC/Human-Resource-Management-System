package lk.gov.ciaboc.hrms.attendance;

import lk.gov.ciaboc.hrms.dashboard.DashboardContributor;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataUsageChecker;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Attendance tiles (absent / late today within scope, events awaiting review); also guards category codes used by schedules. */
@Component
@RequiredArgsConstructor
public class AttendanceDashboard implements DashboardContributor, MasterDataUsageChecker {
    private static final String SCOPE = " AND (:scopeAll OR e.org_unit_code IN (:scopeUnits) OR e.id = :self) AND (:restrictedOk OR e.restricted = FALSE)";

    private final NamedParameterJdbcTemplate jdbc;
    private final EmployeeLookup lookup;
    private final AttendanceEventRepository events;
    private final DutyPolicyAssignmentRepository schedules;
    private final AttendanceProcessingService processing;

    @Override
    @Transactional(readOnly = true)
    public List<KpiTile> tiles(UserPrincipal me) {
        List<KpiTile> out = new ArrayList<>();
        if (me.has(Permission.ATT_VIEW)) {
            MapSqlParameterSource p = scope(me);
            p.addValue("d", processing.today());
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT d.status, COUNT(*) AS n FROM attendance_daily d JOIN employee e ON e.id = d.employee_id " +
                    "WHERE d.work_date = :d" + SCOPE + " GROUP BY d.status", p);
            long absent = 0, late = 0;
            for (Map<String, Object> r : rows) {
                long n = ((Number) r.get("n")).longValue();
                if ("ABSENT".equals(r.get("status"))) absent = n;
                if ("LATE".equals(r.get("status"))) late = n;
            }
            String hint = rows.isEmpty() ? "Today's attendance has not been processed yet" : "From processed attendance";
            out.add(new KpiTile("att-absent-today", "Absent today", String.valueOf(absent), hint, "/attendance?status=ABSENT", "red", 20));
            out.add(new KpiTile("att-late-today", "Late arrivals today", String.valueOf(late), hint, "/attendance?status=LATE", "neutral", 21));
        }
        if (me.has(Permission.ATT_MANAGE)) {
            out.add(new KpiTile("att-review", "Attendance events to review", String.valueOf(events.countAwaitingReview()),
                    "Flagged duplicates, odd hours and unknown employees", "/attendance/events", "gold", 22));
        }
        return out;
    }

    @Override
    public long usageCount(String category, String code) {
        if (!MasterDataCategory.EMPLOYEE_CATEGORY.name().equals(category)) return 0;
        return schedules.countCurrentByCategory(code);
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
