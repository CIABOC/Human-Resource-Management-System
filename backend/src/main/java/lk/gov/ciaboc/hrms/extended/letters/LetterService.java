package lk.gov.ciaboc.hrms.extended.letters;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.TemplateRenderer;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.extended.ExtSupport;
import lk.gov.ciaboc.hrms.extended.letters.LetterEntities.IssuedDocument;
import lk.gov.ciaboc.hrms.extended.letters.LetterEntities.LetterRequest;
import lk.gov.ciaboc.hrms.extended.letters.LetterEntities.LetterTemplate;
import lk.gov.ciaboc.hrms.masterdata.MasterDataItem;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static lk.gov.ciaboc.hrms.extended.ExtSupport.*;

/**
 * Letters and certificates (FR-EXT-005): configurable trilingual templates, self-service requests (FR-EXT-003) with
 * optional LETTER_REQUEST approval, issue by HR with a unique unguessable verification code, printable view,
 * revocation and the issued-document register. Public verification exposes only minimal data.
 */
@Service
@RequiredArgsConstructor
public class LetterService implements WorkflowHandler {
    public static final String PROCESS = "LETTER_REQUEST";
    static final String ENTITY_REQUEST = "LetterRequest";
    static final String ENTITY_DOC = "IssuedDocument";
    private static final Set<String> LANGUAGES = Set.of("en", "si", "ta");
    private static final Set<LetterRequest.Status> OPEN = EnumSet.of(LetterRequest.Status.PENDING, LetterRequest.Status.RETURNED,
            LetterRequest.Status.APPROVED);
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");
    private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z2-7]{24}$");
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExtendedLetterTemplateRepository templates;
    private final ExtendedLetterRequestRepository requests;
    private final ExtendedIssuedDocumentRepository documents;
    private final EmployeeLookup lookup;
    private final ExtSupport support;
    private final MasterDataService masterData;
    private final SettingsService settings;
    private final WorkflowService workflow;
    private final NotificationService notifications;
    private final AuditService audit;

    // ===================================================================== DTOs

    public record TemplateInput(String code, String language, String name, LetterTemplate.Kind documentKind, String title,
                                String bodyHtml, Boolean selfService, Boolean active) {
    }

    public record TemplateOption(String code, String name, String documentKind, List<String> languages) {
    }

    public record RequestInput(String templateCode, String language, String purpose, String addressee) {
    }

    public record IssueInput(Long requestId, Long employeeId, String templateCode, String language, String purpose,
                             String addressee, Map<String, String> fields) {
    }

    public record PreviewResult(String title, String bodyHtml, List<String> missingFields, List<String> placeholders) {
    }

    public record RequestView(Long id, EmployeeLookup.EmployeeBrief employee, String templateCode, String documentName,
                              String language, String purpose, String addressee, String status, Long workflowInstanceId,
                              Long issuedDocumentId, Instant createdAt, List<String> requiredFields) {
    }

    public record DocumentView(Long id, String documentNo, EmployeeLookup.EmployeeBrief employee, String templateCode,
                               String documentTypeName, String documentKind, String language, String title,
                               Instant issuedAt, String issuedBy, String status, Instant revokedAt, String revokedBy,
                               String revokeReason, String verificationCode, Long requestId) {
    }

    public record MyLetters(List<RequestView> requests, List<DocumentView> documents, List<TemplateOption> available,
                            boolean approvalRequired) {
    }

    /** The only data ever returned by public verification. */
    public record Verification(boolean valid, String status, String documentType, String issueDate, String holderName) {
    }

    // ===================================================================== templates

    @Transactional(readOnly = true)
    public List<LetterTemplate> templates() {
        return templates.findAllByOrderByCodeAscLanguageAsc();
    }

    @Transactional
    public LetterTemplate saveTemplate(Long id, TemplateInput in) {
        LetterTemplate t;
        if (id == null) {
            if (in.code() == null || !in.code().matches("^[A-Z0-9_]{1,40}$"))
                throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits or underscores");
            if (in.language() == null || !LANGUAGES.contains(in.language())) throw ApiException.badRequest("Language must be en, si or ta");
            if (templates.findByCodeAndLanguage(in.code(), in.language()).isPresent())
                throw ApiException.conflict("Template " + in.code() + " already has a '" + in.language() + "' version");
            t = new LetterTemplate();
            t.setCode(in.code());
            t.setLanguage(in.language());
        } else {
            t = templates.findById(id).orElseThrow(() -> ApiException.notFound("Letter template", id));
        }
        require(in.name(), "Name");
        require(in.title(), "Document title");
        require(in.bodyHtml(), "Template body");
        maxLength(in.name(), 200, "Name");
        maxLength(in.title(), 300, "Document title");
        maxLength(in.bodyHtml(), 100_000, "Template body");
        if (in.documentKind() == null) throw ApiException.badRequest("Document kind is required");
        if (DocumentHtml.unsafe(in.bodyHtml()) || DocumentHtml.unsafe(in.title()))
            throw ApiException.rule("UNSAFE_TEMPLATE", "Templates may contain formatting only (no scripts, frames, forms, event handlers or links to code)");
        String before = id == null ? null : t.getTitle() + "\n" + t.getBodyHtml();
        t.setName(in.name().trim());
        t.setTitle(in.title().trim());
        t.setBodyHtml(in.bodyHtml());
        t.setDocumentKind(in.documentKind());
        t.setSelfService(in.selfService() == null || in.selfService());
        t.setActive(in.active() == null || in.active());
        templates.save(t);
        audit.log(id == null ? AuditAction.CREATE : AuditAction.UPDATE, "LetterTemplate", t.getId(),
                "Letter template " + t.getCode() + "/" + t.getLanguage() + " saved", before, t.getTitle() + "\n" + t.getBodyHtml());
        return t;
    }

    /** Renders a template for an employee without issuing anything (HR preview). */
    @Transactional(readOnly = true)
    public PreviewResult preview(IssueInput in) {
        LetterTemplate t = activeTemplate(in.templateCode(), in.language());
        Employee e = lookup.requireAccess(in.employeeId());
        Map<String, Object> vars = vars(e, t.getLanguage(), in.purpose(), in.addressee(), in.fields(), "(document number)", LocalDate.now());
        return new PreviewResult(TemplateRenderer.render(t.getTitle(), vars), TemplateRenderer.render(t.getBodyHtml(), vars),
                missing(t, in.fields()), placeholders(t));
    }

    // ===================================================================== self-service (FR-EXT-003)

    @Transactional(readOnly = true)
    public MyLetters myLetters() {
        Long me = requireEmployee();
        EmployeeLookup.EmployeeBrief b = lookup.brief(me);
        List<RequestView> reqs = requests.findByEmployeeIdOrderByIdDesc(me).stream().map(r -> requestView(r, b)).toList();
        List<DocumentView> docs = documents.findByEmployeeIdOrderByIdDesc(me).stream().map(d -> documentView(d, b)).toList();
        return new MyLetters(reqs, docs, selfServiceOptions(), settings.getBool("ext.letters.approval-required", true));
    }

    @Transactional(readOnly = true)
    public List<TemplateOption> selfServiceOptions() {
        return options(templates.findAllByOrderByCodeAscLanguageAsc().stream().filter(t -> t.isActive() && t.isSelfService()).toList());
    }

    @Transactional(readOnly = true)
    public List<TemplateOption> allOptions() {
        return options(templates.findAllByOrderByCodeAscLanguageAsc().stream().filter(LetterTemplate::isActive).toList());
    }

    private static List<TemplateOption> options(List<LetterTemplate> ts) {
        Map<String, List<LetterTemplate>> byCode = ts.stream().collect(Collectors.groupingBy(LetterTemplate::getCode, TreeMap::new, Collectors.toList()));
        return byCode.values().stream().map(list -> {
            LetterTemplate en = list.stream().filter(x -> "en".equals(x.getLanguage())).findFirst().orElse(list.get(0));
            return new TemplateOption(en.getCode(), en.getName(), en.getDocumentKind().name(),
                    list.stream().map(LetterTemplate::getLanguage).sorted(Comparator.comparing(l -> List.of("en", "si", "ta").indexOf(l))).toList());
        }).toList();
    }

    @Transactional
    public RequestView request(RequestInput in) {
        Long me = requireEmployee();
        Employee e = lookup.require(me);
        require(in.templateCode(), "Document");
        String lang = blank(in.language()) ? "en" : in.language();
        LetterTemplate t = activeTemplate(in.templateCode(), lang);
        if (!t.isSelfService()) throw ApiException.rule("NOT_SELF_SERVICE", "This document cannot be requested through self-service");
        require(in.purpose(), "Purpose");
        maxLength(in.purpose(), 500, "Purpose");
        maxLength(in.addressee(), 300, "Addressee");
        if (requests.existsByEmployeeIdAndTemplateCodeAndStatusIn(me, t.getCode(), OPEN))
            throw ApiException.rule("OPEN_REQUEST", "You already have an open request for this document");
        LetterRequest r = new LetterRequest();
        r.setEmployeeId(me);
        r.setTemplateCode(t.getCode());
        r.setLanguage(lang);
        r.setPurpose(in.purpose().trim());
        r.setAddressee(trimToNull(in.addressee()));
        boolean approval = settings.getBool("ext.letters.approval-required", true);
        r.setStatus(approval ? LetterRequest.Status.PENDING : LetterRequest.Status.APPROVED);
        requests.save(r);
        if (approval) {
            WorkflowInstance wi = workflow.start(PROCESS, ENTITY_REQUEST, r.getId(), t.getName() + " for " + e.getEmployeeNo() + " " + e.getNameWithInitials(),
                    "/self/letters", me);
            r.setWorkflowInstanceId(wi.getId());
        } else {
            Map<String, Object> vars = new HashMap<>();
            vars.put("employeeName", e.getNameWithInitials());
            vars.put("document", t.getName());
            vars.put("purpose", r.getPurpose());
            notifications.notifyRole("HR_ADMIN", "EXT_LETTER_REQUESTED", vars, "/extended/letters");
        }
        audit.log(AuditAction.CREATE, ENTITY_REQUEST, r.getId(), "Requested " + t.getCode() + " (" + lang + ")");
        return requestView(r, lookup.brief(me));
    }

    /** The employee withdraws their own request before it is issued. */
    @Transactional
    public RequestView cancelMine(Long id) {
        Long me = requireEmployee();
        LetterRequest r = requests.findById(id).filter(x -> x.getEmployeeId().equals(me))
                .orElseThrow(() -> ApiException.notFound("Letter request", id));
        if (!OPEN.contains(r.getStatus())) throw ApiException.rule("NOT_OPEN", "Only open requests can be withdrawn");
        if (r.getStatus() != LetterRequest.Status.APPROVED && r.getWorkflowInstanceId() != null) {
            workflow.cancel(r.getWorkflowInstanceId(), "Withdrawn by the employee"); // calls onCancelled
        } else {
            r.setStatus(LetterRequest.Status.CANCELLED);
            audit.log(AuditAction.CANCEL, ENTITY_REQUEST, r.getId(), "Letter request withdrawn");
        }
        return requestView(r, lookup.brief(me));
    }

    // ===================================================================== HR

    @Transactional(readOnly = true)
    public List<RequestView> requests(LetterRequest.Status status) {
        Set<Long> visible = support.visibleIds();
        List<LetterRequest> rows = requests.findAllByOrderByIdDesc().stream()
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> visible(visible, r.getEmployeeId())).toList();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = support.briefs(rows.stream().map(LetterRequest::getEmployeeId).toList());
        return rows.stream().map(r -> requestView(r, briefs.get(r.getEmployeeId()))).toList();
    }

    @Transactional
    public DocumentView issue(IssueInput in) {
        LetterRequest r = null;
        Long employeeId;
        String code;
        String lang;
        String purpose;
        String addressee;
        if (in.requestId() != null) {
            r = requests.findById(in.requestId()).orElseThrow(() -> ApiException.notFound("Letter request", in.requestId()));
            if (r.getStatus() != LetterRequest.Status.APPROVED)
                throw ApiException.rule("NOT_APPROVED", "Only approved requests can be issued (current status: " + r.getStatus() + ")");
            employeeId = r.getEmployeeId();
            code = r.getTemplateCode();
            lang = r.getLanguage();
            purpose = r.getPurpose();
            addressee = r.getAddressee();
        } else {
            if (in.employeeId() == null) throw ApiException.badRequest("Employee is required");
            employeeId = in.employeeId();
            code = in.templateCode();
            lang = blank(in.language()) ? "en" : in.language();
            purpose = trimToNull(in.purpose());
            addressee = trimToNull(in.addressee());
            maxLength(purpose, 500, "Purpose");
            maxLength(addressee, 300, "Addressee");
        }
        Employee e = lookup.requireAccess(employeeId);
        UserPrincipal me = CurrentUser.get();
        if (employeeId.equals(me.getEmployeeId()))
            throw ApiException.rule("SEGREGATION_OF_DUTIES", "You cannot issue a letter or certificate to yourself");
        LetterTemplate t = activeTemplate(code, lang);
        Map<String, String> fields = cleanFields(in.fields());
        List<String> missing = missing(t, fields);
        if (!missing.isEmpty())
            throw ApiException.rule("MISSING_FIELDS", "Please provide: " + String.join(", ", missing));

        LocalDate today = LocalDate.now(DocumentHtml.ZONE);
        String docNo = settings.get("ext.letters.document-prefix", "CIABOC/HR") + "/" + today.getYear() + "/"
                + String.format("%06d", documents.nextNumber());
        Map<String, Object> vars = vars(e, lang, purpose, addressee, fields, docNo, today);
        IssuedDocument d = new IssuedDocument();
        d.setDocumentNo(docNo);
        d.setVerificationCode(newVerificationCode());
        d.setEmployeeId(e.getId());
        d.setHolderName(e.getNameWithInitials());
        d.setTemplateCode(t.getCode());
        d.setLanguage(lang);
        d.setDocumentKind(t.getDocumentKind().name());
        d.setDocumentTypeName(t.getName());
        d.setTitle(TemplateRenderer.render(t.getTitle(), vars));
        d.setBodyHtml(TemplateRenderer.render(t.getBodyHtml(), vars));
        d.setContentSha256(sha256(d.getTitle() + "\n" + d.getBodyHtml()));
        d.setRequestId(r == null ? null : r.getId());
        d.setIssuedAt(Instant.now());
        d.setIssuedBy(me.getUsername());
        d.setStatus(IssuedDocument.Status.VALID);
        documents.save(d);
        if (r != null) {
            r.setStatus(LetterRequest.Status.ISSUED);
            r.setIssuedDocumentId(d.getId());
        }
        audit.log(AuditAction.CREATE, ENTITY_DOC, d.getId(), "Issued " + t.getCode() + " " + docNo + " to " + e.getEmployeeNo());
        Map<String, Object> nv = new HashMap<>();
        nv.put("document", t.getName());
        nv.put("documentNo", docNo);
        notifications.notifyEmployee(e.getId(), "EXT_LETTER_ISSUED", nv, "/self/letters");
        return documentView(d, lookup.brief(e.getId()));
    }

    @Transactional(readOnly = true)
    public List<DocumentView> register(IssuedDocument.Status status) {
        Set<Long> visible = support.visibleIds();
        List<IssuedDocument> rows = documents.findAllByOrderByIdDesc().stream()
                .filter(d -> status == null || d.getStatus() == status)
                .filter(d -> visible(visible, d.getEmployeeId())).toList();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = support.briefs(rows.stream().map(IssuedDocument::getEmployeeId).toList());
        return rows.stream().map(d -> documentView(d, briefs.get(d.getEmployeeId()))).toList();
    }

    @Transactional
    public DocumentView revoke(Long id, String reason) {
        IssuedDocument d = document(id);
        lookup.requireAccess(d.getEmployeeId());
        if (d.getStatus() == IssuedDocument.Status.REVOKED) throw ApiException.rule("ALREADY_REVOKED", "This document is already revoked");
        require(reason, "Reason");
        maxLength(reason, 500, "Reason");
        d.setStatus(IssuedDocument.Status.REVOKED);
        d.setRevokedAt(Instant.now());
        d.setRevokedBy(CurrentUser.username());
        d.setRevokeReason(reason.trim());
        audit.log(AuditAction.DEACTIVATE, ENTITY_DOC, d.getId(), "Revoked " + d.getDocumentNo() + ": " + reason.trim());
        return documentView(d, lookup.brief(d.getEmployeeId()));
    }

    /** Printable HTML; the holder may print their own documents, HR any document within scope. */
    @Transactional(readOnly = true)
    public String printable(Long id) {
        IssuedDocument d = document(id);
        authorizeView(d);
        audit.log(AuditAction.VIEW, ENTITY_DOC, d.getId(), "Viewed/printed " + d.getDocumentNo());
        return DocumentHtml.printable(d, settings.get("org.name", "Commission to Investigate Allegations of Bribery or Corruption"),
                settings.get("ext.letters.signatory", "Director (Administration)"), verifyUrl(d.getVerificationCode()));
    }

    @Transactional(readOnly = true)
    public DocumentView documentDetail(Long id) {
        IssuedDocument d = document(id);
        authorizeView(d);
        return documentView(d, lookup.brief(d.getEmployeeId()));
    }

    private void authorizeView(IssuedDocument d) {
        UserPrincipal me = CurrentUser.get();
        if (d.getEmployeeId().equals(me.getEmployeeId()) && me.has(Permission.SELF_SERVICE)) return;
        if (!me.has(Permission.EXT_VIEW) && !me.has(Permission.EXT_MANAGE)) throw ApiException.notFound("Document", d.getId());
        lookup.requireAccess(d.getEmployeeId());
    }

    // ===================================================================== public verification

    /** Returns minimal confirmation data, or empty if the code is malformed or unknown. */
    @Transactional(readOnly = true)
    public Optional<Verification> verify(String rawCode, String clientIp) {
        String code = normalizeCode(rawCode);
        if (code == null) return Optional.empty();
        Optional<IssuedDocument> found = documents.findByVerificationCode(code);
        audit.logAs(null, "public", AuditAction.VIEW, ENTITY_DOC, found.map(IssuedDocument::getId).orElse(null),
                "Public verification " + (found.isPresent() ? "match" : "no match") + " from " + clientIp);
        return found.map(d -> new Verification(d.getStatus() == IssuedDocument.Status.VALID, d.getStatus().name(),
                d.getDocumentTypeName(), d.getIssuedAt().atZone(DocumentHtml.ZONE).toLocalDate().toString(), d.getHolderName()));
    }

    String orgName() {
        return settings.get("org.name", "Commission to Investigate Allegations of Bribery or Corruption");
    }

    /** Accepts codes with or without separators, in any case. */
    static String normalizeCode(String raw) {
        if (raw == null || raw.length() > 64) return null;
        String c = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!CODE_FORMAT.matcher(c).matches()) return null;
        return group(c);
    }

    private String newVerificationCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] bytes = new byte[15]; // 120 bits of entropy
            RANDOM.nextBytes(bytes);
            StringBuilder sb = new StringBuilder();
            int buffer = 0;
            int bits = 0;
            for (byte b : bytes) {
                buffer = (buffer << 8) | (b & 0xff);
                bits += 8;
                while (bits >= 5) {
                    sb.append(BASE32[(buffer >> (bits - 5)) & 31]);
                    bits -= 5;
                }
            }
            String code = group(sb.toString());
            if (!documents.existsByVerificationCode(code)) return code;
        }
        throw new IllegalStateException("Could not generate a unique verification code");
    }

    private static String group(String c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length(); i += 4) {
            if (i > 0) sb.append('-');
            sb.append(c, i, Math.min(c.length(), i + 4));
        }
        return sb.toString();
    }

    private String verifyUrl(String code) {
        String base = settings.get("ext.verify.base-url", "");
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/api/public/verify/" + code + "/page";
    }

    // ===================================================================== workflow (LETTER_REQUEST)

    @Override
    public String processCode() {
        return PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        requestOf(wi).setStatus(LetterRequest.Status.APPROVED); // HR then issues it from the letters screen
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        requestOf(wi).setStatus(LetterRequest.Status.REJECTED);
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        requestOf(wi).setStatus(LetterRequest.Status.RETURNED);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        requestOf(wi).setStatus(LetterRequest.Status.PENDING);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        requestOf(wi).setStatus(LetterRequest.Status.CANCELLED);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        LetterRequest r = requestOf(wi);
        Employee e = lookup.require(r.getEmployeeId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Employee", e.getEmployeeNo() + " — " + e.getNameWithInitials());
        m.put("Designation", support.designationName(e.getDesignationId()));
        m.put("Document", templateName(r.getTemplateCode(), r.getLanguage()));
        m.put("Language", r.getLanguage());
        m.put("Purpose", r.getPurpose());
        if (r.getAddressee() != null) m.put("Addressee", r.getAddressee());
        return m;
    }

    private LetterRequest requestOf(WorkflowInstance wi) {
        return requests.findById(wi.getEntityId()).orElseThrow(() -> ApiException.notFound("Letter request", wi.getEntityId()));
    }

    // ===================================================================== rendering helpers

    private Map<String, Object> vars(Employee e, String lang, String purpose, String addressee, Map<String, String> fields,
                                     String docNo, LocalDate date) {
        Map<String, Object> v = new HashMap<>();
        v.put("employee.title", html(mdLabel("TITLE", e.getTitle(), lang)));
        v.put("employee.fullName", html(e.getFullName()));
        v.put("employee.nameWithInitials", html(e.getNameWithInitials()));
        v.put("employee.employeeNo", html(e.getEmployeeNo()));
        v.put("employee.nic", html(e.getNic()));
        v.put("employee.designation", html(support.designationName(e.getDesignationId())));
        v.put("employee.orgUnit", html(support.unitName(e.getOrgUnitCode())));
        v.put("employee.appointmentDate", html(DocumentHtml.formatDate(e.getAppointmentDate(), lang)));
        v.put("employee.confirmationDate", html(DocumentHtml.formatDate(e.getConfirmationDate(), lang)));
        v.put("employee.retirementDate", html(DocumentHtml.formatDate(e.getRetirementDate(), lang)));
        v.put("employee.separationDate", html(DocumentHtml.formatDate(e.getSeparationDate(), lang)));
        v.put("employee.employmentType", html(mdLabel("EMPLOYMENT_TYPE", e.getEmploymentType(), lang).toLowerCase(Locale.ROOT)));
        v.put("org.name", html(orgName()));
        v.put("document.no", html(docNo));
        v.put("document.date", html(DocumentHtml.formatDate(date, lang)));
        v.put("request.purpose", html(purpose));
        v.put("request.addressee", html(addressee));
        v.put("signatory", html(settings.get("ext.letters.signatory", "")));
        if (fields != null) fields.forEach((k, val) -> v.put("field." + k, html(val)));
        return v;
    }

    private String mdLabel(String category, String code, String lang) {
        if (code == null) return "";
        for (MasterDataItem i : masterData.list(category, false)) {
            if (code.equals(i.getCode())) {
                if ("si".equals(lang) && i.getNameSi() != null) return i.getNameSi();
                if ("ta".equals(lang) && i.getNameTa() != null) return i.getNameTa();
                return i.getNameEn();
            }
        }
        return code;
    }

    /** {{field.*}} placeholders must be supplied by HR at issue (e.g. salary figures held outside this module). */
    private static List<String> missing(LetterTemplate t, Map<String, String> fields) {
        return placeholders(t).stream().filter(p -> p.startsWith("field."))
                .filter(p -> fields == null || blank(fields.get(p.substring(6))))
                .map(p -> p.substring(6)).distinct().toList();
    }

    private static List<String> placeholders(LetterTemplate t) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(t.getTitle() + "\n" + t.getBodyHtml());
        while (m.find()) out.add(m.group(1));
        return new ArrayList<>(out);
    }

    private static Map<String, String> cleanFields(Map<String, String> in) {
        Map<String, String> out = new HashMap<>();
        if (in == null) return out;
        in.forEach((k, v) -> {
            if (k == null || !k.matches("^[a-zA-Z0-9_]{1,40}$")) throw ApiException.badRequest("Invalid field name: " + k);
            if (v != null && v.length() > 2000) throw ApiException.badRequest("Field " + k + " is too long");
            if (v != null && !v.isBlank()) out.put(k, v.trim());
        });
        return out;
    }

    private LetterTemplate activeTemplate(String code, String lang) {
        if (blank(code)) throw ApiException.badRequest("Document template is required");
        String l = blank(lang) ? "en" : lang;
        if (!LANGUAGES.contains(l)) throw ApiException.badRequest("Language must be en, si or ta");
        return templates.findByCodeAndLanguage(code, l).filter(LetterTemplate::isActive)
                .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "No active '" + l + "' version of template " + code));
    }

    private String templateName(String code, String lang) {
        return templates.findByCodeAndLanguage(code, lang).map(LetterTemplate::getName)
                .or(() -> templates.findByCodeAndLanguage(code, "en").map(LetterTemplate::getName)).orElse(code);
    }

    private IssuedDocument document(Long id) {
        return documents.findById(id).orElseThrow(() -> ApiException.notFound("Document", id));
    }

    private static Long requireEmployee() {
        Long me = CurrentUser.employeeId();
        if (me == null) throw ApiException.rule("NO_EMPLOYEE", "Your user account is not linked to an employee record");
        return me;
    }

    private RequestView requestView(LetterRequest r, EmployeeLookup.EmployeeBrief b) {
        List<String> required = templates.findByCodeAndLanguage(r.getTemplateCode(), r.getLanguage())
                .map(t -> missing(t, null)).orElse(List.of());
        return new RequestView(r.getId(), b, r.getTemplateCode(), templateName(r.getTemplateCode(), r.getLanguage()), r.getLanguage(),
                r.getPurpose(), r.getAddressee(), r.getStatus().name(), r.getWorkflowInstanceId(), r.getIssuedDocumentId(),
                r.getCreatedAt(), required);
    }

    private static DocumentView documentView(IssuedDocument d, EmployeeLookup.EmployeeBrief b) {
        return new DocumentView(d.getId(), d.getDocumentNo(), b, d.getTemplateCode(), d.getDocumentTypeName(), d.getDocumentKind(),
                d.getLanguage(), d.getTitle(), d.getIssuedAt(), d.getIssuedBy(), d.getStatus().name(), d.getRevokedAt(),
                d.getRevokedBy(), d.getRevokeReason(), d.getVerificationCode(), d.getRequestId());
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
