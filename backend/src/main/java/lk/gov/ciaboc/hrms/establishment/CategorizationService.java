package lk.gov.ciaboc.hrms.establishment;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drag-and-drop position categorization. The browser holds moves as a draft; a submitted batch changes the official
 * categorization only after approval, effective from its date, with history preserved.
 */
@Service
@RequiredArgsConstructor
public class CategorizationService implements WorkflowHandler {
    public static final String PROCESS = "POSITION_CATEGORIZATION";
    private static final List<Categorization.Batch.Status> OPEN = List.of(Categorization.Batch.Status.PENDING, Categorization.Batch.Status.RETURNED);

    private final PositionCategoryRepository categories;
    private final CategoryAssignmentRepository assignments;
    private final CategorizationBatchRepository batches;
    private final CategorizationLineRepository lines;
    private final DesignationRepository designations;
    private final SettingsService settings;
    private final AuditService audit;
    private final WorkflowService workflow;

    // ------------------------------------------------------------------ chart

    public record PositionCard(Long designationId, String code, String name, String grade, boolean pending) {
    }

    public record CategoryColumn(Long id, String code, String name, String nameSi, String nameTa, String color, int sortOrder,
                                 boolean closed, String description, List<PositionCard> positions) {
    }

    public record PendingBatch(Long id, Long workflowInstanceId, String status, LocalDate effectiveDate, int moves) {
    }

    public record Chart(String rootName, LocalDate asOf, List<CategoryColumn> categories, List<PositionCard> unassigned,
                        List<PendingBatch> pendingBatches) {
    }

