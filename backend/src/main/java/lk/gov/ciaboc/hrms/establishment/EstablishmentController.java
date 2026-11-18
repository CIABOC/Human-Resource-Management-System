package lk.gov.ciaboc.hrms.establishment;

import lk.gov.ciaboc.hrms.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Structure, designations and positions are readable by any signed-in user (needed for drop-downs and the org chart);
 * cadre and change history need EST_VIEW; changes need EST_MANAGE and approval.
 */
@RestController
@RequestMapping("/api/establishment")
@RequiredArgsConstructor
public class EstablishmentController {
    private final EstablishmentService service;
    private final DesignationRepository designations;
    private final PositionRepository positions;

    @GetMapping("/org-units")
    public List<OrgUnit> orgUnits(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.orgUnits(asOf);
    }

    @GetMapping("/org-units/{code}/history")
    @PreAuthorize("hasAuthority('EST_VIEW')")
    public List<OrgUnit> orgUnitHistory(@PathVariable String code) {
        return service.orgUnitHistory(code);
    }

    @GetMapping("/org-chart")
    public List<EstablishmentService.OrgNode> orgChart(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.orgChart(asOf);
    }

    @GetMapping("/designations")
    public List<Designation> designations(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return includeInactive ? designations.findAllByOrderByNameAsc() : designations.findByActiveTrueOrderByNameAsc();
    }

    @GetMapping("/positions")
    public List<Position> positions(@RequestParam(defaultValue = "false") boolean includeInactive,
                                   @RequestParam(required = false) String orgUnitCode) {
        return positions.findAllByOrderByCodeAsc().stream()
                .filter(p -> includeInactive || p.isActive())
                .filter(p -> orgUnitCode == null || orgUnitCode.equals(p.getOrgUnitCode()))
                .toList();
    }

    @GetMapping("/cadre")
    @PreAuthorize("hasAuthority('EST_VIEW')")
    public List<EstablishmentService.CadreRow> cadre(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                                     @RequestParam(required = false) String orgUnitCode) {
        return service.cadreSummary(asOf, orgUnitCode);
    }

    @GetMapping("/cadre/history")
    @PreAuthorize("hasAuthority('EST_VIEW')")
    public List<CadreAllocation> cadreHistory(@RequestParam Long designationId, @RequestParam String orgUnitCode) {
        return service.cadreHistory(designationId, orgUnitCode);
    }

    @GetMapping("/changes")
    @PreAuthorize("hasAuthority('EST_VIEW')")
    public List<EstablishmentChange> changes(@RequestParam(required = false) String status) {
        EstablishmentChange.Status s = null;
        if (status != null && !status.isBlank()) {
            try {
                s = EstablishmentChange.Status.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown status " + status);
            }
        }
        return service.changes(s);
    }

    @GetMapping("/changes/{id}")
    @PreAuthorize("hasAuthority('EST_VIEW')")
    public EstablishmentChange change(@PathVariable Long id) {
        return service.change(id);
    }

    @PostMapping("/changes")
    @PreAuthorize("hasAuthority('EST_MANAGE')")
    public EstablishmentChange submit(@RequestBody EstablishmentService.ChangeRequest r) {
        return service.submit(r);
    }

    @PutMapping("/changes/{id}")
    @PreAuthorize("hasAuthority('EST_MANAGE')")
    public EstablishmentChange revise(@PathVariable Long id, @RequestBody EstablishmentService.ChangeRequest r) {
        return service.revise(id, r);
    }
}
