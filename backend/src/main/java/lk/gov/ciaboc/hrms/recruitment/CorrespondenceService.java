package lk.gov.ciaboc.hrms.recruitment;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.TemplateRenderer;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.establishment.Designation;
import lk.gov.ciaboc.hrms.establishment.DesignationRepository;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.establishment.OrgUnit;
import lk.gov.ciaboc.hrms.integration.IntegrationService;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.OutboundMessage;
import lk.gov.ciaboc.hrms.notification.OutboundMessageRepository;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.*;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Configurable correspondence (FR-REC-009, -010): templates with {{placeholders}} per language, letter generation with
 * an HTML preview for printing, e-mail dispatch through the shared outbound queue (retried with back-off, delivery
 * status recorded), and a correspondence log per applicant.
 */
@Service
@RequiredArgsConstructor
public class CorrespondenceService {
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{1,40}$");
    private static final Set<String> LANGS = Set.of("en", "si", "ta");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    private final RecruitmentLetterTemplateRepository templates;
    private final CorrespondenceLogRepository logs;
    private final ApplicantRepository applicants;
    private final RecruitmentExamRepository exams;
    private final InterviewRepository interviews;
    private final RecruitmentService recruitment;
    private final DesignationRepository designations;
    private final EstablishmentService establishment;
    private final EmployeeLookup employees;
    private final MasterDataService masterData;
    private final SettingsService settings;
    private final IntegrationService integrations;
    private final OutboundMessageRepository outbound;
    private final AuditService audit;

    // ===================================================================== templates

    public record TemplateRequest(String code, String language, String name, String category, String subject, String body,
                                  Boolean active) {
    }

    @Transactional(readOnly = true)
    public List<LetterTemplate> templates() {
        return templates.findAllByOrderByCategoryAscCodeAscLanguageAsc();
    }

    @Transactional
    public LetterTemplate saveTemplate(Long id, TemplateRequest r) {
        LetterTemplate t = id == null ? new LetterTemplate() : template(id);
        String code = r.code() == null ? null : r.code().trim().toUpperCase();
        if (code == null || !CODE.matcher(code).matches()) throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits, '-' or '_'");
        String lang = r.language() == null ? "en" : r.language().trim().toLowerCase();
        if (!LANGS.contains(lang)) throw ApiException.badRequest("Language must be en, si or ta");
        RecruitmentService.require(r.name(), "Name");
        RecruitmentService.require(r.subject(), "Subject");
        RecruitmentService.require(r.body(), "Body");
        LetterCategory cat = RecruitmentService.parse(LetterCategory.class, r.category(), "letter category");
        if (cat == null) throw ApiException.badRequest("Category is required");
        templates.findByCodeAndLanguage(code, lang).filter(o -> !o.getId().equals(t.getId()))
                .ifPresent(o -> { throw ApiException.conflict("A template " + code + " (" + lang + ") already exists"); });
        String before = t.getId() == null ? null : t.getSubject() + "\n" + t.getBody();
        t.setCode(code);
        t.setLanguage(lang);
        t.setName(r.name().trim());
        t.setCategory(cat);
        t.setSubject(r.subject().trim());
        t.setBody(r.body());
        t.setActive(r.active() == null || r.active());
        templates.save(t);
        audit.log(id == null ? AuditAction.CREATE : AuditAction.CONFIG_CHANGE, "LetterTemplate", t.getId(),
                "Letter template " + code + " (" + lang + ") saved", before, t.getSubject() + "\n" + t.getBody());
        return t;
    }

    private LetterTemplate template(Long id) {
        return templates.findById(id).orElseThrow(() -> ApiException.notFound("Letter template", id));
    }

    // ===================================================================== generation

    /** Optional context for a letter: which examination / interview it refers to, or the intended appointment date. */
    public record LetterRequest(Long templateId, Long examId, Long interviewId, LocalDate appointmentDate, Boolean sendEmail) {
    }

    public record LetterPreview(String subject, String html, String recipientEmail, boolean emailAvailable) {
    }

    public record LogView(Long id, Long applicantId, String applicationNo, String applicantName, String templateCode,
                          String category, String language, String subject, String channel, String recipient,
                          String status, String deliveryStatus, Instant generatedAt, String generatedBy) {
    }

    @Transactional(readOnly = true)
    public LetterPreview preview(Long applicantId, LetterRequest r) {
        Applicant a = recruitment.applicant(applicantId);
        LetterTemplate t = activeTemplate(r.templateId());
        Map<String, Object> vars = variables(a, r);
        return new LetterPreview(TemplateRenderer.render(t.getSubject(), vars), html(t, vars), a.getEmail(), emailEnabled() && a.getEmail() != null);
    }

    @Transactional
    public LogView generate(Long applicantId, LetterRequest r) {
        Applicant a = recruitment.applicant(applicantId);
        return toView(create(a, activeTemplate(r.templateId()), r), a);
    }

    public record BulkRequest(Long templateId, String applicantStatus, Long examId, Boolean sendEmail) {
    }