    @Transactional(readOnly = true)
    public Chart chart(LocalDate asOf) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        Map<Long, Long> catOf = assignments.effectiveOn(d).stream()
                .collect(Collectors.toMap(Categorization.Assignment::getDesignationId, Categorization.Assignment::getCategoryId, (a, b) -> a));
        List<Categorization.Batch> open = batches.findByStatusIn(OPEN);
        Set<Long> pendingDes = open.isEmpty() ? Set.of() : lines.findByBatchIdIn(open.stream().map(Categorization.Batch::getId).toList())
                .stream().map(Categorization.Line::getDesignationId).collect(Collectors.toSet());
        Map<Long, List<PositionCard>> byCat = new HashMap<>();
        List<PositionCard> unassigned = new ArrayList<>();
        for (Designation des : designations.findByActiveTrueOrderByNameAsc()) {
            PositionCard card = new PositionCard(des.getId(), des.getCode(), des.getName(), des.getGrade(), pendingDes.contains(des.getId()));
            Long c = catOf.get(des.getId());
            if (c == null) unassigned.add(card);
            else byCat.computeIfAbsent(c, k -> new ArrayList<>()).add(card);
        }
        List<CategoryColumn> cols = categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(c -> new CategoryColumn(c.getId(), c.getCode(), c.getName(), c.getNameSi(), c.getNameTa(), c.getColor(),
                        c.getSortOrder(), c.isClosed(), c.getDescription(), byCat.getOrDefault(c.getId(), List.of())))
                .toList();
        List<PendingBatch> pending = open.stream().map(b -> new PendingBatch(b.getId(), b.getWorkflowInstanceId(), b.getStatus().name(),
                b.getEffectiveDate(), lines.findByBatchId(b.getId()).size())).toList();
        return new Chart(settings.get("org.short-name", "Commission"), d, cols, unassigned, pending);
    }

    // ------------------------------------------------------------------ categories (classification metadata)

    public record CategoryRequest(String code, String name, String nameSi, String nameTa, String color, Integer sortOrder,
                                  Boolean closed, String description) {
    }

    @Transactional
    public PositionCategory createCategory(CategoryRequest r) {
        if (r.code() == null || !r.code().matches("^[A-Z0-9_]{1,40}$"))
            throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits or underscores");
        if (categories.findByCode(r.code()).isPresent()) throw ApiException.conflict("Category code already exists");
        PositionCategory c = new PositionCategory();
        c.setCode(r.code());
        apply(c, r);
        categories.save(c);
        audit.log(AuditAction.CREATE, "PositionCategory", c.getId(), "Position category '" + c.getName() + "' created", null, r);
        return c;
    }

    @Transactional
    public PositionCategory updateCategory(Long id, CategoryRequest r) {
        PositionCategory c = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category", id));
        Map<String, Object> before = Map.of("name", c.getName(), "color", c.getColor(), "closed", c.isClosed(), "sortOrder", c.getSortOrder());
        apply(c, r);
        audit.log(AuditAction.UPDATE, "PositionCategory", id, "Position category '" + c.getName() + "' updated", before, r);
        return c;
    }

    private static void apply(PositionCategory c, CategoryRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Name is required");
        c.setName(r.name().trim());
        c.setNameSi(r.nameSi());
        c.setNameTa(r.nameTa());
        if (r.color() != null && !r.color().isBlank()) {
            if (!r.color().matches("^#[0-9A-Fa-f]{6}$")) throw ApiException.badRequest("Colour must be like #0B1F4B");
            c.setColor(r.color());
        }
        if (r.sortOrder() != null) c.setSortOrder(r.sortOrder());
        if (r.closed() != null) c.setClosed(r.closed());
        c.setDescription(r.description());
    }

    // ------------------------------------------------------------------ submit a draft

    public record Move(Long designationId, Long toCategoryId) {
    }

    public record SubmitRequest(LocalDate effectiveDate, String reason, List<Move> moves) {
    }

    @Transactional
    public Categorization.Batch submit(SubmitRequest r) {
        if (r.moves() == null || r.moves().isEmpty()) throw ApiException.badRequest("There are no changes to submit");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason is required");
        LocalDate d = r.effectiveDate() == null ? LocalDate.now() : r.effectiveDate();
        Set<Long> seen = new HashSet<>();
        Set<Long> pending = openDesignationIds();
        Categorization.Batch b = new Categorization.Batch();
        b.setEffectiveDate(d);
        b.setReason(r.reason().trim());
        b.setStatus(Categorization.Batch.Status.PENDING);
        batches.save(b);
        List<String> summary = new ArrayList<>();
        for (Move m : r.moves()) {
            if (m.designationId() == null || !seen.add(m.designationId())) throw ApiException.badRequest("Each position may be moved once per submission");
            if (pending.contains(m.designationId())) throw ApiException.rule("ALREADY_PENDING", "A move of this position is already awaiting approval");
            Categorization.Line l = new Categorization.Line();
            l.setBatchId(b.getId());
            l.setDesignationId(m.designationId());
            l.setFromCategoryId(assignments.effectiveOn(m.designationId(), d).map(Categorization.Assignment::getCategoryId).orElse(null));
            l.setToCategoryId(m.toCategoryId());
            summary.add(validate(l, d));
            lines.save(l);
        }
        WorkflowInstance wi = workflow.start(PROCESS, "CategorizationBatch", b.getId(),
                "Position categorization: " + r.moves().size() + " move(s) from " + d, "/establishment/org-chart", null);
        b.setWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.SUBMIT, "CategorizationBatch", b.getId(), "Categorization submitted: " + String.join("; ", summary));
        return b;
    }

    /** Validates a move and returns a readable description. */
    private String validate(Categorization.Line l, LocalDate d) {
        Designation des = designations.findById(l.getDesignationId()).filter(Designation::isActive)
                .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Position is not an active designation"));
        if (Objects.equals(l.getFromCategoryId(), l.getToCategoryId()))
            throw ApiException.badRequest(des.getName() + " is already in that category");
        if (l.getToCategoryId() != null) {
            PositionCategory to = categories.findById(l.getToCategoryId())
                    .orElseThrow(() -> ApiException.notFound("Category", l.getToCategoryId()));
            if (to.isClosed()) throw ApiException.rule("CATEGORY_CLOSED", "'" + to.getName() + "' is closed and does not accept positions");
        }
        if (!assignments.startingAfter(l.getDesignationId(), d).isEmpty())
            throw ApiException.rule("LATER_CHANGE", des.getName() + " already has a later approved categorization");
        return des.getName() + ": " + name(l.getFromCategoryId()) + " → " + name(l.getToCategoryId());
    }

    private Set<Long> openDesignationIds() {
        List<Long> ids = batches.findByStatusIn(OPEN).stream().map(Categorization.Batch::getId).toList();
        return ids.isEmpty() ? Set.of() : lines.findByBatchIdIn(ids).stream().map(Categorization.Line::getDesignationId).collect(Collectors.toSet());
    }

    private String name(Long categoryId) {
        return categoryId == null ? "Unassigned" : categories.findById(categoryId).map(PositionCategory::getName).orElse("?");
    }

    // ------------------------------------------------------------------ audit trail

    public record HistoryRow(Long lineId, Long batchId, Instant when, String by, String designation, String from, String to,
                             LocalDate effective, String status, Long workflowInstanceId) {
    }

    @Transactional(readOnly = true)
    public List<HistoryRow> history() {
        Map<Long, Categorization.Batch> bs = batches.findAll().stream().collect(Collectors.toMap(Categorization.Batch::getId, Function.identity()));
        Map<Long, String> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, Designation::getName));
        Map<Long, String> cats = categories.findAll().stream().collect(Collectors.toMap(PositionCategory::getId, PositionCategory::getName));
        return lines.findByBatchIdIn(bs.keySet()).stream()
                .map(l -> {
                    Categorization.Batch b = bs.get(l.getBatchId());
                    return new HistoryRow(l.getId(), b.getId(), b.getCreatedAt(), b.getCreatedBy(), des.get(l.getDesignationId()),
                            l.getFromCategoryId() == null ? "Unassigned" : cats.get(l.getFromCategoryId()),
                            l.getToCategoryId() == null ? "Unassigned" : cats.get(l.getToCategoryId()),
                            b.getEffectiveDate(), b.getStatus().name(), b.getWorkflowInstanceId());
                })
                .sorted(Comparator.comparing(HistoryRow::when, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // ------------------------------------------------------------------ workflow callbacks

    @Override
    public String processCode() {
        return PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        Categorization.Batch b = batch(wi.getEntityId());
        LocalDate d = b.getEffectiveDate();
        for (Categorization.Line l : lines.findByBatchId(b.getId())) {
            validate(l, d);
            assignments.effectiveOn(l.getDesignationId(), d).ifPresent(cur -> {
                if (cur.getEffectiveFrom().equals(d)) assignments.delete(cur);
                else cur.setEffectiveTo(d);
            });
            assignments.flush();
            if (l.getToCategoryId() != null) {
                Categorization.Assignment a = new Categorization.Assignment();
                a.setDesignationId(l.getDesignationId());
                a.setCategoryId(l.getToCategoryId());
                a.setEffectiveFrom(d);
                a.setBatchId(b.getId());
                assignments.save(a);
            }
        }
        b.setStatus(Categorization.Batch.Status.APPLIED);
        b.setAppliedAt(Instant.now());
        audit.log(AuditAction.UPDATE, "CategorizationBatch", b.getId(), "Categorization applied from " + d);
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        batch(wi.getEntityId()).setStatus(Categorization.Batch.Status.REJECTED);
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        batch(wi.getEntityId()).setStatus(Categorization.Batch.Status.RETURNED);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        batch(wi.getEntityId()).setStatus(Categorization.Batch.Status.PENDING);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        batch(wi.getEntityId()).setStatus(Categorization.Batch.Status.CANCELLED);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        Categorization.Batch b = batch(wi.getEntityId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Effective date", b.getEffectiveDate());
        m.put("Reason", b.getReason());
        int i = 1;
        for (Categorization.Line l : lines.findByBatchId(b.getId())) {
            String des = designations.findById(l.getDesignationId()).map(Designation::getName).orElse("?");
            m.put("Move " + i++, des + ": " + name(l.getFromCategoryId()) + " → " + name(l.getToCategoryId()));
        }
        return m;
    }

    private Categorization.Batch batch(Long id) {
        return batches.findById(id).orElseThrow(() -> ApiException.notFound("Categorization batch", id));
    }
}
