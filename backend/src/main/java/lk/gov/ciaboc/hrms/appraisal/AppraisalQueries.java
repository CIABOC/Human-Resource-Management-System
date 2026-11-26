package lk.gov.ciaboc.hrms.appraisal;

import lk.gov.ciaboc.hrms.appraisal.AppraisalAccess.PeriodInfo;
import lk.gov.ciaboc.hrms.appraisal.AppraisalEntities.*;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.establishment.OrgUnit;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read models: the caller's role context, the supervisor review queue (screen 3), the reviewing officer queue
 * (screen 12) and the aggregated management dashboard (screen 5). The management view shows process completion
 * separately from aggregated results; individual results appear only in the restricted drill-down.
 */
@Service
@RequiredArgsConstructor
public class AppraisalQueries {
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    private final AprAppraisalRepository appraisals;
    private final AprKpiRepository kpis;
    private final AprPolicyVersionRepository policies;
    private final AprRatingBandRepository bands;
    private final AppraisalAccess access;
    private final AppraisalService service;
    private final EmployeeLookup lookup;
    private final EstablishmentService establishment;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ context

    public record Context(Long employeeId, Long currentPeriodId, List<PeriodInfo> periods, boolean hasAppraisal,
                          boolean supervisor, boolean reviewer, boolean manage, boolean overview, boolean report,
                          boolean policyApprove, boolean admin, int pendingVerifications, int pendingApprovals) {
    }

    @Transactional(readOnly = true)
    public Context context() {
        UserPrincipal me = CurrentUser.get();
        Long pid = access.currentPeriodId();
        boolean has = me.getEmployeeId() != null && !appraisals.findByEmployeeIdOrderByIdDesc(me.getEmployeeId()).isEmpty();
        List<Appraisal> team = appraisals.findBySupervisorUserIdIn(access.supervisorIds());
        List<Appraisal> review = appraisals.findByReviewerUserIdIn(access.reviewerIds());
        boolean manage = me.has(Permission.APR_MANAGE);
        int pendingVer = (int) team.stream().filter(a -> a.getStatus() == Status.SUBMITTED).count();
        int pendingApp = (int) review.stream().filter(a -> a.getStatus() == Status.VERIFIED).count();
        return new Context(me.getEmployeeId(), pid, access.periods(), has, !team.isEmpty(), !review.isEmpty() || manage,
                manage, me.has(Permission.APR_OVERVIEW), me.has(Permission.APR_REPORT),
                me.has(Permission.APR_POLICY_APPROVE) && !me.hasRole("SYSTEM_ADMIN"), me.has(Permission.APR_ADMIN),
                pendingVer, pendingApp);
    }

    // ------------------------------------------------------------------ supervisor (screen 3)

    public record QueueRow(Long appraisalId, Long employeeId, String employeeNo, String employeeName, String designation,
                           String orgUnitName, int kpisVerified, int kpisTotal, String selfStatus, String behaviourStatus,
                           String status, String agreementStatus, String queueStatus, String delegatedFrom, boolean overdue,
                           BigDecimal kpiScore, BigDecimal behaviourScore, BigDecimal finalScore, String category) {
    }

    public record TeamDashboard(int employees, int pendingVerifications, int overdueActions, int assessmentsCompleted,
                                int assessmentsTotal, List<QueueRow> queue) {
    }

    @Transactional(readOnly = true)
    public TeamDashboard team(Long periodId) {
        Long pid = periodId != null ? periodId : access.currentPeriodId();
        Long me = CurrentUser.get().getId();
        Map<Long, String> delegators = access.delegatorsOf(me, false);
        List<Appraisal> list = appraisals.findBySupervisorUserIdIn(access.supervisorIds()).stream()
                .filter(a -> pid == null || a.getPeriodId().equals(pid)).toList();
        List<QueueRow> rows = rows(list, a -> a.getSupervisorUserId().equals(me) ? null : delegators.get(a.getSupervisorUserId()));
        return new TeamDashboard(rows.size(),
                (int) rows.stream().filter(r -> "Verification pending".equals(r.queueStatus()) || "Progress to verify".equals(r.queueStatus())).count(),
                (int) rows.stream().filter(QueueRow::overdue).count(),
                (int) rows.stream().filter(r -> "SUBMITTED".equals(r.behaviourStatus())).count(), rows.size(), rows);
    }

