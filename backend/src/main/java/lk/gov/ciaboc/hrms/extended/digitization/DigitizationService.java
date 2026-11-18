package lk.gov.ciaboc.hrms.extended.digitization;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeDetails;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeRepository;
import lk.gov.ciaboc.hrms.employee.EmployeeService;
import lk.gov.ciaboc.hrms.extended.ExtSupport;
import lk.gov.ciaboc.hrms.extended.digitization.DigitizationEntities.Batch;
import lk.gov.ciaboc.hrms.extended.digitization.DigitizationEntities.Item;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.files.StoredFile;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static lk.gov.ciaboc.hrms.extended.ExtSupport.*;

/**
 * Controlled batch digitization of legacy personal files (FR-EXT-011): create batch → upload scans with index data →
 * submit → QA by a second person → file verified scans into the employee's digital personal file.
 *
 * <p>Filing decision: verified scans are filed through {@link EmployeeService#upload} (the employee module's own
 * upload capability, with its document-type, classification and access checks and audit) by presenting the staged
 * file as a MultipartFile. The staged copy (module EXTENDED) is retained as the digitization evidence trail.
 */
@Service
@RequiredArgsConstructor
public class DigitizationService {
    static final String ENTITY = "DigitizationBatch";

    private final BatchRepository batches;
    private final ItemRepository items;
    private final EmployeeRepository employeeRepo;
    private final EmployeeLookup lookup;
    private final EmployeeService employees;
    private final FileStorageService files;
    private final MasterDataService masterData;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ExtSupport support;

    public record BatchInput(String title, String sourceLocation, String remarks) {
    }

    public record IndexInput(String employeeNo, String documentType, String title, LocalDate documentDate,
                             EmployeeDetails.Document.Classification classification) {
    }

    public record QaInput(String decision, String remarks) {
    }

    public record BatchSummary(Long id, String batchNo, String title, String sourceLocation, String status, String createdBy,
                               Instant createdAt, Instant submittedAt, Instant completedAt, Map<String, Long> counts,
                               long total) {
    }

    public record ItemView(Long id, Long employeeId, String employeeNo, String employeeName, String documentType,
                           String title, LocalDate documentDate, String classification, String fileName, String status,
                           String uploadedBy, Instant uploadedAt, String qaBy, Instant qaAt, String qaRemarks,
                           Long employeeDocumentId, String filedBy, Instant filedAt) {
    }

    public record BatchDetail(BatchSummary batch, String remarks, List<ItemView> items, int hiddenItems) {
    }

    // ===================================================================== batches

