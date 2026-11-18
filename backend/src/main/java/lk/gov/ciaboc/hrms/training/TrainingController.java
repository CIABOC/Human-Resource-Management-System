package lk.gov.ciaboc.hrms.training;

import lk.gov.ciaboc.hrms.files.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Training and development API (SRS 4.9). */
@RestController
@RequestMapping("/api/training")
@RequiredArgsConstructor
public class TrainingController {
    private final TrainingService service;
    private final FileStorageService files;

    @GetMapping("/programmes")
    @PreAuthorize("hasAuthority('TRN_VIEW')")
    public List<TrainingService.ProgrammeView> programmes(@RequestParam(required = false) String status) {
        return service.programmes(status);
    }

    @GetMapping("/programmes/open")
    public List<TrainingService.ProgrammeView> open() {
        return service.openProgrammes();
    }

    @GetMapping("/programmes/{id}")
    @PreAuthorize("hasAuthority('TRN_VIEW')")
    public TrainingService.ProgrammeView programme(@PathVariable Long id) {
        return service.programmeView(id);
    }

    @PostMapping("/programmes")
    @PreAuthorize("hasAuthority('TRN_MANAGE')")
    public TrainingService.ProgrammeView create(@RequestBody TrainingService.ProgrammeRequest r) {
        return service.saveProgramme(null, r);
    }

    @PutMapping("/programmes/{id}")
    @PreAuthorize("hasAuthority('TRN_MANAGE')")
    public TrainingService.ProgrammeView update(@PathVariable Long id, @RequestBody TrainingService.ProgrammeRequest r) {
        return service.saveProgramme(id, r);
    }

    public record StatusRequest(String status) {
    }

    @PostMapping("/programmes/{id}/status")
    @PreAuthorize("hasAuthority('TRN_MANAGE')")
    public TrainingService.ProgrammeView status(@PathVariable Long id, @RequestBody StatusRequest r) {
        return service.changeStatus(id, r.status());
    }

    @GetMapping("/programmes/{id}/nominations")
    @PreAuthorize("hasAuthority('TRN_VIEW')")
    public List<TrainingService.NominationView> nominations(@PathVariable Long id) {
        return service.forProgramme(id);
    }

    @PostMapping("/nominations")
    @PreAuthorize("hasAuthority('TRN_MANAGE')")
    public TrainingService.NominationView nominate(@RequestBody TrainingService.NominationRequest r) {
        return service.nominate(r);
    }

    public record SelfNominationRequest(Long programmeId, String reason) {
    }

    @PostMapping("/my/nominations")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public TrainingService.NominationView selfNominate(@RequestBody SelfNominationRequest r) {
        return service.selfNominate(r.programmeId(), r.reason());
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public List<TrainingService.NominationView> mine() {
        return service.mine();
    }

    @GetMapping("/employees/{employeeId}/history")
    public List<TrainingService.NominationView> history(@PathVariable Long employeeId) {
        return service.history(employeeId);
    }

    public record CancelRequest(String reason) {
    }

    @PostMapping("/nominations/{id}/cancel")
    public TrainingService.NominationView cancel(@PathVariable Long id, @RequestBody(required = false) CancelRequest r) {
        return service.cancel(id, r == null ? null : r.reason());
    }

    @PutMapping("/nominations/{id}/completion")
    @PreAuthorize("hasAuthority('TRN_MANAGE')")
    public TrainingService.NominationView completion(@PathVariable Long id, @RequestBody TrainingService.CompletionRequest r) {
        return service.recordCompletion(id, r);
    }

    @PostMapping(value = "/nominations/{id}/certificate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('TRN_MANAGE')")
    public TrainingService.NominationView certificate(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return service.uploadCertificate(id, file);
    }

    @GetMapping("/nominations/{id}/certificate")
    public ResponseEntity<Resource> downloadCertificate(@PathVariable Long id) {
        return files.download(service.certificateForDownload(id));
    }
}
