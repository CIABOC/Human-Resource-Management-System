package lk.gov.ciaboc.hrms.reports;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReportService {
    private static final Pattern SCOPE = Pattern.compile("/\\*SCOPE:([a-zA-Z_][a-zA-Z0-9_]*)\\*/");
    private static final Pattern ORG = Pattern.compile("/\\*ORG:(.+?)\\*/");
    private static final Pattern PERIOD = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final List<ReportProvider> providers;
    private final NamedParameterJdbcTemplate jdbc;
    private final EmployeeLookup lookup;
    private final EstablishmentService establishment;
    private final SettingsService settings;
    private final AuditService audit;

    public record ReportFilters(LocalDate from, LocalDate to, LocalDate asOf, String period, String orgUnitCode,
                                Long designationId, String status) {
    }

    public record CatalogueEntry(String code, String category, String title, String description,
                                 List<ReportDefinition.Column> columns, Set<ReportDefinition.Filter> filters,
                                 boolean sensitive) {
    }

    public record ReportResult(String code, String title, String category, List<ReportDefinition.Column> columns,
                               List<Map<String, Object>> rows, boolean truncated, Instant generatedAt,
                               String generatedBy, ReportFilters filters) {
    }

    public List<CatalogueEntry> catalogue() {
        return all().stream().filter(this::permitted)
                .map(d -> new CatalogueEntry(d.code(), d.category(), d.title(), d.description(), d.columns(), d.filters(), d.sensitive()))
                .sorted(Comparator.comparing(CatalogueEntry::category).thenComparing(CatalogueEntry::title))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReportResult run(String code, ReportFilters f) {
        ReportDefinition d = definition(code);
        ReportResult r = execute(d, f == null ? new ReportFilters(null, null, null, null, null, null, null) : f);
        audit.log(AuditAction.VIEW, "Report", code, "Report run: " + d.title() + " (" + r.rows().size() + " rows)");
        return r;
    }

    /** Runs and audits an export (FR-RPT-003). */
    @Transactional(readOnly = true)
    public ReportResult runForExport(String code, ReportFilters f, String format) {
        ReportDefinition d = definition(code);
        ReportResult r = execute(d, f == null ? new ReportFilters(null, null, null, null, null, null, null) : f);
        audit.log(AuditAction.EXPORT, "Report", code, "Report exported as " + format.toUpperCase() + ": " + d.title()
                + " (" + r.rows().size() + " rows)" + (d.sensitive() ? " [SENSITIVE]" : ""));
        return r;
    }

    private ReportDefinition definition(String code) {
        ReportDefinition d = all().stream().filter(x -> x.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Report", code));
        if (!permitted(d)) throw ApiException.forbidden("You are not authorized to run this report");
        return d;
    }

    private ReportResult execute(ReportDefinition d, ReportFilters f) {
        if (f.from() != null && f.to() != null && f.to().isBefore(f.from()))
            throw ApiException.badRequest("'To' date cannot be before 'from' date");
        if (f.period() != null && !f.period().isBlank() && !PERIOD.matcher(f.period()).matches())
            throw ApiException.badRequest("Period must be in the form YYYY-MM");
        UserPrincipal me = CurrentUser.get();
        LocalDate asOf = f.asOf() == null ? LocalDate.now() : f.asOf();

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("from", f.from(), Types.DATE);
        p.addValue("to", f.to(), Types.DATE);
        p.addValue("asOf", asOf, Types.DATE);
        p.addValue("period", blank(f.period()) ? null : f.period(), Types.VARCHAR);
        p.addValue("designationId", f.designationId(), Types.BIGINT);
        p.addValue("status", blank(f.status()) ? null : f.status(), Types.VARCHAR);

        Set<String> scope = lookup.scopeUnits();
        p.addValue("scopeAll", scope == null, Types.BOOLEAN);
        p.addValue("scopeUnits", scope == null || scope.isEmpty() ? List.of("__NONE__") : new ArrayList<>(scope));
        p.addValue("restrictedOk", me.has(Permission.EMP_RESTRICTED), Types.BOOLEAN);
        p.addValue("selfEmployeeId", me.getEmployeeId(), Types.BIGINT);

        boolean orgAll = blank(f.orgUnitCode());
        p.addValue("orgUnitAll", orgAll, Types.BOOLEAN);
        p.addValue("orgUnits", orgAll ? List.of("__NONE__") : new ArrayList<>(establishment.unitAndDescendants(f.orgUnitCode(), asOf)));

        int max = settings.getInt("reports.max-rows", 10000);
        p.addValue("maxRows", max + 1, Types.INTEGER);

        String sql = expand(d.sql());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM (" + sql + ") report_q LIMIT :maxRows", p);
        boolean truncated = rows.size() > max;
        if (truncated) rows = rows.subList(0, max);
        return new ReportResult(d.code(), d.title(), d.category(), d.columns(), rows, truncated, Instant.now(),
                me.getFullName(), new ReportFilters(f.from(), f.to(), asOf, f.period(), f.orgUnitCode(), f.designationId(), f.status()));
    }

    static String expand(String sql) {
        Matcher m = SCOPE.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String a = m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(" AND (:scopeAll OR " + a + ".org_unit_code IN (:scopeUnits) OR "
                    + a + ".id = :selfEmployeeId) AND (:restrictedOk OR " + a + ".restricted = FALSE) "));
        }
        m.appendTail(sb);
        Matcher o = ORG.matcher(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        while (o.find()) {
            o.appendReplacement(sb2, Matcher.quoteReplacement(" AND (:orgUnitAll OR " + o.group(1) + " IN (:orgUnits)) "));
        }
        o.appendTail(sb2);
        return sb2.toString();
    }

    private boolean permitted(ReportDefinition d) {
        UserPrincipal me = CurrentUser.get();
        if (!me.has(Permission.RPT_VIEW)) return false;
        if (d.permission() != null && !me.getPermissions().contains(d.permission())) return false;
        return !d.sensitive() || me.has(Permission.RPT_SENSITIVE);
    }

    private List<ReportDefinition> all() {
        List<ReportDefinition> out = new ArrayList<>();
        providers.forEach(p -> out.addAll(p.reports()));
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
