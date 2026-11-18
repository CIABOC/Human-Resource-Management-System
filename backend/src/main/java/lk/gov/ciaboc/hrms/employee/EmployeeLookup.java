package lk.gov.ciaboc.hrms.employee;

import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.establishment.Designation;
import lk.gov.ciaboc.hrms.establishment.DesignationRepository;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.establishment.OrgUnit;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Public API of the employee module for other modules: compact employee summaries and data-scope enforcement
 * (FR-UAM-004, NFR-SEC-10). Modules must use this instead of reading Employee directly when exposing employee data.
 */
@Service
@RequiredArgsConstructor
public class EmployeeLookup {
    /** Statuses for which an employee is still in service. */
    public static final Set<String> IN_SERVICE = Set.of("ACTIVE", "ON_LEAVE", "SECONDED", "PROBATION");

    private final EmployeeRepository employees;
    private final DesignationRepository designations;
    private final EstablishmentService establishment;

    public record EmployeeBrief(Long id, String employeeNo, String name, String nameWithInitials, String designation,
                                String orgUnitCode, String orgUnitName, String status, String employeeCategory,
                                String employmentType, boolean restricted) {
    }

    @Transactional(readOnly = true)
    public Employee require(Long id) {
        return employees.findById(id).orElseThrow(() -> ApiException.notFound("Employee", id));
    }

    @Transactional(readOnly = true)
    public EmployeeBrief brief(Long id) {
        return briefs(List.of(id)).get(id);
    }

    @Transactional(readOnly = true)
    public Map<Long, EmployeeBrief> briefs(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, String> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, Designation::getName));
        Map<String, String> units = establishment.orgUnits(LocalDate.now()).stream()
                .collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getName, (a, b) -> a));
        Map<Long, EmployeeBrief> out = new LinkedHashMap<>();
        for (Employee e : employees.findAllById(new LinkedHashSet<>(ids))) out.put(e.getId(), toBrief(e, des, units));
        return out;
    }

    /** All in-service employees as briefs (for module screens such as payroll runs and attendance processing). */
    @Transactional(readOnly = true)
    public List<EmployeeBrief> inService() {
        Map<Long, String> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, Designation::getName));
        Map<String, String> units = establishment.orgUnits(LocalDate.now()).stream()
                .collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getName, (a, b) -> a));
        return employees.findByStatusIn(IN_SERVICE).stream().map(e -> toBrief(e, des, units))
                .sorted(Comparator.comparing(EmployeeBrief::employeeNo)).toList();
    }

    private static EmployeeBrief toBrief(Employee e, Map<Long, String> des, Map<String, String> units) {
        return new EmployeeBrief(e.getId(), e.getEmployeeNo(), e.getFullName(), e.getNameWithInitials(),
                des.get(e.getDesignationId()), e.getOrgUnitCode(), units.get(e.getOrgUnitCode()), e.getStatus(),
                e.getEmployeeCategory(), e.getEmploymentType(), e.isRestricted());
    }

    /**
     * Org-unit codes the current user may see, or null for "all". An empty set means "only own records".
     */
    @Transactional(readOnly = true)
    public Set<String> scopeUnits() {
        UserPrincipal p = CurrentUser.get();
        return switch (p.getDataScope()) {
            case ALL -> null;
            case SELF -> Set.of();
            case DIVISION -> {
                if (p.getEmployeeId() == null) yield Set.of();
                Employee me = employees.findById(p.getEmployeeId()).orElse(null);
                if (me == null || me.getOrgUnitCode() == null) yield Set.of();
                // Widen to the enclosing DIVISION-level unit, then include all of its sub-units.
                String top = me.getOrgUnitCode();
                Map<String, OrgUnit> byCode = establishment.orgUnits(LocalDate.now()).stream()
                        .collect(Collectors.toMap(OrgUnit::getCode, u -> u, (a, b) -> a));
                for (String c : establishment.unitAndAncestors(me.getOrgUnitCode(), LocalDate.now())) {
                    OrgUnit u = byCode.get(c);
                    if (u != null && u.getUnitType() == OrgUnit.UnitType.DIVISION) {
                        top = c;
                        break;
                    }
                }
                yield establishment.unitAndDescendants(top, LocalDate.now());
            }
        };
    }

    /** True if the current user may see this employee's records, given they hold the relevant module permission. */
    public boolean canAccess(Employee e) {
        UserPrincipal p = CurrentUser.get();
        if (e.getId().equals(p.getEmployeeId())) return true;
        if (e.isRestricted() && !p.has(Permission.EMP_RESTRICTED)) return false;
        Set<String> scope = scopeUnits();
        return scope == null || (e.getOrgUnitCode() != null && scope.contains(e.getOrgUnitCode()));
    }

    /** Throws 404 (not 403, to avoid confirming existence) if the current user may not see this employee. */
    @Transactional(readOnly = true)
    public Employee requireAccess(Long employeeId) {
        Employee e = require(employeeId);
        if (!canAccess(e)) throw ApiException.notFound("Employee", employeeId);
        return e;
    }

    /** Ids of employees visible to the current user, or null for all. Restricted employees are excluded unless permitted. */
    @Transactional(readOnly = true)
    public Set<Long> visibleEmployeeIds() {
        UserPrincipal p = CurrentUser.get();
        Set<String> scope = scopeUnits();
        if (scope == null && p.has(Permission.EMP_RESTRICTED)) return null;
        Set<Long> ids = new HashSet<>();
        for (Employee e : scope == null ? employees.findAll() : employees.findAllById(scope.isEmpty() ? List.of() :
                employees.idsInUnits(scope))) {
            if (!e.isRestricted() || p.has(Permission.EMP_RESTRICTED)) ids.add(e.getId());
        }
        if (p.getEmployeeId() != null) ids.add(p.getEmployeeId());
        return ids;
    }
}
