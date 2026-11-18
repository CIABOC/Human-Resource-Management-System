package lk.gov.ciaboc.hrms.common.contracts;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;

/**
 * Contract between Attendance/Leave (producer) and Payroll (consumer), FR-ATT-022 / FR-LEV-010 / FR-PAY-030.
 * Implemented by the attendance module; payroll obtains it through ObjectProvider so either can be deployed first.
 */
public interface NoPaySource {
    /**
     * Approved no-pay days per employee id for the period: unauthorized absences plus approved unpaid leave, after review
     * and approval by an authorized officer. Employees with zero days may be omitted.
     */
    Map<Long, BigDecimal> approvedNoPayDays(YearMonth period);
}
