package lk.gov.ciaboc.hrms.performance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Weighted overall score of an evaluation (FR-PER-004). Weights and the rating scale come from the configured
 * evaluation model; nothing here is specific to any model.
 */
public final class PerformanceScoring {
    private PerformanceScoring() {
    }

    public record WeightedRating(BigDecimal weight, BigDecimal rating) {
    }

    public record Result(BigDecimal score, BigDecimal percent) {
    }

    /**
     * score = sum(rating x weight) / sum(weight), on the model's own scale; percent = (score - min) / (max - min) x 100.
     * Returns nulls when nothing has been rated yet. Weights need not add up to 100 (they are normalized).
     */
    public static Result weighted(List<WeightedRating> ratings, BigDecimal min, BigDecimal max) {
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal weights = BigDecimal.ZERO;
        for (WeightedRating r : ratings) {
            if (r.rating() == null || r.weight() == null || r.weight().signum() <= 0) continue;
            sum = sum.add(r.rating().multiply(r.weight()));
            weights = weights.add(r.weight());
        }
        if (weights.signum() == 0) return new Result(null, null);
        BigDecimal score = sum.divide(weights, 2, RoundingMode.HALF_UP);
        BigDecimal range = max.subtract(min);
        BigDecimal percent = range.signum() <= 0 ? null
                : sum.divide(weights, 8, RoundingMode.HALF_UP).subtract(min).multiply(BigDecimal.valueOf(100))
                .divide(range, 2, RoundingMode.HALF_UP);
        return new Result(score, percent);
    }
}
