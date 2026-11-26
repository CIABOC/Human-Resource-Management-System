package lk.gov.ciaboc.hrms.appraisal;

import lk.gov.ciaboc.hrms.appraisal.AppraisalEntities.*;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lk.gov.ciaboc.hrms.workflow.OrgDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Who may do what on an appraisal. Supervisor and reviewing officer come from the approved structure (the post the
 * employee reports to, then the head of the unit or its nearest ancestor); the reviewing officer is the supervisor's
 * own supervisor. Delegations (screen 15) let another authorized officer act for a period and scope; every action
 * taken under a delegation is recorded against both the delegate and the delegator.
 */
@Component
@RequiredArgsConstructor
class AppraisalAccess {
    private final AppUserRepository users;
    private final OrgDirectory org;
    private final AprDelegationRepository delegations;
    private final EmployeeLookup lookup;
    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ periods (per_period of the performance module)

    record PeriodInfo(Long id, String code, String name, LocalDate startDate, LocalDate endDate, LocalDate dueDate, String status) {
    }

    @Transactional(readOnly = true)
    public List<PeriodInfo> periods() {
        return jdbc.query("SELECT id, code, name, start_date, end_date, due_date, status FROM per_period ORDER BY start_date DESC, id DESC",
                (rs, i) -> new PeriodInfo(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        toDate(rs.getDate("start_date")), toDate(rs.getDate("end_date")), toDate(rs.getDate("due_date")),
                        rs.getString("status")));
    }

    public PeriodInfo period(Long id) {
        return periods().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Appraisal period", id));
    }

    /** The open period that contains today, else the latest open period, else the latest period; null when none. */
    public Long currentPeriodId() {
        List<PeriodInfo> all = periods();
        LocalDate today = LocalDate.now();
        return all.stream().filter(p -> "OPEN".equals(p.status()) && !today.isBefore(p.startDate()) && !today.isAfter(p.endDate()))
                .findFirst()
                .or(() -> all.stream().filter(p -> "OPEN".equals(p.status())).findFirst())
                .or(() -> all.stream().findFirst())
                .map(PeriodInfo::id).orElse(null);
    }

    /** Stage end dates of the evaluation cycle (per_cycle_stage), by stage code. */
    public Map<String, LocalDate> stageEnds(Long periodId) {
        Map<String, LocalDate> out = new HashMap<>();
        jdbc.query("SELECT stage_code, end_date FROM per_cycle_stage WHERE period_id = ? AND enabled",
                rs -> {
                    LocalDate d = toDate(rs.getDate("end_date"));
                    if (d != null) out.put(rs.getString("stage_code"), d);
                }, periodId);
        return out;
    }

    private static LocalDate toDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    // ------------------------------------------------------------------ officers

    record Officers(Long supervisorUserId, Long reviewerUserId) {
    }

    @Transactional(readOnly = true)
    public Officers resolve(Long employeeId) {
        Long sup = supervisorUserOf(employeeId);
        Long rev = null;
        if (sup != null) {
            Long supEmp = users.findById(sup).map(AppUser::getEmployeeId).orElse(null);
            if (supEmp != null) rev = supervisorUserOf(supEmp);
            if (Objects.equals(rev, sup) || Objects.equals(rev, userOfEmployee(employeeId))) rev = null;
        }
        return new Officers(sup, rev);
    }

    private Long supervisorUserOf(Long employeeId) {
        Long ownUser = users.findByEmployeeId(employeeId).map(AppUser::getId).orElse(null);
        String post = org.postOfEmployee(employeeId);
        Set<String> seen = new HashSet<>();
        if (post != null) {
            String cur = org.post(post).map(OrgDirectory.PostInfo::reportsTo).orElse(null);
            while (cur != null && !cur.isBlank() && seen.add(cur)) {
                Long u = firstOther(org.userIdsHoldingPosition(cur), ownUser);
                if (u != null) return u;
                cur = org.post(cur).map(OrgDirectory.PostInfo::reportsTo).orElse(null);
            }
        }
        for (String unit : org.unitAndAncestors(org.orgUnitOfEmployee(employeeId))) {
            String head = org.headPostOfUnit(unit);
            if (head == null || head.equals(post) || seen.contains(head)) continue;
            Long u = firstOther(org.userIdsHoldingPosition(head), ownUser);
            if (u != null) return u;
        }
        return null;
    }