    @Transactional(readOnly = true)
    public List<BatchSummary> list() {
        List<Batch> all = batches.findAllByOrderByIdDesc();
        return all.stream().map(b -> summary(b, items.findByBatchIdOrderByIdAsc(b.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public BatchDetail get(Long id) {
        Batch b = batch(id);
        List<Item> all = items.findByBatchIdOrderByIdAsc(id);
        Set<Long> visible = support.visibleIds();
        List<Item> mine = all.stream().filter(i -> visible(visible, i.getEmployeeId())).toList();
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = support.briefs(mine.stream().map(Item::getEmployeeId).toList());
        return new BatchDetail(summary(b, all), b.getRemarks(), mine.stream().map(i -> view(i, briefs.get(i.getEmployeeId()))).toList(),
                all.size() - mine.size());
    }

    @Transactional
    public BatchDetail create(BatchInput in) {
        require(in.title(), "Batch title");
        maxLength(in.title(), 300, "Batch title");
        maxLength(in.sourceLocation(), 300, "Source location");
        maxLength(in.remarks(), 1000, "Remarks");
        Batch b = new Batch();
        b.setBatchNo("DIG-" + LocalDate.now().getYear() + "-" + String.format("%04d", batches.nextNumber()));
        b.setTitle(in.title().trim());
        b.setSourceLocation(trimToNull(in.sourceLocation()));
        b.setRemarks(trimToNull(in.remarks()));
        b.setStatus(Batch.Status.OPEN);
        batches.save(b);
        audit.log(AuditAction.CREATE, ENTITY, b.getId(), "Digitization batch " + b.getBatchNo() + " created");
        return get(b.getId());
    }

    // ===================================================================== items

    @Transactional
    public ItemView addItem(Long batchId, IndexInput in, MultipartFile file) {
        Batch b = batch(batchId);
        if (b.getStatus() != Batch.Status.OPEN) throw ApiException.rule("BATCH_NOT_OPEN", "Scans can only be added while the batch is open");
        Item i = new Item();
        i.setBatchId(b.getId());
        index(i, in);
        store(i, file);
        i.setStatus(Item.Status.UPLOADED);
        i.setUploadedBy(CurrentUser.username());
        items.save(i);
        audit.log(AuditAction.UPLOAD, "DigitizationItem", i.getId(), "Scanned '" + i.getTitle() + "' for " + i.getEmployeeNo()
                + " added to " + b.getBatchNo());
        return view(i, lookup.brief(i.getEmployeeId()));
    }

    /** Corrects the index data and/or replaces the scan of an item not yet verified; it then needs QA again. */
    @Transactional
    public ItemView correct(Long itemId, IndexInput in, MultipartFile file) {
        Item i = item(itemId);
        Batch b = batch(i.getBatchId());
        lookup.requireAccess(i.getEmployeeId());
        if (b.getStatus() == Batch.Status.COMPLETED) throw ApiException.rule("BATCH_COMPLETED", "The batch is completed");
        if (i.getStatus() != Item.Status.UPLOADED && i.getStatus() != Item.Status.REJECTED)
            throw ApiException.rule("NOT_EDITABLE", "Only uploaded or rejected scans can be corrected");
        if (!CurrentUser.username().equals(i.getUploadedBy())) throw ApiException.forbidden("Only the officer who uploaded the scan can correct it");
        if (in != null) index(i, in);
        if (file != null && !file.isEmpty()) store(i, file);
        i.setStatus(Item.Status.UPLOADED);
        i.setQaBy(null);
        i.setQaAt(null);
        audit.log(AuditAction.UPDATE, "DigitizationItem", i.getId(), "Scan corrected: '" + i.getTitle() + "' for " + i.getEmployeeNo());
        return view(i, lookup.brief(i.getEmployeeId()));
    }

    @Transactional
    public ItemView withdraw(Long itemId) {
        Item i = item(itemId);
        lookup.requireAccess(i.getEmployeeId());
        if (i.getStatus() != Item.Status.UPLOADED && i.getStatus() != Item.Status.REJECTED)
            throw ApiException.rule("NOT_EDITABLE", "Only uploaded or rejected scans can be withdrawn");
        i.setStatus(Item.Status.WITHDRAWN);
        audit.log(AuditAction.UPDATE, "DigitizationItem", i.getId(), "Scan withdrawn: '" + i.getTitle() + "'");
        completeIfDone(batch(i.getBatchId()));
        return view(i, lookup.brief(i.getEmployeeId()));
    }

    @Transactional
    public BatchDetail submit(Long batchId) {
        Batch b = batch(batchId);
        if (b.getStatus() != Batch.Status.OPEN) throw ApiException.rule("BATCH_NOT_OPEN", "Only open batches can be submitted for QA");
        long uploaded = items.findByBatchIdOrderByIdAsc(batchId).stream().filter(i -> i.getStatus() == Item.Status.UPLOADED).count();
        if (uploaded == 0) throw ApiException.rule("EMPTY_BATCH", "Add at least one scanned document before submitting");
        b.setStatus(Batch.Status.IN_QA);
        b.setSubmittedBy(CurrentUser.username());
        b.setSubmittedAt(Instant.now());
        Map<String, Object> vars = new HashMap<>();
        vars.put("batchNo", b.getBatchNo());
        vars.put("title", b.getTitle());
        vars.put("count", uploaded);
        vars.put("submittedBy", CurrentUser.get().getFullName());
        notifications.notifyRole("HR_ADMIN", "EXT_DIGITIZATION_QA", vars, "/extended/digitization/" + b.getId());
        audit.log(AuditAction.SUBMIT, ENTITY, b.getId(), "Batch " + b.getBatchNo() + " submitted for QA (" + uploaded + " scans)");
        return get(batchId);
    }

    /** QA by a second person: the verifier can never be the officer who uploaded the scan. */
    @Transactional
    public ItemView qa(Long itemId, QaInput in) {
        Item i = item(itemId);
        Batch b = batch(i.getBatchId());
        lookup.requireAccess(i.getEmployeeId());
        if (b.getStatus() != Batch.Status.IN_QA) throw ApiException.rule("NOT_IN_QA", "The batch has not been submitted for QA");
        if (i.getStatus() != Item.Status.UPLOADED) throw ApiException.rule("NOT_PENDING_QA", "This scan is not awaiting QA");
        if (CurrentUser.username().equals(i.getUploadedBy()))
            throw ApiException.rule("SEGREGATION_OF_DUTIES", "QA must be done by a different officer from the one who uploaded the scan");
        String decision = in == null || in.decision() == null ? "" : in.decision().toUpperCase(Locale.ROOT);
        switch (decision) {
            case "VERIFY" -> i.setStatus(Item.Status.VERIFIED);
            case "REJECT" -> {
                if (blank(in.remarks())) throw ApiException.badRequest("A reason is required when rejecting a scan");
                i.setStatus(Item.Status.REJECTED);
            }
            default -> throw ApiException.badRequest("Decision must be VERIFY or REJECT");
        }
        maxLength(in.remarks(), 1000, "Remarks");
        i.setQaBy(CurrentUser.username());
        i.setQaAt(Instant.now());
        i.setQaRemarks(trimToNull(in.remarks()));
        audit.log(i.getStatus() == Item.Status.VERIFIED ? AuditAction.APPROVE : AuditAction.REJECT, "DigitizationItem", i.getId(),
                "QA " + decision.toLowerCase() + ": '" + i.getTitle() + "' for " + i.getEmployeeNo());
        return view(i, lookup.brief(i.getEmployeeId()));
    }

    /** Files every verified scan of the batch into the employees' digital personal files. */
    @Transactional
    public BatchDetail file(Long batchId) {
        Batch b = batch(batchId);
        if (b.getStatus() != Batch.Status.IN_QA) throw ApiException.rule("NOT_IN_QA", "Only batches in QA can be filed");
        List<Item> verified = items.findByBatchIdOrderByIdAsc(batchId).stream().filter(i -> i.getStatus() == Item.Status.VERIFIED).toList();
        if (verified.isEmpty()) throw ApiException.rule("NOTHING_TO_FILE", "No verified scans to file");
        for (Item i : verified) {
            StoredFile meta = files.meta(i.getFileId());
            byte[] content = read(i.getFileId());
            EmployeeDetails.Document d = employees.upload(i.getEmployeeId(), i.getDocumentType(), i.getTitle(), i.getDocumentDate(),
                    i.getClassification(), "Digitized legacy record, batch " + b.getBatchNo() + " (QA: " + i.getQaBy() + ")",
                    new StoredBytesMultipartFile(meta.getOriginalName(), meta.getContentType(), content));
            i.setEmployeeDocumentId(d.getId());
            i.setStatus(Item.Status.FILED);
            i.setFiledBy(CurrentUser.username());
            i.setFiledAt(Instant.now());
        }
        audit.log(AuditAction.PROCESS, ENTITY, b.getId(), verified.size() + " scan(s) of " + b.getBatchNo() + " filed into personal files");
        completeIfDone(b);
        return get(batchId);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> download(Long itemId) {
        Item i = item(itemId);
        lookup.requireAccess(i.getEmployeeId());
        requireClassification(i.getClassification());
        audit.log(AuditAction.DOWNLOAD, "DigitizationItem", i.getId(), "Downloaded scan '" + i.getTitle() + "' of " + i.getEmployeeNo());
        return files.download(i.getFileId());
    }

    // ===================================================================== helpers

    private void index(Item i, IndexInput in) {
        if (in == null) throw ApiException.badRequest("Index data is required");
        require(in.employeeNo(), "Employee number");
        Employee e = employeeRepo.findByEmployeeNo(in.employeeNo().trim())
                .orElseThrow(() -> ApiException.rule("UNKNOWN_EMPLOYEE", "No employee with number " + in.employeeNo().trim()));
        lookup.requireAccess(e.getId());
        require(in.documentType(), "Document type");
        masterData.requireActive(MasterDataCategory.DOCUMENT_TYPE, in.documentType(), "Document type");
        require(in.title(), "Title");
        maxLength(in.title(), 300, "Title");
        if (in.documentDate() != null && in.documentDate().isAfter(LocalDate.now()))
            throw ApiException.badRequest("Document date cannot be in the future");
        EmployeeDetails.Document.Classification cls = in.classification() == null
                ? EmployeeDetails.Document.Classification.CONFIDENTIAL : in.classification();
        requireClassification(cls);
        i.setEmployeeId(e.getId());
        i.setEmployeeNo(e.getEmployeeNo());
        i.setDocumentType(in.documentType());
        i.setTitle(in.title().trim());
        i.setDocumentDate(in.documentDate());
        i.setClassification(cls);
    }

    private static void requireClassification(EmployeeDetails.Document.Classification cls) {
        if (cls == EmployeeDetails.Document.Classification.HIGHLY_CONFIDENTIAL && !CurrentUser.get().has(Permission.EMP_RESTRICTED))
            throw ApiException.forbidden("Only authorized officers can handle highly confidential documents");
    }

    private void store(Item i, MultipartFile file) {
        StoredFile f = files.store(file, "EXTENDED");
        i.setFileId(f.getId());
        i.setFileName(f.getOriginalName());
        i.setFileSha256(f.getSha256());
    }

    private byte[] read(Long fileId) {
        ResponseEntity<Resource> r = files.download(fileId);
        if (r.getBody() == null) throw ApiException.notFound("File content", fileId);
        try (InputStream in = r.getBody().getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw ApiException.rule("FILE_READ", "The scanned file could not be read");
        }
    }

    private void completeIfDone(Batch b) {
        if (b.getStatus() == Batch.Status.COMPLETED) return;
        List<Item> all = items.findByBatchIdOrderByIdAsc(b.getId());
        boolean done = !all.isEmpty() && all.stream().allMatch(i -> i.getStatus() == Item.Status.FILED || i.getStatus() == Item.Status.WITHDRAWN)
                && all.stream().anyMatch(i -> i.getStatus() == Item.Status.FILED);
        if (done && b.getStatus() == Batch.Status.IN_QA) {
            b.setStatus(Batch.Status.COMPLETED);
            b.setCompletedAt(Instant.now());
            audit.log(AuditAction.UPDATE, ENTITY, b.getId(), "Batch " + b.getBatchNo() + " completed");
        }
    }

    private Batch batch(Long id) {
        return batches.findById(id).orElseThrow(() -> ApiException.notFound("Digitization batch", id));
    }

    private Item item(Long id) {
        return items.findById(id).orElseThrow(() -> ApiException.notFound("Scanned document", id));
    }

    private static BatchSummary summary(Batch b, List<Item> all) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Item.Status s : Item.Status.values()) counts.put(s.name(), all.stream().filter(i -> i.getStatus() == s).count());
        return new BatchSummary(b.getId(), b.getBatchNo(), b.getTitle(), b.getSourceLocation(), b.getStatus().name(), b.getCreatedBy(),
                b.getCreatedAt(), b.getSubmittedAt(), b.getCompletedAt(), counts, all.size());
    }

    private static ItemView view(Item i, EmployeeLookup.EmployeeBrief b) {
        return new ItemView(i.getId(), i.getEmployeeId(), i.getEmployeeNo(), b == null ? null : b.nameWithInitials(), i.getDocumentType(),
                i.getTitle(), i.getDocumentDate(), i.getClassification().name(), i.getFileName(), i.getStatus().name(),
                i.getUploadedBy(), i.getCreatedAt(), i.getQaBy(), i.getQaAt(), i.getQaRemarks(), i.getEmployeeDocumentId(),
                i.getFiledBy(), i.getFiledAt());
    }
}
