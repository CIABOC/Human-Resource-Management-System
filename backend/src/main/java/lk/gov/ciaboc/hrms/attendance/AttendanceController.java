package lk.gov.ciaboc.hrms.attendance;

import lk.gov.ciaboc.hrms.common.PageResponse;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {
    private final AttendanceProcessingService processing;
    private final AttendanceIngestService ingest;
    private final AttendanceCorrectionService corrections;
    private final EmployeeLookup lookup;

    public record ProcessRequest(LocalDate date, LocalDate to) {
    }

    // ------------------------------------------------------------------ daily results

    @GetMapping("/daily")
    @PreAuthorize("hasAuthority('ATT_VIEW')")
    public List<AttendanceProcessingService.DailyRow> daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                            @RequestParam(required = false) String orgUnitCode,
                                                            @RequestParam(required = false) String status) {
        return processing.dailyView(date, orgUnitCode, status);
    }

    @GetMapping("/employees/{employeeId}")
    @PreAuthorize("hasAuthority('ATT_VIEW')")
    public List<AttendanceProcessingService.DailyRow> employee(@PathVariable Long employeeId,
                                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        lookup.requireAccess(employeeId);
        return processing.employeeView(employeeId, from, to);
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public List<AttendanceProcessingService.DailyRow> mine(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return processing.mine(from, to);
    }

    @PostMapping("/process")
    @PreAuthorize("hasAuthority('ATT_MANAGE')")
    public List<AttendanceProcessingService.ProcessResult> process(@RequestBody ProcessRequest r) {
        return processing.processRange(r == null ? null : r.date(), r == null ? null : r.to());
    }

    // ------------------------------------------------------------------ events, ingestion and review

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('ATT_VIEW')")
    public PageResponse<AttendanceIngestService.EventView> events(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                  @RequestParam(required = false) Long employeeId,
                                                                  @RequestParam(defaultValue = "false") boolean flaggedOnly,
                                                                  @RequestParam(defaultValue = "false") boolean awaitingReview,
                                                                  @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "50") int size) {
        return ingest.search(from, to, employeeId, flaggedOnly, awaitingReview, page, size);
    }

    @PostMapping("/events/{id}/review")
    @PreAuthorize("hasAuthority('ATT_MANAGE')")
    public AttendanceIngestService.EventView review(@PathVariable Long id, @RequestBody AttendanceIngestService.ReviewRequest r) {
        return ingest.review(id, r);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ATT_MANAGE')")
    public AttendanceIngestService.IngestResult importCsv(@RequestPart("file") MultipartFile file) {
        return ingest.importCsv(file);
    }

    /** Device batch ingestion (INT-04); only while the ATTENDANCE_DEVICE integration is enabled. */
    @PostMapping("/device-events")
    @PreAuthorize("hasAuthority('ATT_MANAGE')")
    public AttendanceIngestService.IngestResult deviceEvents(@RequestBody AttendanceIngestService.DeviceBatch b) {
        return ingest.ingestDeviceBatch(b);
    }

    @GetMapping("/device-integration")
    @PreAuthorize("hasAuthority('ATT_MANAGE')")
    public Map<String, Object> deviceIntegration() {
        return Map.of("enabled", ingest.deviceIntegrationEnabled());
    }

    // ------------------------------------------------------------------ corrections

    @PostMapping("/corrections")
    @PreAuthorize("hasAnyAuthority('SELF_SERVICE','ATT_MANAGE')")
    public AttendanceCorrectionService.CorrectionView requestCorrection(@RequestBody AttendanceCorrectionService.CorrectionRequest r) {
        return corrections.request(r);
    }

    @GetMapping("/corrections/my")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public List<AttendanceCorrectionService.CorrectionView> myCorrections() {
        return corrections.mine();
    }

    @GetMapping("/corrections")
    @PreAuthorize("hasAuthority('ATT_VIEW')")
    public List<AttendanceCorrectionService.CorrectionView> corrections(@RequestParam(required = false) String status) {
        return corrections.list(status);
    }
}
