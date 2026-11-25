package lk.gov.ciaboc.hrms.attendance;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * Working schedule as shown on the attendance configuration screens and used by daily processing (FR-ATT-010..013).
 * Since V60 this is a view, not a table: one APPROVED {@link DutyPolicy} version combined with the policy's group-level
 * {@link DutyPolicyAssignment} (organization, division or category). {@code id} is the policy version id.
 */
@Getter
@Setter
public class WorkSchedule implements EffectiveDated {
    private Long id;
    private String code;
    private int versionNo;
    private String name;
    private LocalTime startTime;
    private LocalTime endTime;
    private int lateGraceMinutes;
    private int earlyGraceMinutes;
    /** Comma-separated MON..SUN. */
    private String workDays;
    private String orgUnitCode;
    private String employeeCategory;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String remarks;

    static WorkSchedule of(DutyPolicy p, DutyPolicyAssignment a) {
        WorkSchedule s = new WorkSchedule();
        s.id = p.getId();
        s.code = p.getCode();
        s.versionNo = p.getVersionNo();
        s.name = p.getName();
        s.startTime = p.getInTime();
        s.endTime = p.getOutTime();
        s.lateGraceMinutes = p.getGraceMinutes();
        s.earlyGraceMinutes = p.getEarlyGraceMinutes();
        s.workDays = p.getWorkingDays();
        s.effectiveFrom = p.getEffectiveFrom();
        s.effectiveTo = p.getEffectiveTo();
        s.remarks = p.getReason();
        if (a != null) {
            s.orgUnitCode = a.getOrgUnitCode();
            s.employeeCategory = a.getEmployeeCategory();
        }
        return s;
    }

    public Set<DayOfWeek> workDaySet() {
        // no working days configured (e.g. a shift roster): every day is a potential working day
        if (workDays == null || workDays.isBlank()) return EnumSet.allOf(DayOfWeek.class);
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        Arrays.stream(workDays.split(",")).map(String::trim).filter(x -> !x.isEmpty()).forEach(x -> out.add(toDay(x)));
        return out;
    }

    public boolean isWorkDay(LocalDate d) {
        return workDaySet().contains(d.getDayOfWeek());
    }

    @JsonIgnore
    @Override
    public boolean isEffectiveOn(LocalDate date) {
        return EffectiveDated.super.isEffectiveOn(date);
    }

    static DayOfWeek toDay(String s) {
        for (DayOfWeek d : DayOfWeek.values()) if (d.name().startsWith(s.toUpperCase())) return d;
        throw new IllegalArgumentException("Unknown day " + s);
    }
}
