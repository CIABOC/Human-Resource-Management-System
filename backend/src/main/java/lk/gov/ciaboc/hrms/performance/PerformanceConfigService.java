package lk.gov.ciaboc.hrms.performance;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataItem;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.masterdata.MasterDataUsageChecker;
import lk.gov.ciaboc.hrms.performance.PerformanceEntities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Evaluation periods, configurable evaluation models with weighted criteria / KPIs / objectives and rating scales, and
 * the assignment of a model to each employee category (FR-PER-001..003). There is never one forced template.
 */
@Service
@RequiredArgsConstructor
public class PerformanceConfigService implements MasterDataUsageChecker {
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{1,40}$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EvaluationPeriodRepository periods;
    private final EvaluationModelRepository models;
    private final EvaluationCriterionRepository criteria;
    private final CategoryModelRepository categoryModels;
    private final EvaluationRepository evaluations;
    private final MasterDataService masterData;
    private final AuditService audit;

    // ===================================================================== periods

    public record PeriodRequest(String code, String name, LocalDate startDate, LocalDate endDate, LocalDate dueDate) {
    }

    @Transactional(readOnly = true)
    public List<EvaluationPeriod> periods() {
        return periods.findAllByOrderByStartDateDesc();
    }

    @Transactional
    public EvaluationPeriod savePeriod(Long id, PeriodRequest r) {
        EvaluationPeriod p = id == null ? new EvaluationPeriod() : period(id);
        String code = code(r.code());
        if (id == null && periods.existsByCode(code)) throw ApiException.conflict("Period " + code + " already exists");
        if (id != null && !p.getCode().equals(code) && periods.existsByCode(code)) throw ApiException.conflict("Period " + code + " already exists");
        require(r.name(), "Name");
        if (r.startDate() == null || r.endDate() == null) throw ApiException.badRequest("Start and end dates are required");
        if (r.endDate().isBefore(r.startDate())) throw ApiException.badRequest("End date cannot be before start date");
        if (r.dueDate() != null && r.dueDate().isBefore(r.startDate())) throw ApiException.badRequest("Due date cannot be before the start date");
        String before = id == null ? null : p.getName() + " " + p.getStartDate() + "→" + p.getEndDate() + " due " + p.getDueDate();
        p.setCode(code);
        p.setName(r.name().trim());
        p.setStartDate(r.startDate());
        p.setEndDate(r.endDate());
        p.setDueDate(r.dueDate());
        periods.save(p);
        audit.log(id == null ? AuditAction.CREATE : AuditAction.UPDATE, "EvaluationPeriod", p.getId(), "Evaluation period " + code + " saved",
                before, p.getName() + " " + p.getStartDate() + "→" + p.getEndDate() + " due " + p.getDueDate());
        return p;
    }

    @Transactional
    public EvaluationPeriod setPeriodStatus(Long id, String status) {
        EvaluationPeriod p = period(id);
        PeriodStatus to;
        try {
            to = PeriodStatus.valueOf(status);
        } catch (Exception e) {
            throw ApiException.badRequest("Status must be OPEN or CLOSED");
        }
        PeriodStatus from = p.getStatus();
        p.setStatus(to);
        audit.log(AuditAction.UPDATE, "EvaluationPeriod", id, "Evaluation period " + p.getCode() + " " + from + " → " + to, from.name(), to.name());
        return p;
    }

    EvaluationPeriod period(Long id) {
        return periods.findById(id).orElseThrow(() -> ApiException.notFound("Evaluation period", id));
    }

    // ===================================================================== models & criteria

    public record CriterionRequest(Long id, String criterionType, String title, String description, BigDecimal weight,
                                   Integer sortOrder) {
    }

    public record ModelRequest(String code, String name, String description, BigDecimal ratingMin, BigDecimal ratingMax,
                               Boolean active, List<CriterionRequest> criteria) {
    }

    public record ModelView(EvaluationModel model, List<EvaluationCriterion> criteria, BigDecimal totalWeight,
                            List<String> categories, long evaluations, boolean locked) {
    }

    @Transactional(readOnly = true)
    public List<ModelView> models() {
        return models.findAllByOrderByCodeAsc().stream().map(this::modelView).toList();
    }

    @Transactional(readOnly = true)
    public ModelView modelView(Long id) {
        return modelView(model(id));
    }

    private ModelView modelView(EvaluationModel m) {
        List<EvaluationCriterion> cs = criteria.findByModelIdOrderBySortOrderAscIdAsc(m.getId());
        BigDecimal total = cs.stream().map(EvaluationCriterion::getWeight).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> cats = categoryModels.findAllByOrderByEmployeeCategoryAsc().stream()
                .filter(c -> c.getModelId().equals(m.getId())).map(CategoryModel::getEmployeeCategory).toList();
        long used = evaluations.countByModelId(m.getId());
        return new ModelView(m, cs, total, cats, used, used > 0);
    }

