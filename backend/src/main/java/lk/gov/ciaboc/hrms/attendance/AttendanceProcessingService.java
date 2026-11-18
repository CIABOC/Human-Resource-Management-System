package lk.gov.ciaboc.hrms.attendance;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.config.AppProperties;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeRepository;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.leave.LeaveChangedEvent;
import lk.gov.ciaboc.hrms.leave.LeaveService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Daily attendance processing (FR-ATT-006..009, -014..018): derives check-in/out, worked minutes and the day status
 * from usable events, the applicable effective-dated schedule, the holiday calendar and approved leave. Runs on demand
 * and nightly for the previous day; days already processed are re-evaluated when leave, corrections or reviews change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceProcessingService {
    private final AttendanceDailyRepository daily;
    private final AttendanceEventRepository events;
    private final ScheduleService schedules;
    private final HolidayService holidays;
    private final LeaveService leave;
    private final EmployeeRepository employees;
    private final EmployeeLookup lookup;
    private final EstablishmentService establishment;
    private final SettingsService settings;
    private final AuditService audit;
    private final AppProperties props;

    public record ProcessResult(LocalDate date, int processed, Map<String, Integer> byStatus) {
    }

    public record DailyRow(Long id, Long employeeId, EmployeeLookup.EmployeeBrief employee, LocalDate workDate, String status,
                           Instant checkIn, Instant checkOut, int workedMinutes, int lateMinutes, int earlyMinutes,
                           String leaveTypeCode, boolean leaveHalfDay, boolean unpaidLeave, boolean corrected,
                           String exceptionNote, Instant processedAt) {
    }

    public ZoneId zone() {
        try {
            return ZoneId.of(props.timezone() == null || props.timezone().isBlank() ? "Asia/Colombo" : props.timezone());
        } catch (Exception e) {
            return ZoneId.of("Asia/Colombo");
        }
    }

    public LocalDate today() {
        return LocalDate.now(zone());
    }

    // ------------------------------------------------------------------ processing

    @Transactional
    public List<ProcessResult> processRange(LocalDate from, LocalDate to) {
        if (from == null) throw ApiException.badRequest("Date is required");
        LocalDate t = to == null ? from : to;
        if (t.isBefore(from)) throw ApiException.badRequest("End date cannot be before start date");
        if (ChronoUnit.DAYS.between(from, t) > 31) throw ApiException.badRequest("At most 31 days can be processed at once");
        if (t.isAfter(today())) throw ApiException.rule("FUTURE_DATE", "Attendance cannot be processed for future dates");
        List<ProcessResult> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(t); d = d.plusDays(1)) out.add(process(d, null));
        audit.log(AuditAction.PROCESS, "AttendanceDaily", from.toString(), "Attendance processed for " + from
                + (t.equals(from) ? "" : " to " + t) + ": " + out.stream().mapToInt(ProcessResult::processed).sum() + " employee-days");
        return out;
    }

    /** Nightly processing of the previous day (configurable via attendance.nightly.enabled). */
    @Scheduled(cron = "${app.attendance.nightly-cron:0 30 1 * * *}", zone = "${app.timezone:Asia/Colombo}")
    @Transactional
    public void nightly() {
        if (!settings.getBool("attendance.nightly.enabled", true)) return;
        LocalDate d = today().minusDays(1);
        try {
            ProcessResult r = process(d, null);
            audit.log(AuditAction.PROCESS, "AttendanceDaily", d.toString(), "Nightly attendance processing for " + d + ": " + r.processed() + " employees");
        } catch (Exception e) {
            log.error("Nightly attendance processing failed for {}", d, e);
        }
    }

    /** Re-evaluates days an employee already has processed results for (after leave approval or cancellation). */
    @EventListener
    public void onLeaveChanged(LeaveChangedEvent e) {
        LocalDate end = e.to().isAfter(today()) ? today() : e.to();
        if (end.isBefore(e.from())) return;
        Set<LocalDate> done = daily.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(e.employeeId(), e.from(), end).stream()
                .map(AttendanceDaily::getWorkDate).collect(Collectors.toSet());
        for (LocalDate d : done) process(d, Set.of(e.employeeId()));
    }

    /** Reprocesses the given (date → employees) pairs, but only where the day was already processed. */
    int reprocessIfProcessed(Map<LocalDate, Set<Long>> pairs) {
        int n = 0;
        for (var en : pairs.entrySet()) {
            if (en.getKey().isAfter(today()) || en.getValue().isEmpty()) continue;
            Set<Long> already = daily.processedFor(en.getValue(), en.getKey(), en.getKey()).stream()
                    .map(r -> (Long) r[0]).collect(Collectors.toSet());
            if (!already.isEmpty()) {
                process(en.getKey(), already);
                n += already.size();
            }
        }
        return n;
    }

    /** Processes one employee-day regardless of earlier processing (used after an approved correction). */
    void processEmployeeDay(Long employeeId, LocalDate d) {
        if (!d.isAfter(today())) process(d, Set.of(employeeId));
    }

    ProcessResult process(LocalDate d, Set<Long> only) {
        if (d.isAfter(today())) throw ApiException.rule("FUTURE_DATE", "Attendance cannot be processed for future dates");
        ZoneId zone = zone();
        boolean holiday = holidays.holidaysBetween(d, d).contains(d);
        List<WorkSchedule> effective = schedules.effectiveOn(d);
        Map<Long, LeaveService.LeaveDay> leaveByEmp = new HashMap<>();
        for (LeaveService.LeaveDay l : leave.approvedLeave(d, d)) {
            leaveByEmp.merge(l.employeeId(), l, (a, b) -> a.halfDay() ? b : a); // a full day wins over a half day
        }
        Map<Long, List<AttendanceEvent>> evByEmp = events.findByWorkDateOrderByEventTimeAsc(d).stream()
                .filter(AttendanceEvent::isUsable).collect(Collectors.groupingBy(AttendanceEvent::getEmployeeId));
        Map<Long, AttendanceDaily> existing = daily.findByWorkDate(d).stream()
                .collect(Collectors.toMap(AttendanceDaily::getEmployeeId, x -> x, (a, b) -> a));
        Map<String, List<String>> chains = new HashMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        int n = 0;
        Instant now = Instant.now();
        for (Employee e : employees.findByStatusIn(EmployeeLookup.IN_SERVICE)) {
            if (only != null && !only.contains(e.getId())) continue;
            if (e.getAppointmentDate() != null && e.getAppointmentDate().isAfter(d)) continue;
            List<String> chain = e.getOrgUnitCode() == null ? List.of()
                    : chains.computeIfAbsent(e.getOrgUnitCode(), u -> establishment.unitAndAncestors(u, d));
            Optional<WorkSchedule> s = schedules.resolve(effective, chain, e.getEmployeeCategory());
            AttendanceDaily r = existing.getOrDefault(e.getId(), new AttendanceDaily());
            r.setEmployeeId(e.getId());
            r.setWorkDate(d);
            evaluate(r, d, s.orElse(null), holiday, leaveByEmp.get(e.getId()), evByEmp.getOrDefault(e.getId(), List.of()), zone);
            r.setProcessedAt(now);
            daily.save(r);
            counts.merge(r.getStatus().name(), 1, Integer::sum);
            n++;
        }
        return new ProcessResult(d, n, counts);
    }

    /** Pure evaluation of one employee-day. */
    static void evaluate(AttendanceDaily r, LocalDate d, WorkSchedule s, boolean holiday, LeaveService.LeaveDay leaveDay,
                         List<AttendanceEvent> evs, ZoneId zone) {
        Instant in = evs.stream().filter(x -> x.getDirection() == AttendanceEvent.Direction.IN).map(AttendanceEvent::getEventTime)
                .min(Comparator.naturalOrder()).orElse(null);
        Instant out = evs.stream().filter(x -> x.getDirection() == AttendanceEvent.Direction.OUT).map(AttendanceEvent::getEventTime)
                .filter(t -> in == null || t.isAfter(in)).max(Comparator.naturalOrder()).orElse(null);
        r.setCheckIn(in);
        r.setCheckOut(out);
        r.setWorkedMinutes(in != null && out != null ? (int) ChronoUnit.MINUTES.between(in, out) : 0);
        r.setLateMinutes(0);
        r.setEarlyMinutes(0);
        r.setScheduleId(s == null ? null : s.getId());
        r.setLeaveApplicationId(null);
        r.setLeaveTypeCode(null);
        r.setLeaveHalfDay(false);
        r.setUnpaidLeave(false);
        r.setCorrected(evs.stream().anyMatch(x -> x.getSource() == AttendanceEvent.Source.MANUAL));
        List<String> notes = new ArrayList<>();
        if (leaveDay != null) {
            r.setLeaveApplicationId(leaveDay.applicationId());
            r.setLeaveTypeCode(leaveDay.leaveTypeCode());
            r.setLeaveHalfDay(leaveDay.halfDay());
            r.setUnpaidLeave(!leaveDay.paid());
        }
        if (s == null) {
            r.setStatus(AttendanceDaily.Status.NO_SCHEDULE);
            notes.add("No working schedule applies");
        } else if (holiday) {
            r.setStatus(AttendanceDaily.Status.HOLIDAY);
        } else if (!s.isWorkDay(d)) {
            r.setStatus(AttendanceDaily.Status.WEEKEND);
        } else if (leaveDay != null && !leaveDay.halfDay()) {
            r.setStatus(AttendanceDaily.Status.ON_LEAVE);
        } else if (in == null && out == null) {
            r.setStatus(leaveDay != null ? AttendanceDaily.Status.ON_LEAVE : AttendanceDaily.Status.ABSENT);
            if (leaveDay != null) notes.add("Half-day leave; no attendance recorded for the other half");
        } else {
            ZonedDateTime start = d.atTime(s.getStartTime()).atZone(zone);
            ZonedDateTime end = d.atTime(s.getEndTime()).atZone(zone);
            int late = 0, early = 0;
            if (leaveDay == null) {
                if (in != null && in.isAfter(start.plusMinutes(s.getLateGraceMinutes()).toInstant()))
                    late = (int) ChronoUnit.MINUTES.between(start.toInstant(), in);
                if (out != null && out.isBefore(end.minusMinutes(s.getEarlyGraceMinutes()).toInstant()))
                    early = (int) ChronoUnit.MINUTES.between(out, end.toInstant());
            } else {
                notes.add("Half-day leave (" + leaveDay.leaveTypeCode() + ")");
            }
            r.setLateMinutes(late);
            r.setEarlyMinutes(early);
            r.setStatus(late > 0 ? AttendanceDaily.Status.LATE : early > 0 ? AttendanceDaily.Status.EARLY : AttendanceDaily.Status.PRESENT);
            if (late > 0 && early > 0) notes.add("Also left " + early + " min early");
            if (in == null) notes.add("No check-in recorded");
            if (out == null) notes.add("No check-out recorded");
        }
        if (r.isCorrected()) notes.add("Includes an approved manual correction");
        r.setExceptionNote(notes.isEmpty() ? null : String.join("; ", notes));
    }

    // ------------------------------------------------------------------ views

    @Transactional(readOnly = true)
    public List<DailyRow> dailyView(LocalDate date, String orgUnitCode, String status) {
        LocalDate d = date == null ? today() : date;
        Set<Long> visible = lookup.visibleEmployeeIds();
        Set<String> units = orgUnitCode == null || orgUnitCode.isBlank() ? null : establishment.unitAndDescendants(orgUnitCode, LocalDate.now());
        List<AttendanceDaily> rows = daily.findByWorkDate(d).stream()
                .filter(r -> visible == null || visible.contains(r.getEmployeeId()))
                .filter(r -> status == null || status.isBlank() || r.getStatus().name().equalsIgnoreCase(status))
                .toList();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = lookup.briefs(rows.stream().map(AttendanceDaily::getEmployeeId).toList());
        return rows.stream()
                .filter(r -> units == null || (briefs.get(r.getEmployeeId()) != null && units.contains(briefs.get(r.getEmployeeId()).orgUnitCode())))
                .map(r -> row(r, briefs.get(r.getEmployeeId())))
                .sorted(Comparator.comparing(x -> x.employee() == null ? "" : x.employee().employeeNo()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DailyRow> employeeView(Long employeeId, LocalDate from, LocalDate to) {
        LocalDate t = to == null ? today() : to;
        LocalDate f = from == null ? t.withDayOfMonth(1) : from;
        if (t.isBefore(f)) throw ApiException.badRequest("End date cannot be before start date");
        if (ChronoUnit.DAYS.between(f, t) > 366) throw ApiException.badRequest("The range cannot exceed one year");
        EmployeeLookup.EmployeeBrief b = lookup.brief(employeeId);
        return daily.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(employeeId, f, t).stream().map(r -> row(r, b)).toList();
    }

    @Transactional(readOnly = true)
    public List<DailyRow> mine(LocalDate from, LocalDate to) {
        return employeeView(CurrentUser.employeeId(), from, to);
    }

    private static DailyRow row(AttendanceDaily r, EmployeeLookup.EmployeeBrief b) {
        return new DailyRow(r.getId(), r.getEmployeeId(), b, r.getWorkDate(), r.getStatus().name(), r.getCheckIn(), r.getCheckOut(),
                r.getWorkedMinutes(), r.getLateMinutes(), r.getEarlyMinutes(), r.getLeaveTypeCode(), r.isLeaveHalfDay(),
                r.isUnpaidLeave(), r.isCorrected(), r.getExceptionNote(), r.getProcessedAt());
    }
}