    private static Long firstOther(Set<Long> ids, Long exclude) {
        return ids == null ? null : ids.stream().filter(id -> !id.equals(exclude)).min(Long::compare).orElse(null);
    }

    public String nameOf(Long userId) {
        if (userId == null) return null;
        return users.findById(userId).map(AppUser::getFullName).orElse(null);
    }

    public Map<Long, String> namesOf(Collection<Long> userIds) {
        Map<Long, String> out = new HashMap<>();
        users.findAllById(userIds.stream().filter(Objects::nonNull).distinct().toList()).forEach(u -> out.put(u.getId(), u.getFullName()));
        return out;
    }

    public Long userOfEmployee(Long employeeId) {
        return employeeId == null ? null : users.findByEmployeeId(employeeId).filter(AppUser::isActive).map(AppUser::getId).orElse(null);
    }

    // ------------------------------------------------------------------ delegation

    /** Users the current user may act for (delegator id -> name), excluding themself. */
    public Map<Long, String> delegatorsOf(Long userId, boolean approval) {
        LocalDate today = LocalDate.now();
        Map<Long, String> out = new HashMap<>();
        for (Delegation d : delegations.findByDelegateUserIdAndStatus(userId, "ACTIVE")) {
            if (today.isBefore(d.getDateFrom()) || today.isAfter(d.getDateTo())) continue;
            boolean ok = d.getScope() == DelegationScope.ALL
                    || (approval ? d.getScope() == DelegationScope.APPROVAL : d.getScope() == DelegationScope.VERIFICATION_AND_REVIEW);
            if (ok) out.put(d.getDelegatorUserId(), d.getDelegatorName());
        }
        return out;
    }

    /** Result of an authority check: allowed, and the name of the delegator when acting under a delegation. */
    record Acting(boolean allowed, String onBehalfOf) {
        static final Acting NO = new Acting(false, null);
        static final Acting SELF = new Acting(true, null);
    }

    public boolean isEmployee(Appraisal a) {
        Long me = CurrentUser.get().getEmployeeId();
        return me != null && me.equals(a.getEmployeeId());
    }

    public Acting asSupervisor(Appraisal a) {
        UserPrincipal me = CurrentUser.get();
        if (isEmployee(a) || a.getSupervisorUserId() == null) return Acting.NO;
        if (a.getSupervisorUserId().equals(me.getId())) return Acting.SELF;
        String d = delegatorsOf(me.getId(), false).get(a.getSupervisorUserId());
        return d != null ? new Acting(true, d) : Acting.NO;
    }

    /** Reviewing officer, a delegate of theirs, or - when no reviewing officer is set - an appraisal administrator. */
    public Acting asReviewer(Appraisal a) {
        UserPrincipal me = CurrentUser.get();
        if (isEmployee(a)) return Acting.NO;
        if (a.getReviewerUserId() == null) {
            return me.has(Permission.APR_MANAGE) && !me.getId().equals(a.getSupervisorUserId()) && inScope(a) ? Acting.SELF : Acting.NO;
        }
        if (a.getReviewerUserId().equals(me.getId())) return Acting.SELF;
        String d = delegatorsOf(me.getId(), true).get(a.getReviewerUserId());
        return d != null ? new Acting(true, d) : Acting.NO;
    }

    public boolean isHr(Appraisal a) {
        UserPrincipal me = CurrentUser.get();
        return me.has(Permission.APR_MANAGE) && !isEmployee(a) && inScope(a);
    }

    public boolean inScope(Appraisal a) {
        Set<Long> visible = lookup.visibleEmployeeIds();
        return visible == null || visible.contains(a.getEmployeeId());
    }

    public boolean canView(Appraisal a) {
        return isEmployee(a) || asSupervisor(a).allowed() || asReviewer(a).allowed() || isHr(a);
    }

    public void requireView(Appraisal a) {
        if (!canView(a)) throw ApiException.notFound("Appraisal", a.getId());
    }

    /** Ids of supervisors the current user acts for (self plus delegators). */
    public Set<Long> supervisorIds() {
        Long me = CurrentUser.get().getId();
        Set<Long> ids = new HashSet<>(delegatorsOf(me, false).keySet());
        ids.add(me);
        return ids;
    }

    public Set<Long> reviewerIds() {
        Long me = CurrentUser.get().getId();
        Set<Long> ids = new HashSet<>(delegatorsOf(me, true).keySet());
        ids.add(me);
        return ids;
    }
}