    /** Team members of the current user (for check-ins, feedback and special cases). */
    public record Member(Long employeeId, String employeeNo, String name, String designation, Long appraisalId) {
    }

    @Transactional(readOnly = true)
    public List<Member> members() {
        UserPrincipal me = CurrentUser.get();
        Long pid = access.currentPeriodId();
        List<Appraisal> list = appraisals.findBySupervisorUserIdIn(access.supervisorIds()).stream()
                .filter(a -> pid == null || a.getPeriodId().equals(pid)).toList();
        if (me.has(Permission.APR_MANAGE) && pid != null) {
            Set<Long> visible = lookup.visibleEmployeeIds();
            List<Appraisal> all = appraisals.findByPeriodId(pid).stream()
                    .filter(a -> visible == null || visible.contains(a.getEmployeeId()))
                    .filter(a -> !a.getEmployeeId().equals(me.getEmployeeId())).toList();
            Set<Long> seen = list.stream().map(Appraisal::getId).collect(Collectors.toSet());
            list = new ArrayList<>(list);
            for (Appraisal a : all) if (!seen.contains(a.getId())) list.add(a);
        }
        Map<Long, EmployeeLookup.EmployeeBrief> b = lookup.briefs(list.stream().map(Appraisal::getEmployeeId).toList());
        return list.stream().filter(a -> b.containsKey(a.getEmployeeId())).map(a -> {
            EmployeeLookup.EmployeeBrief e = b.get(a.getEmployeeId());
            return new Member(e.id(), e.employeeNo(), e.name(), e.designation(), a.getId());
        }).sorted(Comparator.comparing(Member::name)).toList();
    }

    // ------------------------------------------------------------------ reviewing officer (screen 12)

    @Transactional(readOnly = true)
    public List<QueueRow> approvals(Long periodId) {
        UserPrincipal me = CurrentUser.get();
        Map<Long, String> delegators = access.delegatorsOf(me.getId(), true);
        List<Appraisal> list = new ArrayList<>(appraisals.findByReviewerUserIdIn(access.reviewerIds()));
        if (me.has(Permission.APR_MANAGE)) {
            // Appraisals without a reviewing officer are approved by appraisal administrators.
            Set<Long> ids = list.stream().map(Appraisal::getId).collect(Collectors.toSet());
            appraisals.findByStatus(Status.VERIFIED).stream()
                    .filter(a -> a.getReviewerUserId() == null && !ids.contains(a.getId()) && access.asReviewer(a).allowed())
                    .forEach(list::add);
        }
        List<Appraisal> filtered = list.stream()
                .filter(a -> periodId == null || a.getPeriodId().equals(periodId))
                .filter(a -> a.getStatus() == Status.VERIFIED || a.getStatus() == Status.APPROVED || a.getStatus() == Status.FINALIZED)
                .toList();
        return rows(filtered, a -> a.getReviewerUserId() == null || a.getReviewerUserId().equals(me.getId()) ? null : delegators.get(a.getReviewerUserId()))
                .stream().sorted(Comparator.comparing((QueueRow r) -> !"VERIFIED".equals(r.status())).thenComparing(QueueRow::employeeName)).toList();
    }

