package lk.gov.ciaboc.hrms.recruitment;

import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Recruitment and appointment API (SRS 4.3). Reading needs REC_VIEW; every change needs REC_MANAGE. Available positions
 * and published announcements are visible to all signed-in staff.
 */
@RestController
@RequestMapping("/api/recruitment")
@RequiredArgsConstructor
public class RecruitmentController {
    private final RecruitmentService service;
    private final SelectionService selection;
    private final CorrespondenceService correspondence;
    private final FileStorageService files;

    public record StatusRequest(String status, String remarks) {
    }

    // ------------------------------------------------------------------ open to all staff (FR-REC-002, -003)

    @GetMapping("/positions")
    public List<RecruitmentService.AvailablePosition> availablePositions() {
        return service.availablePositions();
    }

    @GetMapping("/announcements/published")
    public List<Announcement> publishedAnnouncements() {
        return service.publishedAnnouncements();
    }

    // ------------------------------------------------------------------ vacancies

    @GetMapping("/vacancies")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<RecruitmentService.VacancyView> vacancies(@RequestParam(required = false) String status) {
        return service.listVacancies(status);
    }

    @GetMapping("/vacancies/suggestions")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<RecruitmentService.VacancySuggestion> suggestions() {
        return service.suggestions();
    }

    @GetMapping("/vacancies/{id}")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public RecruitmentService.VacancyView vacancy(@PathVariable Long id) {
        return service.vacancyView(id);
    }

    @PostMapping("/vacancies")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public RecruitmentService.VacancyView createVacancy(@RequestBody RecruitmentService.VacancyRequest r) {
        return service.createVacancy(r);
    }

    @PutMapping("/vacancies/{id}")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public RecruitmentService.VacancyView updateVacancy(@PathVariable Long id, @RequestBody RecruitmentService.VacancyRequest r) {
        return service.updateVacancy(id, r);
    }

    @PostMapping("/vacancies/{id}/status")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public RecruitmentService.VacancyView vacancyStatus(@PathVariable Long id, @RequestBody StatusRequest r) {
        return service.changeVacancyStatus(id, r.status(), r.remarks());
    }

    // ------------------------------------------------------------------ announcements

    @GetMapping("/announcements")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<Announcement> announcements() {
        return service.announcements();
    }

    @PostMapping("/announcements")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public Announcement createAnnouncement(@RequestBody RecruitmentService.AnnouncementRequest r) {
        return service.saveAnnouncement(null, r);
    }

    @PutMapping("/announcements/{id}")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public Announcement updateAnnouncement(@PathVariable Long id, @RequestBody RecruitmentService.AnnouncementRequest r) {
        return service.saveAnnouncement(id, r);
    }

    @PostMapping("/announcements/{id}/publish")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public Announcement publish(@PathVariable Long id) {
        return service.publish(id, true);
    }

    @PostMapping("/announcements/{id}/unpublish")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public Announcement unpublish(@PathVariable Long id) {
        return service.publish(id, false);
    }

    // ------------------------------------------------------------------ applicants

    @GetMapping("/applicants")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<RecruitmentService.ApplicantListItem> applicants(@RequestParam(required = false) Long vacancyId,
                                                                 @RequestParam(required = false) String status,
                                                                 @RequestParam(required = false) String q) {
        return service.searchApplicants(vacancyId, status, q);
    }

    @GetMapping("/applicants/{id}")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public RecruitmentService.ApplicantDetail applicant(@PathVariable Long id) {
        return service.applicantDetail(id);
    }

    @PostMapping("/applicants")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public Applicant createApplicant(@RequestBody RecruitmentService.ApplicantRequest r) {
        return service.createApplicant(r);
    }

    @PutMapping("/applicants/{id}")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public Applicant updateApplicant(@PathVariable Long id, @RequestBody RecruitmentService.ApplicantRequest r) {
        return service.updateApplicant(id, r);
    }

    @PostMapping("/applicants/{id}/status")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public Applicant applicantStatus(@PathVariable Long id, @RequestBody StatusRequest r) {
        return service.changeApplicantStatus(id, r.status(), r.remarks());
    }

    @PostMapping(value = "/applicants/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public ApplicantDocument upload(@PathVariable Long id, @RequestParam String documentType, @RequestParam String title,
                                    @RequestPart("file") MultipartFile file) {
        return service.uploadDocument(id, documentType, title, file);
    }

    @GetMapping("/applicants/{id}/documents/{docId}/file")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public ResponseEntity<Resource> download(@PathVariable Long id, @PathVariable Long docId) {
        return files.download(service.documentForDownload(id, docId).getFileId());
    }