    public record BulkResult(int generated, int queued, List<LogView> letters) {
    }

    /** Generates the same letter for every applicant of a vacancy in a status (e.g. regret letters for NOT_SELECTED). */
    @Transactional
    public BulkResult generateBulk(Long vacancyId, BulkRequest r) {
        recruitment.vacancy(vacancyId);
        ApplicantStatus st = RecruitmentService.parse(ApplicantStatus.class, r.applicantStatus(), "applicant status");
        if (st == null) throw ApiException.badRequest("Applicant status is required");
        LetterTemplate t = activeTemplate(r.templateId());
        List<LogView> out = new ArrayList<>();
        int queued = 0;
        for (Applicant a : applicants.findByVacancyIdAndStatusOrderByIdAsc(vacancyId, st)) {
            LetterRequest lr = new LetterRequest(t.getId(), r.examId(), null, null, r.sendEmail());
            CorrespondenceLog l = create(a, t, lr);
            if ("QUEUED".equals(l.getStatus())) queued++;
            out.add(toView(l, a));
        }
        if (out.isEmpty()) throw ApiException.rule("NO_APPLICANTS", "No applicants of this vacancy are " + st);
        return new BulkResult(out.size(), queued, out);
    }

    private CorrespondenceLog create(Applicant a, LetterTemplate t, LetterRequest r) {
        Map<String, Object> vars = variables(a, r);
        String subject = TemplateRenderer.render(t.getSubject(), vars);
        String text = TemplateRenderer.render(t.getBody(), vars);
        boolean wantEmail = Boolean.TRUE.equals(r.sendEmail());
        CorrespondenceLog l = new CorrespondenceLog();
        l.setApplicantId(a.getId());
        l.setVacancyId(a.getVacancyId());
        l.setTemplateId(t.getId());
        l.setTemplateCode(t.getCode());
        l.setCategory(t.getCategory().name());
        l.setLanguage(t.getLanguage());
        l.setSubject(subject.length() > 300 ? subject.substring(0, 300) : subject);
        l.setBody(text);
        l.setGeneratedAt(Instant.now());
        l.setGeneratedBy(CurrentUser.username());
        if (wantEmail) {
            l.setChannel("EMAIL");
            l.setRecipient(a.getEmail());
            if (a.getEmail() == null) {
                l.setStatus("FAILED");
            } else if (!emailEnabled()) {
                l.setStatus("PREPARED"); // e-mail integration switched off: printed copy must be posted
            } else {
                OutboundMessage m = new OutboundMessage();
                m.setChannel("EMAIL");
                m.setRecipient(a.getEmail());
                m.setSubject(l.getSubject());
                m.setBody(letterhead() + "\n" + LocalDate.now().format(DATE) + "\n\n" + text);
                m.setSensitive(false);
                m.setCreatedAt(Instant.now());
                m.setNextAttemptAt(Instant.now());
                outbound.save(m);
                l.setOutboundMessageId(m.getId());
                l.setStatus("QUEUED");
            }
        } else {
            l.setChannel("PRINT");
            l.setStatus("PREPARED");
        }
        logs.save(l);
        audit.log(AuditAction.CREATE, "Correspondence", l.getId(), t.getCategory() + " letter for " + a.getApplicationNo()
                + " via " + l.getChannel() + " (" + l.getStatus() + ")");
        return l;
    }

    @Transactional(readOnly = true)
    public List<LogView> logForApplicant(Long applicantId) {
        Applicant a = recruitment.applicant(applicantId);
        return logs.findByApplicantIdOrderByIdDesc(applicantId).stream().map(l -> toView(l, a)).toList();
    }

    @Transactional(readOnly = true)
    public List<LogView> logForVacancy(Long vacancyId) {
        Map<Long, Applicant> apps = new HashMap<>();
        applicants.findByVacancyIdOrderByIdAsc(vacancyId).forEach(a -> apps.put(a.getId(), a));
        return logs.findByVacancyIdOrderByIdDesc(vacancyId).stream().map(l -> toView(l, apps.get(l.getApplicantId()))).toList();
    }

    public record LetterDocument(LogView log, String html) {
    }

    /** Re-renders a logged letter as printable HTML (the stored text is what was issued). */
    @Transactional(readOnly = true)
    public LetterDocument letter(Long logId) {
        CorrespondenceLog l = logs.findById(logId).orElseThrow(() -> ApiException.notFound("Letter", logId));
        Applicant a = recruitment.applicant(l.getApplicantId());
        audit.log(AuditAction.VIEW, "Correspondence", logId, "Viewed letter " + l.getTemplateCode() + " of " + a.getApplicationNo());
        return new LetterDocument(toView(l, a), wrap(l.getSubject(), l.getBody(), l.getGeneratedAt()));
    }

    // ===================================================================== rendering

