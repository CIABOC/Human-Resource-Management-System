package lk.gov.ciaboc.hrms.establishment;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/establishment")
@RequiredArgsConstructor
public class CategorizationController {
    private final CategorizationService service;

    @GetMapping("/categorization")
    @PreAuthorize("hasAuthority('EST_VIEW') or hasAuthority('EMP_VIEW')")
    public CategorizationService.Chart chart(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.chart(asOf);
    }

    @GetMapping("/categorization/history")
    @PreAuthorize("hasAuthority('EST_VIEW')")
    public List<CategorizationService.HistoryRow> history() {
        return service.history();
    }

    @PostMapping("/categorization/batches")
    @PreAuthorize("hasAuthority('EST_MANAGE')")
    public Categorization.Batch submit(@RequestBody CategorizationService.SubmitRequest r) {
        return service.submit(r);
    }

    /** Explains whether a category may be dropped under another (closed / circular checks, REQ 7.4). */
    @GetMapping("/categorization/check-category-move")
    @PreAuthorize("hasAuthority('EST_VIEW')")
    public CategorizationService.DropCheck checkCategoryMove(@RequestParam Long categoryId, @RequestParam(required = false) Long toParentId) {
        return service.checkCategoryMove(categoryId, toParentId);
    }

    @PostMapping("/position-categories")
    @PreAuthorize("hasAuthority('EST_MANAGE')")
    public PositionCategory create(@RequestBody CategorizationService.CategoryRequest r) {
        return service.createCategory(r);
    }

    @PutMapping("/position-categories/{id}")
    @PreAuthorize("hasAuthority('EST_MANAGE')")
    public PositionCategory update(@PathVariable Long id, @RequestBody CategorizationService.CategoryRequest r) {
        return service.updateCategory(id, r);
    }
}
