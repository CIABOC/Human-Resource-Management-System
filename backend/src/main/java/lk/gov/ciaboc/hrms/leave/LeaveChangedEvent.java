package lk.gov.ciaboc.hrms.leave;

import java.time.LocalDate;

/**
 * Published (synchronously, inside the approving transaction) when approved leave starts or stops applying to a date
 * range, so attendance can re-evaluate days it has already processed (FR-LEV-010 / FR-ATT-014).
 */
public record LeaveChangedEvent(Long employeeId, LocalDate from, LocalDate to) {
}
