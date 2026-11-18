package lk.gov.ciaboc.hrms.extended.digitization;

import lk.gov.ciaboc.hrms.employee.EmployeeDetails;
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
@RequestMapping("/api/extended/digitization")
@RequiredArgsConstructor
public class DigitizationController {
    private final DigitizationService service;

    @GetMapping("/batches")
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public List<DigitizationService.BatchSummary> list() {
        return service.list();
    }

    @GetMapping("/batches/{id}")
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public DigitizationService.BatchDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/batches")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public DigitizationService.BatchDetail create(@RequestBody DigitizationService.BatchInput in) {
        return service.create(in);
    }

    @PostMapping(value = "/batches/{id}/items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public DigitizationService.ItemView add(@PathVariable Long id, @RequestParam String employeeNo, @RequestParam String documentType,
                                            @RequestParam String title,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate documentDate,
                                            @RequestParam(required = false) EmployeeDetails.Document.Classification classification,
                                            @RequestPart("file") MultipartFile file) {
        return service.addItem(id, new DigitizationService.IndexInput(employeeNo, documentType, title, documentDate, classification), file);
    }

    @PostMapping(value = "/items/{itemId}/correct", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public DigitizationService.ItemView correct(@PathVariable Long itemId, @RequestParam String employeeNo,
                                                @RequestParam String documentType, @RequestParam String title,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate documentDate,
                                                @RequestParam(required = false) EmployeeDetails.Document.Classification classification,
                                                @RequestPart(value = "file", required = false) MultipartFile file) {
        return service.correct(itemId, new DigitizationService.IndexInput(employeeNo, documentType, title, documentDate, classification), file);
    }

    @PostMapping("/items/{itemId}/withdraw")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public DigitizationService.ItemView withdraw(@PathVariable Long itemId) {
        return service.withdraw(itemId);
    }

    @PostMapping("/batches/{id}/submit")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public DigitizationService.BatchDetail submit(@PathVariable Long id) {
        return service.submit(id);
    }

    @PostMapping("/items/{itemId}/qa")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public DigitizationService.ItemView qa(@PathVariable Long itemId, @RequestBody DigitizationService.QaInput in) {
        return service.qa(itemId, in);
    }

    @PostMapping("/batches/{id}/file")
    @PreAuthorize("hasAuthority('EXT_MANAGE') and hasAuthority('EMP_MANAGE')")
    public DigitizationService.BatchDetail file(@PathVariable Long id) {
        return service.file(id);
    }

    @GetMapping("/items/{itemId}/file")
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public ResponseEntity<Resource> download(@PathVariable Long itemId) {
        return service.download(itemId);
    }
}
