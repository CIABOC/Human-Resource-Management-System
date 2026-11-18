package lk.gov.ciaboc.hrms.attendance;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;

/** Working schedules and the holiday calendar (FR-ATT-010..013); no-pay calculation and approval (FR-ATT-021..023). */
@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceConfigController {
    private final ScheduleService schedules;
    private final HolidayService holidays;
    private final NoPayService noPay;

    public record EndRequest(LocalDate effectiveTo) {
    }

    // ------------------------------------------------------------------ schedules

    @GetMapping("/schedules")
    @PreAuthorize("hasAnyAuthority('ATT_VIEW','ATT_CONFIG')")
    public List<WorkSchedule> schedules(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                        @RequestParam(defaultValue = "false") boolean allVersions) {
        return allVersions ? schedules.all() : schedules.effectiveOn(asOf);
    }

    @GetMapping("/schedules/{code}/history")
    @PreAuthorize("hasAnyAuthority('ATT_VIEW','ATT_CONFIG')")
    public List<WorkSchedule> history(@PathVariable String code) {
        return schedules.history(code);
    }

    @PostMapping("/schedules")
    @PreAuthorize("hasAuthority('ATT_CONFIG')")
    public WorkSchedule create(@RequestBody ScheduleService.ScheduleRequest r) {
        return schedules.create(r);
    }

    @PutMapping("/schedules/{code}")
    @PreAuthorize("hasAuthority('ATT_CONFIG')")
    public WorkSchedule revise(@PathVariable String code, @RequestBody ScheduleService.ScheduleRequest r) {
        return schedules.revise(code, r);
    }

    @PostMapping("/schedules/{code}/end")
    @PreAuthorize("hasAuthority('ATT_CONFIG')")
    public WorkSchedule end(@PathVariable String code, @RequestBody EndRequest r) {
        return schedules.end(code, r == null ? null : r.effectiveTo());
    }

    // ------------------------------------------------------------------ holidays (readable by every signed-in user)

    @GetMapping("/holidays")
    public List<PublicHoliday> holidays(@RequestParam(required = false) Integer year) {
        int y = year == null ? Year.now().getValue() : year;
        return holidays.list(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31));
    }

    @PostMapping("/holidays")
    @PreAuthorize("hasAuthority('ATT_CONFIG')")
    public PublicHoliday createHoliday(@RequestBody HolidayService.HolidayRequest r) {
        return holidays.create(r);
    }

    @PutMapping("/holidays/{id}")
    @PreAuthorize("hasAuthority('ATT_CONFIG')")
    public PublicHoliday updateHoliday(@PathVariable Long id, @RequestBody HolidayService.HolidayRequest r) {
        return holidays.update(id, r);
    }

    @DeleteMapping("/holidays/{id}")
    @PreAuthorize("hasAuthority('ATT_CONFIG')")
    public void deleteHoliday(@PathVariable Long id) {
        holidays.delete(id);
    }

    // ------------------------------------------------------------------ no-pay

    @GetMapping("/nopay/runs")
    @PreAuthorize("hasAuthority('ATT_VIEW')")
    public List<NoPayRun> runs() {
        return noPay.runs();
    }

    @GetMapping("/nopay/runs/{period}")
    @PreAuthorize("hasAuthority('ATT_VIEW')")
    public NoPayService.RunView run(@PathVariable String period) {
        return noPay.byPeriod(period);
    }

    @PostMapping("/nopay/calculate")
    @PreAuthorize("hasAuthority('ATT_MANAGE')")
    public NoPayService.RunView calculate(@RequestBody NoPayService.PeriodRequest r) {
        return noPay.calculate(r == null ? null : r.period());
    }

    @PostMapping("/nopay/runs/{id}/lines/{lineId}/adjust")
    @PreAuthorize("hasAuthority('ATT_MANAGE')")
    public NoPayService.LineView adjust(@PathVariable Long id, @PathVariable Long lineId, @RequestBody NoPayService.AdjustRequest r) {
        return noPay.adjust(id, lineId, r);
    }

    @PostMapping("/nopay/runs/{id}/submit")
    @PreAuthorize("hasAuthority('ATT_MANAGE')")
    public NoPayService.RunView submit(@PathVariable Long id) {
        return noPay.submit(id);
    }
}
