package lk.gov.ciaboc.hrms.appraisal;

import lk.gov.ciaboc.hrms.appraisal.AppraisalService.AppraisalView;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Performance appraisal API. Every endpoint is authenticated; authority over an individual appraisal (employee,
 * supervisor, reviewing officer, delegate, appraisal administrator) is decided per record in the services.
 */
@RestController
@RequestMapping("/api/appraisal")
@RequiredArgsConstructor
public class AppraisalController {
    private final AppraisalService service;
    private final AppraisalQueries queries;
    private final AppraisalWorkService work;
    private final AppraisalAdminService admin;

    // ------------------------------------------------------------------ context & dashboards

    @GetMapping("/context")
    public AppraisalQueries.Context context() {
        return queries.context();
    }

    @GetMapping("/my")
    public AppraisalService.MyDashboard my(@RequestParam(required = false) Long periodId) {
        return service.my(periodId);
    }

    @GetMapping("/team")
    public AppraisalQueries.TeamDashboard team(@RequestParam(required = false) Long periodId) {
        return queries.team(periodId);
    }

    @GetMapping("/team/members")
    public List<AppraisalQueries.Member> members() {
        return queries.members();
    }

    @GetMapping("/approvals")
    public List<AppraisalQueries.QueueRow> approvals(@RequestParam(required = false) Long periodId) {
        return queries.approvals(periodId);
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('APR_OVERVIEW')")
    public AppraisalQueries.Overview overview(@RequestParam(required = false) Long periodId,
                                              @RequestParam(required = false) String orgUnitCode,
                                              @RequestParam(required = false) Long designationId) {
        return queries.overview(periodId, orgUnitCode, designationId);
    }

    @GetMapping("/overview/drilldown")
    @PreAuthorize("hasAuthority('APR_REPORT')")
    public List<AppraisalQueries.DrillRow> drilldown(@RequestParam(required = false) Long periodId, @RequestParam String departmentCode,
                                                     @RequestParam(required = false) Long designationId) {
        return queries.drilldown(periodId, departmentCode, designationId);
    }

    // ------------------------------------------------------------------ one appraisal

    @GetMapping("/appraisals/{id}")
    public AppraisalView appraisal(@PathVariable Long id) {
        return service.view(id);
    }

    @PutMapping("/appraisals/{id}/agreement")
    public AppraisalView saveAgreement(@PathVariable Long id, @RequestBody List<AppraisalService.KpiDef> kpis) {
        return service.saveAgreement(id, kpis);
    }

    @PostMapping("/appraisals/{id}/agreement/sign")
    public AppraisalView sign(@PathVariable Long id) {
        return service.signAgreement(id);
    }

    @PostMapping("/appraisals/{id}/agreement/return")
    public AppraisalView returnAgreement(@PathVariable Long id, @RequestBody AppraisalService.CommentRequest r) {
        return service.returnAgreement(id, r.comment());
    }

    @PutMapping("/kpis/{kpiId}/progress")
    public AppraisalView progress(@PathVariable Long kpiId, @RequestBody AppraisalService.ProgressRequest r) {
        return service.saveProgress(kpiId, r);
    }

    @PostMapping(value = "/kpis/{kpiId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AppraisalView uploadEvidence(@PathVariable Long kpiId, @RequestParam("file") MultipartFile file) {
        return service.uploadEvidence(kpiId, file);
    }

    @DeleteMapping("/evidence/{id}")
    public AppraisalView deleteEvidence(@PathVariable Long id) {
        return service.deleteEvidence(id);
    }

    @GetMapping("/evidence/{id}/download")
    public ResponseEntity<Resource> downloadEvidence(@PathVariable Long id) {
        return service.downloadEvidence(id);
    }

    @PostMapping("/kpis/{kpiId}/submit")
    public AppraisalView submitKpi(@PathVariable Long kpiId) {
        return service.submitKpi(kpiId);
    }

    @PutMapping("/appraisals/{id}/self-assessment")
    public AppraisalView selfAssessment(@PathVariable Long id, @RequestBody AppraisalService.SelfAssessmentRequest r) {
        return service.saveSelfAssessment(id, r);
    }

    @PutMapping("/appraisals/{id}/verification")
    public AppraisalView verification(@PathVariable Long id, @RequestBody AppraisalService.VerificationRequest r) {
        return service.saveVerification(id, r);
    }

    @PostMapping("/appraisals/{id}/verification/return")
    public AppraisalView returnToEmployee(@PathVariable Long id, @RequestBody AppraisalService.CommentRequest r) {
        return service.returnToEmployee(id, r.comment());
    }

    @PutMapping("/appraisals/{id}/behaviour")
    public AppraisalView behaviour(@PathVariable Long id, @RequestBody AppraisalService.BehaviourRequest r) {
        return service.saveBehaviour(id, r);
    }

    @PostMapping("/appraisals/{id}/approve")
    public AppraisalView approve(@PathVariable Long id, @RequestBody AppraisalService.ApprovalRequest r) {
        return service.approve(id, r);
    }

    @PostMapping("/appraisals/{id}/return")
    public AppraisalView returnForCorrection(@PathVariable Long id, @RequestBody AppraisalService.CommentRequest r) {
        return service.returnForCorrection(id, r.comment());
    }

    @PostMapping("/appraisals/{id}/acknowledge")
    public AppraisalView acknowledge(@PathVariable Long id, @RequestBody(required = false) AppraisalService.CommentRequest r) {
        return service.acknowledge(id, r == null ? null : r.comment());
    }

    @PutMapping("/appraisals/{id}/officers")
    @PreAuthorize("hasAuthority('APR_MANAGE')")
    public AppraisalView officers(@PathVariable Long id, @RequestBody AppraisalService.OfficersRequest r) {
        return service.setOfficers(id, r);
    }

    @PostMapping("/periods/{periodId}/start")
    @PreAuthorize("hasAuthority('APR_MANAGE')")
    public AppraisalService.StartResult start(@PathVariable Long periodId) {
        return service.start(periodId);
    }

    // ------------------------------------------------------------------ appeals

    @GetMapping("/my/appeals")
    public List<AppraisalWorkService.AppealView> myAppeals() {
        return work.myAppeals();
    }

    @PostMapping(value = "/appraisals/{id}/appeals", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AppraisalWorkService.AppealView appeal(@PathVariable Long id, @RequestParam String grounds,
                                                  @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return work.submitAppeal(id, grounds, files);
    }

    @GetMapping("/appeals")
    @PreAuthorize("hasAuthority('APR_MANAGE')")
    public List<AppraisalWorkService.AppealView> appeals() {
        return work.allAppeals();
    }

    @PostMapping("/appeals/{id}/decision")
    @PreAuthorize("hasAuthority('APR_MANAGE')")
    public AppraisalWorkService.AppealView decideAppeal(@PathVariable Long id, @RequestBody AppraisalWorkService.AppealDecision r) {
        return work.decideAppeal(id, r);
    }

    @GetMapping("/appeals/{id}/files/{fileId}")
    public ResponseEntity<Resource> appealFile(@PathVariable Long id, @PathVariable Long fileId) {
        return work.appealFile(id, fileId);
    }

    // ------------------------------------------------------------------ check-ins & feedback

    @GetMapping("/checkins")
    public List<AppraisalWorkService.CheckinView> checkins() {
        return work.checkins();
    }

    @PostMapping("/checkins")
    public AppraisalWorkService.CheckinView schedule(@RequestBody AppraisalWorkService.CheckinRequest r) {
        return work.scheduleCheckin(r);
    }

    @PostMapping("/checkins/{id}/done")
    public AppraisalWorkService.CheckinView done(@PathVariable Long id, @RequestBody AppraisalWorkService.CheckinRequest r) {
        return work.completeCheckin(id, r);
    }

    @GetMapping("/feedback")
    public AppraisalWorkService.FeedbackLists feedback() {
        return work.feedback();
    }

    @PostMapping("/feedback")
    public AppraisalWorkService.FeedbackView sendFeedback(@RequestBody AppraisalWorkService.FeedbackRequest r) {
        return work.sendFeedback(r);
    }

    // ------------------------------------------------------------------ special cases

    @GetMapping("/special-cases")
    public List<AppraisalWorkService.CaseView> cases() {
        return work.cases();
    }

    @PostMapping(value = "/special-cases", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AppraisalWorkService.CaseView submitCase(@RequestPart("request") AppraisalWorkService.CaseRequest r,
                                                    @RequestPart(value = "file", required = false) MultipartFile file) {
        return work.submitCase(r, file);
    }

    @PostMapping("/special-cases/{id}/decision")
    @PreAuthorize("hasAuthority('APR_MANAGE')")
    public AppraisalWorkService.CaseView decideCase(@PathVariable Long id, @RequestBody AppraisalWorkService.DecisionRequest r) {
        return work.decideCase(id, r);
    }

    @GetMapping("/special-cases/{id}/file")
    public ResponseEntity<Resource> caseFile(@PathVariable Long id) {
        return work.caseFile(id);
    }

    // ------------------------------------------------------------------ delegation & reopening

    @GetMapping("/delegations")
    public List<AppraisalWorkService.DelegationView> delegations() {
        return work.delegations();
    }

    @GetMapping("/officers")
    public List<AppraisalWorkService.Officer> officers() {
        return work.officers();
    }

    @PostMapping("/delegations")
    public AppraisalWorkService.DelegationView delegate(@RequestBody AppraisalWorkService.DelegationRequest r) {
        return work.delegate(r);
    }

    @PostMapping("/delegations/{id}/revoke")
    public AppraisalWorkService.DelegationView revoke(@PathVariable Long id) {
        return work.revoke(id);
    }

    @GetMapping("/reopens")
    public List<AppraisalWorkService.ReopenView> reopens() {
        return work.reopens();
    }

    @GetMapping("/reopens/candidates")
    public List<AppraisalWorkService.ReopenCandidate> reopenCandidates() {
        return work.reopenCandidates();
    }

    @PostMapping("/reopens")
    public AppraisalWorkService.ReopenView requestReopen(@RequestBody AppraisalWorkService.ReopenRequest r) {
        return work.requestReopen(r);
    }

    @PostMapping("/reopens/{id}/decision")
    @PreAuthorize("hasAuthority('APR_MANAGE')")
    public AppraisalWorkService.ReopenView decideReopen(@PathVariable Long id, @RequestBody AppraisalWorkService.DecisionRequest r) {
        return work.decideReopen(id, r);
    }

    // ------------------------------------------------------------------ configuration & sync

    @GetMapping("/policy")
    public AppraisalAdminService.PolicyScreen policy() {
        return admin.policy();
    }

    @PutMapping("/policy/draft")
    @PreAuthorize("hasAuthority('APR_MANAGE')")
    public AppraisalAdminService.PolicyView saveDraft(@RequestBody AppraisalAdminService.PolicyRequest r) {
        return admin.saveDraft(r);
    }

    @PostMapping("/policy/{id}/submit")
    @PreAuthorize("hasAuthority('APR_MANAGE')")
    public AppraisalAdminService.PolicyView submitPolicy(@PathVariable Long id) {
        return admin.submit(id);
    }

    @PostMapping("/policy/{id}/approve")
    @PreAuthorize("hasAuthority('APR_POLICY_APPROVE')")
    public AppraisalAdminService.PolicyView approvePolicy(@PathVariable Long id) {
        return admin.approve(id);
    }

    @PostMapping("/policy/{id}/reject")
    @PreAuthorize("hasAuthority('APR_POLICY_APPROVE')")
    public AppraisalAdminService.PolicyView rejectPolicy(@PathVariable Long id, @RequestBody(required = false) AppraisalService.CommentRequest r) {
        return admin.reject(id, r == null ? null : r.comment());
    }

    @GetMapping("/sync")
    @PreAuthorize("hasAuthority('APR_ADMIN')")
    public AppraisalAdminService.SyncScreen syncStatus() {
        return admin.syncStatus();
    }

    @PostMapping("/sync/all")
    @PreAuthorize("hasAuthority('APR_ADMIN')")
    public AppraisalAdminService.SyncScreen syncAll() {
        return admin.syncAll();
    }

    @PostMapping("/sync/{code}")
    @PreAuthorize("hasAuthority('APR_ADMIN')")
    public AppraisalAdminService.SyncScreen sync(@PathVariable String code) {
        return admin.sync(code);
    }
}
