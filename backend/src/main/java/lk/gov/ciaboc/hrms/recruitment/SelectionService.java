package lk.gov.ciaboc.hrms.recruitment;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeService;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.*;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Selection and appointment (FR-REC-007, -008). The system ranks applicants by their recorded marks; the selection
 * panel decides whom to recommend (with written justification when departing from the ranking) and Management approves
 * through the RECRUITMENT_SELECTION workflow. Humans remain accountable for every selection decision (C-05).
 */
@Service
@RequiredArgsConstructor
public class SelectionService implements WorkflowHandler {
    public static final String PROCESS = "RECRUITMENT_SELECTION";

    private final RecruitmentService recruitment;
    private final SelectionListRepository selections;
    private final SelectionItemRepository items;
    private final ApplicantRepository applicants;
    private final EmployeeService employees;
    private final WorkflowService workflow;
    private final AuditService audit;

    public record ItemView(Long id, Long applicantId, String applicationNo, String applicantName, String applicantStatus,
                           int rankNo, BigDecimal examScore, BigDecimal interviewScore, BigDecimal totalScore,
                           boolean recommended, String remarks) {
    }

    public record SelectionView(Long id, Long vacancyId, String vacancyRef, int numPosts, long alreadyAppointed,
                                String status, String remarks, Long workflowInstanceId, Instant submittedAt,
                                Instant decidedAt, List<ItemView> items) {
    }

    public record ItemDecision(Long applicantId, Boolean recommended, String remarks) {
    }

    public record SelectionUpdate(String remarks, List<ItemDecision> items) {
    }

