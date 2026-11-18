package lk.gov.ciaboc.hrms.common;

import java.time.LocalDate;

/** Records that change over time carry a validity window so the system can answer "what was true on date X" (SYS-04). */
public interface EffectiveDated {
    LocalDate getEffectiveFrom();

    LocalDate getEffectiveTo();

    /** effectiveTo is exclusive: a record ending on D is no longer valid on D. */
    default boolean isEffectiveOn(LocalDate date) {
        return !getEffectiveFrom().isAfter(date) && (getEffectiveTo() == null || getEffectiveTo().isAfter(date));
    }
}
