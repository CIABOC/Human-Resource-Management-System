package lk.gov.ciaboc.hrms.performance;

import lk.gov.ciaboc.hrms.performance.PerformanceScoring.WeightedRating;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceScoringTest {
    private static WeightedRating r(int weight, String rating) {
        return new WeightedRating(BigDecimal.valueOf(weight), rating == null ? null : new BigDecimal(rating));
    }

    @Test
    void weightedScoreOnModelScale() {
        // 30*5 + 25*4 + 15*3 + 20*4 + 10*2 = 150+100+45+80+20 = 395 / 100 = 3.95
        var res = PerformanceScoring.weighted(List.of(r(30, "5"), r(25, "4"), r(15, "3"), r(20, "4"), r(10, "2")),
                BigDecimal.ONE, BigDecimal.valueOf(5));
        assertThat(res.score()).isEqualByComparingTo("3.95");
        // (3.95 - 1) / 4 * 100 = 73.75
        assertThat(res.percent()).isEqualByComparingTo("73.75");
    }

    @Test
    void unratedCriteriaAreIgnoredAndWeightsNormalized() {
        var res = PerformanceScoring.weighted(List.of(r(60, "4"), r(40, null)), BigDecimal.ONE, BigDecimal.valueOf(4));
        assertThat(res.score()).isEqualByComparingTo("4.00");
        assertThat(res.percent()).isEqualByComparingTo("100.00");
    }

    @Test
    void nothingRatedGivesNoScore() {
        var res = PerformanceScoring.weighted(List.of(r(100, null)), BigDecimal.ONE, BigDecimal.valueOf(5));
        assertThat(res.score()).isNull();
        assertThat(res.percent()).isNull();
    }
}