    private List<QueueRow> rows(List<Appraisal> list, java.util.function.Function<Appraisal, String> delegatedFrom) {
        if (list.isEmpty()) return List.of();
        Map<Long, EmployeeLookup.EmployeeBrief> b = lookup.briefs(list.stream().map(Appraisal::getEmployeeId).toList());
        Map<Long, List<Kpi>> byAppraisal = kpis.findByAppraisalIdIn(list.stream().map(Appraisal::getId).toList()).stream()
                .collect(Collectors.groupingBy(Kpi::getAppraisalId));
        Map<Long, PeriodInfo> periods = access.periods().stream().collect(Collectors.toMap(PeriodInfo::id, p -> p));
        LocalDate today = LocalDate.now(ZONE);
        List<QueueRow> out = new ArrayList<>();
        for (Appraisal a : list) {
            EmployeeLookup.EmployeeBrief e = b.get(a.getEmployeeId());
            if (e == null) continue;
            List<Kpi> ks = byAppraisal.getOrDefault(a.getId(), List.of()).stream().filter(k -> !k.isExcepted()).toList();
            int verified = (int) ks.stream().filter(k -> k.getDecision() == Decision.VERIFIED || k.getDecision() == Decision.ADJUSTED).count();
            boolean progressToVerify = ks.stream().anyMatch(k -> k.getProgressStatus() == ProgressStatus.SUBMITTED);
            PeriodInfo p = periods.get(a.getPeriodId());
            LocalDate closes = p == null ? null : service.selfAssessmentCloses(a, p);
            boolean overdue = a.getStatus() != Status.FINALIZED && closes != null && today.isAfter(closes);
            out.add(new QueueRow(a.getId(), e.id(), e.employeeNo(), e.name(), e.designation(), e.orgUnitName(), verified, ks.size(),
                    a.getSelfStatus().name(), a.getBehaviourStatus().name(), a.getStatus().name(), a.getAgreementStatus().name(),
                    queueStatus(a, progressToVerify), delegatedFrom.apply(a), overdue,
                    a.getKpiScore(), a.getBehaviourScore(), a.getFinalScore(), a.getCategory()));
        }
        out.sort(Comparator.comparing(QueueRow::employeeName));
        return out;
    }

    private static String queueStatus(Appraisal a, boolean progressToVerify) {
        if (a.getAgreementStatus() == AgreementStatus.SUBMITTED) return "Agreement to sign";
        if (a.getAgreementStatus() == AgreementStatus.SUPERVISOR_SIGNED) return "Agreement with reviewer";
        if (a.getAgreementStatus() == AgreementStatus.DRAFT) return "Agreement in draft";
        return switch (a.getStatus()) {
            case DRAFT -> progressToVerify ? "Progress to verify" : "Awaiting employee";
            case SUBMITTED -> a.getVerificationCompletedAt() == null ? "Verification pending"
                    : a.getBehaviourStatus() != StepStatus.SUBMITTED ? "Assessment pending" : "Ready for review";
            case VERIFIED -> "With reviewing officer";
            case APPROVED -> "Awaiting acknowledgement";
            case ACKNOWLEDGED, FINALIZED -> "Finalized";
        };
    }

    // ------------------------------------------------------------------ management (screen 5)

    public record CompletionRow(String unitCode, String unitName, int total, int completed, int pending, int overdue) {
    }

    public record Spread(String category, int count) {
    }

    public record ResultRow(String unitCode, String unitName, int finalized, BigDecimal averageScore, List<Spread> spread) {
    }

    public record Overview(PeriodInfo period, Instant dataAsOf, int totalAppraisals, int completed, int pending, int overdue,
                           BigDecimal averageScore, List<String> categories, List<Spread> categorySpread,
                           List<CompletionRow> completion, List<ResultRow> results, Map<String, Integer> stages,
                           int pendingApprovals, boolean canDrillDown, boolean canExport, BigDecimal kpiWeight, BigDecimal behaviourWeight) {
    }

