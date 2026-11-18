package lk.gov.ciaboc.hrms.extended;

import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.establishment.Designation;
import lk.gov.ciaboc.hrms.establishment.DesignationRepository;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.establishment.OrgUnit;
import lk.gov.ciaboc.hrms.establishment.Position;
import lk.gov.ciaboc.hrms.establishment.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Small lookups and helpers shared by the extended-scope services. */
@Component
@RequiredArgsConstructor
public class ExtSupport {
    private final DesignationRepository designations;
    private final PositionRepository positions;
    private final EstablishmentService establishment;
    private final EmployeeLookup lookup;

    @Transactional(readOnly = true)
    public String designationName(Long id) {
        if (id == null) return null;
        return designations.findById(id).map(Designation::getName).orElse(String.valueOf(id));
    }

    @Transactional(readOnly = true)
    public Designation activeDesignation(Long id) {
        if (id == null) throw ApiException.badRequest("Designation is required");
        return designations.findById(id).filter(Designation::isActive)
                .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Designation is not active"));
    }

    @Transactional(readOnly = true)
    public String unitName(String code) {
        if (code == null) return null;
        return establishment.orgUnit(code, LocalDate.now()).map(OrgUnit::getName).orElse(code);
    }

    @Transactional(readOnly = true)
    public OrgUnit activeUnit(String code, LocalDate on) {
        if (blank(code)) throw ApiException.badRequest("Org unit is required");
        return establishment.orgUnit(code, on)
                .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Org unit " + code + " is not active on " + on));
    }

    @Transactional(readOnly = true)
    public String positionTitle(Long id) {
        if (id == null) return null;
        return positions.findById(id).map(Position::getTitle).orElse(null);
    }

    @Transactional(readOnly = true)
    public Position position(Long id) {
        return positions.findById(id).orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Position not found"));
    }

    /** Employee briefs keyed by id (no scope check; callers filter visibility first). */
    public Map<Long, EmployeeLookup.EmployeeBrief> briefs(Collection<Long> ids) {
        return lookup.briefs(ids);
    }

    /** Ids the current user may see (null = all). */
    public Set<Long> visibleIds() {
        return lookup.visibleEmployeeIds();
    }

    public static boolean visible(Set<Long> visible, Long employeeId) {
        return visible == null || visible.contains(employeeId);
    }

    public static boolean inService(Employee e) {
        return EmployeeLookup.IN_SERVICE.contains(e.getStatus());
    }

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public static String trimToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    public static void require(String v, String label) {
        if (blank(v)) throw ApiException.badRequest(label + " is required");
    }

    public static void maxLength(String v, int max, String label) {
        if (v != null && v.length() > max) throw ApiException.badRequest(label + " must be at most " + max + " characters");
    }

    public static <T, K> Map<K, T> index(Collection<T> items, Function<T, K> key) {
        return items.stream().collect(Collectors.toMap(key, x -> x, (a, b) -> a));
    }

    /** Minimal HTML escaping for values substituted into letter templates and generated pages. */
    public static String html(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
