package lk.gov.ciaboc.hrms.extended.movement;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/extended/movements")
@RequiredArgsConstructor
public class MovementController {
    private final MovementService service;

    @GetMapping
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public List<MovementService.MovementView> list(@RequestParam(required = false) MovementRequest.Status status,
                                                   @RequestParam(required = false) MovementRequest.Type type,
                                                   @RequestParam(required = false) Long employeeId) {
        return service.list(status, type, employeeId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public MovementService.MovementView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/cadre-check")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public MovementService.CadreCheck cadreCheck(@RequestBody MovementService.MovementInput in) {
        return service.preview(in);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public MovementService.MovementView create(@RequestBody MovementService.MovementInput in) {
        return service.create(in);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public MovementService.MovementView revise(@PathVariable Long id, @RequestBody MovementService.MovementInput in) {
        return service.revise(id, in);
    }
}