    /**
     * Saves a model and replaces its criteria. A model already used by evaluations is locked (its criteria and scale
     * cannot change, so recorded evaluations stay interpretable); copy it to make a new version instead.
     */
    @Transactional
    public ModelView saveModel(Long id, ModelRequest r) {
        EvaluationModel m = id == null ? new EvaluationModel() : model(id);
        String code = code(r.code());
        if ((id == null || !m.getCode().equals(code)) && models.existsByCode(code)) throw ApiException.conflict("Model " + code + " already exists");
        require(r.name(), "Name");
        if (r.ratingMin() == null || r.ratingMax() == null || r.ratingMax().compareTo(r.ratingMin()) <= 0)
            throw ApiException.badRequest("Rating scale maximum must be greater than the minimum");
        boolean locked = id != null && evaluations.countByModelId(id) > 0;
        List<CriterionRequest> crs = r.criteria() == null ? List.of() : r.criteria();
        if (crs.isEmpty()) throw ApiException.badRequest("At least one criterion, KPI or objective is required");
        for (CriterionRequest c : crs) {
            require(c.title(), "Criterion title");
            if (c.weight() == null || c.weight().signum() <= 0) throw ApiException.badRequest("Weight of '" + c.title() + "' must be positive");
        }
        BigDecimal total = crs.stream().map(CriterionRequest::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(HUNDRED) != 0) throw ApiException.rule("WEIGHTS", "Criterion weights must add up to 100 (currently " + total + ")");
        String before = id == null ? null : summary(m);
        if (locked) {
            boolean scaleChanged = m.getRatingMin().compareTo(r.ratingMin()) != 0 || m.getRatingMax().compareTo(r.ratingMax()) != 0;
            List<EvaluationCriterion> existing = criteria.findByModelIdOrderBySortOrderAscIdAsc(id);
            boolean criteriaChanged = existing.size() != crs.size() || crs.stream().anyMatch(c -> existing.stream().noneMatch(e ->
                    e.getId().equals(c.id()) && e.getWeight().compareTo(c.weight()) == 0 && e.getTitle().equals(c.title().trim())));
            if (scaleChanged || criteriaChanged)
                throw ApiException.rule("MODEL_IN_USE", "This model is used by recorded evaluations; copy it to create a new version");
        }
        m.setCode(code);
        m.setName(r.name().trim());
        m.setDescription(r.description());
        m.setRatingMin(r.ratingMin());
        m.setRatingMax(r.ratingMax());
        m.setActive(r.active() == null || r.active());
        if (!m.isActive() && id != null && categoryModels.countByModelId(id) > 0)
            throw ApiException.rule("MODEL_ASSIGNED", "Reassign the employee categories using this model before deactivating it");
        models.save(m);
        if (!locked) {
            Map<Long, EvaluationCriterion> existing = new HashMap<>();
            criteria.findByModelIdOrderBySortOrderAscIdAsc(m.getId()).forEach(c -> existing.put(c.getId(), c));
            int order = 1;
            for (CriterionRequest c : crs) {
                EvaluationCriterion ec = c.id() != null && existing.containsKey(c.id()) ? existing.remove(c.id()) : new EvaluationCriterion();
                ec.setModelId(m.getId());
                ec.setCriterionType(parseType(c.criterionType()));
                ec.setTitle(c.title().trim());
                ec.setDescription(c.description());
                ec.setWeight(c.weight());
                ec.setSortOrder(c.sortOrder() == null ? order : c.sortOrder());
                order++;
                criteria.save(ec);
            }
            criteria.deleteAll(existing.values());
        } else {
            // Only descriptive text may change on a locked model.
            Map<Long, CriterionRequest> byId = new HashMap<>();
            crs.forEach(c -> byId.put(c.id(), c));
            for (EvaluationCriterion ec : criteria.findByModelIdOrderBySortOrderAscIdAsc(m.getId())) {
                CriterionRequest c = byId.get(ec.getId());
                if (c != null) ec.setDescription(c.description());
            }
        }
        audit.log(id == null ? AuditAction.CREATE : AuditAction.CONFIG_CHANGE, "EvaluationModel", m.getId(),
                "Evaluation model " + code + " saved", before, summary(m));
        return modelView(m);
    }

    /** Copies a model (e.g. to revise a locked one). The copy is never a sample. */
    @Transactional
    public ModelView copyModel(Long id, String newCode, String newName) {
        EvaluationModel src = model(id);
        List<CriterionRequest> crs = criteria.findByModelIdOrderBySortOrderAscIdAsc(id).stream()
                .map(c -> new CriterionRequest(null, c.getCriterionType().name(), c.getTitle(), c.getDescription(), c.getWeight(), c.getSortOrder()))
                .toList();
        ModelView v = saveModel(null, new ModelRequest(newCode, blank(newName) ? src.getName() + " (copy)" : newName,
                src.getDescription(), src.getRatingMin(), src.getRatingMax(), true, crs));
        return v;
    }

