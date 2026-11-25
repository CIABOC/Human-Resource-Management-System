package lk.gov.ciaboc.hrms.attendance;

import lk.gov.ciaboc.hrms.attendance.DutyPolicyAssignment.ScopeType;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.Json;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Effective-dated working schedules (FR-ATT-010..013), stored since V60 as duty-time policies (REQ 1): each version is a
 * {@link DutyPolicy} row and where it applies is a {@link DutyPolicyAssignment}. A change creates a new version from its
 * effective date; the previous version is closed, never overwritten, so past attendance can always be re-evaluated
 * against the rules that applied at the time.
 * <p>
 * Versions saved here are approved immediately (they are made by holders of ATT_CONFIG). The DUTY_TIME_POLICY approval
 * workflow, flexible and shift-roster policies and employee-level assignments of REQ 1 are honoured when resolving, but
 * cannot yet be maintained from this service.
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {
    private static final String AUTHORITY = "Attendance configuration";

    private final DutyPolicyRepository policies;
    private final DutyPolicyAssignmentRepository assignments;
    private final EstablishmentService establishment;
    private final MasterDataService masterData;
    private final AuditService audit;

    public record ScheduleRequest(String code, String name, LocalTime startTime, LocalTime endTime, Integer lateGraceMinutes,
                                  Integer earlyGraceMinutes, List<String> workDays, String orgUnitCode,
                                  String employeeCategory, LocalDate effectiveFrom, String remarks) {
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<WorkSchedule> effectiveOn(LocalDate date) {
        LocalDate d = date == null ? LocalDate.now() : date;
        return policies.approvedOn(d).stream().map(p -> WorkSchedule.of(p, groupAssignment(p.getCode(), d))).toList();
    }

    /** Every approved version, newest first per code, for configuration screens. */
    @Transactional(readOnly = true)
    public List<WorkSchedule> all() {
        return policies.allApproved().stream().map(p -> WorkSchedule.of(p, groupAssignment(p.getCode(), p.getEffectiveFrom()))).toList();
    }

    @Transactional(readOnly = true)
    public List<WorkSchedule> history(String code) {
        List<DutyPolicy> h = policies.approvedHistory(code);
        if (h.isEmpty()) throw ApiException.notFound("Working schedule", code);
        return h.stream().map(p -> WorkSchedule.of(p, groupAssignment(code, p.getEffectiveFrom()))).toList();
    }

    /** Snapshot of the policies and assignments in force on {@code d}, for resolving many employees at once. */
    @Transactional(readOnly = true)
    public Resolver resolverOn(LocalDate d) {
        Map<String, DutyPolicy> byCode = policies.approvedOn(d).stream()
                .collect(Collectors.toMap(DutyPolicy::getCode, Function.identity(), (a, b) -> a.getEffectiveFrom().isAfter(b.getEffectiveFrom()) ? a : b));
        List<DutyPolicyAssignment> active = assignments.effectiveOn(d).stream().filter(a -> byCode.containsKey(a.getPolicyCode())).toList();
        return new Resolver(byCode, active);
    }

    /**
     * The schedule applying to one employee (REQ 1.3): an EMPLOYEE assignment, else a CATEGORY one, else the DIVISION
     * assignment of the nearest unit up the hierarchy, else ORGANIZATION. Ties go to the latest effective date.
     */
    public static final class Resolver {
        private final Map<String, DutyPolicy> byCode;
        private final List<DutyPolicyAssignment> active;

        Resolver(Map<String, DutyPolicy> byCode, List<DutyPolicyAssignment> active) {
            this.byCode = byCode;
            this.active = active;
        }

        public Optional<WorkSchedule> resolve(Long employeeId, List<String> unitAndAncestors, String category) {
            DutyPolicyAssignment best = null;
            int bestRank = Integer.MAX_VALUE;
            for (DutyPolicyAssignment a : active) {
                int rank = switch (a.getScopeType()) {
                    case EMPLOYEE -> Objects.equals(a.getEmployeeId(), employeeId) ? 0 : -1;
                    case CATEGORY -> category != null && category.equals(a.getEmployeeCategory()) ? 1 : -1;
                    case DIVISION -> {
                        int idx = unitAndAncestors == null ? -1 : unitAndAncestors.indexOf(a.getOrgUnitCode());
                        yield idx < 0 ? -1 : 10 + idx;
                    }
                    case ORGANIZATION -> 100_000;
                };
                if (rank < 0) continue;
                if (rank < bestRank || (rank == bestRank && a.getEffectiveFrom().isAfter(best.getEffectiveFrom()))) {
                    best = a;
                    bestRank = rank;
                }
            }
            return best == null ? Optional.empty() : Optional.of(WorkSchedule.of(byCode.get(best.getPolicyCode()), best));
        }
    }

    public List<String> unitChain(String unitCode, LocalDate d) {
        return unitCode == null ? List.of() : establishment.unitAndAncestors(unitCode, d);
    }

    /** Assignments still in force for a category (guards deactivation of the category code). */
    @Transactional(readOnly = true)
    public long countCurrentByCategory(String category) {
        return assignments.countCurrentByCategory(category);
    }

    // ------------------------------------------------------------------ changes

    @Transactional
    public WorkSchedule create(ScheduleRequest r) {
        if (r == null) throw ApiException.badRequest("Schedule details are required");
        if (r.code() == null || !r.code().matches("^[A-Z0-9_-]{1,40}$"))
            throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits, '-' or '_'");
        if (policies.existsByCode(r.code())) throw ApiException.conflict("Schedule code " + r.code() + " already exists");
        Validated v = validate(r);
        DutyPolicy p = new DutyPolicy();
        p.setCode(r.code());
        p.setVersionNo(1);
        p.setPolicyType(DutyPolicy.PolicyType.FIXED);
        p.setLateThresholdMinutes(120);
        p.setEarlyThresholdMinutes(120);
        apply(p, v, r);
        policies.save(p);
        assignments.save(newAssignment(r.code(), v, r.effectiveFrom(), "Working schedule " + r.code() + " created"));
        audit.log(AuditAction.CREATE, "DutyPolicy", p.getId(), "Working schedule " + p.getCode() + " created from " + p.getEffectiveFrom(),
                null, Json.write(r));
        return WorkSchedule.of(p, groupAssignment(p.getCode(), p.getEffectiveFrom()));
    }

    /** New version of an existing schedule from {@code r.effectiveFrom()} (FR-ATT-013: changes keep history). */
    @Transactional
    public WorkSchedule revise(String code, ScheduleRequest r) {
        if (r == null || r.effectiveFrom() == null) throw ApiException.badRequest("Effective from date is required");
        LocalDate d = r.effectiveFrom();
        DutyPolicy cur = policies.approvedOn(code, d).orElseThrow(() -> ApiException.rule("NOT_EFFECTIVE",
                "Schedule " + code + " is not in force on " + d + "; create a new schedule instead"));
        if (policies.countStartingAfter(code, d) > 0)
            throw ApiException.rule("FUTURE_VERSION", "A later version of schedule " + code + " already exists; revise that version instead");
        Validated v = validate(r);
        String before = Json.write(WorkSchedule.of(cur, groupAssignment(code, d)));
        DutyPolicy target;
        if (cur.getEffectiveFrom().equals(d)) {
            target = cur;
        } else {
            cur.setEffectiveTo(d);
            policies.saveAndFlush(cur);
            target = copyOf(cur);
            target.setVersionNo(policies.maxVersionNo(code) + 1);
        }
        apply(target, v, r);
        policies.save(target);
        reassign(code, v, d);
        audit.log(AuditAction.UPDATE, "DutyPolicy", target.getId(), "Working schedule " + code + " revised from " + d, before, Json.write(r));
        return WorkSchedule.of(target, groupAssignment(code, d));
    }

    /** Ends a schedule: it no longer applies from {@code effectiveTo} (exclusive). */
    @Transactional
    public WorkSchedule end(String code, LocalDate effectiveTo) {
        if (effectiveTo == null) throw ApiException.badRequest("End date is required");
        LocalDate last = effectiveTo.minusDays(1);
        DutyPolicy cur = policies.approvedOn(code, last).orElseThrow(() ->
                ApiException.rule("NOT_EFFECTIVE", "Schedule " + code + " is not in force before " + effectiveTo));
        if (!effectiveTo.isAfter(cur.getEffectiveFrom()))
            throw ApiException.rule("EFFECTIVE_DATE", "End date must be after the version's start date " + cur.getEffectiveFrom());
        if (policies.countStartingAfter(code, last) > 0)
            throw ApiException.rule("FUTURE_VERSION", "A later version of schedule " + code + " exists");
        cur.setEffectiveTo(effectiveTo);
        for (DutyPolicyAssignment a : assignments.groupAssignments(code)) {
            if (a.getEffectiveFrom().isBefore(effectiveTo) && (a.getEffectiveTo() == null || a.getEffectiveTo().isAfter(effectiveTo))) {
                a.setEffectiveTo(effectiveTo);
                a.setEndReason("Working schedule ended");
            }
        }
        audit.log(AuditAction.DEACTIVATE, "DutyPolicy", cur.getId(), "Working schedule " + code + " ends on " + effectiveTo);
        return WorkSchedule.of(cur, groupAssignment(code, last));
    }

    // ------------------------------------------------------------------ helpers

    private record Validated(int lateGrace, int earlyGrace, int spanMinutes, String workDays, String unit, String category) {
        ScopeType scope() {
            return unit != null ? ScopeType.DIVISION : category != null ? ScopeType.CATEGORY : ScopeType.ORGANIZATION;
        }
    }

    private Validated validate(ScheduleRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Schedule name is required");
        if (r.startTime() == null || r.endTime() == null) throw ApiException.badRequest("Start and end times are required");
        if (!r.endTime().isAfter(r.startTime())) throw ApiException.badRequest("End time must be after start time");
        if (r.effectiveFrom() == null) throw ApiException.badRequest("Effective from date is required");
        int late = r.lateGraceMinutes() == null ? 0 : r.lateGraceMinutes();
        int early = r.earlyGraceMinutes() == null ? 0 : r.earlyGraceMinutes();
        int span = (int) ChronoUnit.MINUTES.between(r.startTime(), r.endTime());
        if (late < 0 || early < 0 || late + early >= span)
            throw ApiException.badRequest("Grace minutes must be zero or more and shorter than the working day");
        if (r.workDays() == null || r.workDays().isEmpty()) throw ApiException.badRequest("Select at least one work day");
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String w : r.workDays()) {
            try {
                days.add(WorkSchedule.toDay(w.trim()));
            } catch (Exception e) {
                throw ApiException.badRequest("Unknown work day: " + w);
            }
        }
        String unit = blank(r.orgUnitCode()) ? null : r.orgUnitCode().trim();
        String cat = blank(r.employeeCategory()) ? null : r.employeeCategory().trim();
        if (unit != null && cat != null)
            throw ApiException.badRequest("A schedule applies to either an org unit or an employee category, not both");
        if (unit != null && establishment.orgUnit(unit, r.effectiveFrom()).isEmpty())
            throw ApiException.rule("INVALID_REFERENCE", "Org unit " + unit + " is not active on " + r.effectiveFrom());
        masterData.requireActive(MasterDataCategory.EMPLOYEE_CATEGORY, cat, "Employee category");
        String dayList = String.join(",", days.stream().sorted().map(x -> x.name().substring(0, 3)).toList());
        return new Validated(late, early, span, dayList, unit, cat);
    }

    private static void apply(DutyPolicy p, Validated v, ScheduleRequest r) {
        p.setName(r.name().trim());
        p.setInTime(r.startTime());
        p.setOutTime(r.endTime());
        p.setGraceMinutes(v.lateGrace());
        p.setEarlyGraceMinutes(v.earlyGrace());
        p.setMinWorkMinutes(v.spanMinutes() - p.getLunchMinutes());
        p.setHalfDayMinutes(v.spanMinutes() / 2);
        p.setWorkingDays(v.workDays());
        p.setEffectiveFrom(r.effectiveFrom());
        p.setEffectiveTo(null);
        p.setReason(r.remarks());
        p.setStatus(DutyPolicy.Status.APPROVED);
        p.setAuthorityRef(AUTHORITY);
        p.setApprovedAt(Instant.now());
    }

    /** New version carrying over the settings this screen does not edit (thresholds, lunch, core hours, rotation). */
    private static DutyPolicy copyOf(DutyPolicy c) {
        DutyPolicy p = new DutyPolicy();
        p.setCode(c.getCode());
        p.setPolicyType(c.getPolicyType());
        p.setCoreStart(c.getCoreStart());
        p.setCoreEnd(c.getCoreEnd());
        p.setLateThresholdMinutes(c.getLateThresholdMinutes());
        p.setEarlyThresholdMinutes(c.getEarlyThresholdMinutes());
        p.setLunchStart(c.getLunchStart());
        p.setLunchMinutes(c.getLunchMinutes());
        p.setRotationPattern(c.getRotationPattern());
        p.setRotationStartDate(c.getRotationStartDate());
        return p;
    }

    /** Group-level assignment of a code in force on {@code d} (latest start wins), or null. */
    private DutyPolicyAssignment groupAssignment(String code, LocalDate d) {
        return assignments.groupAssignments(code).stream().filter(a -> a.isEffectiveOn(d)).findFirst().orElse(null);
    }

    private static DutyPolicyAssignment newAssignment(String code, Validated v, LocalDate from, String reason) {
        DutyPolicyAssignment a = new DutyPolicyAssignment();
        a.setPolicyCode(code);
        a.setScopeType(v.scope());
        a.setOrgUnitCode(v.scope() == ScopeType.DIVISION ? v.unit() : null);
        a.setEmployeeCategory(v.scope() == ScopeType.CATEGORY ? v.category() : null);
        a.setEffectiveFrom(from);
        a.setAuthorityRef(AUTHORITY);
        a.setReason(reason);
        return a;
    }

    /** Points the code's group assignment at the requested scope from {@code d}, keeping earlier assignments as history. */
    private void reassign(String code, Validated v, LocalDate d) {
        DutyPolicyAssignment cur = groupAssignment(code, d);
        if (cur != null && cur.getScopeType() == v.scope() && Objects.equals(cur.getOrgUnitCode(), v.scope() == ScopeType.DIVISION ? v.unit() : null)
                && Objects.equals(cur.getEmployeeCategory(), v.scope() == ScopeType.CATEGORY ? v.category() : null)) {
            cur.setEffectiveTo(null);
            return;
        }
        if (cur != null) {
            if (cur.getEffectiveFrom().equals(d)) {
                assignments.delete(cur);
                assignments.flush();
            } else {
                cur.setEffectiveTo(d);
                cur.setEndReason("Working schedule revised from " + d);
                assignments.saveAndFlush(cur);
            }
        }
        assignments.save(newAssignment(code, v, d, "Working schedule " + code + " revised from " + d));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