    private Map<String, Object> variables(Applicant a, LetterRequest r) {
        Vacancy v = recruitment.vacancy(a.getVacancyId());
        Map<String, Object> m = new HashMap<>();
        m.put("orgName", settings.get("org.name", "CIABOC"));
        m.put("date", LocalDate.now().format(DATE));
        m.put("applicationNo", a.getApplicationNo());
        m.put("applicantTitle", titleLabel(a.getTitle()));
        m.put("applicantName", a.getFullName());
        m.put("nameWithInitials", a.getNameWithInitials());
        m.put("address", a.getAddress() == null ? "" : a.getAddress());
        m.put("vacancyRef", v.getReferenceNo());
        m.put("vacancyTitle", v.getTitle());
        m.put("designation", designations.findById(v.getDesignationId()).map(Designation::getName).orElse(""));
        m.put("orgUnit", establishment.orgUnit(v.getOrgUnitCode(), LocalDate.now()).map(OrgUnit::getName).orElse(v.getOrgUnitCode()));
        if (r.examId() != null) {
            RecruitmentExam e = exams.findById(r.examId()).filter(x -> x.getVacancyId().equals(v.getId()))
                    .orElseThrow(() -> ApiException.badRequest("The examination does not belong to this vacancy"));
            m.put("examTitle", e.getTitle());
            m.put("examType", e.getExamType().name().toLowerCase());
            m.put("examDate", e.getExamDate().format(DATE));
            m.put("examTime", e.getStartTime() == null ? "" : e.getStartTime().toString());
            m.put("examVenue", e.getVenue() == null ? "" : e.getVenue());
        }
        Optional<Interview> iv = r.interviewId() != null
                ? interviews.findById(r.interviewId()).filter(x -> x.getApplicantId().equals(a.getId()))
                : interviews.findByApplicantIdOrderByInterviewDateAsc(a.getId()).stream()
                .filter(x -> x.getStatus() == Interview.Status.SCHEDULED).reduce((x, y) -> y);
        if (r.interviewId() != null && iv.isEmpty()) throw ApiException.badRequest("The interview does not belong to this applicant");
        iv.ifPresent(i -> {
            m.put("interviewDate", i.getInterviewDate().format(DATE));
            m.put("interviewTime", i.getInterviewTime() == null ? "" : i.getInterviewTime().toString());
            m.put("interviewVenue", i.getVenue() == null ? "" : i.getVenue());
        });
        if (a.getEmployeeId() != null) {
            var e = employees.require(a.getEmployeeId());
            m.put("employeeNo", e.getEmployeeNo());
            if (e.getAppointmentDate() != null) m.put("appointmentDate", e.getAppointmentDate().format(DATE));
        }
        if (r.appointmentDate() != null) m.put("appointmentDate", r.appointmentDate().format(DATE));
        return m;
    }

    private String titleLabel(String code) {
        if (code == null) return "";
        return masterData.list(MasterDataCategory.TITLE.name(), false).stream().filter(i -> i.getCode().equals(code))
                .map(i -> i.getNameEn()).findFirst().orElse(code);
    }

    private String html(LetterTemplate t, Map<String, Object> vars) {
        return wrap(TemplateRenderer.render(t.getSubject(), vars), TemplateRenderer.render(t.getBody(), vars), Instant.now());
    }

    /**
     * Template text is treated as plain text: everything is HTML-escaped (no markup injection from templates or applicant
     * data) and paragraphs/line breaks are preserved for printing.
     */
    private String wrap(String subject, String body, Instant when) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"letter\"><div class=\"letter-head\">").append(esc(letterhead())).append("</div>");
        sb.append("<div class=\"letter-date\">").append(esc(LocalDate.ofInstant(when, java.time.ZoneId.of("Asia/Colombo")).format(DATE))).append("</div>");
        sb.append("<h3 class=\"letter-subject\">").append(esc(subject)).append("</h3>");
        for (String para : body.split("\\r?\\n\\s*\\r?\\n")) {
            sb.append("<p>").append(esc(para.strip()).replace("\n", "<br/>")).append("</p>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private String letterhead() {
        return settings.get("org.name", "Commission to Investigate Allegations of Bribery or Corruption");
    }

    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }

    private LetterTemplate activeTemplate(Long id) {
        if (id == null) throw ApiException.badRequest("Template is required");
        LetterTemplate t = template(id);
        if (!t.isActive()) throw ApiException.rule("INACTIVE", "Template " + t.getCode() + " is inactive");
        return t;
    }

    private boolean emailEnabled() {
        return integrations.isEnabled(IntegrationService.EMAIL);
    }

    private LogView toView(CorrespondenceLog l, Applicant a) {
        String delivery = null;
        if (l.getOutboundMessageId() != null)
            delivery = outbound.findById(l.getOutboundMessageId()).map(m -> m.getStatus().name()).orElse(null);
        return new LogView(l.getId(), l.getApplicantId(), a == null ? null : a.getApplicationNo(),
                a == null ? null : a.getNameWithInitials(), l.getTemplateCode(), l.getCategory(), l.getLanguage(),
                l.getSubject(), l.getChannel(), l.getRecipient(), l.getStatus(), delivery, l.getGeneratedAt(), l.getGeneratedBy());
    }
}
