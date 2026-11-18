package lk.gov.ciaboc.hrms.leave;

import lk.gov.ciaboc.hrms.common.PageResponse;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/leave")
@RequiredArgsConstructor
public class LeaveController {
    private final LeaveService leave;
    private final LeaveConfigService config;
    private final LeaveBalanceService balances;
    private final EmployeeLookup lookup;
    private final FileStorageService files;

    public record CommentRequest(String comment) {
    }

    // ------------------------------------------------------------------ configuration (FR-LEV-001, -002)

    /** Leave types are visible to every signed-in user (needed to apply). */
    @GetMapping("/types")
    public List<LeaveType> types(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return config.types(activeOnly);
    }

    @PostMapping("/types")
    @PreAuthorize("hasAuthority('LEAVE_CONFIG')")
    public LeaveType createType(@RequestBody LeaveConfigService.LeaveTypeRequest r) {
        return config.createType(r);
    }

    @PutMapping("/types/{id}")
    @PreAuthorize("hasAuthority('LEAVE_CONFIG')")
    public LeaveType updateType(@PathVariable Long id, @RequestBody LeaveConfigService.LeaveTypeRequest r) {
        return config.updateType(id, r);
    }

    @GetMapping("/entitlements")
    @PreAuthorize("hasAnyAuthority('LEAVE_CONFIG','LEAVE_MANAGE','LEAVE_VIEW')")
    public List<LeaveEntitlement> entitlements(@RequestParam(required = false) Long leaveTypeId,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return config.entitlements(leaveTypeId, asOf);
    }

    @PostMapping("/entitlements")
    @PreAuthorize("hasAuthority('LEAVE_CONFIG')")
    public LeaveEntitlement setEntitlement(@RequestBody LeaveConfigService.EntitlementRequest r) {
        return config.setEntitlement(r);
    }

    // ------------------------------------------------------------------ self service

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public LeaveService.DocumentRef upload(@RequestPart("file") MultipartFile file) {
        return leave.uploadDocument(file);
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public LeaveService.PreviewResult preview(@RequestBody LeaveService.ApplyRequest r) {
        return leave.preview(r);
    }

    @PostMapping("/applications")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public LeaveService.ApplicationView apply(@RequestBody LeaveService.ApplyRequest r) {
        return leave.apply(r);
    }

    @PutMapping("/applications/{id}")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public LeaveService.ApplicationView revise(@PathVariable Long id, @RequestBody LeaveService.ApplyRequest r) {
        return leave.revise(id, r);
    }

    @PostMapping("/applications/{id}/withdraw")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public LeaveService.ApplicationView withdraw(@PathVariable Long id, @RequestBody(required = false) CommentRequest r) {
        return leave.withdraw(id, r == null ? null : r.comment());
    }

    @PostMapping("/applications/{id}/cancel-request")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public LeaveService.ApplicationView cancelRequest(@PathVariable Long id, @RequestBody CommentRequest r) {
        return leave.requestCancellation(id, r == null ? null : r.comment());
    }

    @GetMapping("/my/applications")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public List<LeaveService.ApplicationView> myApplications() {
        return leave.mine();
    }

    @GetMapping("/my/balances")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public List<LeaveBalanceService.BalanceView> myBalances(@RequestParam(required = false) Integer year) {
        return balances.forEmployee(CurrentUser.employeeId(), year == null ? Year.now().getValue() : year);
    }

    // ------------------------------------------------------------------ applications (owner or LEAVE_VIEW within scope)

    @GetMapping("/applications/{id}")
    public LeaveService.ApplicationView get(@PathVariable Long id) {
        return leave.get(id);
    }

    @GetMapping("/applications/{id}/document")
    public ResponseEntity<Resource> document(@PathVariable Long id) {
        return files.download(leave.documentFileId(id));
    }

    @GetMapping("/applications")
    @PreAuthorize("hasAuthority('LEAVE_VIEW')")
    public PageResponse<LeaveService.ApplicationView> search(@RequestParam(required = false) String status,
                                                              @RequestParam(required = false) Long employeeId,
                                                              @RequestParam(required = false) Long leaveTypeId,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                              @RequestParam(required = false) String orgUnitCode,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "25") int size) {
        return leave.search(new LeaveService.SearchFilter(status, employeeId, leaveTypeId, from, to, orgUnitCode, page, size));
    }

    @GetMapping("/team-calendar")
    @PreAuthorize("hasAuthority('LEAVE_VIEW')")
    public LeaveService.TeamCalendar teamCalendar(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(required = false) String orgUnitCode) {
        return leave.teamCalendar(from, to, orgUnitCode);
    }

    // ------------------------------------------------------------------ balances (FR-LEV-006)

    @GetMapping("/balances")
    @PreAuthorize("hasAuthority('LEAVE_VIEW')")
    public List<LeaveBalanceService.BalanceView> balances(@RequestParam(required = false) Integer year,
                                                          @RequestParam(required = false) Long employeeId) {
        int y = year == null ? Year.now().getValue() : year;
        if (employeeId != null) {
            lookup.requireAccess(employeeId);
            EmployeeLookup.EmployeeBrief b = lookup.brief(employeeId);
            return balances.forEmployee(employeeId, y).stream().map(v -> new LeaveBalanceService.BalanceView(v.id(), v.employeeId(),
                    v.leaveTypeId(), v.leaveTypeCode(), v.leaveTypeName(), v.year(), v.entitled(), v.adjusted(), v.used(), v.pending(),
                    v.available(), b)).toList();
        }
        Set<Long> visible = lookup.visibleEmployeeIds();
        return balances.list(y, visible);
    }

    @GetMapping("/balances/adjustments")
    @PreAuthorize("hasAuthority('LEAVE_VIEW')")
    public List<LeaveBalanceAdjustment> adjustments(@RequestParam Long employeeId, @RequestParam(required = false) Integer year) {
        lookup.requireAccess(employeeId);
        return balances.adjustments(employeeId, year);
    }

    public record YearRequest(Integer year) {
    }

    @PostMapping("/balances/initialise")
    @PreAuthorize("hasAuthority('LEAVE_MANAGE')")
    public LeaveBalanceService.InitialiseResult initialise(@RequestBody YearRequest r) {
        return balances.initialise(r == null || r.year() == null ? Year.now().getValue() : r.year());
    }

    @PostMapping("/balances/adjust")
    @PreAuthorize("hasAuthority('LEAVE_MANAGE')")
    public LeaveBalanceService.BalanceView adjust(@RequestBody LeaveBalanceService.AdjustRequest r) {
        return balances.adjust(r);
    }
}
