package lk.gov.ciaboc.hrms.legal;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.files.StoredFile;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LegalService {
    private final LegalDocumentRepository documents;
    private final LegalSectionRepository sections;
    private final AmendmentRepository amendments;
    private final FileStorageService files;
    private final MasterDataService masterData;
    private final AuditService audit;

    public record DocumentRequest(LegalDocument.DocType docType, String docNumber, String title, String issuingAuthority,
                                  LocalDate publicationDate, LocalDate effectiveDate, String description) {
    }

    public record SectionRequest(String parentKey, LegalSection.Level level, String number, String heading, String content,
                                 Integer sortOrder, LocalDate effectiveFrom, Set<String> tags, Set<Long> designationIds) {
    }

    public record AmendmentRequest(Long sectionId, String parentKey, Long amendingDocumentId, Amendment.Type amendmentType,
                                   String description, LocalDate effectiveDate, String number, String heading,
                                   String content) {
    }

    // ------------------------------------------------------------ documents

    @Transactional(readOnly = true)
    public List<LegalDocument> list() {
        return documents.findAllByOrderByDocTypeAscPublicationDateDesc();
    }

    public LegalDocument document(Long id) {
        return documents.findById(id).orElseThrow(() -> ApiException.notFound("Legal document", id));
    }

    @Transactional
    public LegalDocument create(DocumentRequest r, MultipartFile file) {
        LegalDocument d = new LegalDocument();
        apply(d, r);
        attach(d, file);
        documents.save(d);
        d.setSeriesId(d.getId());
        audit.log(AuditAction.CREATE, "LegalDocument", d.getId(), "Legal document added: " + d.getTitle(), null, r);
        return d;
    }

    @Transactional
    public LegalDocument update(Long id, DocumentRequest r) {
        LegalDocument d = document(id);
        DocumentRequest before = new DocumentRequest(d.getDocType(), d.getDocNumber(), d.getTitle(), d.getIssuingAuthority(),
                d.getPublicationDate(), d.getEffectiveDate(), d.getDescription());
        apply(d, r);
        if (d.getVerificationStatus() == LegalDocument.Verification.VERIFIED) {
            d.setVerificationStatus(LegalDocument.Verification.DRAFT); // edited metadata must be re-verified
            d.setVerifiedBy(null);
            d.setVerifiedAt(null);
        }
        audit.log(AuditAction.UPDATE, "LegalDocument", id, "Legal document updated: " + d.getTitle(), before, r);
        return d;
    }

    /** Uploads a new version; the previous version is marked superseded and kept. */
    @Transactional
    public LegalDocument newVersion(Long id, DocumentRequest r, MultipartFile file) {
        LegalDocument prev = document(id);
        if (prev.getStatus() == LegalDocument.Status.SUPERSEDED) throw ApiException.rule("SUPERSEDED", "Upload against the latest version");
        LegalDocument d = new LegalDocument();
        apply(d, r);
        attach(d, file);
        d.setSeriesId(prev.getSeriesId());
        d.setVersionNo(prev.getVersionNo() + 1);
        d.setPreviousVersionId(prev.getId());
        documents.save(d);
        prev.setStatus(LegalDocument.Status.SUPERSEDED);
        audit.log(AuditAction.UPLOAD, "LegalDocument", d.getId(), "New version " + d.getVersionNo() + " of " + d.getTitle());
        return d;
    }

    @Transactional
    public LegalDocument setStatus(Long id, LegalDocument.Status status) {
        LegalDocument d = document(id);
        LegalDocument.Status old = d.getStatus();
        d.setStatus(status);
        audit.log(AuditAction.UPDATE, "LegalDocument", id, "Status " + old + " → " + status);
        return d;
    }

    @Transactional
    public LegalDocument verify(Long id) {
        LegalDocument d = document(id);
        d.setVerificationStatus(LegalDocument.Verification.VERIFIED);
        d.setVerifiedBy(CurrentUser.username());
        d.setVerifiedAt(Instant.now());
        audit.log(AuditAction.APPROVE, "LegalDocument", id, "Verified against official document: " + d.getTitle());
        return d;
    }

    @Transactional(readOnly = true)
    public List<LegalDocument> versions(Long id) {
        return documents.findBySeriesIdOrderByVersionNoDesc(document(id).getSeriesId());
    }

    private void apply(LegalDocument d, DocumentRequest r) {
        if (r.docType() == null) throw ApiException.badRequest("Document type is required");
        if (r.docNumber() == null || r.docNumber().isBlank()) throw ApiException.badRequest("Document number is required");
        if (r.title() == null || r.title().isBlank()) throw ApiException.badRequest("Title is required");
        d.setDocType(r.docType());
        d.setDocNumber(r.docNumber().trim());
        d.setTitle(r.title().trim());
        d.setIssuingAuthority(r.issuingAuthority());
        d.setPublicationDate(r.publicationDate());
        d.setEffectiveDate(r.effectiveDate());
        d.setDescription(r.description());
    }

    private void attach(LegalDocument d, MultipartFile file) {
        if (file == null || file.isEmpty()) return;
        StoredFile f = files.store(file, "LEGAL");
        d.setFileId(f.getId());
        d.setFileName(f.getOriginalName());
    }

    // ------------------------------------------------------------ provisions

    @Transactional(readOnly = true)
    public List<LegalSection> sections(Long documentId, LocalDate asOf) {
        return sections.effectiveOn(documentId, asOf == null ? LocalDate.now() : asOf);
    }

    @Transactional(readOnly = true)
    public List<LegalSection> sectionVersions(Long sectionId) {
        return sections.findBySectionKeyOrderByVersionNoDesc(section(sectionId).getSectionKey());
    }

    @Transactional(readOnly = true)
    public List<LegalSection> forDesignation(Long designationId) {
        return sections.currentForDesignation(designationId, LocalDate.now());
    }

    @Transactional(readOnly = true)
    public List<LegalSection> forTag(String tag) {
        return sections.currentForTag(tag, LocalDate.now());
    }

    @Transactional
    public LegalSection addSection(Long documentId, SectionRequest r) {
        LegalDocument doc = document(documentId);
        LegalSection s = new LegalSection();
        s.setDocumentId(doc.getId());
        s.setSectionKey(UUID.randomUUID().toString());
        applySection(s, r);
        s.setEffectiveFrom(r.effectiveFrom() != null ? r.effectiveFrom()
                : doc.getEffectiveDate() != null ? doc.getEffectiveDate() : LocalDate.now());
        sections.save(s);
        audit.log(AuditAction.CREATE, "LegalSection", s.getId(), "Provision " + s.getNumber() + " added to " + doc.getTitle(), null, r);
        return s;
    }

    /**
     * Draft provisions may be edited in place. Verified provisions only change through amendments, except their
     * classification (tags and designation links) which is metadata.
     */
    @Transactional
    public LegalSection updateSection(Long id, SectionRequest r) {
        LegalSection s = section(id);
        if (s.getVerificationStatus() == LegalDocument.Verification.VERIFIED) {
            s.setTags(validTags(r.tags()));
            s.setDesignationIds(r.designationIds() == null ? new HashSet<>() : new HashSet<>(r.designationIds()));
            audit.log(AuditAction.UPDATE, "LegalSection", id, "Classification updated for provision " + s.getNumber());
            return s;
        }
        String before = s.getContent();
        applySection(s, r);
        if (r.effectiveFrom() != null) s.setEffectiveFrom(r.effectiveFrom());
        audit.log(AuditAction.UPDATE, "LegalSection", id, "Provision " + s.getNumber() + " edited (draft)", before, s.getContent());
        return s;
    }

    @Transactional
    public LegalSection verifySection(Long id) {
        LegalSection s = section(id);
        s.setVerificationStatus(LegalDocument.Verification.VERIFIED);
        audit.log(AuditAction.APPROVE, "LegalSection", id, "Provision " + s.getNumber() + " verified against official document");
        return s;
    }

    private void applySection(LegalSection s, SectionRequest r) {
        if (r.level() == null) throw ApiException.badRequest("Level is required");
        if (r.number() == null || r.number().isBlank()) throw ApiException.badRequest("Number is required");
        s.setParentKey(r.parentKey() == null || r.parentKey().isBlank() ? null : r.parentKey());
        s.setLevel(r.level());
        s.setNumber(r.number().trim());
        s.setHeading(r.heading());
        s.setContent(r.content());
        s.setSortOrder(r.sortOrder() == null ? 0 : r.sortOrder());
        s.setTags(validTags(r.tags()));
        s.setDesignationIds(r.designationIds() == null ? new HashSet<>() : new HashSet<>(r.designationIds()));
    }

    private Set<String> validTags(Set<String> tags) {
        Set<String> out = new HashSet<>();
        if (tags != null) for (String t : tags) {
            masterData.requireActive(MasterDataCategory.LEGAL_TOPIC, t, "Topic");
            out.add(t);
        }
        return out;
    }

    private LegalSection section(Long id) {
        return sections.findById(id).orElseThrow(() -> ApiException.notFound("Provision", id));
    }

    // ------------------------------------------------------------ amendments

    @Transactional(readOnly = true)
    public List<Amendment> amendments(Long documentId) {
        return amendments.findByOriginalDocumentIdOrderByEffectiveDateDesc(documentId);
    }

    @Transactional
    public Amendment amend(Long documentId, AmendmentRequest r) {
        LegalDocument doc = document(documentId);
        if (r.amendmentType() == null) throw ApiException.badRequest("Amendment type is required");
        if (r.description() == null || r.description().isBlank()) throw ApiException.badRequest("Description is required");
        if (r.effectiveDate() == null) throw ApiException.badRequest("Effective date is required");
        if (r.amendingDocumentId() != null) document(r.amendingDocumentId());
        Amendment a = new Amendment();
        a.setOriginalDocumentId(doc.getId());
        a.setAmendingDocumentId(r.amendingDocumentId());
        a.setAmendmentType(r.amendmentType());
        a.setDescription(r.description().trim());
        a.setEffectiveDate(r.effectiveDate());

        if (r.amendmentType() == Amendment.Type.INSERTED) {
            if (r.number() == null || r.number().isBlank()) throw ApiException.badRequest("Number of the inserted provision is required");
            LegalSection n = new LegalSection();
            n.setDocumentId(doc.getId());
            n.setSectionKey(UUID.randomUUID().toString());
            n.setParentKey(r.parentKey());
            n.setLevel(LegalSection.Level.SECTION);
            n.setNumber(r.number().trim());
            n.setHeading(r.heading());
            n.setContent(r.content());
            n.setEffectiveFrom(r.effectiveDate());
            sections.save(n);
            a.setResultingSectionId(n.getId());
        } else {
            if (r.sectionId() == null) throw ApiException.badRequest("The amended provision is required");
            LegalSection cur = section(r.sectionId());
            if (!cur.getDocumentId().equals(doc.getId())) throw ApiException.badRequest("Provision belongs to another document");
            if (cur.getEffectiveTo() != null) throw ApiException.rule("NOT_CURRENT", "Only the current version of a provision can be amended");
            if (!r.effectiveDate().isAfter(cur.getEffectiveFrom()))
                throw ApiException.rule("EFFECTIVE_DATE", "Amendment must take effect after the provision's current version");
            a.setSectionId(cur.getId());
            cur.setEffectiveTo(r.effectiveDate());
            if (r.amendmentType() != Amendment.Type.REPEALED) {
                LegalSection n = new LegalSection();
                n.setDocumentId(cur.getDocumentId());
                n.setSectionKey(cur.getSectionKey());
                n.setParentKey(cur.getParentKey());
                n.setLevel(cur.getLevel());
                n.setNumber(cur.getNumber());
                n.setHeading(r.heading() != null ? r.heading() : cur.getHeading());
                n.setContent(r.content() != null ? r.content() : cur.getContent());
                n.setSortOrder(cur.getSortOrder());
                n.setEffectiveFrom(r.effectiveDate());
                n.setVersionNo(cur.getVersionNo() + 1);
                n.setPreviousVersionId(cur.getId());
                n.setTags(new HashSet<>(cur.getTags()));
                n.setDesignationIds(new HashSet<>(cur.getDesignationIds()));
                sections.save(n);
                a.setResultingSectionId(n.getId());
            }
        }
        amendments.save(a);
        audit.log(AuditAction.CREATE, "Amendment", a.getId(), r.amendmentType() + " amendment to " + doc.getTitle() + ": " + a.getDescription());
        return a;
    }

    public void auditDownload(LegalDocument d) {
        audit.log(AuditAction.DOWNLOAD, "LegalDocument", d.getId(), "Downloaded " + d.getFileName());
    }
}