    @Transactional(readOnly = true)
    public Overview overview(Long periodId, String orgUnitCode, Long designationId) {
        UserPrincipal me = CurrentUser.get();
        if (!me.has(Permission.APR_OVERVIEW)) throw ApiException.forbidden("The appraisal dashboard is restricted to management");
        Long pid = periodId != null ? periodId : access.currentPeriodId();
        if (pid == null) return emptyOverview();
        PeriodInfo p = access.period(pid);
        List<Row> rows = scopedRows(pid, orgUnitCode, designationId, false);
        LocalDate today = LocalDate.now(ZONE);
        Map<String, String> unitNames = unitNames();
        Map<String, String> parent = parents();

        Map<String, List<Row>> byDept = new TreeMap<>();
        for (Row r : rows) byDept.computeIfAbsent(department(r.unitCode(), parent), k -> new ArrayList<>()).add(r);

        List<String> cats = categories();
        List<CompletionRow> completion = new ArrayList<>();
        List<ResultRow> results = new ArrayList<>();
        for (Map.Entry<String, List<Row>> en : byDept.entrySet()) {
            List<Row> rs = en.getValue();
            int done = (int) rs.stream().filter(Row::finalized).count();
            int overdue = (int) rs.stream().filter(r -> !r.finalized() && r.closes() != null && today.isAfter(r.closes())).count();
            completion.add(new CompletionRow(en.getKey(), unitNames.getOrDefault(en.getKey(), en.getKey()), rs.size(), done, rs.size() - done - overdue, overdue));
            List<Row> fin = rs.stream().filter(r -> r.finalized() && r.finalScore() != null).toList();
            results.add(new ResultRow(en.getKey(), unitNames.getOrDefault(en.getKey(), en.getKey()), fin.size(), avg(fin), spread(fin, cats)));
        }
        completion.sort(Comparator.comparing(CompletionRow::unitName));
        results.sort(Comparator.comparing(ResultRow::unitName));
        List<Row> allFin = rows.stream().filter(r -> r.finalized() && r.finalScore() != null).toList();
        int done = (int) rows.stream().filter(Row::finalized).count();
        int overdue = (int) rows.stream().filter(r -> !r.finalized() && r.closes() != null && today.isAfter(r.closes())).count();
        Map<String, Integer> stages = new LinkedHashMap<>();
        for (String s : List.of("Agreement", "In progress", "Self-assessment submitted", "Verified", "Approved", "Finalized")) stages.put(s, 0);
        for (Row r : rows) stages.merge(stage(r), 1, Integer::sum);
        int pendingApprovals = (int) rows.stream().filter(r -> "VERIFIED".equals(r.status())).count();
        PolicyVersion pol = policies.findFirstByStatusOrderByVersionNoDesc(PolicyStatus.APPROVED).orElse(null);
        audit.log(AuditAction.VIEW, AppraisalService.ENTITY, pid, "Viewed aggregated appraisal dashboard for period " + p.code());
        return new Overview(p, Instant.now(), rows.size(), done, rows.size() - done - overdue, overdue, avg(allFin), cats, spread(allFin, cats),
                completion, results, stages, pendingApprovals, me.has(Permission.APR_REPORT), me.has(Permission.APR_REPORT) && me.has(Permission.RPT_VIEW),
                pol == null ? null : pol.getKpiWeight(), pol == null ? null : pol.getBehaviourWeight());
    }

    private Overview emptyOverview() {
        return new Overview(null, Instant.now(), 0, 0, 0, 0, null, categories(), List.of(), List.of(), List.of(), Map.of(), 0, false, false, null, null);
    }

    public record DrillRow(Long appraisalId, String employeeNo, String employeeName, String designation, String unitName,
                           String status, BigDecimal kpiScore, BigDecimal behaviourScore, BigDecimal finalScore, String category) {
    }

    /** Restricted: individual results of one department, only within the caller's authorized units. */
    @Transactional(readOnly = true)
    public List<DrillRow> drilldown(Long periodId, String departmentCode, Long designationId) {
        UserPrincipal me = CurrentUser.get();
        if (!me.has(Permission.APR_REPORT)) throw ApiException.forbidden("Drill-down requires authorization");
        Long pid = periodId != null ? periodId : access.currentPeriodId();
        if (pid == null || departmentCode == null) return List.of();
        List<Row> rows = scopedRows(pid, departmentCode, designationId, true);
        Map<String, String> unitNames = unitNames();
        audit.log(AuditAction.VIEW, AppraisalService.ENTITY, pid, "Drilled down into appraisal results of " + departmentCode);
        return rows.stream().map(r -> new DrillRow(r.appraisalId(), r.employeeNo(), r.name(), r.designation(),
                        unitNames.getOrDefault(r.unitCode(), r.unitCode()), r.status(), r.kpiScore(), r.behaviourScore(),
                        r.finalized() ? r.finalScore() : null, r.finalized() ? r.category() : null))
                .sorted(Comparator.comparing(DrillRow::employeeName)).toList();
    }

    private record Row(Long appraisalId, Long employeeId, String employeeNo, String name, String designation, String unitCode,
                       String status, String agreementStatus, boolean finalized, BigDecimal kpiScore, BigDecimal behaviourScore,
                       BigDecimal finalScore, String category, LocalDate closes) {
    }

