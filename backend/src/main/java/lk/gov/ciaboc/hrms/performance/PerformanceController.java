package lk.gov.ciaboc.hrms.performance;

import lk.gov.ciaboc.hrms.performance.PerformanceEntities.EvaluationPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Performance evaluation API (SRS 4.8). Configuration needs PER_MANAGE; HR lists need PER_VIEW; evaluators act on
 * evaluations assigned to them; employees see their own finalized evaluations. Per-record confidentiality is enforced in
 * {@link EvaluationService} (FR-PER-009).
 */
@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
public class PerformanceController {
    private final PerformanceConfigService config;
    private final EvaluationService evaluations;

    // ------------------------------------------------------------------ periods

    @GetMapping("/periods")
    public List<EvaluationPeriod> periods() {
        return config.periods();
    }

    @PostMapping("/periods")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public EvaluationPeriod createPeriod(@RequestBody PerformanceConfigService.PeriodRequest r) {
        return config.savePeriod(null, r);
    }

    @PutMapping("/periods/{id}")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public EvaluationPeriod updatePeriod(@PathVariable Long id, @RequestBody PerformanceConfigService.PeriodRequest r) {
        return config.savePeriod(id, r);
    }

    public record StatusRequest(String status) {
    }

    @PostMapping("/periods/{id}/status")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public EvaluationPeriod periodStatus(@PathVariable Long id, @RequestBody StatusRequest r) {
        return config.setPeriodStatus(id, r.status());
    }

    @PostMapping("/periods/{id}/generate")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public EvaluationService.GenerateResult generate(@PathVariable Long id) {
        return evaluations.generate(id);
    }

    // ------------------------------------------------------------------ models & category assignment

    @GetMapping("/models")
    @PreAuthorize("hasAnyAuthority('PER_MANAGE','PER_VIEW')")
    public List<PerformanceConfigService.ModelView> models() {
        return config.models();
    }

    @GetMapping("/models/{id}")
    @PreAuthorize("hasAnyAuthority('PER_MANAGE','PER_VIEW')")
    public PerformanceConfigService.ModelView model(@PathVariable Long id) {
        return config.modelView(id);
    }

    @PostMapping("/models")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public PerformanceConfigService.ModelView createModel(@RequestBody PerformanceConfigService.ModelRequest r) {
        return config.saveModel(null, r);
    }

    @PutMapping("/models/{id}")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public PerformanceConfigService.ModelView updateModel(@PathVariable Long id, @RequestBody PerformanceConfigService.ModelRequest r) {
        return config.saveModel(id, r);
    }

    public record CopyRequest(String code, String name) {
    }

    @PostMapping("/models/{id}/copy")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public PerformanceConfigService.ModelView copyModel(@PathVariable Long id, @RequestBody CopyRequest r) {
        return config.copyModel(id, r.code(), r.name());
    }

    @GetMapping("/category-models")
    @PreAuthorize("hasAnyAuthority('PER_MANAGE','PER_VIEW')")
    public List<PerformanceConfigService.CategoryAssignment> categoryModels() {
        return config.categoryAssignments();
    }

    public record AssignRequest(Long modelId) {
    }

    @PutMapping("/category-models/{category}")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public PerformanceConfigService.CategoryAssignment assign(@PathVariable String category, @RequestBody AssignRequest r) {
        return config.assign(category, r.modelId());
    }

    // ------------------------------------------------------------------ evaluations

    @GetMapping("/evaluations")
    @PreAuthorize("hasAuthority('PER_VIEW')")
    public List<EvaluationService.EvaluationListItem> list(@RequestParam(required = false) Long periodId,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String orgUnitCode) {
        return evaluations.list(periodId, status, orgUnitCode);
    }

    @GetMapping("/evaluations/assigned")
    public List<EvaluationService.EvaluationListItem> assigned() {
        return evaluations.assignedToMe();
    }

    @GetMapping("/my/evaluations")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public List<EvaluationService.EvaluationListItem> mine() {
        return evaluations.mine();
    }

    @GetMapping("/employees/{employeeId}/history")
    public List<EvaluationService.EvaluationListItem> history(@PathVariable Long employeeId) {
        return evaluations.history(employeeId);
    }

    /** Access is decided per record (evaluator, subject after finalization, workflow participant, PER_VIEW in scope). */
    @GetMapping("/evaluations/{id}")
    public EvaluationService.EvaluationDetail detail(@PathVariable Long id) {
        return evaluations.detail(id);
    }

    @PostMapping("/evaluations")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public EvaluationService.EvaluationDetail create(@RequestBody EvaluationService.CreateRequest r) {
        return evaluations.create(r);
    }

    public record ReassignRequest(Long evaluatorUserId) {
    }

    @PutMapping("/evaluations/{id}/evaluator")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public EvaluationService.EvaluationDetail reassign(@PathVariable Long id, @RequestBody ReassignRequest r) {
        return evaluations.reassign(id, r.evaluatorUserId());
    }

    @GetMapping("/evaluators")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public List<EvaluationService.EvaluatorOption> evaluators() {
        return evaluations.evaluators();
    }

    /** Only the assigned evaluator may rate (checked in the service). */
    @PutMapping("/evaluations/{id}")
    public EvaluationService.EvaluationDetail save(@PathVariable Long id, @RequestBody EvaluationService.SaveRequest r) {
        return evaluations.save(id, r);
    }

    @PostMapping("/evaluations/{id}/submit")
    public EvaluationService.EvaluationDetail submit(@PathVariable Long id) {
        return evaluations.submit(id);
    }

    public record AcknowledgeRequest(String comments) {
    }

    @PostMapping("/evaluations/{id}/acknowledge")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public EvaluationService.EvaluationDetail acknowledge(@PathVariable Long id, @RequestBody(required = false) AcknowledgeRequest r) {
        return evaluations.acknowledge(id, r == null ? null : r.comments());
    }

    public record ReminderRequest(Long periodId) {
    }

    @PostMapping("/reminders")
    @PreAuthorize("hasAuthority('PER_MANAGE')")
    public EvaluationService.ReminderResult reminders(@RequestBody(required = false) ReminderRequest r) {
        return evaluations.sendReminders(r == null ? null : r.periodId(), true);
    }
}
