package lk.gov.ciaboc.hrms.attendance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lk.gov.ciaboc.hrms.common.EffectiveDated;
import lombok.Getter;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Effective-dated working schedule (FR-ATT-010..013). Applies institution-wide when org unit and category are empty,
 * otherwise to the org unit (and its sub-units) and/or employee category; the most specific schedule wins.
 */
@Getter
@Setter
@Entity
@Table(name = "work_schedule")
public class WorkSchedule extends BaseEntity implements EffectiveDated {
    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "late_grace_minutes", nullable = false)
    private int lateGraceMinutes;

    @Column(name = "early_grace_minutes", nullable = false)
    private int earlyGraceMinutes;

    /** Comma-separated MON..SUN. */
    @Column(name = "work_days", nullable = false, length = 40)
    private String workDays;

    @Column(name = "org_unit_code", length = 40)
    private String orgUnitCode;

    @Column(name = "employee_category", length = 40)
    private String employeeCategory;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(length = 500)
    private String remarks;

    public Set<DayOfWeek> workDaySet() {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if (workDays == null) return out;
        Arrays.stream(workDays.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(s -> out.add(toDay(s)));
        return out;
    }

    public boolean isWorkDay(LocalDate d) {
        return workDaySet().contains(d.getDayOfWeek());
    }

    static DayOfWeek toDay(String s) {
        for (DayOfWeek d : DayOfWeek.values()) if (d.name().startsWith(s.toUpperCase())) return d;
        throw new IllegalArgumentException("Unknown day " + s);
    }
}
