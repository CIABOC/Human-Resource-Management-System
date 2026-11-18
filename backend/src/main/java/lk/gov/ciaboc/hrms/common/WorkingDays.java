package lk.gov.ciaboc.hrms.common;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

public final class WorkingDays {
    private WorkingDays() {
    }

    /** Counts days in [from, to] that are not weekends and not in the given holiday set. */
    public static int count(LocalDate from, LocalDate to, Set<LocalDate> holidays) {
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWorkingDay(d, holidays)) n++;
        }
        return n;
    }

    public static boolean isWorkingDay(LocalDate d, Set<LocalDate> holidays) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && (holidays == null || !holidays.contains(d));
    }
}