    private List<Row> scopedRows(Long periodId, String orgUnitCode, Long designationId, boolean restrictToScope) {
        Set<String> units = orgUnitCode == null || orgUnitCode.isBlank() ? null : establishment.unitAndDescendants(orgUnitCode, LocalDate.now());
        Set<Long> visible = restrictToScope ? lookup.visibleEmployeeIds() : null;
        PeriodInfo p = access.period(periodId);
        List<Row> out = new ArrayList<>();
        jdbc.query("SELECT a.id, a.employee_id, e.employee_no, COALESCE(e.name_with_initials, e.full_name) AS name, d.name AS designation, " +
                        "e.org_unit_code, e.designation_id, a.status, a.agreement_status, a.kpi_score, a.behaviour_score, a.final_score, a.category " +
                        "FROM apr_appraisal a JOIN employee e ON e.id = a.employee_id LEFT JOIN designation d ON d.id = e.designation_id " +
                        "WHERE a.period_id = ?",
                rs -> {
                    String unit = rs.getString("org_unit_code");
                    long des = rs.getLong("designation_id");
                    long emp = rs.getLong("employee_id");
                    if (units != null && !units.contains(unit)) return;
                    if (designationId != null && designationId != des) return;
                    if (visible != null && !visible.contains(emp)) return;
                    String status = rs.getString("status");
                    out.add(new Row(rs.getLong("id"), emp, rs.getString("employee_no"), rs.getString("name"), rs.getString("designation"),
                            unit, status, rs.getString("agreement_status"), "FINALIZED".equals(status),
                            rs.getBigDecimal("kpi_score"), rs.getBigDecimal("behaviour_score"), rs.getBigDecimal("final_score"),
                            rs.getString("category"), null));
                }, periodId);
        LocalDate closes = p.dueDate() != null ? p.dueDate() : p.endDate().plusDays(30);
        return out.stream().map(r -> new Row(r.appraisalId(), r.employeeId(), r.employeeNo(), r.name(), r.designation(), r.unitCode(),
                r.status(), r.agreementStatus(), r.finalized(), r.kpiScore(), r.behaviourScore(), r.finalScore(), r.category(), closes)).toList();
    }

    private static String stage(Row r) {
        if (!"FINAL".equals(r.agreementStatus())) return "Agreement";
        return switch (r.status()) {
            case "DRAFT" -> "In progress";
            case "SUBMITTED" -> "Self-assessment submitted";
            case "VERIFIED" -> "Verified";
            case "APPROVED", "ACKNOWLEDGED" -> "Approved";
            default -> "Finalized";
        };
    }

    private List<String> categories() {
        return policies.findFirstByStatusOrderByVersionNoDesc(PolicyStatus.APPROVED)
                .map(p -> bands.findByPolicyVersionIdOrderBySortOrderAsc(p.getId()).stream().map(RatingBand::getCategory).toList())
                .orElse(List.of());
    }

    private static List<Spread> spread(List<Row> fin, List<String> cats) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        cats.forEach(c -> counts.put(c, 0));
        for (Row r : fin) if (r.category() != null) counts.merge(r.category(), 1, Integer::sum);
        return counts.entrySet().stream().map(e -> new Spread(e.getKey(), e.getValue())).toList();
    }

    private static BigDecimal avg(List<Row> fin) {
        if (fin.isEmpty()) return null;
        return fin.stream().map(Row::finalScore).reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(fin.size()), 2, RoundingMode.HALF_UP);
    }

    private Map<String, String> unitNames() {
        return establishment.orgUnits(LocalDate.now()).stream().collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getName, (a, b) -> a));
    }

    private Map<String, String> parents() {
        Map<String, String> m = new HashMap<>();
        establishment.orgUnits(LocalDate.now()).forEach(u -> m.put(u.getCode(), u.getParentCode()));
        return m;
    }

    /** Department = the unit directly below the root of the structure (the unit itself when it is the root). */
    private static String department(String unit, Map<String, String> parent) {
        if (unit == null) return "-";
        String cur = unit;
        Set<String> seen = new HashSet<>();
        while (seen.add(cur)) {
            String p = parent.get(cur);
            if (p == null || p.isBlank()) return cur;
            String pp = parent.get(p);
            if (pp == null || pp.isBlank()) return cur;
            cur = p;
        }
        return cur;
    }
}
