package lk.gov.ciaboc.hrms.employee;

import lk.gov.ciaboc.hrms.establishment.Designation;
import lk.gov.ciaboc.hrms.establishment.DesignationRepository;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.establishment.OrgUnit;
import lk.gov.ciaboc.hrms.establishment.Position;
import lk.gov.ciaboc.hrms.establishment.PositionRepository;
import lk.gov.ciaboc.hrms.masterdata.MasterDataUsageChecker;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.workflow.OrgDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves workflow approvers from the live organization structure, and guards master data in use. */
@Component
@RequiredArgsConstructor
public class EmployeeOrgDirectory implements OrgDirectory, MasterDataUsageChecker {
    private final EmployeeRepository employees;
    private final EmployeeAssignmentRepository assignments;
    private final PositionRepository positions;
    private final DesignationRepository designations;
    private final AppUserRepository users;
    private final EstablishmentService establishment;
    private final EmployeeDocumentRepository documents;

    @Override
    @Transactional(readOnly = true)
    public String orgUnitOfEmployee(Long employeeId) {
        return employees.findById(employeeId).map(Employee::getOrgUnitCode).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> userIdsHoldingPosition(String positionCode) {
        Set<Long> out = new LinkedHashSet<>();
        positions.findByCode(positionCode).ifPresent(pos -> {
            for (Employee e : holders(pos.getId())) {
                users.findByEmployeeId(e.getId()).filter(AppUser::isActive).ifPresent(u -> out.add(u.getId()));
            }
        });
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> divisionHeadUserIdsFor(String orgUnitCode, Long excludeUserId) {
        List<String> chain = establishment.unitAndAncestors(orgUnitCode, LocalDate.now());
        List<AppUser> heads = users.findActiveByRole("DIVISION_HEAD");
        // Nearest level that has a head wins, so a section's request goes to its own head first.
        for (String unit : chain) {
            Set<Long> atLevel = new LinkedHashSet<>();
            for (AppUser u : heads) {
                if (u.getEmployeeId() == null || u.getId().equals(excludeUserId)) continue;
                String hisUnit = orgUnitOfEmployee(u.getEmployeeId());
                if (unit.equals(hisUnit)) atLevel.add(u.getId());
            }
            if (!atLevel.isEmpty()) return atLevel;
        }
        return Set.of();
    }

    // ------------------------------------------------------------------ hierarchy for workflow routing (REQ 10.1-10.5)

    @Override
    @Transactional(readOnly = true)
    public Optional<PostInfo> post(String positionCode) {
        if (positionCode == null) return Optional.empty();
        return positions.findByCode(positionCode).map(p -> {
            Map<String, OrgUnit> units = unitsByCode();
            Set<String> headPosts = headPosts(units);
            List<String> names = holders(p.getId()).stream().map(Employee::getFullName).toList();
            return toInfo(p, designations.findById(p.getDesignationId()).orElse(null), units, headPosts, names);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostInfo> posts() {
        Map<String, OrgUnit> units = unitsByCode();
        Set<String> headPosts = headPosts(units);
        Map<Long, Designation> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, Function.identity()));
        Map<Long, List<String>> holderNames = new HashMap<>();
        for (Employee e : employees.findAll((root, q, cb) -> cb.isNotNull(root.get("positionId")))) {
            if (isActive(e)) holderNames.computeIfAbsent(e.getPositionId(), k -> new ArrayList<>()).add(e.getFullName());
        }
        List<PostInfo> out = new ArrayList<>();
        for (Position p : positions.findAllByOrderByCodeAsc()) {
            if (!p.isActive()) continue;
            out.add(toInfo(p, des.get(p.getDesignationId()), units, headPosts, holderNames.getOrDefault(p.getId(), List.of())));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public String postOfEmployee(Long employeeId) {
        if (employeeId == null) return null;
        return employees.findById(employeeId).map(Employee::getPositionId).flatMap(positions::findById)
                .map(Position::getCode).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public String headPostOfUnit(String orgUnitCode) {
        if (orgUnitCode == null) return null;
        Map<String, OrgUnit> units = unitsByCode();
        for (String code : establishment.unitAndAncestors(orgUnitCode, LocalDate.now())) {
            OrgUnit u = units.get(code);
            if (u != null && u.getHeadPositionCode() != null && !u.getHeadPositionCode().isBlank()) return u.getHeadPositionCode();
        }
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> unitAndAncestors(String orgUnitCode) {
        return orgUnitCode == null ? List.of() : establishment.unitAndAncestors(orgUnitCode, LocalDate.now());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> employeeAttributes(Long employeeId) {
        if (employeeId == null) return Map.of();
        Employee e = employees.findById(employeeId).orElse(null);
        if (e == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employeeId", e.getId());
        m.put("employeeNo", e.getEmployeeNo());
        m.put("orgUnitCode", e.getOrgUnitCode());
        m.put("unitChain", unitAndAncestors(e.getOrgUnitCode()));
        m.put("employeeCategory", e.getEmployeeCategory());
        m.put("serviceCategory", e.getServiceCategory());
        m.put("employmentType", e.getEmploymentType());
        Designation d = e.getDesignationId() == null ? null : designations.findById(e.getDesignationId()).orElse(null);
        if (d != null) {
            m.put("designationCode", d.getCode());
            m.put("designationName", d.getName());
            m.put("designationLevel", d.getLevel());
            m.put("grade", d.getGrade());
            m.put("statutory", d.isStatutory());
        }
        Position p = e.getPositionId() == null ? null : positions.findById(e.getPositionId()).orElse(null);
        m.put("positionCode", p == null ? null : p.getCode());
        boolean head = false;
        if (p != null) {
            Map<String, OrgUnit> units = unitsByCode();
            head = headPosts(units).contains(p.getCode());
            m.put("reportsToPositionCode", p.getReportsToPositionCode());
        }
        m.put("isUnitHead", head);
        return m;
    }

    private PostInfo toInfo(Position p, Designation d, Map<String, OrgUnit> units, Set<String> headPosts, List<String> holders) {
        OrgUnit u = units.get(p.getOrgUnitCode());
        return new PostInfo(p.getCode(), p.getTitle(), d == null ? null : d.getCode(), d == null ? null : d.getName(),
                d == null ? null : d.getLevel(), d != null && d.isStatutory(), d == null ? null : d.getLegalBasis(),
                p.getOrgUnitCode(), u == null ? p.getOrgUnitCode() : u.getName(), p.getReportsToPositionCode(),
                headPosts.contains(p.getCode()), p.getPostStatus(), p.isActive(), holders);
    }

    private List<Employee> holders(Long positionId) {
        return employees.findAll((root, q, cb) -> cb.equal(root.get("positionId"), positionId)).stream()
                .filter(EmployeeOrgDirectory::isActive).toList();
    }

    private static final Set<String> SEPARATED = Set.of("RETIRED", "RESIGNED", "TERMINATED", "TRANSFERRED", "DECEASED", "SEPARATED");

    /** Separated officers no longer hold their post for routing purposes. */
    private static boolean isActive(Employee e) {
        return e.getStatus() == null || !SEPARATED.contains(e.getStatus());
    }

    private Map<String, OrgUnit> unitsByCode() {
        return establishment.orgUnits(LocalDate.now()).stream().collect(Collectors.toMap(OrgUnit::getCode, Function.identity(), (a, b) -> a));
    }

    private static Set<String> headPosts(Map<String, OrgUnit> units) {
        return units.values().stream().map(OrgUnit::getHeadPositionCode).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public long usageCount(String category, String code) {
        return switch (category) {
            case "EMPLOYEE_STATUS" -> employees.countByStatus(code);
            case "EMPLOYMENT_TYPE" -> employees.countByEmploymentType(code);
            case "EMPLOYEE_CATEGORY" -> employees.countByEmployeeCategory(code);
            case "SERVICE_CATEGORY" -> employees.countByServiceCategory(code);
            case "DOCUMENT_TYPE" -> documents.countByDocumentType(code);
            default -> 0;
        };
    }
}
