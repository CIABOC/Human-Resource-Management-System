package lk.gov.ciaboc.hrms.attendance;

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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Effective-dated working schedules (FR-ATT-010..013). A change creates a new version from its effective date; the
 * previous version is closed, never overwritten, so past attendance can always be re-evaluated against the rules
 * that applied at the time.
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {
    private final WorkScheduleRepository repo;
    private final EstablishmentService establishment;
    private final MasterDataService masterData;
    private final AuditService audit;

    public record ScheduleRequest(String code, String name, LocalTime startTime, LocalTime endTime, Integer lateGraceMinutes,
                                  Integer earlyGraceMinutes, List<String> workDays, String orgUnitCode,
                                  String employeeCategory, LocalDate effectiveFrom, String remarks) {
    }

    @Transactional(readOnly = true)
    public List<WorkSchedule> effectiveOn(LocalDate d) {
        return repo.effectiveOn(d == null ? LocalDate.now() : d);
    }

    /** Current and future versions (latest version per code that has not ended before today), for configuration screens. */
    @Transactional(readOnly = true)
    public List<WorkSchedule> all() {
        return repo.findAll().stream()
                .sorted(Comparator.comparing(WorkSchedule::getCode).thenComparing(WorkSchedule::getEffectiveFrom, Comparator.reverseOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkSchedule> history(String code) {
        List<WorkSchedule> h = repo.findByCodeOrderByEffectiveFromDesc(code);
        if (h.isEmpty()) throw ApiException.notFound("Working schedule", code);
        return h;
    }

    @Transactional
    public WorkSchedule create(ScheduleRequest r) {
        if (r == null) throw ApiException.badRequest("Schedule details are required");
        if (r.code() == null || !r.code().matches("^[A-Z0-9_-]{1,40}$"))
            throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits, '-' or '_'");
        if (repo.existsByCode(r.code())) throw ApiException.conflict("Schedule code " + r.code() + " already exists");
        WorkSchedule s = new WorkSchedule();
        s.setCode(r.code());
        copy(s, r);
        repo.save(s);
        audit.log(AuditAction.CREATE, "WorkSchedule", s.getId(), "Working schedule " + s.getCode() + " created from " + s.getEffectiveFrom(),
                null, Json.write(r));
        return s;
    }

    /** New version of an existing schedule from {@code r.effectiveFrom()} (FR-ATT-013: changes keep history). */
    @Transactional
    public WorkSchedule revise(String code, ScheduleRequest r) {
        if (r == null || r.effectiveFrom() == null) throw ApiException.badRequest("Effective from date is required");
        LocalDate d = r.effectiveFrom();
        WorkSchedule cur = repo.effectiveOn(code, d).orElseThrow(() -> ApiException.rule("NOT_EFFECTIVE",
                "Schedule " + code + " is not in force on " + d + "; create a new schedule instead"));
        if (repo.countStartingAfter(code, d) > 0)
            throw ApiException.rule("FUTURE_VERSION", "A later version of schedule " + code + " already exists; revise that version instead");
        String before = Json.write(cur);
        WorkSchedule target;
        if (cur.getEffectiveFrom().equals(d)) {
            target = cur;
        } else {
            cur.setEffectiveTo(d);
            repo.saveAndFlush(cur);
            target = new WorkSchedule();
            target.setCode(code);
        }
        copy(target, r);
        repo.save(target);
        audit.log(AuditAction.UPDATE, "WorkSchedule", target.getId(), "Working schedule " + code + " revised from " + d, before, Json.write(r));
        return target;
    }

    /** Ends a schedule: it no longer applies from {@code effectiveTo} (exclusive). */
    @Transactional
    public WorkSchedule end(String code, LocalDate effectiveTo) {
        if (effectiveTo == null) throw ApiException.badRequest("End date is required");
        WorkSchedule cur = repo.effectiveOn(code, effectiveTo.minusDays(1)).orElseThrow(() ->
                ApiException.rule("NOT_EFFECTIVE", "Schedule " + code + " is not in force before " + effectiveTo));
        if (!effectiveTo.isAfter(cur.getEffectiveFrom()))
            throw ApiException.rule("EFFECTIVE_DATE", "End date must be after the version's start date " + cur.getEffectiveFrom());
        if (repo.countStartingAfter(code, effectiveTo.minusDays(1)) > 0)
            throw ApiException.rule("FUTURE_VERSION", "A later version of schedule " + code + " exists");
        cur.setEffectiveTo(effectiveTo);
        audit.log(AuditAction.DEACTIVATE, "WorkSchedule", cur.getId(), "Working schedule " + code + " ends on " + effectiveTo);
        return cur;
    }

    /**
     * The schedule applying to an employee on a date: the most specific match by org unit (nearest unit in the
     * hierarchy wins) and employee category, among versions in force on that date.
     */
    public Optional<WorkSchedule> resolve(List<WorkSchedule> effective, List<String> unitAndAncestors, String category) {
        WorkSchedule best = null;
        int bestScore = -1;
        for (WorkSchedule s : effective) {
            int score = 0;
            if (s.getOrgUnitCode() != null) {
                int idx = unitAndAncestors == null ? -1 : unitAndAncestors.indexOf(s.getOrgUnitCode());
                if (idx < 0) continue;
                score += 1000 - idx * 10;
            }
            if (s.getEmployeeCategory() != null) {
                if (!s.getEmployeeCategory().equals(category)) continue;
                score += 5;
            }
            if (score > bestScore || (score == bestScore && best != null && s.getEffectiveFrom().isAfter(best.getEffectiveFrom()))) {
                best = s;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    public List<String> unitChain(String unitCode, LocalDate d) {
        return unitCode == null ? List.of() : establishment.unitAndAncestors(unitCode, d);
    }

    private void copy(WorkSchedule s, ScheduleRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Schedule name is required");
        if (r.startTime() == null || r.endTime() == null) throw ApiException.badRequest("Start and end times are required");
        if (!r.endTime().isAfter(r.startTime())) throw ApiException.badRequest("End time must be after start time");
        if (r.effectiveFrom() == null) throw ApiException.badRequest("Effective from date is required");
        int late = r.lateGraceMinutes() == null ? 0 : r.lateGraceMinutes();
        int early = r.earlyGraceMinutes() == null ? 0 : r.earlyGraceMinutes();
        long span = ChronoUnit.MINUTES.between(r.startTime(), r.endTime());
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
        if (unit != null && establishment.orgUnit(unit, r.effectiveFrom()).isEmpty())
            throw ApiException.rule("INVALID_REFERENCE", "Org unit " + unit + " is not active on " + r.effectiveFrom());
        String cat = blank(r.employeeCategory()) ? null : r.employeeCategory().trim();
        masterData.requireActive(MasterDataCategory.EMPLOYEE_CATEGORY, cat, "Employee category");
        s.setName(r.name().trim());
        s.setStartTime(r.startTime());
        s.setEndTime(r.endTime());
        s.setLateGraceMinutes(late);
        s.setEarlyGraceMinutes(early);
        s.setWorkDays(String.join(",", days.stream().sorted().map(x -> x.name().substring(0, 3)).toList()));
        s.setOrgUnitCode(unit);
        s.setEmployeeCategory(cat);
        s.setEffectiveFrom(r.effectiveFrom());
        s.setRemarks(r.remarks());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
