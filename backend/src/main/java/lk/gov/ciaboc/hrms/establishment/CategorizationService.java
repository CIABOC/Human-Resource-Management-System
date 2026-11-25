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
 * Drag-and-drop position categorization (REQ 7.1–7.5). The browser holds moves as a draft; a submitted batch —
 * designation moves and category re-nesting — changes the official categorization only after approval, effective
 * from its date, with history preserved. Closed categories reject drops and circular nesting is rejected.
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

    public record PositionCard(Long designationId, String code, String name, String grade, Integer level, boolean dyingCadre,
                               boolean statutory, boolean pending) {
    }

    public record CategoryColumn(Long id, String code, String name, String nameSi, String nameTa, String color, int sortOrder,
                                 boolean closed, String description, Long parentId, boolean pending, List<PositionCard> positions) {
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
        List<Categorization.Line> openLines = open.isEmpty() ? List.of() : lines.findByBatchIdIn(open.stream().map(Categorization.Batch::getId).toList());
        Set<Long> pendingDes = openLines.stream().map(Categorization.Line::getDesignationId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> pendingCat = openLines.stream().map(Categorization.Line::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, List<PositionCard>> byCat = new HashMap<>();
        List<PositionCard> unassigned = new ArrayList<>();
        for (Designation des : designations.findByActiveTrueOrderByNameAsc()) {
            PositionCard card = new PositionCard(des.getId(), des.getCode(), des.getName(), des.getGrade(), des.getLevel(),
                    des.isDyingCadre(), des.isStatutory(), pendingDes.contains(des.getId()));
            Long c = catOf.get(des.getId());
            if (c == null) unassigned.add(card);
            else byCat.computeIfAbsent(c, k -> new ArrayList<>()).add(card);
        }
        Comparator<PositionCard> byLevel = Comparator.comparing((PositionCard p) -> p.level() == null ? 99 : p.level()).thenComparing(PositionCard::name);
        byCat.values().forEach(l -> l.sort(byLevel));
        List<CategoryColumn> cols = categories.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(c -> new CategoryColumn(c.getId(), c.getCode(), c.getName(), c.getNameSi(), c.getNameTa(), c.getColor(),
                        c.getSortOrder(), c.isClosed(), c.getDescription(), c.getParentId(), pendingCat.contains(c.getId()),
                        byCat.getOrDefault(c.getId(), List.of())))
                .toList();
        List<PendingBatch> pending = open.stream().map(b -> new PendingBatch(b.getId(), b.getWorkflowInstanceId(), b.getStatus().name(),
                b.getEffectiveDate(), lines.findByBatchId(b.getId()).size())).toList();
        return new Chart(settings.get("org.short-name", "Commission"), d, cols, unassigned, pending);
    }

    // ------------------------------------------------------------------ categories (classification metadata)

    public record CategoryRequest(String code, String name, String nameSi, String nameTa, String color, Integer sortOrder,
                                  Boolean closed, String description, Long parentId) {
    }

    /** New (empty) categories may be created directly, optionally nested; re-nesting existing ones goes through a draft. */
    @Transactional
    public PositionCategory createCategory(CategoryRequest r) {
        if (r.code() == null || !r.code().matches("^[A-Z0-9_]{1,40}$"))
            throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits or underscores");
        if (categories.findByCode(r.code()).isPresent()) throw ApiException.conflict("Category code already exists");
        PositionCategory c = new PositionCategory();
        c.setCode(r.code());
        apply(c, r);
        if (r.parentId() != null) {
            PositionCategory parent = category(r.parentId());
            if (parent.isClosed()) throw ApiException.rule("CATEGORY_CLOSED", "'" + parent.getName() + "' is closed and cannot receive sub-categories");
            c.setParentId(parent.getId());
        }
        categories.save(c);
        audit.log(AuditAction.CREATE, "PositionCategory", c.getId(), "Position category '" + c.getName() + "' created", null, r);
        return c;
    }

    @Transactional
    public PositionCategory updateCategory(Long id, CategoryRequest r) {
        PositionCategory c = category(id);
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

    /** Designation into a category (toCategoryId null = unassigned). */
    public record Move(Long designationId, Long toCategoryId) {
    }

    /** Category under another category (toParentId null = top level). */
    public record CategoryMove(Long categoryId, Long toParentId) {
    }

    public record SubmitRequest(LocalDate effectiveDate, String reason, List<Move> moves, List<CategoryMove> categoryMoves) {
    }

    /** Validation result for a single proposed drop, so the UI can explain rejected drops before submission (REQ 7.4). */
    public record DropCheck(boolean allowed, String reason) {
    }

    @Transactional(readOnly = true)
    public DropCheck checkCategoryMove(Long categoryId, Long toParentId) {
        try {
            validateCategoryMove(categoryId, toParentId, currentParents());
            return new DropCheck(true, null);
        } catch (ApiException e) {
            return new DropCheck(false, e.getMessage());
        }
    }

    @Transactional
    public Categorization.Batch submit(SubmitRequest r) {
        List<Move> moves = r.moves() == null ? List.of() : r.moves();
        List<CategoryMove> catMoves = r.categoryMoves() == null ? List.of() : r.categoryMoves();
        if (moves.isEmpty() && catMoves.isEmpty()) throw ApiException.badRequest("There are no changes to submit");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason is required");
        LocalDate d = r.effectiveDate() == null ? LocalDate.now() : r.effectiveDate();
        List<Categorization.Line> openLines = openLines();
        Set<Long> pendingDes = openLines.stream().map(Categorization.Line::getDesignationId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> pendingCat = openLines.stream().map(Categorization.Line::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet());

        Categorization.Batch b = new Categorization.Batch();
        b.setEffectiveDate(d);
        b.setReason(r.reason().trim());
        b.setStatus(Categorization.Batch.Status.PENDING);
        batches.save(b);
        List<String> summary = new ArrayList<>();

        // Category re-nesting first (so designation drops into a re-nested category are checked against the result).
        Map<Long, Long> parents = currentParents();
        Set<Long> seenCat = new HashSet<>();
        for (CategoryMove m : catMoves) {
            if (m.categoryId() == null || !seenCat.add(m.categoryId())) throw ApiException.badRequest("Each category may be moved once per submission");
            if (pendingCat.contains(m.categoryId())) throw ApiException.rule("ALREADY_PENDING", "A move of this category is already awaiting approval");
            validateCategoryMove(m.categoryId(), m.toParentId(), parents);
            Categorization.Line l = new Categorization.Line();
            l.setBatchId(b.getId());
            l.setLineType("CATEGORY");
            l.setCategoryId(m.categoryId());
            l.setFromParentId(parents.get(m.categoryId()));
            l.setToParentId(m.toParentId());
            parents.put(m.categoryId(), m.toParentId());
            lines.save(l);
            summary.add("Category " + name(m.categoryId()) + " under " + parentName(m.toParentId()));
        }
        Set<Long> seen = new HashSet<>();
        for (Move m : moves) {
            if (m.designationId() == null || !seen.add(m.designationId())) throw ApiException.badRequest("Each position may be moved once per submission");
            if (pendingDes.contains(m.designationId())) throw ApiException.rule("ALREADY_PENDING", "A move of this position is already awaiting approval");
            Categorization.Line l = new Categorization.Line();
            l.setBatchId(b.getId());
            l.setDesignationId(m.designationId());
            l.setFromCategoryId(assignments.effectiveOn(m.designationId(), d).map(Categorization.Assignment::getCategoryId).orElse(null));
            l.setToCategoryId(m.toCategoryId());
            summary.add(validateDesignationMove(l, d));
            lines.save(l);
        }
        int count = moves.size() + catMoves.size();
        WorkflowInstance wi = workflow.start(PROCESS, "CategorizationBatch", b.getId(),
                "Position categorization: " + count + " change(s) from " + d, "/establishment/org-chart", null);
        b.setWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.SUBMIT, "CategorizationBatch", b.getId(), "Categorization submitted: " + String.join("; ", summary));
        return b;
    }

    /** Validates a designation move and returns a readable description. */
    private String validateDesignationMove(Categorization.Line l, LocalDate d) {
        Designation des = designations.findById(l.getDesignationId()).filter(Designation::isActive)
                .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Position is not an active designation"));
        if (Objects.equals(l.getFromCategoryId(), l.getToCategoryId()))
            throw ApiException.badRequest(des.getName() + " is already in that category");
        if (l.getToCategoryId() != null) {
            PositionCategory to = category(l.getToCategoryId());
            if (to.isClosed()) throw ApiException.rule("CATEGORY_CLOSED", "'" + to.getName() + "' is closed and does not accept positions");
        }
        if (!assignments.startingAfter(l.getDesignationId(), d).isEmpty())
            throw ApiException.rule("LATER_CHANGE", des.getName() + " already has a later approved categorization");
        return des.getName() + ": " + name(l.getFromCategoryId()) + " → " + name(l.getToCategoryId());
    }

    /** Rejects closed targets and circular nesting (a category under itself or one of its descendants). */
    private void validateCategoryMove(Long categoryId, Long toParentId, Map<Long, Long> parents) {
        PositionCategory c = category(categoryId);
        if (Objects.equals(parents.get(categoryId), toParentId))
            throw ApiException.badRequest("'" + c.getName() + "' is already there");
        if (toParentId == null) return;
        PositionCategory target = category(toParentId);
        if (target.getId().equals(categoryId))
            throw ApiException.rule("CIRCULAR", "A category cannot be placed under itself");
        if (target.isClosed())
            throw ApiException.rule("CATEGORY_CLOSED", "'" + target.getName() + "' is closed and does not accept sub-categories");
        Long p = toParentId;
        Set<Long> guard = new HashSet<>();
        while (p != null && guard.add(p)) {
            if (p.equals(categoryId))
                throw ApiException.rule("CIRCULAR", "'" + target.getName() + "' is inside '" + c.getName()
                        + "'; placing '" + c.getName() + "' under it would create a circular hierarchy");
            p = parents.get(p);
        }
    }

    private Map<Long, Long> currentParents() {
        Map<Long, Long> m = new HashMap<>();
        categories.findAll().forEach(c -> m.put(c.getId(), c.getParentId()));
        return m;
    }

    private List<Categorization.Line> openLines() {
        List<Long> ids = batches.findByStatusIn(OPEN).stream().map(Categorization.Batch::getId).toList();
        return ids.isEmpty() ? List.of() : lines.findByBatchIdIn(ids);
    }

    private PositionCategory category(Long id) {
        return categories.findById(id).orElseThrow(() -> ApiException.notFound("Category", id));
    }

    private String name(Long categoryId) {
        return categoryId == null ? "Unassigned" : categories.findById(categoryId).map(PositionCategory::getName).orElse("?");
    }

    private String parentName(Long categoryId) {
        return categoryId == null ? "top level" : name(categoryId);
    }

    // ------------------------------------------------------------------ audit trail (REQ 7.5)

    public record HistoryRow(Long lineId, Long batchId, Instant when, String by, String designation, String from, String to,
                             LocalDate effective, String status, Long workflowInstanceId) {
    }

    @Transactional(readOnly = true)
    public List<HistoryRow> history() {
        Map<Long, Categorization.Batch> bs = batches.findAll().stream().collect(Collectors.toMap(Categorization.Batch::getId, Function.identity()));
        if (bs.isEmpty()) return List.of();
        Map<Long, String> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, Designation::getName));
        Map<Long, String> cats = categories.findAll().stream().collect(Collectors.toMap(PositionCategory::getId, PositionCategory::getName));
        return lines.findByBatchIdIn(bs.keySet()).stream()
                .map(l -> {
                    Categorization.Batch b = bs.get(l.getBatchId());
                    boolean category = "CATEGORY".equals(l.getLineType());
                    String subject = category ? "Category: " + cats.get(l.getCategoryId()) : des.get(l.getDesignationId());
                    String from = category ? (l.getFromParentId() == null ? "Top level" : cats.get(l.getFromParentId()))
                            : (l.getFromCategoryId() == null ? "Unassigned" : cats.get(l.getFromCategoryId()));
                    String to = category ? (l.getToParentId() == null ? "Top level" : cats.get(l.getToParentId()))
                            : (l.getToCategoryId() == null ? "Unassigned" : cats.get(l.getToCategoryId()));
                    return new HistoryRow(l.getId(), b.getId(), b.getCreatedAt(), b.getCreatedBy(), subject, from, to,
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
        List<Categorization.Line> ls = lines.findByBatchId(b.getId());
        Map<Long, Long> parents = currentParents();
        for (Categorization.Line l : ls) {
            if (!"CATEGORY".equals(l.getLineType())) continue;
            validateCategoryMove(l.getCategoryId(), l.getToParentId(), parents);
            parents.put(l.getCategoryId(), l.getToParentId());
            category(l.getCategoryId()).setParentId(l.getToParentId());
        }
        for (Categorization.Line l : ls) {
            if ("CATEGORY".equals(l.getLineType())) continue;
            validateDesignationMove(l, d);
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
            if ("CATEGORY".equals(l.getLineType())) {
                m.put("Change " + i++, "Category " + name(l.getCategoryId()) + ": " + parentName(l.getFromParentId()) + " → " + parentName(l.getToParentId()));
            } else {
                String des = designations.findById(l.getDesignationId()).map(Designation::getName).orElse("?");
                m.put("Change " + i++, des + ": " + name(l.getFromCategoryId()) + " → " + name(l.getToCategoryId()));
            }
        }
        return m;
    }

    private Categorization.Batch batch(Long id) {
        return batches.findById(id).orElseThrow(() -> ApiException.notFound("Categorization batch", id));
    }
}
