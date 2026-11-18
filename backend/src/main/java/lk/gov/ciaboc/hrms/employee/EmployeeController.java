package lk.gov.ciaboc.hrms.employee;

import lk.gov.ciaboc.hrms.common.PageResponse;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {
    private final EmployeeService service;
    private final FileStorageService files;

    @GetMapping
    @PreAuthorize("hasAuthority('EMP_VIEW')")
    public PageResponse<EmployeeService.EmployeeListItem> search(@RequestParam(required = false) String q,
                                                                 @RequestParam(required = false) String orgUnitCode,
                                                                 @RequestParam(required = false) Long designationId,
                                                                 @RequestParam(required = false) String status,
                                                                 @RequestParam(required = false) String category,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "25") int size) {
        return service.search(q, orgUnitCode, designationId, status, category, page, size);
    }

    @GetMapping("/me")
    public EmployeeService.EmployeeView me() {
        return service.view(CurrentUser.employeeId());
    }

    @GetMapping("/{id}")
    public EmployeeService.EmployeeView get(@PathVariable Long id) {
        return service.view(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public lk.gov.ciaboc.hrms.employee.Employee create(@RequestBody EmployeeService.EmployeeRequest r) {
        return service.create(r);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public EmployeeService.SaveResult update(@PathVariable Long id, @RequestBody EmployeeService.EmployeeRequest r) {
        return service.update(id, r);
    }

    @PostMapping("/{id}/assignments")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public EmployeeAssignment assign(@PathVariable Long id, @RequestBody EmployeeService.AssignRequest r) {
        return service.assign(id, r);
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public Employee status(@PathVariable Long id, @RequestBody EmployeeService.StatusRequest r) {
        return service.changeStatus(id, r);
    }

    @GetMapping("/{id}/service-history")
    public EmployeeService.ServiceHistory serviceHistory(@PathVariable Long id) {
        return service.serviceHistory(id);
    }

    @GetMapping("/{id}/details")
    public Map<String, Object> details(@PathVariable Long id) {
        return service.details(id);
    }

    @PostMapping("/{id}/qualifications")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public EmployeeDetails.Qualification addQualification(@PathVariable Long id, @RequestBody EmployeeDetails.Qualification r) {
        return service.saveQualification(id, null, r);
    }

    @PutMapping("/{id}/qualifications/{qid}")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public EmployeeDetails.Qualification updateQualification(@PathVariable Long id, @PathVariable Long qid, @RequestBody EmployeeDetails.Qualification r) {
        return service.saveQualification(id, qid, r);
    }

    @DeleteMapping("/{id}/qualifications/{qid}")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public void deleteQualification(@PathVariable Long id, @PathVariable Long qid) {
        service.deleteQualification(id, qid);
    }

    @PostMapping("/{id}/previous-employment")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public EmployeeDetails.PreviousEmployment addPrev(@PathVariable Long id, @RequestBody EmployeeDetails.PreviousEmployment r) {
        return service.savePreviousEmployment(id, null, r);
    }

    @PutMapping("/{id}/previous-employment/{pid}")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public EmployeeDetails.PreviousEmployment updatePrev(@PathVariable Long id, @PathVariable Long pid, @RequestBody EmployeeDetails.PreviousEmployment r) {
        return service.savePreviousEmployment(id, pid, r);
    }

    @DeleteMapping("/{id}/previous-employment/{pid}")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public void deletePrev(@PathVariable Long id, @PathVariable Long pid) {
        service.deletePreviousEmployment(id, pid);
    }

    @PostMapping("/{id}/emergency-contacts")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public EmployeeDetails.EmergencyContact addContact(@PathVariable Long id, @RequestBody EmployeeDetails.EmergencyContact r) {
        return service.saveEmergencyContact(id, null, r);
    }

    @PutMapping("/{id}/emergency-contacts/{cid}")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public EmployeeDetails.EmergencyContact updateContact(@PathVariable Long id, @PathVariable Long cid, @RequestBody EmployeeDetails.EmergencyContact r) {
        return service.saveEmergencyContact(id, cid, r);
    }

    @DeleteMapping("/{id}/emergency-contacts/{cid}")
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public void deleteContact(@PathVariable Long id, @PathVariable Long cid) {
        service.deleteEmergencyContact(id, cid);
    }

    @GetMapping("/{id}/documents")
    public List<EmployeeDetails.Document> documents(@PathVariable Long id) {
        return service.documents(id);
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('EMP_MANAGE')")
    public EmployeeDetails.Document upload(@PathVariable Long id, @RequestParam String documentType,
                                           @RequestParam String title,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate documentDate,
                                           @RequestParam(required = false) EmployeeDetails.Document.Classification classification,
                                           @RequestParam(required = false) String remarks,
                                           @RequestPart("file") MultipartFile file) {
        return service.upload(id, documentType, title, documentDate, classification, remarks, file);
    }

    @GetMapping("/{id}/documents/{docId}/file")
    public ResponseEntity<Resource> download(@PathVariable Long id, @PathVariable Long docId) {
        return files.download(service.documentForDownload(id, docId).getFileId());
    }

    // ---- self-service & change requests ----

    public record SelfChangeRequest(Map<String, String> changes, String reason) {
    }

    @PostMapping("/me/change-requests")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public EmployeeDetails.ProfileChangeRequest requestChange(@RequestBody SelfChangeRequest r) {
        return service.requestSelfChange(r.changes(), r.reason());
    }

    @GetMapping("/{id}/change-requests")
    public List<EmployeeDetails.ProfileChangeRequest> changeRequests(@PathVariable Long id) {
        return service.changeRequests(id);
    }

    // ---- bulk import ----

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('EMP_IMPORT')")
    public EmployeeService.ImportResult importCsv(@RequestPart("file") MultipartFile file,
                                                  @RequestParam(defaultValue = "false") boolean commit) {
        return service.importCsv(file, commit);
    }

    @GetMapping("/import/template")
    @PreAuthorize("hasAuthority('EMP_IMPORT')")
    public ResponseEntity<byte[]> importTemplate() {
        String csv = String.join(",", EmployeeService.IMPORT_COLUMNS) + "\n"
                + "E0001,Mr,Sample Full Name,S. F. Name,199012345678,1990-01-15,MALE,sample@ciaboc.gov.lk,0771234567,ACTIVE,PERMANENT,EXECUTIVE,,2020-01-01,ADMIN,AO\n";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=employee-import-template.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