    @Transactional(readOnly = true)
    public List<SelectionView> forVacancy(Long vacancyId) {
        recruitment.vacancy(vacancyId);
        return selections.findByVacancyIdOrderByIdDesc(vacancyId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SelectionView get(Long id) {
        return view(selection(id));
    }

    /**
     * Creates (or refreshes) the editable selection list for a vacancy: every shortlisted applicant, ranked by score.
     * Existing recommendations and remarks are kept when refreshing.
     */
    @Transactional
    public SelectionView prepare(Long vacancyId) {
        Vacancy v = recruitment.vacancy(vacancyId);
        if (!RecruitmentService.ACTIVE_VACANCY.contains(v.getStatus()))
            throw ApiException.rule("VACANCY_CLOSED", "The vacancy is no longer active");
        List<SelectionList> existing = selections.findByVacancyIdOrderByIdDesc(vacancyId);
        if (existing.stream().anyMatch(s -> s.getStatus() == SelectionStatus.PENDING))
            throw ApiException.rule("PENDING_SELECTION", "A selection list for this vacancy is awaiting approval");
        SelectionList s = existing.stream()
                .filter(x -> x.getStatus() == SelectionStatus.DRAFT || x.getStatus() == SelectionStatus.RETURNED)
                .findFirst().orElseGet(() -> {
                    SelectionList n = new SelectionList();
                    n.setVacancyId(vacancyId);
                    n.setStatus(SelectionStatus.DRAFT);
                    return selections.save(n);
                });
        Map<Long, SelectionItem> previous = items.findBySelectionIdOrderByRankNoAsc(s.getId()).stream()
                .collect(Collectors.toMap(SelectionItem::getApplicantId, i -> i));
        items.deleteBySelectionId(s.getId());
        items.flush();
        record Ranked(Applicant a, SelectionScoring.Score sc) {
        }
        List<Ranked> ranked = applicants.findByVacancyIdAndStatusOrderByIdAsc(vacancyId, ApplicantStatus.SHORTLISTED).stream()
                .map(a -> new Ranked(a, recruitment.score(a, v)))
                .sorted(Comparator.comparing((Ranked r) -> r.sc().total()).reversed()
                        .thenComparing(r -> r.a().getReceivedDate()).thenComparing(r -> r.a().getId()))
                .toList();
        if (ranked.isEmpty()) throw ApiException.rule("NO_APPLICANTS", "There are no shortlisted applicants to rank");
        int rank = 0;
        BigDecimal lastScore = null;
        for (int i = 0; i < ranked.size(); i++) {
            Ranked r = ranked.get(i);
            if (lastScore == null || r.sc().total().compareTo(lastScore) != 0) rank = i + 1; // ties share a rank
            lastScore = r.sc().total();
            SelectionItem it = new SelectionItem();
            it.setSelectionId(s.getId());
            it.setApplicantId(r.a().getId());
            it.setRankNo(rank);
            it.setExamScore(r.sc().examPercent());
            it.setInterviewScore(r.sc().interviewPercent());
            it.setTotalScore(r.sc().total());
            SelectionItem old = previous.get(r.a().getId());
            it.setRecommended(old != null && old.isRecommended());
            it.setRemarks(old == null ? null : old.getRemarks());
            items.save(it);
        }
        audit.log(AuditAction.PROCESS, "SelectionList", s.getId(), "Ranked " + ranked.size() + " applicants for " + v.getReferenceNo());
        return view(s);
    }

    /** Records the panel's decisions. Recommending someone below a non-recommended higher-ranked applicant needs a justification. */
    @Transactional
    public SelectionView update(Long id, SelectionUpdate r) {
        SelectionList s = selection(id);
        if (s.getStatus() != SelectionStatus.DRAFT && s.getStatus() != SelectionStatus.RETURNED)
            throw ApiException.rule("NOT_EDITABLE", "Only draft or returned selection lists can be changed");
        Map<Long, SelectionItem> byApplicant = items.findBySelectionIdOrderByRankNoAsc(id).stream()
                .collect(Collectors.toMap(SelectionItem::getApplicantId, i -> i));
        if (r.items() != null) {
            for (ItemDecision d : r.items()) {
                SelectionItem it = byApplicant.get(d.applicantId());
                if (it == null) throw ApiException.badRequest("Applicant " + d.applicantId() + " is not on this selection list");
                if (d.recommended() != null) it.setRecommended(d.recommended());
                it.setRemarks(RecruitmentService.blankToNull(d.remarks()));
            }
        }
        s.setRemarks(RecruitmentService.blankToNull(r.remarks()));
        audit.log(AuditAction.UPDATE, "SelectionList", id, "Selection decisions recorded: "
                + byApplicant.values().stream().filter(SelectionItem::isRecommended).count() + " recommended");
        return view(s);
    }

    @Transactional
    public SelectionView submit(Long id) {
        SelectionList s = selection(id);
        if (s.getStatus() != SelectionStatus.DRAFT && s.getStatus() != SelectionStatus.RETURNED)
            throw ApiException.rule("NOT_EDITABLE", "Only draft or returned selection lists can be submitted");
        Vacancy v = recruitment.vacancy(s.getVacancyId());
        validateDecisions(s, v);
        boolean resubmit = s.getStatus() == SelectionStatus.RETURNED && s.getWorkflowInstanceId() != null;
        s.setStatus(SelectionStatus.PENDING);
        s.setSubmittedAt(Instant.now());
        markRecommended(s, true);
        if (resubmit) {
            workflow.resubmit(s.getWorkflowInstanceId(), "Selection list revised");
        } else {
            WorkflowInstance wi = workflow.start(PROCESS, "SelectionList", s.getId(),
                    "Selection for " + v.getReferenceNo() + " — " + v.getTitle(), "/recruitment/vacancies/" + v.getId() + "?tab=selection", null);
            s.setWorkflowInstanceId(wi.getId());
        }
        audit.log(AuditAction.SUBMIT, "SelectionList", id, "Selection list for " + v.getReferenceNo() + " submitted for approval");
        return view(s);
    }

    private void validateDecisions(SelectionList s, Vacancy v) {
        List<SelectionItem> list = items.findBySelectionIdOrderByRankNoAsc(s.getId());
        long recommended = list.stream().filter(SelectionItem::isRecommended).count();
        if (recommended == 0) throw ApiException.rule("NONE_RECOMMENDED", "Recommend at least one applicant");
        long open = v.getNumPosts() - applicants.countByVacancyIdAndStatus(v.getId(), ApplicantStatus.APPOINTED)
                - applicants.countByVacancyIdAndStatus(v.getId(), ApplicantStatus.SELECTED);
        if (recommended > open)
            throw ApiException.rule("TOO_MANY", "Only " + Math.max(0, open) + " post(s) remain to be filled; " + recommended + " recommended");
        for (SelectionItem it : list) {
            if (!it.isRecommended()) continue;
            boolean bypasses = list.stream().anyMatch(o -> !o.isRecommended() && o.getRankNo() < it.getRankNo());
            if (bypasses && RecruitmentService.blank(it.getRemarks()))
                throw ApiException.rule("JUSTIFICATION_REQUIRED", "Give a reason for recommending the applicant ranked " + it.getRankNo()
                        + " ahead of a higher-ranked applicant who is not recommended");
        }
        for (SelectionItem it : list) {
            Applicant a = recruitment.applicant(it.getApplicantId());
            if (a.getStatus() != ApplicantStatus.SHORTLISTED && a.getStatus() != ApplicantStatus.RECOMMENDED)
                throw ApiException.rule("STALE_LIST", "Applicant " + a.getApplicationNo() + " is now " + a.getStatus() + "; refresh the ranking");
        }
    }

    /** Moves recommended applicants to RECOMMENDED (pending approval) or back to SHORTLISTED. */
    private void markRecommended(SelectionList s, boolean pending) {
        for (SelectionItem it : items.findBySelectionIdOrderByRankNoAsc(s.getId())) {
            if (!it.isRecommended()) continue;
            Applicant a = recruitment.applicant(it.getApplicantId());
            ApplicantStatus to = pending ? ApplicantStatus.RECOMMENDED : ApplicantStatus.SHORTLISTED;
            if (a.getStatus() != to && (a.getStatus() == ApplicantStatus.SHORTLISTED || a.getStatus() == ApplicantStatus.RECOMMENDED)) {
                ApplicantStatus from = a.getStatus();
                a.setStatus(to);
                recruitment.recordStatus(a, from, to, pending ? "Recommended in selection list #" + s.getId() : "Selection list #" + s.getId() + " not approved");
            }
        }
    }

    // ===================================================================== workflow callbacks

    @Override
    public String processCode() {
        return PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        SelectionList s = selection(wi.getEntityId());
        s.setStatus(SelectionStatus.APPROVED);
        s.setDecidedAt(Instant.now());
        for (SelectionItem it : items.findBySelectionIdOrderByRankNoAsc(s.getId())) {
            Applicant a = recruitment.applicant(it.getApplicantId());
            ApplicantStatus from = a.getStatus();
            if (it.isRecommended() && from == ApplicantStatus.RECOMMENDED) {
                a.setStatus(ApplicantStatus.SELECTED);
                recruitment.recordStatus(a, from, ApplicantStatus.SELECTED, "Selection approved (request #" + wi.getId() + ")");
            } else if (!it.isRecommended() && from == ApplicantStatus.SHORTLISTED) {
                a.setStatus(ApplicantStatus.NOT_SELECTED);
                recruitment.recordStatus(a, from, ApplicantStatus.NOT_SELECTED, "Not selected (request #" + wi.getId() + ")");
            }
        }
        audit.log(AuditAction.APPROVE, "SelectionList", s.getId(), "Selection list approved");
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        SelectionList s = selection(wi.getEntityId());
        s.setStatus(SelectionStatus.REJECTED);
        s.setDecidedAt(Instant.now());
        markRecommended(s, false);
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        SelectionList s = selection(wi.getEntityId());
        s.setStatus(SelectionStatus.RETURNED);
        markRecommended(s, false);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        SelectionList s = selection(wi.getEntityId());
        if (s.getStatus() == SelectionStatus.PENDING) return; // already handled by submit()
        validateDecisions(s, recruitment.vacancy(s.getVacancyId()));
        s.setStatus(SelectionStatus.PENDING);
        s.setSubmittedAt(Instant.now());
        markRecommended(s, true);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        SelectionList s = selection(wi.getEntityId());
        s.setStatus(SelectionStatus.CANCELLED);
        markRecommended(s, false);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        SelectionList s = selection(wi.getEntityId());
        Vacancy v = recruitment.vacancy(s.getVacancyId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Vacancy", v.getReferenceNo() + " — " + v.getTitle());
        m.put("Posts", v.getNumPosts());
        m.put("Weights (exam / interview)", v.getExamWeight() + "% / " + v.getInterviewWeight() + "%");
        m.put("Panel remarks", s.getRemarks());
        for (ItemView it : view(s).items()) {
            m.put("#" + it.rankNo() + " " + it.applicationNo() + " " + it.applicantName(),
                    "score " + it.totalScore() + (it.recommended() ? " — RECOMMENDED" : " — not recommended")
                            + (it.remarks() == null ? "" : " (" + it.remarks() + ")"));
        }
        m.put("Note", "Ranking is computed from recorded marks; the recommendation is the panel's decision.");
        return m;
    }

    // ===================================================================== appointment (FR-REC-008)

    public record AppointRequest(String employeeNo, LocalDate appointmentDate, String orgUnitCode, Long designationId,
                                 Long positionId, String status, String employmentType, String employeeCategory,
                                 String serviceCategory) {
    }

    public record AppointResult(Long applicantId, Long employeeId, String employeeNo) {
    }

    /** Creates the employee record from the selected applicant's data, without re-entry. */
    @Transactional
    public AppointResult appoint(Long applicantId, AppointRequest r) {
        Applicant a = recruitment.applicant(applicantId);
        if (a.getStatus() != ApplicantStatus.SELECTED)
            throw ApiException.rule("NOT_SELECTED", "Only applicants whose selection has been approved can be appointed");
        if (a.getEmployeeId() != null) throw ApiException.conflict("Applicant has already been appointed");
        Vacancy v = recruitment.vacancy(a.getVacancyId());
        RecruitmentService.require(r.employeeNo(), "Employee number");
        RecruitmentService.require(r.employmentType(), "Employment type");
        RecruitmentService.require(r.employeeCategory(), "Employee category");
        LocalDate date = r.appointmentDate() == null ? LocalDate.now() : r.appointmentDate();
        String unit = RecruitmentService.blank(r.orgUnitCode()) ? v.getOrgUnitCode() : r.orgUnitCode();
        Long des = r.designationId() == null ? v.getDesignationId() : r.designationId();
        String status = RecruitmentService.blank(r.status()) ? "PROBATION" : r.status();
        EmployeeService.EmployeeRequest req = new EmployeeService.EmployeeRequest(r.employeeNo().trim(), a.getTitle(),
                a.getFullName(), a.getNameWithInitials(), null, null, a.getNic(), a.getDateOfBirth(), a.getGender(),
                a.getCivilStatus(), a.getEmail(), null, a.getMobile(), a.getAddress(), a.getAddress(), status,
                r.employmentType(), r.employeeCategory(), RecruitmentService.blankToNull(r.serviceCategory()), date,
                null, null, null, null, null, null, null, null, false, unit, des, r.positionId());
        Employee e = employees.create(req);
        employees.event(e.getId(), "RECRUITMENT", date, "Recruited under vacancy " + v.getReferenceNo() + " (application "
                + a.getApplicationNo() + ")", v.getReferenceNo());
        ApplicantStatus from = a.getStatus();
        a.setEmployeeId(e.getId());
        a.setStatus(ApplicantStatus.APPOINTED);
        recruitment.recordStatus(a, from, ApplicantStatus.APPOINTED, "Appointed as employee " + e.getEmployeeNo() + " from " + date);
        audit.log(AuditAction.CREATE, "Applicant", a.getId(), "Applicant " + a.getApplicationNo() + " appointed as employee " + e.getEmployeeNo());
        recruitment.refreshFilled(v);
        return new AppointResult(a.getId(), e.getId(), e.getEmployeeNo());
    }

    // ===================================================================== helpers

    private SelectionView view(SelectionList s) {
        Vacancy v = recruitment.vacancy(s.getVacancyId());
        Map<Long, Applicant> apps = applicants.findByVacancyIdOrderByIdAsc(v.getId()).stream()
                .collect(Collectors.toMap(Applicant::getId, a -> a));
        List<ItemView> list = items.findBySelectionIdOrderByRankNoAsc(s.getId()).stream().map(i -> {
            Applicant a = apps.get(i.getApplicantId());
            return new ItemView(i.getId(), i.getApplicantId(), a == null ? null : a.getApplicationNo(),
                    a == null ? null : a.getNameWithInitials(), a == null ? null : a.getStatus().name(), i.getRankNo(),
                    i.getExamScore(), i.getInterviewScore(), i.getTotalScore(), i.isRecommended(), i.getRemarks());
        }).toList();
        return new SelectionView(s.getId(), v.getId(), v.getReferenceNo(), v.getNumPosts(),
                applicants.countByVacancyIdAndStatus(v.getId(), ApplicantStatus.APPOINTED), s.getStatus().name(),
                s.getRemarks(), s.getWorkflowInstanceId(), s.getSubmittedAt(), s.getDecidedAt(), list);
    }

    private SelectionList selection(Long id) {
        return selections.findById(id).orElseThrow(() -> ApiException.notFound("Selection list", id));
    }
}
