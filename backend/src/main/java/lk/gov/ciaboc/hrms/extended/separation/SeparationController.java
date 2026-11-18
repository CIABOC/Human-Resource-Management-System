package lk.gov.ciaboc.hrms.extended.separation;

import lk.gov.ciaboc.hrms.extended.separation.SeparationEntities.ClearanceItemTemplate;
import lk.gov.ciaboc.hrms.extended.separation.SeparationEntities.SeparationCase;
import lk.gov.ciaboc.hrms.extended.separation.SeparationEntities.SeparationType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/extended")
@RequiredArgsConstructor
public class SeparationController {
    private final SeparationService service;
    private final RetirementService retirement;

    // ---------------------------------------------------------------- retirement forecast

    @GetMapping("/retirements/forecast")
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public List<RetirementService.ForecastRow> forecast(@RequestParam(required = false) Integer months) {
        return retirement.forecast(months);
    }

    /** Runs the advance-notice job now (normally daily). */
    @PostMapping("/retirements/run-notices")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public Map<String, Integer> runNotices() {
        return Map.of("sent", retirement.sendNotices(LocalDate.now()));
    }

    // ---------------------------------------------------------------- configuration

    @GetMapping("/separation-types")
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public List<SeparationType> types() {
        return service.types();
    }

    @GetMapping("/clearance-templates")
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public List<ClearanceItemTemplate> templates() {
        return service.templates();
    }

    @PostMapping("/clearance-templates")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public ClearanceItemTemplate createTemplate(@RequestBody SeparationService.TemplateInput in) {
        return service.saveTemplate(null, in);
    }

    @PutMapping("/clearance-templates/{id}")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public ClearanceItemTemplate updateTemplate(@PathVariable Long id, @RequestBody SeparationService.TemplateInput in) {
        return service.saveTemplate(id, in);
    }

    // ---------------------------------------------------------------- separation cases

    @GetMapping("/separations")
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public List<SeparationService.CaseSummary> list(@RequestParam(required = false) SeparationCase.Status status) {
        return service.list(status);
    }

    @GetMapping("/separations/{id}")
    public SeparationService.CaseDetail get(@PathVariable Long id) {
        return service.get(id); // EXT_VIEW, or a role responsible for one of the case's items / the settlement
    }

    @PostMapping("/separations")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public SeparationService.CaseDetail open(@RequestBody SeparationService.CaseInput in) {
        return service.open(in);
    }

    @PostMapping("/separations/{id}/settlement")
    public SeparationService.CaseDetail settlement(@PathVariable Long id, @RequestBody SeparationService.SettlementInput in) {
        return service.settlement(id, in); // role-checked in the service (configurable settlement role)
    }

    @PostMapping("/separations/{id}/complete")
    @PreAuthorize("hasAuthority('EXT_MANAGE') and hasAuthority('EMP_MANAGE')")
    public SeparationService.CaseDetail complete(@PathVariable Long id) {
        return service.complete(id);
    }

    @PostMapping("/separations/{id}/cancel")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public SeparationService.CaseDetail cancel(@PathVariable Long id, @RequestBody SeparationService.ItemAction a) {
        return service.cancel(id, a);
    }

    // ---------------------------------------------------------------- clearance

    /** Items awaiting the current user's roles; any signed-in staff role may hold a clearance responsibility. */
    @GetMapping("/clearance/my-tasks")
    public List<SeparationService.TaskView> myTasks() {
        return service.myTasks();
    }

    @PostMapping("/clearance/items/{id}/clear")
    public SeparationService.CaseDetail clear(@PathVariable Long id, @RequestBody(required = false) SeparationService.ItemAction a) {
        return service.clear(id, a); // responsible-role check in the service
    }

    @PostMapping("/clearance/items/{id}/waive")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public SeparationService.CaseDetail waive(@PathVariable Long id, @RequestBody SeparationService.ItemAction a) {
        return service.waive(id, a);
    }
}
