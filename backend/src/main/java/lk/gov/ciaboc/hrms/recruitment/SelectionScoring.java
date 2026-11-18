package lk.gov.ciaboc.hrms.recruitment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Computes the ranking score used to ORDER applicants for the selection panel. The score supports, but never replaces,
 * the human selection decision (C-05): the panel decides who is recommended and Management approves.
 *
 * <ul>
 *   <li>Examination percentage = total marks over all (non-cancelled) examinations / total maximum marks x 100.
 *       An absent applicant scores 0 for that examination.</li>
 *   <li>Interview percentage = average over completed interviews of marks / maximum x 100.</li>
 *   <li>Total = weighted mean of the available components using the vacancy's configured weights. A component with
 *       no data (e.g. no interview held) is left out and the remaining weights are re-normalized.</li>
 * </ul>
 */
public final class SelectionScoring {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SelectionScoring() {
    }

    /** One mark against a maximum. {@code marks == null} means "not recorded" (ignored); absent counts as 0. */
    public record Mark(BigDecimal marks, BigDecimal max, boolean absent) {
    }

    public record Score(BigDecimal examPercent, BigDecimal interviewPercent, BigDecimal total) {
    }

    public static BigDecimal examPercent(List<Mark> exams) {
        BigDecimal got = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        boolean any = false;
        for (Mark m : exams) {
            if (m.max() == null || m.max().signum() <= 0) continue;
            if (m.absent()) {
                max = max.add(m.max());
                any = true;
            } else if (m.marks() != null) {
                got = got.add(m.marks());
                max = max.add(m.max());
                any = true;
            }
        }
        if (!any || max.signum() == 0) return null;
        return got.multiply(HUNDRED).divide(max, 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal interviewPercent(List<Mark> interviews) {
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (Mark m : interviews) {
            if (m.max() == null || m.max().signum() <= 0) continue;
            if (m.absent()) {
                n++;
            } else if (m.marks() != null) {
                sum = sum.add(m.marks().multiply(HUNDRED).divide(m.max(), 6, RoundingMode.HALF_UP));
                n++;
            }
        }
        if (n == 0) return null;
        return sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    public static Score score(List<Mark> exams, List<Mark> interviews, BigDecimal examWeight, BigDecimal interviewWeight) {
        BigDecimal e = examPercent(exams);
        BigDecimal i = interviewPercent(interviews);
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal weights = BigDecimal.ZERO;
        if (e != null && examWeight != null && examWeight.signum() > 0) {
            weighted = weighted.add(e.multiply(examWeight));
            weights = weights.add(examWeight);
        }
        if (i != null && interviewWeight != null && interviewWeight.signum() > 0) {
            weighted = weighted.add(i.multiply(interviewWeight));
            weights = weights.add(interviewWeight);
        }
        BigDecimal total = weights.signum() == 0 ? BigDecimal.ZERO.setScale(2) : weighted.divide(weights, 2, RoundingMode.HALF_UP);
        return new Score(e, i, total);
    }
}
