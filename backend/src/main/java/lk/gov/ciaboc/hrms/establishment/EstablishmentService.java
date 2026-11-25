package lk.gov.ciaboc.hrms.establishment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.contracts.EstablishmentEvents;
import lk.gov.ciaboc.hrms.employee.EmployeeAssignment;
import lk.gov.ciaboc.hrms.employee.EmployeeAssignmentRepository;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EstablishmentService implements WorkflowHandler {
    public static final String PROCESS = "ESTABLISHMENT_CHANGE";

    private static final ObjectMapper PAYLOAD = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();

    private final OrgUnitRepository orgUnits;
    private final DesignationRepository designations;
    private final PositionRepository positions;
    private final CadreAllocationRepository cadre;
    private final EstablishmentChangeRepository changes;
    private final EmployeeAssignmentRepository assignments;
    private final MasterDataService masterData;
    private final AuditService audit;
    private final WorkflowService workflow;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final lk.gov.ciaboc.hrms.employee.EmployeeRepository employees;
    private final PositionCategoryRepository categoryRepo;
    private final CategoryAssignmentRepository categoryAssignments;
    private final CadreControlTotalRepository controlTotals;

    // ===================================================================== payloads

    public record OrgUnitPayload(String code, String name, String nameSi, String nameTa, OrgUnit.UnitType unitType,
                                 String parentCode, Integer sortOrder, String location, String headTitle) {
    }

    public record DesignationPayload(String code, String name, String nameSi, String nameTa, String grade,
                                     String serviceCategory, String description, Integer level, String salaryCode,
                                     Boolean dyingCadre, Boolean statutory, String legalBasis) {
    }

    public record PositionPayload(String code, String title, Long designationId, String orgUnitCode,
                                  String reportsToPositionCode) {
    }

    public record CadrePayload(Long designationId, String orgUnitCode, Integer approvedQty) {
    }

    /**
     * A proposed establishment change. For cadre changes (CCR, REQ 6.2) also the approving authority, reference number
     * (circular or Commission decision) and, when reducing below filled + acting posts, an authorised exception reason.
     * {@code draft = true} saves without submitting (REQ 6.3 Draft state).
     */
    public record ChangeRequest(EstablishmentChange.ChangeType changeType, String targetRef, Map<String, Object> payload,
                                LocalDate effectiveDate, String reason, String approvingAuthority, String referenceNo,
                                String exceptionReason, Boolean draft) {
        public ChangeRequest(EstablishmentChange.ChangeType changeType, String targetRef, Map<String, Object> payload,
                             LocalDate effectiveDate, String reason) {
            this(changeType, targetRef, payload, effectiveDate, reason, null, null, null, false);
        }
    }

    /** Impact of a cadre change on posts (REQ 6.4, 6.5), shown before submission. */
    public record CadreImpact(Long designationId, String designationName, String orgUnitCode, int currentApproved, int proposed,
                              long filled, long acting, int minimumAllowed, boolean belowFilled, boolean dyingCadre,
                              int postsToCreate, List<String> newPositionCodes, int postsToFreeze, int postsToBeAbolished,
                              String blockedReason) {
    }

    // ===================================================================== queries

    @Transactional(readOnly = true)
    public List<OrgUnit> orgUnits(LocalDate asOf) {
        return orgUnits.effectiveOn(asOf == null ? LocalDate.now() : asOf);
    }

    @Transactional(readOnly = true)
    public Optional<OrgUnit> orgUnit(String code, LocalDate asOf) {
        return orgUnits.effectiveOn(code, asOf == null ? LocalDate.now() : asOf);
    }

    @Transactional(readOnly = true)
    public List<OrgUnit> orgUnitHistory(String code) {
        return orgUnits.findByCodeOrderByEffectiveFromDesc(code);
    }

    /** Codes of the unit and all its descendants as at the date (used for data-scope filtering). */
    @Transactional(readOnly = true)
    public Set<String> unitAndDescendants(String code, LocalDate asOf) {
        Map<String, List<String>> children = new HashMap<>();
        for (OrgUnit u : orgUnits(asOf)) {
            if (u.getParentCode() != null) children.computeIfAbsent(u.getParentCode(), k -> new ArrayList<>()).add(u.getCode());
        }
        Set<String> out = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(List.of(code));
        while (!stack.isEmpty()) {
            String c = stack.pop();
            if (out.add(c)) stack.addAll(children.getOrDefault(c, List.of()));
        }
        return out;
    }

    /** The unit and its ancestors, nearest first. */
    @Transactional(readOnly = true)
    public List<String> unitAndAncestors(String code, LocalDate asOf) {
        Map<String, String> parent = orgUnits(asOf).stream().filter(u -> u.getParentCode() != null)
                .collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getParentCode, (a, b) -> a));
        List<String> out = new ArrayList<>();
        String c = code;
        while (c != null && !out.contains(c)) {
            out.add(c);
            c = parent.get(c);
        }
        return out;
    }

    /**
     * One unit on the organization chart (REQ 7.6): head post and holder, own cadre and staff, plus roll-up totals of
     * the unit and all its sub-units. {@code headcount} and {@code approvedCadre} are the unit's own figures.
     */
    public record OrgNode(String code, String name, String nameSi, String nameTa, String unitType, String parentCode,
                          String location, String headTitle, String headPositionCode, Long headEmployeeId,
                          String headEmployeeName, boolean headActing, long headcount, int approvedCadre,
                          long rollupFilled, long rollupActing, int rollupApproved, List<OrgNode> children) {
    }

    /** Hierarchical organization chart as at a date (FR-EST-005, -007, REQ 7.6). Constant number of queries. */
    @Transactional(readOnly = true)
    public List<OrgNode> orgChart(LocalDate asOf) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        List<OrgUnit> units = orgUnits.effectiveOn(d);
        Map<String, Integer> cadreByUnit = new HashMap<>();
        cadre.effectiveOn(d).forEach(c -> cadreByUnit.merge(c.getOrgUnitCode(), c.getApprovedQty(), Integer::sum));
        Map<String, long[]> occByUnit = new HashMap<>(); // [filled, acting]
        for (Object[] r : assignments.occupancyWithActing(d)) {
            long[] v = occByUnit.computeIfAbsent((String) r[1], k -> new long[2]);
            v[Boolean.TRUE.equals(r[2]) ? 1 : 0] += (Long) r[3];
        }
        // Holders of head posts.
        Map<String, Position> headPosts = new HashMap<>();
        for (OrgUnit u : units) {
            if (u.getHeadPositionCode() != null) positions.findByCode(u.getHeadPositionCode()).ifPresent(p -> headPosts.put(u.getCode(), p));
        }
        Map<Long, EmployeeAssignment> holderByPost = new HashMap<>();
        if (!headPosts.isEmpty()) {
            for (EmployeeAssignment a : assignments.findByPositionIdInEffectiveOn(headPosts.values().stream().map(Position::getId).toList(), d))
                holderByPost.put(a.getPositionId(), a);
        }
        Map<Long, String> holderNames = new HashMap<>();
        if (!holderByPost.isEmpty())
            employees.findAllById(holderByPost.values().stream().map(EmployeeAssignment::getEmployeeId).toList())
                    .forEach(e -> holderNames.put(e.getId(), e.getNameWithInitials()));
        Set<String> codes = units.stream().map(OrgUnit::getCode).collect(Collectors.toSet());
        Map<String, List<OrgUnit>> byParent = new HashMap<>();
        List<OrgUnit> roots = new ArrayList<>();
        for (OrgUnit u : units) {
            if (u.getParentCode() == null || !codes.contains(u.getParentCode())) roots.add(u);
            else byParent.computeIfAbsent(u.getParentCode(), k -> new ArrayList<>()).add(u);
        }
        return roots.stream().map(u -> node(u, byParent, cadreByUnit, occByUnit, headPosts, holderByPost, holderNames)).toList();
    }

    private OrgNode node(OrgUnit u, Map<String, List<OrgUnit>> byParent, Map<String, Integer> cadreByUnit,
                         Map<String, long[]> occByUnit, Map<String, Position> headPosts,
                         Map<Long, EmployeeAssignment> holderByPost, Map<Long, String> holderNames) {
        List<OrgNode> kids = byParent.getOrDefault(u.getCode(), List.of()).stream()
                .map(c -> node(c, byParent, cadreByUnit, occByUnit, headPosts, holderByPost, holderNames)).toList();
        long[] own = occByUnit.getOrDefault(u.getCode(), new long[2]);
        int approved = cadreByUnit.getOrDefault(u.getCode(), 0);
        long rf = own[0] + kids.stream().mapToLong(OrgNode::rollupFilled).sum();
        long ra = own[1] + kids.stream().mapToLong(OrgNode::rollupActing).sum();
        int rApproved = approved + kids.stream().mapToInt(OrgNode::rollupApproved).sum();
        Position head = headPosts.get(u.getCode());
        EmployeeAssignment holder = head == null ? null : holderByPost.get(head.getId());
        return new OrgNode(u.getCode(), u.getName(), u.getNameSi(), u.getNameTa(), u.getUnitType().name(), u.getParentCode(),
                u.getLocation(), u.getHeadTitle(), u.getHeadPositionCode(), holder == null ? null : holder.getEmployeeId(),
                holder == null ? null : holderNames.get(holder.getEmployeeId()), holder != null && holder.isActing(),
                own[0] + own[1], approved, rf, ra, rApproved, kids);
    }

    /**
     * One establishment line (designation × unit). Vacant = Approved − Filled − Acting (≥ 0); excess = the reverse
     * (spec §5). {@code occupied} = filled + acting.
     */
    public record CadreRow(Long designationId, String designationCode, String designationName, String grade,
                           String orgUnitCode, String orgUnitName, int approved, Integer proposed, long occupied,
                           long vacant, long excess, long filled, long acting, Integer level, String location,
                           String division, String positionCategory, boolean dyingCadre, boolean statutory) {
    }

    /** Approved, proposed, occupied and vacant cadre (FR-EST-025). */
    @Transactional(readOnly = true)
    public List<CadreRow> cadreSummary(LocalDate asOf, String orgUnitCode) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        List<OrgUnit> unitList = orgUnits.effectiveOn(d);
        Map<String, OrgUnit> unitByCode = unitList.stream().collect(Collectors.toMap(OrgUnit::getCode, u -> u, (a, b) -> a));
        Map<String, String> unitNames = unitList.stream()
                .collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getName, (a, b) -> a));
        Map<Long, Designation> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, x -> x));
        Map<Long, String> categoryOf = new HashMap<>();
        Map<Long, String> categoryNames = categoryRepo.findAll().stream().collect(Collectors.toMap(PositionCategory::getId, PositionCategory::getName));
        categoryAssignments.effectiveOn(d).forEach(a -> categoryOf.put(a.getDesignationId(), categoryNames.get(a.getCategoryId())));
        Map<String, Integer> approved = new LinkedHashMap<>();
        cadre.effectiveOn(d).forEach(c -> approved.put(c.getDesignationId() + "|" + c.getOrgUnitCode(), c.getApprovedQty()));
        Map<String, Long> occupied = new HashMap<>();
        Map<String, long[]> split = new HashMap<>();
        for (Object[] r : assignments.occupancyWithActing(d)) {
            String k = r[0] + "|" + r[1];
            occupied.merge(k, (Long) r[3], Long::sum);
            split.computeIfAbsent(k, x -> new long[2])[Boolean.TRUE.equals(r[2]) ? 1 : 0] += (Long) r[3];
        }
        Map<String, Integer> proposed = new HashMap<>();
        changes.findByChangeTypeAndStatusIn(EstablishmentChange.ChangeType.CADRE_SET,
                        List.of(EstablishmentChange.Status.PENDING, EstablishmentChange.Status.RETURNED))
                .forEach(c -> proposed.put(c.getCadreDesignationId() + "|" + c.getCadreOrgUnitCode(), c.getCadreProposedQty()));
        Set<String> keys = new LinkedHashSet<>(approved.keySet());
        keys.addAll(occupied.keySet());
        keys.addAll(proposed.keySet());
        Set<String> scope = orgUnitCode == null || orgUnitCode.isBlank() ? null : unitAndDescendants(orgUnitCode, d);
        List<CadreRow> rows = new ArrayList<>();
        for (String k : keys) {
            String[] p = k.split("\\|", 2);
            Long desId = Long.valueOf(p[0]);
            String unit = p[1];
            if (scope != null && !scope.contains(unit)) continue;
            Designation ds = des.get(desId);
            int a = approved.getOrDefault(k, 0);
            long o = occupied.getOrDefault(k, 0L);
            long[] fa = split.getOrDefault(k, new long[2]);
            OrgUnit ou = unitByCode.get(unit);
            rows.add(new CadreRow(desId, ds == null ? null : ds.getCode(), ds == null ? "?" : ds.getName(),
                    ds == null ? null : ds.getGrade(), unit, unitNames.getOrDefault(unit, unit), a, proposed.get(k), o,
                    Math.max(0, a - o), Math.max(0, o - a), fa[0], fa[1], ds == null ? null : ds.getLevel(),
                    ou == null ? null : ou.getLocation(), divisionOf(unit, unitByCode), categoryOf.get(desId),
                    ds != null && ds.isDyingCadre(), ds != null && ds.isStatutory()));
        }
        rows.sort(Comparator.comparing(CadreRow::orgUnitName).thenComparing(CadreRow::designationName));
        return rows;
    }

    /** Name of the top-level group a unit rolls up to (the child of the root), used to group cadre by division. */
    private static String divisionOf(String unit, Map<String, OrgUnit> byCode) {
        OrgUnit u = byCode.get(unit);
        if (u == null) return unit;
        OrgUnit cur = u;
        Set<String> guard = new HashSet<>();
        while (cur.getParentCode() != null && byCode.containsKey(cur.getParentCode()) && guard.add(cur.getCode())) {
            OrgUnit parent = byCode.get(cur.getParentCode());
            if (parent.getParentCode() == null) return cur.getName(); // cur is a child of the root
            cur = parent;
        }
        return cur.getName();
    }

    public record ReconciliationLine(String scope, String refCode, String label, int controlTotal, long matrixTotal, long difference) {
    }

    public record Reconciliation(int organizationControl, long matrixTotal, long statutoryExcluded, List<ReconciliationLine> units,
                                 List<ReconciliationLine> designations, List<ReconciliationLine> unitsWithoutControl) {
    }

    /**
     * REQ 6.11: compares the cadre matrix summed by designation and by unit with the control totals of the source
     * tables and flags every difference. Statutory posts (the DG) are excluded from the 971-post total.
     */
    @Transactional(readOnly = true)
    public Reconciliation reconciliation(LocalDate asOf) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        Map<Long, Designation> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, x -> x));
        Map<String, Long> byUnit = new TreeMap<>();
        Map<String, Long> byDes = new TreeMap<>();
        long total = 0, statutory = 0;
        for (CadreAllocation c : cadre.effectiveOn(d)) {
            Designation ds = des.get(c.getDesignationId());
            if (ds != null && ds.isStatutory()) {
                statutory += c.getApprovedQty();
                continue;
            }
            byUnit.merge(c.getOrgUnitCode(), (long) c.getApprovedQty(), Long::sum);
            if (ds != null) byDes.merge(ds.getCode(), (long) c.getApprovedQty(), Long::sum);
            total += c.getApprovedQty();
        }
        List<CadreControlTotal> controls = controlTotals.findAll();
        int orgControl = controls.stream().filter(c -> "ORGANIZATION".equals(c.getScope())).mapToInt(CadreControlTotal::getApprovedTotal).sum();
        List<ReconciliationLine> units = new ArrayList<>();
        List<ReconciliationLine> dess = new ArrayList<>();
        Set<String> controlledUnits = new HashSet<>();
        for (CadreControlTotal c : controls) {
            if ("UNIT".equals(c.getScope())) {
                controlledUnits.add(c.getRefCode());
                long m = byUnit.getOrDefault(c.getRefCode(), 0L);
                units.add(new ReconciliationLine("UNIT", c.getRefCode(), c.getLabel(), c.getApprovedTotal(), m, m - c.getApprovedTotal()));
            } else if ("DESIGNATION".equals(c.getScope())) {
                long m = byDes.getOrDefault(c.getRefCode(), 0L);
                dess.add(new ReconciliationLine("DESIGNATION", c.getRefCode(), c.getLabel(), c.getApprovedTotal(), m, m - c.getApprovedTotal()));
            }
        }
        Map<String, String> unitNames = orgUnits.effectiveOn(d).stream().collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getName, (a, b) -> a));
        List<ReconciliationLine> orphan = new ArrayList<>();
        byUnit.forEach((u, m) -> {
            if (!controlledUnits.contains(u)) orphan.add(new ReconciliationLine("UNIT", u, unitNames.getOrDefault(u, u), 0, m, m));
        });
        return new Reconciliation(orgControl, total, statutory, units, dess, orphan);
    }

    @Transactional(readOnly = true)
    public List<CadreAllocation> cadreHistory(Long designationId, String orgUnitCode) {
        return cadre.findByDesignationIdAndOrgUnitCodeOrderByEffectiveFromDesc(designationId, orgUnitCode);
    }

    // ===================================================================== change submission

    @Transactional
    public EstablishmentChange submit(ChangeRequest r) {
        if (r.changeType() == null) throw ApiException.badRequest("Change type is required");
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason for the change is required");
        EstablishmentChange c = new EstablishmentChange();
        c.setChangeType(r.changeType());
        fill(c, r);
        boolean draft = Boolean.TRUE.equals(r.draft());
        c.setStatus(draft ? EstablishmentChange.Status.DRAFT : EstablishmentChange.Status.PENDING);
        changes.save(c);
        audit.log(AuditAction.CREATE, "EstablishmentChange", c.getId(), (draft ? "Draft: " : "") + c.getSummary(), null, c.getPayload());
        if (!draft) startApproval(c);
        return c;
    }

    /** Submits a saved draft for approval (Draft → Pending approval, REQ 6.3). */
    @Transactional
    public EstablishmentChange submitDraft(Long id) {
        EstablishmentChange c = change(id);
        if (c.getStatus() != EstablishmentChange.Status.DRAFT) throw ApiException.rule("NOT_DRAFT", "Only drafts can be submitted");
        if (!CurrentUser.username().equals(c.getCreatedBy())) throw ApiException.forbidden("Only the author can submit this draft");
        validate(c, true);
        c.setStatus(EstablishmentChange.Status.PENDING);
        startApproval(c);
        return c;
    }

    @Transactional
    public EstablishmentChange discardDraft(Long id) {
        EstablishmentChange c = change(id);
        if (c.getStatus() != EstablishmentChange.Status.DRAFT) throw ApiException.rule("NOT_DRAFT", "Only drafts can be discarded");
        if (!CurrentUser.username().equals(c.getCreatedBy())) throw ApiException.forbidden("Only the author can discard this draft");
        c.setStatus(EstablishmentChange.Status.CANCELLED);
        audit.log(AuditAction.CANCEL, "EstablishmentChange", id, "Draft discarded: " + c.getSummary());
        return c;
    }

    @Transactional
    public EstablishmentChange attach(Long id, Long fileId) {
        EstablishmentChange c = change(id);
        if (c.getStatus() != EstablishmentChange.Status.DRAFT && c.getStatus() != EstablishmentChange.Status.RETURNED
                && c.getStatus() != EstablishmentChange.Status.PENDING)
            throw ApiException.rule("CLOSED", "Documents can only be attached to open requests");
        c.setAttachmentFileId(fileId);
        audit.log(AuditAction.UPLOAD, "EstablishmentChange", id, "Approval document attached");
        return c;
    }

    private void startApproval(EstablishmentChange c) {
        String kind = c.getChangeType() == EstablishmentChange.ChangeType.CADRE_SET ? "Cadre Change Request" : "Establishment change";
        WorkflowInstance wi = workflow.start(PROCESS, "EstablishmentChange", c.getId(), kind + ": " + c.getSummary(),
                "/establishment/changes/" + c.getId(), null);
        c.setWorkflowInstanceId(wi.getId());
    }

    /** Validates a proposed change without saving it and returns its summary and, for cadre changes, the impact preview. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(ChangeRequest r) {
        if (r.changeType() == null) throw ApiException.badRequest("Change type is required");
        EstablishmentChange c = new EstablishmentChange();
        c.setChangeType(r.changeType());
        c.setEffectiveDate(r.effectiveDate() == null ? LocalDate.now() : r.effectiveDate());
        c.setTargetRef(r.targetRef());
        c.setPayload(write(r.payload() == null ? Map.of() : r.payload()));
        c.setExceptionReason(r.exceptionReason());
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.changeType() == EstablishmentChange.ChangeType.CADRE_SET) {
            CadrePayload p = read(c.getPayload(), CadrePayload.class);
            if (p.designationId() == null || p.orgUnitCode() == null || p.approvedQty() == null)
                throw ApiException.badRequest("Designation, unit and quantity are required");
            out.put("impact", cadreImpact(p.designationId(), p.orgUnitCode(), p.approvedQty(), c.getEffectiveDate()));
        }
        try {
            out.put("summary", validate(c, true));
            out.put("valid", true);
        } catch (ApiException e) {
            out.put("valid", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** Computes posts affected by setting a cadre line to {@code newQty} from date {@code d}. */
    @Transactional(readOnly = true)
    public CadreImpact cadreImpact(Long designationId, String unitCode, int newQty, LocalDate d) {
        Designation des = designations.findById(designationId).orElseThrow(() -> ApiException.notFound("Designation", designationId));
        int current = cadre.effectiveOn(designationId, unitCode, d).map(CadreAllocation::getApprovedQty).orElse(0);
        long[] fa = filledActing(designationId, unitCode, d);
        long occupied = fa[0] + fa[1];
        int min = (int) occupied;
        String blocked = null;
        if (newQty < 0) blocked = "Cadre cannot be negative";
        else if (des.isDyingCadre() && newQty > current)
            blocked = des.getName() + " is a dying cadre: no increase or fresh recruitment is allowed";
        int create = Math.max(0, newQty - current);
        int removed = Math.max(0, current - newQty);
        int vacantNow = (int) Math.max(0, current - occupied);
        int freeze = Math.min(removed, vacantNow);
        int abolish = removed - freeze;
        List<String> codes = create == 0 ? List.of() : nextPostCodes(unitCode, des.getCode(), create);
        return new CadreImpact(designationId, des.getName(), unitCode, current, newQty, fa[0], fa[1], min, newQty < min,
                des.isDyingCadre(), create, codes, freeze, abolish, blocked);
    }

    /** [filled, acting] holders of a designation in a unit on a date. */
    private long[] filledActing(Long designationId, String unitCode, LocalDate d) {
        long filled = 0, acting = 0;
        for (Object[] r : assignments.occupancySplit(designationId, unitCode, d)) {
            if (Boolean.TRUE.equals(r[0])) acting += (Long) r[1];
            else filled += (Long) r[1];
        }
        return new long[]{filled, acting};
    }

    private List<String> nextPostCodes(String unitCode, String desCode, int n) {
        String prefix = unitCode + "/" + desCode + "/";
        int max = 0;
        for (Position p : positions.findByCodeStartingWith(prefix)) {
            try {
                max = Math.max(max, Integer.parseInt(p.getCode().substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // manually coded post; not part of the sequence
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(prefix + String.format("%03d", max + i));
        return out;
    }

    /** Correct a change that was returned by an approver (or a saved draft); returned ones are resubmitted via the workflow. */
    @Transactional
    public EstablishmentChange revise(Long id, ChangeRequest r) {
        EstablishmentChange c = change(id);
        if (c.getStatus() != EstablishmentChange.Status.RETURNED && c.getStatus() != EstablishmentChange.Status.DRAFT)
            throw ApiException.rule("NOT_RETURNED", "Only drafts and changes returned for correction can be revised");
        if (!CurrentUser.username().equals(c.getCreatedBy())) throw ApiException.forbidden("Only the submitter can revise this change");
        String before = c.getPayload();
        fill(c, r);
        audit.log(AuditAction.UPDATE, "EstablishmentChange", id, "Revised: " + c.getSummary(), before, c.getPayload());
        return c;
    }

    private void fill(EstablishmentChange c, ChangeRequest r) {
        if (r.reason() == null || r.reason().isBlank()) throw ApiException.badRequest("A reason for the change is required");
        c.setEffectiveDate(r.effectiveDate() == null ? LocalDate.now() : r.effectiveDate());
        c.setReason(r.reason().trim());
        c.setTargetRef(r.targetRef());
        Map<String, Object> payload = r.payload() == null ? Map.of() : r.payload();
        c.setPayload(write(payload));
        c.setApprovingAuthority(blank(r.approvingAuthority()) ? null : r.approvingAuthority().trim());
        c.setReferenceNo(blank(r.referenceNo()) ? null : r.referenceNo().trim());
        c.setExceptionReason(blank(r.exceptionReason()) ? null : r.exceptionReason().trim());
        c.setSummary(validate(c, true));
    }

    @Transactional(readOnly = true)
    public List<EstablishmentChange> changes(EstablishmentChange.Status status) {
        return status == null ? changes.findAllByOrderByIdDesc() : changes.findByStatusOrderByIdDesc(status);
    }

    public EstablishmentChange change(Long id) {
        return changes.findById(id).orElseThrow(() -> ApiException.notFound("Establishment change", id));
    }

    // ===================================================================== workflow callbacks

    @Override
    public String processCode() {
        return PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        EstablishmentChange c = change(wi.getEntityId());
        validate(c, false);
        apply(c);
        c.setStatus(EstablishmentChange.Status.APPLIED);
        c.setAppliedAt(Instant.now());
        audit.log(AuditAction.UPDATE, "EstablishmentChange", c.getId(), "Applied: " + c.getSummary());
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        change(wi.getEntityId()).setStatus(EstablishmentChange.Status.REJECTED);
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        change(wi.getEntityId()).setStatus(EstablishmentChange.Status.RETURNED);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        change(wi.getEntityId()).setStatus(EstablishmentChange.Status.PENDING);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        change(wi.getEntityId()).setStatus(EstablishmentChange.Status.CANCELLED);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        EstablishmentChange c = change(wi.getEntityId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Change type", c.getChangeType().name().replace('_', ' '));
        m.put("Summary", c.getSummary());
        m.put("Effective date", c.getEffectiveDate());
        m.put("Reason", c.getReason());
        Map<?, ?> payload = read(c.getPayload(), Map.class);
        payload.forEach((k, v) -> m.put("New " + k, v));
        return m;
    }

    // ===================================================================== validation & application

    /** Validates a change and returns a human-readable summary. {@code atSubmission} relaxes nothing but wording. */
    private String validate(EstablishmentChange c, boolean atSubmission) {
        LocalDate d = c.getEffectiveDate();
        switch (c.getChangeType()) {
            case ORG_UNIT_CREATE -> {
                OrgUnitPayload p = read(c.getPayload(), OrgUnitPayload.class);
                requireCode(p.code());
                require(p.name(), "Unit name");
                if (p.unitType() == null) throw ApiException.badRequest("Unit type is required");
                if (orgUnits.existsByCode(p.code())) throw ApiException.conflict("Org unit code " + p.code() + " already exists");
                checkParent(p.code(), p.parentCode(), d);
                return "Create " + p.unitType() + " '" + p.name() + "' (" + p.code() + ")";
            }
            case ORG_UNIT_UPDATE -> {
                OrgUnit cur = currentUnit(c.getTargetRef(), d);
                OrgUnitPayload p = read(c.getPayload(), OrgUnitPayload.class);
                require(p.name(), "Unit name");
                checkParent(cur.getCode(), p.parentCode(), d);
                return "Update unit " + cur.getCode() + " → '" + p.name() + "'"
                        + (Objects.equals(p.parentCode(), cur.getParentCode()) ? "" : " under " + p.parentCode());
            }
            case ORG_UNIT_DEACTIVATE -> {
                OrgUnit cur = currentUnit(c.getTargetRef(), d);
                boolean hasChildren = orgUnits.effectiveOn(d).stream().anyMatch(u -> cur.getCode().equals(u.getParentCode()));
                if (hasChildren) throw ApiException.rule("HAS_CHILDREN", "Unit " + cur.getCode() + " still has active sub-units");
                if (assignments.countInUnitOn(cur.getCode(), d) > 0)
                    throw ApiException.rule("HAS_EMPLOYEES", "Unit " + cur.getCode() + " still has employees assigned");
                if (positions.countByOrgUnitCodeAndActiveTrue(cur.getCode()) > 0)
                    throw ApiException.rule("HAS_POSITIONS", "Deactivate the unit's positions first");
                return "Deactivate unit " + cur.getCode() + " '" + cur.getName() + "'";
            }
            case DESIGNATION_CREATE -> {
                DesignationPayload p = read(c.getPayload(), DesignationPayload.class);
                requireCode(p.code());
                require(p.name(), "Designation name");
                if (designations.findByCode(p.code()).isPresent()) throw ApiException.conflict("Designation code " + p.code() + " already exists");
                masterData.requireActive(MasterDataCategory.GRADE, p.grade(), "Grade");
                masterData.requireActive(MasterDataCategory.SERVICE_CATEGORY, p.serviceCategory(), "Service category");
                return "Create designation '" + p.name() + "' (" + p.code() + ")";
            }
            case DESIGNATION_UPDATE -> {
                Designation ds = designation(c.getTargetRef());
                DesignationPayload p = read(c.getPayload(), DesignationPayload.class);
                require(p.name(), "Designation name");
                masterData.requireActive(MasterDataCategory.GRADE, p.grade(), "Grade");
                masterData.requireActive(MasterDataCategory.SERVICE_CATEGORY, p.serviceCategory(), "Service category");
                return "Update designation " + ds.getCode() + " → '" + p.name() + "'";
            }
            case DESIGNATION_DEACTIVATE -> {
                Designation ds = designation(c.getTargetRef());
                if (assignments.countWithDesignationOn(ds.getId(), d) > 0)
                    throw ApiException.rule("IN_USE", "Employees still hold designation " + ds.getCode());
                if (positions.countByDesignationIdAndActiveTrue(ds.getId()) > 0)
                    throw ApiException.rule("IN_USE", "Active positions still use designation " + ds.getCode());
                return "Deactivate designation " + ds.getCode() + " '" + ds.getName() + "'";
            }
            case POSITION_CREATE -> {
                PositionPayload p = read(c.getPayload(), PositionPayload.class);
                requireCode(p.code());
                require(p.title(), "Position title");
                if (positions.findByCode(p.code()).isPresent()) throw ApiException.conflict("Position code " + p.code() + " already exists");
                checkPositionRefs(p, d, null);
                return "Create position '" + p.title() + "' (" + p.code() + ") in " + p.orgUnitCode();
            }
            case POSITION_UPDATE -> {
                Position pos = position(c.getTargetRef());
                PositionPayload p = read(c.getPayload(), PositionPayload.class);
                require(p.title(), "Position title");
                checkPositionRefs(p, d, pos.getCode());
                return "Update position " + pos.getCode() + " → '" + p.title() + "'";
            }
            case POSITION_DEACTIVATE -> {
                Position pos = position(c.getTargetRef());
                if (assignments.countInPositionOn(pos.getId(), d) > 0)
                    throw ApiException.rule("IN_USE", "Position " + pos.getCode() + " is currently filled");
                return "Deactivate position " + pos.getCode() + " '" + pos.getTitle() + "'";
            }
            case CADRE_SET -> {
                CadrePayload p = read(c.getPayload(), CadrePayload.class);
                if (p.designationId() == null || p.orgUnitCode() == null || p.approvedQty() == null)
                    throw ApiException.badRequest("Designation, unit and quantity are required");
                if (p.approvedQty() < 0) throw ApiException.badRequest("Cadre quantity cannot be negative");
                Designation ds = designations.findById(p.designationId()).filter(Designation::isActive)
                        .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Designation is not active"));
                currentUnit(p.orgUnitCode(), d);
                CadreImpact impact = cadreImpact(p.designationId(), p.orgUnitCode(), p.approvedQty(), d);
                if (impact.blockedReason() != null) throw ApiException.rule("CADRE_BLOCKED", impact.blockedReason());
                if (impact.proposed() == impact.currentApproved())
                    throw ApiException.badRequest("The proposed cadre equals the current approved cadre (" + impact.currentApproved() + ")");
                if (impact.belowFilled()) {
                    // REQ 6.4: never below filled + acting unless an authorised exception with a reason is recorded.
                    if (blank(c.getExceptionReason()))
                        throw ApiException.rule("CADRE_BELOW_FILLED", impact.filled() + " post(s) are filled and " + impact.acting()
                                + " acting; the allowed minimum is " + impact.minimumAllowed()
                                + ". Record an authorised exception with a reason to go lower.");
                    if (atSubmission && !CurrentUser.get().has(lk.gov.ciaboc.hrms.security.Permission.EST_CADRE_EXCEPTION)
                            && c.getId() == null)
                        throw ApiException.forbidden("Only an authorised officer can record a cadre-reduction exception");
                }
                c.setCadreDesignationId(p.designationId());
                c.setCadreOrgUnitCode(p.orgUnitCode());
                c.setCadreProposedQty(p.approvedQty());
                c.setCadreOldQty(impact.currentApproved());
                return "Set cadre of " + ds.getName() + " in " + p.orgUnitCode() + " from " + impact.currentApproved()
                        + " to " + p.approvedQty();
            }
        }
        throw ApiException.badRequest("Unsupported change type");
    }

    private void apply(EstablishmentChange c) {
        LocalDate d = c.getEffectiveDate();
        switch (c.getChangeType()) {
            case ORG_UNIT_CREATE -> {
                OrgUnitPayload p = read(c.getPayload(), OrgUnitPayload.class);
                OrgUnit u = new OrgUnit();
                u.setCode(p.code());
                copy(u, p);
                u.setEffectiveFrom(d);
                u.setChangeId(c.getId());
                orgUnits.save(u);
            }
            case ORG_UNIT_UPDATE -> {
                OrgUnit cur = currentUnit(c.getTargetRef(), d);
                OrgUnitPayload p = read(c.getPayload(), OrgUnitPayload.class);
                OrgUnit target = newVersion(cur, d);
                copy(target, new OrgUnitPayload(cur.getCode(), p.name(), p.nameSi(), p.nameTa(),
                        p.unitType() == null ? cur.getUnitType() : p.unitType(), p.parentCode(), p.sortOrder(),
                        p.location() == null ? cur.getLocation() : p.location(), p.headTitle() == null ? cur.getHeadTitle() : p.headTitle()));
                target.setChangeId(c.getId());
                orgUnits.save(target);
            }
            case ORG_UNIT_DEACTIVATE -> {
                OrgUnit cur = currentUnit(c.getTargetRef(), d);
                if (cur.getEffectiveFrom().equals(d)) orgUnits.delete(cur);
                else cur.setEffectiveTo(d);
            }
            case DESIGNATION_CREATE -> {
                DesignationPayload p = read(c.getPayload(), DesignationPayload.class);
                Designation ds = new Designation();
                ds.setCode(p.code());
                copy(ds, p);
                designations.save(ds);
            }
            case DESIGNATION_UPDATE -> copy(designation(c.getTargetRef()), read(c.getPayload(), DesignationPayload.class));
            case DESIGNATION_DEACTIVATE -> designation(c.getTargetRef()).setActive(false);
            case POSITION_CREATE -> {
                PositionPayload p = read(c.getPayload(), PositionPayload.class);
                Position pos = new Position();
                pos.setCode(p.code());
                copy(pos, p);
                pos.setActivatedOn(d);
                positions.save(pos);
            }
            case POSITION_UPDATE -> copy(position(c.getTargetRef()), read(c.getPayload(), PositionPayload.class));
            case POSITION_DEACTIVATE -> {
                Position pos = position(c.getTargetRef());
                pos.setActive(false);
                pos.setDeactivatedOn(d);
            }
            case CADRE_SET -> {
                CadrePayload p = read(c.getPayload(), CadrePayload.class);
                CadreImpact impact = cadreImpact(p.designationId(), p.orgUnitCode(), p.approvedQty(), d);
                List<String> frozen = new ArrayList<>();
                List<String> toAbolish = new ArrayList<>();
                // REQ 6.5: increases create vacant posts with IDs; decreases freeze vacant posts first, then mark
                // filled excess posts "to be abolished on vacation".
                for (String code : impact.newPositionCodes()) {
                    Designation des = designations.findById(p.designationId()).orElseThrow();
                    Position pos = new Position();
                    pos.setCode(code);
                    pos.setTitle(des.getName());
                    pos.setDesignationId(des.getId());
                    pos.setOrgUnitCode(p.orgUnitCode());
                    orgUnits.effectiveOn(p.orgUnitCode(), d).map(OrgUnit::getHeadPositionCode).ifPresent(pos::setReportsToPositionCode);
                    pos.setActivatedOn(d);
                    pos.setChangeId(c.getId());
                    positions.save(pos);
                }
                if (impact.postsToFreeze() + impact.postsToBeAbolished() > 0) {
                    List<Position> live = positions.findByDesignationIdAndOrgUnitCodeAndActiveTrueAndPostStatus(p.designationId(), p.orgUnitCode(), "ACTIVE");
                    live.sort(Comparator.comparing(Position::getCode).reversed());
                    List<Position> vacant = live.stream().filter(x -> assignments.countInPositionOn(x.getId(), d) == 0).toList();
                    List<Position> held = live.stream().filter(x -> assignments.countInPositionOn(x.getId(), d) > 0).toList();
                    int f = impact.postsToFreeze();
                    for (Position x : vacant) {
                        if (f == 0) break;
                        x.setPostStatus("FROZEN");
                        x.setActive(false);
                        x.setDeactivatedOn(d);
                        x.setChangeId(c.getId());
                        frozen.add(x.getCode());
                        f--;
                    }
                    int a = impact.postsToBeAbolished();
                    // Posts held by holders first; if holders are not linked to posts, remaining vacant post records.
                    List<Position> pool = new ArrayList<>(held);
                    vacant.stream().filter(x -> "ACTIVE".equals(x.getPostStatus())).forEach(pool::add);
                    for (Position x : pool) {
                        if (a == 0) break;
                        x.setPostStatus("TO_BE_ABOLISHED");
                        x.setChangeId(c.getId());
                        toAbolish.add(x.getCode());
                        a--;
                    }
                }
                cadre.effectiveOn(p.designationId(), p.orgUnitCode(), d).ifPresent(cur -> {
                    if (cur.getEffectiveFrom().equals(d)) cadre.delete(cur);
                    else cur.setEffectiveTo(d);
                });
                cadre.flush();
                if (p.approvedQty() > 0) {
                    CadreAllocation a = new CadreAllocation();
                    a.setDesignationId(p.designationId());
                    a.setOrgUnitCode(p.orgUnitCode());
                    a.setApprovedQty(p.approvedQty());
                    a.setEffectiveFrom(d);
                    a.setChangeId(c.getId());
                    cadre.save(a);
                }
                events.publishEvent(new EstablishmentEvents.CadreChanged(c.getId(), p.designationId(), p.orgUnitCode(),
                        impact.currentApproved(), p.approvedQty(), d, impact.newPositionCodes(), frozen, toAbolish));
            }
        }
    }

    /** Closes the current version at d and returns the new version (or the same row if it starts on d). */
    private OrgUnit newVersion(OrgUnit cur, LocalDate d) {
        if (cur.getEffectiveFrom().equals(d)) return cur;
        if (cur.getEffectiveFrom().isAfter(d)) throw ApiException.rule("EFFECTIVE_DATE", "Effective date precedes the current version");
        cur.setEffectiveTo(d);
        orgUnits.saveAndFlush(cur);
        OrgUnit n = new OrgUnit();
        n.setCode(cur.getCode());
        n.setEffectiveFrom(d);
        n.setEffectiveTo(null);
        return n;
    }

    private static void copy(OrgUnit u, OrgUnitPayload p) {
        u.setName(p.name().trim());
        u.setNameSi(p.nameSi());
        u.setNameTa(p.nameTa());
        u.setUnitType(p.unitType());
        u.setParentCode(blank(p.parentCode()) ? null : p.parentCode());
        u.setSortOrder(p.sortOrder() == null ? 0 : p.sortOrder());
        u.setLocation(blank(p.location()) ? null : p.location());
        u.setHeadTitle(blank(p.headTitle()) ? null : p.headTitle().trim());
    }

    private static void copy(Designation d, DesignationPayload p) {
        d.setName(p.name().trim());
        d.setNameSi(p.nameSi());
        d.setNameTa(p.nameTa());
        d.setGrade(blank(p.grade()) ? null : p.grade());
        d.setServiceCategory(blank(p.serviceCategory()) ? null : p.serviceCategory());
        d.setDescription(p.description());
        d.setLevel(p.level());
        d.setSalaryCode(blank(p.salaryCode()) ? null : p.salaryCode());
        d.setDyingCadre(Boolean.TRUE.equals(p.dyingCadre()));
        d.setStatutory(Boolean.TRUE.equals(p.statutory()));
        d.setLegalBasis(blank(p.legalBasis()) ? null : p.legalBasis());
    }

    private static void copy(Position pos, PositionPayload p) {
        pos.setTitle(p.title().trim());
        pos.setDesignationId(p.designationId());
        pos.setOrgUnitCode(p.orgUnitCode());
        pos.setReportsToPositionCode(blank(p.reportsToPositionCode()) ? null : p.reportsToPositionCode());
    }

    private void checkParent(String code, String parentCode, LocalDate d) {
        if (blank(parentCode)) return;
        currentUnit(parentCode, d);
        // Reject cycles: the new parent must not be the unit itself or one of its descendants.
        if (unitAndDescendants(code, d).contains(parentCode))
            throw ApiException.rule("CYCLE", "A unit cannot be placed under itself or one of its sub-units");
    }

    private void checkPositionRefs(PositionPayload p, LocalDate d, String selfCode) {
        if (p.designationId() == null || designations.findById(p.designationId()).filter(Designation::isActive).isEmpty())
            throw ApiException.rule("INVALID_REFERENCE", "An active designation is required");
        if (blank(p.orgUnitCode())) throw ApiException.badRequest("Org unit is required");
        currentUnit(p.orgUnitCode(), d);
        if (!blank(p.reportsToPositionCode())) {
            if (p.reportsToPositionCode().equals(selfCode)) throw ApiException.rule("CYCLE", "A position cannot report to itself");
            positions.findByCode(p.reportsToPositionCode()).filter(Position::isActive)
                    .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Reports-to position not found"));
        }
    }

    private OrgUnit currentUnit(String code, LocalDate d) {
        if (blank(code)) throw ApiException.badRequest("Org unit code is required");
        return orgUnits.effectiveOn(code, d)
                .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Org unit " + code + " is not active on " + d));
    }

    private Designation designation(String ref) {
        return designations.findById(parseId(ref)).orElseThrow(() -> ApiException.notFound("Designation", ref));
    }

    private Position position(String ref) {
        return positions.findById(parseId(ref)).orElseThrow(() -> ApiException.notFound("Position", ref));
    }

    private static Long parseId(String ref) {
        try {
            return Long.valueOf(ref);
        } catch (Exception e) {
            throw ApiException.badRequest("A valid target id is required");
        }
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches("^[A-Z0-9_-]{1,40}$"))
            throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits, '-' or '_'");
    }

    private static void require(String v, String label) {
        if (blank(v)) throw ApiException.badRequest(label + " is required");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String write(Object o) {
        try {
            return PAYLOAD.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Invalid payload");
        }
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return PAYLOAD.readValue(json, type);
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid change payload: " + e.getMessage());
        }
    }
}
