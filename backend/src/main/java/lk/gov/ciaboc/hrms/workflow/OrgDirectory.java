package lk.gov.ciaboc.hrms.workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Organizational lookups the workflow engine needs to resolve approvers; implemented by the employee module.
 * Every post is an {@code org_position} row; {@code reportsTo} links posts into the hierarchy used for routing (REQ 10.1-10.4).
 */
public interface OrgDirectory {
    /** Current org unit code of an employee, or null. */
    String orgUnitOfEmployee(Long employeeId);

    /** Active user ids of employees currently holding the given position code. */
    Set<Long> userIdsHoldingPosition(String positionCode);

    /**
     * Active DIVISION_HEAD users at the nearest level (the unit itself, then its ancestors) that has a head other than
     * {@code excludeUserId}. So a head's own request goes to the head of the parent unit.
     */
    Set<Long> divisionHeadUserIdsFor(String orgUnitCode, Long excludeUserId);

    /** Nearest-level division heads of the unit (no exclusion). */
    default Set<Long> divisionHeadUserIdsFor(String orgUnitCode) {
        return divisionHeadUserIdsFor(orgUnitCode, null);
    }

    // ------------------------------------------------------------------ hierarchy (REQ 10.1-10.5)

    /** A post in the approved structure with its reporting line and current holders. */
    record PostInfo(String code, String title, String designationCode, String designationName, Integer level,
                    boolean statutory, String legalBasis, String orgUnitCode, String orgUnitName, String reportsTo,
                    boolean unitHead, String postStatus, boolean active, List<String> holders) {
    }

    /** One post, or empty when the code is unknown. */
    default Optional<PostInfo> post(String positionCode) {
        return Optional.empty();
    }

    /** All active posts (the hierarchy used for routing). */
    default List<PostInfo> posts() {
        return List.of();
    }

    /** Code of the post the employee currently holds, or null when no post is linked. */
    default String postOfEmployee(Long employeeId) {
        return null;
    }

    /** Head post of the unit, or of its nearest ancestor that has one; null when none. */
    default String headPostOfUnit(String orgUnitCode) {
        return null;
    }

    /** The unit and its ancestors, nearest first. */
    default List<String> unitAndAncestors(String orgUnitCode) {
        return orgUnitCode == null ? List.of() : List.of(orgUnitCode);
    }

    /**
     * Routing attributes of an employee (designationCode, designationLevel, positionCode, orgUnitCode, unitChain,
     * isUnitHead, statutory, grade, employeeCategory ...). Used by routing rules (REQ 10.6).
     */
    default Map<String, Object> employeeAttributes(Long employeeId) {
        return Map.of();
    }
}
