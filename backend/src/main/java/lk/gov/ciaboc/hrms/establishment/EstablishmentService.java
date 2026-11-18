package lk.gov.ciaboc.hrms.establishment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
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

    // ===================================================================== payloads

    public record OrgUnitPayload(String code, String name, String nameSi, String nameTa, OrgUnit.UnitType unitType,
                                 String parentCode, Integer sortOrder) {
    }

    public record DesignationPayload(String code, String name, String nameSi, String nameTa, String grade,
                                     String serviceCategory, String description) {
    }

    public record PositionPayload(String code, String title, Long designationId, String orgUnitCode,
                                  String reportsToPositionCode) {
    }

    public record CadrePayload(Long designationId, String orgUnitCode, Integer approvedQty) {
    }

    public record ChangeRequest(EstablishmentChange.ChangeType changeType, String targetRef, Map<String, Object> payload,
                                LocalDate effectiveDate, String reason) {
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

    public record OrgNode(String code, String name, String nameSi, String nameTa, String unitType, String parentCode,
                          long headcount, int approvedCadre, List<PositionNode> positions, List<OrgNode> children) {
    }

    public record PositionNode(Long id, String code, String title, String designationName, String reportsToPositionCode,
                               boolean filled) {
    }

    /** Hierarchical organization chart as at a date (FR-EST-005, -007). */
    @Transactional(readOnly = true)
    public List<OrgNode> orgChart(LocalDate asOf) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        List<OrgUnit> units = orgUnits.effectiveOn(d);
        Map<Long, String> desNames = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, Designation::getName));
        Map<String, Integer> cadreByUnit = new HashMap<>();
        cadre.effectiveOn(d).forEach(c -> cadreByUnit.merge(c.getOrgUnitCode(), c.getApprovedQty(), Integer::sum));
        Map<String, Long> headByUnit = new HashMap<>();
        for (Object[] r : assignments.occupancy(d)) headByUnit.merge((String) r[1], (Long) r[2], Long::sum);
        Map<String, List<Position>> posByUnit = positions.findAllByOrderByCodeAsc().stream().filter(Position::isActive)
                .collect(Collectors.groupingBy(Position::getOrgUnitCode));
        Set<String> codes = units.stream().map(OrgUnit::getCode).collect(Collectors.toSet());
        Map<String, List<OrgUnit>> byParent = new HashMap<>();
        List<OrgUnit> roots = new ArrayList<>();
        for (OrgUnit u : units) {
            if (u.getParentCode() == null || !codes.contains(u.getParentCode())) roots.add(u);
            else byParent.computeIfAbsent(u.getParentCode(), k -> new ArrayList<>()).add(u);
        }
        return roots.stream().map(u -> node(u, byParent, cadreByUnit, headByUnit, posByUnit, desNames, d)).toList();
    }

    private OrgNode node(OrgUnit u, Map<String, List<OrgUnit>> byParent, Map<String, Integer> cadreByUnit,
                         Map<String, Long> headByUnit, Map<String, List<Position>> posByUnit, Map<Long, String> desNames,
                         LocalDate d) {
        List<PositionNode> pos = posByUnit.getOrDefault(u.getCode(), List.of()).stream()
                .map(p -> new PositionNode(p.getId(), p.getCode(), p.getTitle(), desNames.get(p.getDesignationId()),
                        p.getReportsToPositionCode(), assignments.countInPositionOn(p.getId(), d) > 0))
                .toList();
        List<OrgNode> kids = byParent.getOrDefault(u.getCode(), List.of()).stream()
                .map(c -> node(c, byParent, cadreByUnit, headByUnit, posByUnit, desNames, d)).toList();
        return new OrgNode(u.getCode(), u.getName(), u.getNameSi(), u.getNameTa(), u.getUnitType().name(), u.getParentCode(),
                headByUnit.getOrDefault(u.getCode(), 0L), cadreByUnit.getOrDefault(u.getCode(), 0), pos, kids);
    }

    public record CadreRow(Long designationId, String designationCode, String designationName, String grade,
                           String orgUnitCode, String orgUnitName, int approved, Integer proposed, long occupied,
                           long vacant, long excess) {
    }

    /** Approved, proposed, occupied and vacant cadre (FR-EST-025). */
    @Transactional(readOnly = true)
    public List<CadreRow> cadreSummary(LocalDate asOf, String orgUnitCode) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        Map<String, String> unitNames = orgUnits.effectiveOn(d).stream()
                .collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getName, (a, b) -> a));
        Map<Long, Designation> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, x -> x));
        Map<String, Integer> approved = new LinkedHashMap<>();
        cadre.effectiveOn(d).forEach(c -> approved.put(c.getDesignationId() + "|" + c.getOrgUnitCode(), c.getApprovedQty()));
        Map<String, Long> occupied = new HashMap<>();
        for (Object[] r : assignments.occupancy(d)) occupied.put(r[0] + "|" + r[1], (Long) r[2]);
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
            rows.add(new CadreRow(desId, ds == null ? null : ds.getCode(), ds == null ? "?" : ds.getName(),
                    ds == null ? null : ds.getGrade(), unit, unitNames.getOrDefault(unit, unit), a, proposed.get(k), o,
                    Math.max(0, a - o), Math.max(0, o - a)));
        }
        rows.sort(Comparator.comparing(CadreRow::orgUnitName).thenComparing(CadreRow::designationName));
        return rows;
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
        c.setStatus(EstablishmentChange.Status.PENDING);
        changes.save(c);
        WorkflowInstance wi = workflow.start(PROCESS, "EstablishmentChange", c.getId(), "Establishment change: " + c.getSummary(),
                "/establishment/changes/" + c.getId(), null);
        c.setWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.CREATE, "EstablishmentChange", c.getId(), c.getSummary(), null, c.getPayload());
        return c;
    }

    /** Correct a change that was returned by an approver; the requester then resubmits it via the workflow. */
    @Transactional
    public EstablishmentChange revise(Long id, ChangeRequest r) {
        EstablishmentChange c = change(id);
        if (c.getStatus() != EstablishmentChange.Status.RETURNED)
            throw ApiException.rule("NOT_RETURNED", "Only changes returned for correction can be revised");
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
                int now = cadre.effectiveOn(p.designationId(), p.orgUnitCode(), d).map(CadreAllocation::getApprovedQty).orElse(0);
                c.setCadreDesignationId(p.designationId());
                c.setCadreOrgUnitCode(p.orgUnitCode());
                c.setCadreProposedQty(p.approvedQty());
                return "Set cadre of " + ds.getName() + " in " + p.orgUnitCode() + " from " + now + " to " + p.approvedQty();
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
                        p.unitType() == null ? cur.getUnitType() : p.unitType(), p.parentCode(), p.sortOrder()));
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
    }

    private static void copy(Designation d, DesignationPayload p) {
        d.setName(p.name().trim());
        d.setNameSi(p.nameSi());
        d.setNameTa(p.nameTa());
        d.setGrade(blank(p.grade()) ? null : p.grade());
        d.setServiceCategory(blank(p.serviceCategory()) ? null : p.serviceCategory());
        d.setDescription(p.description());
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
