package lk.gov.ciaboc.hrms.recruitment;

import lk.gov.ciaboc.hrms.recruitment.SelectionScoring.Mark;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionScoringTest {
    private static Mark m(String marks, int max) {
        return new Mark(marks == null ? null : new BigDecimal(marks), BigDecimal.valueOf(max), false);
    }

    @Test
    void weightedTotalOfExamAndInterview() {
        var s = SelectionScoring.score(List.of(m("70", 100), m("40", 50)), List.of(m("30", 40)),
                BigDecimal.valueOf(60), BigDecimal.valueOf(40));
        assertThat(s.examPercent()).isEqualByComparingTo("73.33"); // 110 / 150
        assertThat(s.interviewPercent()).isEqualByComparingTo("75.00");
        assertThat(s.total()).isEqualByComparingTo("74.00"); // 73.33*0.6 + 75*0.4
    }

    @Test
    void absentScoresZeroAndMissingComponentIsRenormalized() {
        var absent = new Mark(null, BigDecimal.valueOf(100), true);
        var s = SelectionScoring.score(List.of(absent, m("50", 100)), List.of(), BigDecimal.valueOf(60), BigDecimal.valueOf(40));
        assertThat(s.examPercent()).isEqualByComparingTo("25.00");
        assertThat(s.interviewPercent()).isNull();
        assertThat(s.total()).isEqualByComparingTo("25.00");
    }
}
