package lk.gov.ciaboc.hrms.legal;

import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/legal")
@RequiredArgsConstructor
public class LegalController {
    private final LegalService service;
    private final FileStorageService files;

    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    public List<LegalDocument> list() {
        return service.list();
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    public LegalDocument get(@PathVariable Long id) {
        return service.document(id);
    }

    @GetMapping("/documents/{id}/versions")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    public List<LegalDocument> versions(@PathVariable Long id) {
        return service.versions(id);
    }

    @GetMapping("/documents/{id}/file")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        LegalDocument d = service.document(id);
        if (d.getFileId() == null) throw ApiException.notFound("File for document", id);
        service.auditDownload(d);
        return files.download(d.getFileId());
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    public LegalDocument create(@RequestPart("data") LegalService.DocumentRequest data,
                                @RequestPart(value = "file", required = false) MultipartFile file) {
        return service.create(data, file);
    }

    @PutMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    public LegalDocument update(@PathVariable Long id, @RequestBody LegalService.DocumentRequest data) {
        return service.update(id, data);
    }

    @PostMapping(value = "/documents/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    public LegalDocument newVersion(@PathVariable Long id, @RequestPart("data") LegalService.DocumentRequest data,
                                    @RequestPart(value = "file", required = false) MultipartFile file) {
        return service.newVersion(id, data, file);
    }

    @PostMapping("/documents/{id}/status")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    public LegalDocument status(@PathVariable Long id, @RequestParam LegalDocument.Status status) {
        return service.setStatus(id, status);
    }

    @PostMapping("/documents/{id}/verify")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    public LegalDocument verify(@PathVariable Long id) {
        return service.verify(id);
    }

    @GetMapping("/documents/{id}/sections")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    public List<LegalSection> sections(@PathVariable Long id,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.sections(id, asOf);
    }

    @PostMapping("/documents/{id}/sections")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    public LegalSection addSection(@PathVariable Long id, @RequestBody LegalService.SectionRequest r) {
        return service.addSection(id, r);
    }

    @PutMapping("/sections/{id}")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    public LegalSection updateSection(@PathVariable Long id, @RequestBody LegalService.SectionRequest r) {
        return service.updateSection(id, r);
    }

    @PostMapping("/sections/{id}/verify")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    public LegalSection verifySection(@PathVariable Long id) {
        return service.verifySection(id);
    }

    @GetMapping("/sections/{id}/versions")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    public List<LegalSection> sectionVersions(@PathVariable Long id) {
        return service.sectionVersions(id);
    }

    @GetMapping("/sections/by-designation/{designationId}")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    public List<LegalSection> byDesignation(@PathVariable Long designationId) {
        return service.forDesignation(designationId);
    }

    @GetMapping("/sections/by-topic/{tag}")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    public List<LegalSection> byTopic(@PathVariable String tag) {
        return service.forTag(tag);
    }

    @GetMapping("/documents/{id}/amendments")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    public List<Amendment> amendments(@PathVariable Long id) {
        return service.amendments(id);
    }

    @PostMapping("/documents/{id}/amendments")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    public Amendment amend(@PathVariable Long id, @RequestBody LegalService.AmendmentRequest r) {
        return service.amend(id, r);
    }
}