    EvaluationModel model(Long id) {
        return models.findById(id).orElseThrow(() -> ApiException.notFound("Evaluation model", id));
    }

    List<EvaluationCriterion> criteriaOf(Long modelId) {
        return criteria.findByModelIdOrderBySortOrderAscIdAsc(modelId);
    }

    private String summary(EvaluationModel m) {
        StringBuilder sb = new StringBuilder(m.getName() + " [" + m.getRatingMin() + "-" + m.getRatingMax() + "]");
        if (m.getId() != null) for (EvaluationCriterion c : criteria.findByModelIdOrderBySortOrderAscIdAsc(m.getId()))
            sb.append("; ").append(c.getTitle()).append(" ").append(c.getWeight()).append('%');
        return sb.toString();
    }

    // ===================================================================== category → model

    public record CategoryAssignment(String employeeCategory, String categoryName, Long modelId, String modelCode,
                                     String modelName, boolean sample) {
    }

    @Transactional(readOnly = true)
    public List<CategoryAssignment> categoryAssignments() {
        Map<String, CategoryModel> byCat = new HashMap<>();
        categoryModels.findAll().forEach(c -> byCat.put(c.getEmployeeCategory(), c));
        Map<Long, EvaluationModel> ms = new HashMap<>();
        models.findAll().forEach(m -> ms.put(m.getId(), m));
        List<CategoryAssignment> out = new ArrayList<>();
        for (MasterDataItem i : masterData.list(MasterDataCategory.EMPLOYEE_CATEGORY.name(), false)) {
            CategoryModel c = byCat.get(i.getCode());
            EvaluationModel m = c == null ? null : ms.get(c.getModelId());
            out.add(new CategoryAssignment(i.getCode(), i.getNameEn(), m == null ? null : m.getId(), m == null ? null : m.getCode(),
                    m == null ? null : m.getName(), m != null && m.isSample()));
        }
        return out;
    }

    @Transactional
    public CategoryAssignment assign(String category, Long modelId) {
        masterData.requireActive(MasterDataCategory.EMPLOYEE_CATEGORY, category, "Employee category");
        if (blank(category)) throw ApiException.badRequest("Employee category is required");
        Optional<CategoryModel> cur = categoryModels.findByEmployeeCategory(category);
        String before = cur.map(c -> String.valueOf(c.getModelId())).orElse(null);
        if (modelId == null) {
            cur.ifPresent(categoryModels::delete);
        } else {
            EvaluationModel m = model(modelId);
            if (!m.isActive()) throw ApiException.rule("INACTIVE", "Model " + m.getCode() + " is inactive");
            requireWeights(m);
            CategoryModel c = cur.orElseGet(CategoryModel::new);
            c.setEmployeeCategory(category);
            c.setModelId(modelId);
            categoryModels.save(c);
        }
        audit.log(AuditAction.CONFIG_CHANGE, "CategoryModel", category, "Evaluation model for category " + category + " set",
                before, modelId == null ? null : String.valueOf(modelId));
        return categoryAssignments().stream().filter(a -> a.employeeCategory().equals(category)).findFirst().orElseThrow();
    }

    /** The model configured for an employee category, if any. */
    Optional<EvaluationModel> modelForCategory(String category) {
        if (category == null) return Optional.empty();
        return categoryModels.findByEmployeeCategory(category).flatMap(c -> models.findById(c.getModelId())).filter(EvaluationModel::isActive);
    }

    void requireWeights(EvaluationModel m) {
        BigDecimal total = criteriaOf(m.getId()).stream().map(EvaluationCriterion::getWeight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(HUNDRED) != 0)
            throw ApiException.rule("WEIGHTS", "Criterion weights of model " + m.getCode() + " must add up to 100 (currently " + total + ")");
    }

    @Override
    @Transactional(readOnly = true)
    public long usageCount(String category, String code) {
        return MasterDataCategory.EMPLOYEE_CATEGORY.name().equals(category) && categoryModels.findByEmployeeCategory(code).isPresent() ? 1 : 0;
    }

    // ===================================================================== helpers

    private static CriterionType parseType(String s) {
        if (blank(s)) return CriterionType.CRITERION;
        try {
            return CriterionType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Criterion type must be CRITERION, KPI or OBJECTIVE");
        }
    }

    private static String code(String c) {
        String code = c == null ? null : c.trim().toUpperCase();
        if (code == null || !CODE.matcher(code).matches()) throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits, '-' or '_'");
        return code;
    }

    static void require(String v, String label) {
        if (v == null || v.isBlank()) throw ApiException.badRequest(label + " is required");
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
