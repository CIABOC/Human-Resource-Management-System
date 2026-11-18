package lk.gov.ciaboc.hrms.workflow;

import java.util.Set;

/** Organizational lookups the workflow engine needs to resolve approvers; implemented by the employee module. */
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
}