    @PostMapping("/applicants/{id}/appoint")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public SelectionService.AppointResult appoint(@PathVariable Long id, @RequestBody SelectionService.AppointRequest r) {
        return selection.appoint(id, r);
    }

    // ------------------------------------------------------------------ examinations

    @GetMapping("/vacancies/{id}/exams")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<RecruitmentExam> exams(@PathVariable Long id) {
        return service.exams(id);
    }

    @PostMapping("/exams")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public RecruitmentExam createExam(@RequestBody RecruitmentService.ExamRequest r) {
        return service.saveExam(null, r);
    }

    @PutMapping("/exams/{id}")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public RecruitmentExam updateExam(@PathVariable Long id, @RequestBody RecruitmentService.ExamRequest r) {
        return service.saveExam(id, r);
    }

    @GetMapping("/exams/{id}/results")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<RecruitmentService.ExamResultRow> examResults(@PathVariable Long id) {
        return service.examResults(id);
    }

    @PutMapping("/exams/{id}/results")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public List<RecruitmentService.ExamResultRow> recordResults(@PathVariable Long id,
                                                                @RequestBody List<RecruitmentService.ResultEntry> entries) {
        return service.recordResults(id, entries);
    }

    // ------------------------------------------------------------------ interviews

    @GetMapping("/vacancies/{id}/interviews")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<RecruitmentService.InterviewRow> interviews(@PathVariable Long id) {
        return service.interviews(id);
    }

    @PostMapping("/interviews")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public Interview scheduleInterview(@RequestBody RecruitmentService.InterviewRequest r) {
        return service.scheduleInterview(r);
    }

    @PutMapping("/interviews/{id}/result")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public Interview recordInterview(@PathVariable Long id, @RequestBody RecruitmentService.InterviewResultRequest r) {
        return service.recordInterview(id, r);
    }

    // ------------------------------------------------------------------ selection (FR-REC-007)

    @GetMapping("/vacancies/{id}/selections")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<SelectionService.SelectionView> selections(@PathVariable Long id) {
        return selection.forVacancy(id);
    }

    @PostMapping("/vacancies/{id}/selections")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public SelectionService.SelectionView prepareSelection(@PathVariable Long id) {
        return selection.prepare(id);
    }

    @GetMapping("/selections/{id}")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public SelectionService.SelectionView selection(@PathVariable Long id) {
        return selection.get(id);
    }

    @PutMapping("/selections/{id}")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public SelectionService.SelectionView updateSelection(@PathVariable Long id, @RequestBody SelectionService.SelectionUpdate r) {
        return selection.update(id, r);
    }

    @PostMapping("/selections/{id}/submit")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public SelectionService.SelectionView submitSelection(@PathVariable Long id) {
        return selection.submit(id);
    }

    // ------------------------------------------------------------------ correspondence (FR-REC-009, -010)

    @GetMapping("/letter-templates")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<LetterTemplate> templates() {
        return correspondence.templates();
    }

    @PostMapping("/letter-templates")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public LetterTemplate createTemplate(@RequestBody CorrespondenceService.TemplateRequest r) {
        return correspondence.saveTemplate(null, r);
    }

    @PutMapping("/letter-templates/{id}")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public LetterTemplate updateTemplate(@PathVariable Long id, @RequestBody CorrespondenceService.TemplateRequest r) {
        return correspondence.saveTemplate(id, r);
    }

    @PostMapping("/applicants/{id}/letters/preview")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public CorrespondenceService.LetterPreview preview(@PathVariable Long id, @RequestBody CorrespondenceService.LetterRequest r) {
        return correspondence.preview(id, r);
    }

    @PostMapping("/applicants/{id}/letters")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public CorrespondenceService.LogView generate(@PathVariable Long id, @RequestBody CorrespondenceService.LetterRequest r) {
        return correspondence.generate(id, r);
    }

    @GetMapping("/applicants/{id}/letters")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<CorrespondenceService.LogView> applicantLetters(@PathVariable Long id) {
        return correspondence.logForApplicant(id);
    }

    @PostMapping("/vacancies/{id}/letters")
    @PreAuthorize("hasAuthority('REC_MANAGE')")
    public CorrespondenceService.BulkResult bulkLetters(@PathVariable Long id, @RequestBody CorrespondenceService.BulkRequest r) {
        return correspondence.generateBulk(id, r);
    }

    @GetMapping("/vacancies/{id}/letters")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public List<CorrespondenceService.LogView> vacancyLetters(@PathVariable Long id) {
        return correspondence.logForVacancy(id);
    }

    @GetMapping("/letters/{id}")
    @PreAuthorize("hasAuthority('REC_VIEW')")
    public CorrespondenceService.LetterDocument letter(@PathVariable Long id) {
        return correspondence.letter(id);
    }
}
