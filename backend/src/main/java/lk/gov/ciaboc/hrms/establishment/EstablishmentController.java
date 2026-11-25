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
    private final lk.gov.ciaboc.hrms.files.FileStorageService files;

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

    @GetMapping("/cadre/reconciliation")
    @PreAuthorize("hasAuthority('EST_VIEW')")
    public EstablishmentService.Reconciliation reconciliation(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.reconciliation(asOf);
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

    /** Validates a change and returns its summary and impact (REQ 6.5 impact preview) without saving it. */
    @PostMapping("/changes/preview")
    @PreAuthorize("hasAuthority('EST_MANAGE')")
    public java.util.Map<String, Object> preview(@RequestBody EstablishmentService.ChangeRequest r) {
        return service.preview(r);
    }

    @PostMapping("/changes/{id}/submit")
    @PreAuthorize("hasAuthority('EST_MANAGE')")
    public EstablishmentChange submitDraft(@PathVariable Long id) {
        return service.submitDraft(id);
    }

    @PostMapping("/changes/{id}/discard")
    @PreAuthorize("hasAuthority('EST_MANAGE')")
    public EstablishmentChange discard(@PathVariable Long id) {
        return service.discardDraft(id);
    }

    @PostMapping(value = "/changes/{id}/attachment", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('EST_MANAGE')")
    public EstablishmentChange attach(@PathVariable Long id, @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return service.attach(id, files.store(file, "ESTABLISHMENT").getId());
    }

    @GetMapping("/changes/{id}/attachment")
    @PreAuthorize("hasAuthority('EST_VIEW')")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> attachment(@PathVariable Long id) {
        EstablishmentChange c = service.change(id);
        if (c.getAttachmentFileId() == null) throw ApiException.notFound("Attachment for change", id);
        return files.download(c.getAttachmentFileId());
    }
}
