package lk.gov.ciaboc.hrms.common.contracts;

import java.time.LocalDate;
import java.util.List;

/**
 * Domain events published by the establishment and employee modules (Spring ApplicationEventPublisher). Consumers
 * should use {@code @TransactionalEventListener} (AFTER_COMMIT for notifications, BEFORE_COMMIT to stay atomic).
 */
public final class EstablishmentEvents {
    private EstablishmentEvents() {
    }

    /**
     * An approved Cadre Change Request took effect (REQ 5.8, 6.5). For increases {@code newPositionCodes} lists the
     * post IDs created; for decreases {@code frozenPositionCodes} lists vacant posts frozen and
     * {@code toBeAbolishedPositionCodes} filled excess posts to be abolished on vacation.
     */
    public record CadreChanged(Long changeId, Long designationId, String orgUnitCode, int oldQty, int newQty,
                               LocalDate effectiveDate, List<String> newPositionCodes, List<String> frozenPositionCodes,
                               List<String> toBeAbolishedPositionCodes) {
    }

    /** An employee left service (retirement, resignation, termination, transfer out, end of secondment, death) (REQ 5.1). */
    public record EmployeeSeparated(Long employeeId, String status, String separationReason, LocalDate effectiveDate,
                                    String orgUnitCode, Long designationId, Long positionId) {
    }

    /** An employee's unit/designation/post changed (promotion, transfer, acting ...) (REQ 5.1, 5.7). */
    public record EmployeePlacementChanged(Long employeeId, String reason, LocalDate effectiveDate,
                                           String fromOrgUnitCode, Long fromDesignationId, Long fromPositionId,
                                           String toOrgUnitCode, Long toDesignationId, Long toPositionId, boolean acting) {
    }
}
