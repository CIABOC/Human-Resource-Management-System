package lk.gov.ciaboc.hrms.payroll;

import lk.gov.ciaboc.hrms.payroll.PayrollCalculator.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayrollCalculatorTest {
    private static final BigDecimal D30 = BigDecimal.valueOf(30);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal amount(Result r, ComponentType t, String code) {
        return r.items().stream().filter(i -> i.type() == t && i.code().equals(code)).map(Item::amount).findFirst().orElse(null);
    }

    @Test
    void fixedAndPercentageAllowancesAddToGross() {
        Result r = PayrollCalculator.calculate(new Input(bd("50000"), List.of(
                new Allowance("COLA", "Cost of living", Basis.FIXED, bd("7800"), null),
                new Allowance("SPECIAL", "Special", Basis.PERCENT_OF_BASIC, bd("10"), null),
                new Allowance("ACTING", "Acting", Basis.FIXED, bd("0"), bd("8500"))), null, null, null, D30, null, null));
        assertThat(amount(r, ComponentType.EARNING, "SPECIAL")).isEqualByComparingTo("5000.00");
        assertThat(amount(r, ComponentType.EARNING, "ACTING")).isEqualByComparingTo("8500.00");
        assertThat(r.gross()).isEqualByComparingTo("71300.00");
        assertThat(r.net()).isEqualByComparingTo("71300.00");
    }

    @Test
    void epfAndEtfEmployeeAndEmployerContributions() {
        Result r = PayrollCalculator.calculate(new Input(bd("48000"), null, List.of(
                new Deduction("EPF", "EPF", Source.STATUTORY, Basis.PERCENT_OF_BASIC, bd("8"), Basis.PERCENT_OF_BASIC, bd("12")),
                new Deduction("ETF", "ETF", Source.STATUTORY, Basis.NONE, BigDecimal.ZERO, Basis.PERCENT_OF_BASIC, bd("3"))),
                null, null, D30, null, null));
        assertThat(amount(r, ComponentType.DEDUCTION, "EPF")).isEqualByComparingTo("3840.00");
        assertThat(amount(r, ComponentType.EMPLOYER, "EPF")).isEqualByComparingTo("5760.00");
        assertThat(amount(r, ComponentType.EMPLOYER, "ETF")).isEqualByComparingTo("1440.00");
        assertThat(amount(r, ComponentType.DEDUCTION, "ETF")).isNull();
        assertThat(r.totalDeductions()).isEqualByComparingTo("3840.00");
        assertThat(r.employerContributions()).isEqualByComparingTo("7200.00");
        assertThat(r.net()).isEqualByComparingTo("44160.00");
    }

    @Test
    void percentOfGrossUsesBasicPlusAllowances() {
        Result r = PayrollCalculator.calculate(new Input(bd("40000"), List.of(new Allowance("COLA", "COLA", Basis.FIXED, bd("10000"), null)),
                List.of(new Deduction("X", "X", Source.OTHER, Basis.PERCENT_OF_GROSS, bd("2"), Basis.NONE, null)), null, null, D30, null, null));
        assertThat(amount(r, ComponentType.DEDUCTION, "X")).isEqualByComparingTo("1000.00");
    }

    @Test
    void noPayIsProratedOnBasicByDivisor() {
        Result r = PayrollCalculator.calculate(new Input(bd("45000"), null, null, null, bd("2"), D30, null, null));
        assertThat(amount(r, ComponentType.DEDUCTION, "NOPAY")).isEqualByComparingTo("3000.00");
        Result half = PayrollCalculator.calculate(new Input(bd("31000"), null, null, null, bd("1.5"), bd("31"), null, null));
        assertThat(amount(half, ComponentType.DEDUCTION, "NOPAY")).isEqualByComparingTo("1500.00");
        assertThat(half.net()).isEqualByComparingTo("29500.00");
    }

    @Test
    void noPayIsCappedAtBasic() {
        Result r = PayrollCalculator.calculate(new Input(bd("30000"), null, null, null, bd("40"), D30, null, null));
        assertThat(amount(r, ComponentType.DEDUCTION, "NOPAY")).isEqualByComparingTo("30000.00");
        assertThat(r.warnings()).isNotEmpty();
    }

    @Test
    void loanInstalmentIsCappedAtOutstandingBalance() {
        Result r = PayrollCalculator.calculate(new Input(bd("50000"), null, null, List.of(
                new Loan(1L, "LOAN-A", "Loan A", bd("5000"), bd("2000")),
                new Loan(2L, "LOAN-B", "Loan B", bd("5000"), bd("25500")),
                new Loan(3L, "LOAN-C", "Loan C", bd("5000"), BigDecimal.ZERO)), null, D30, null, null));
        assertThat(amount(r, ComponentType.DEDUCTION, "LOAN-A")).isEqualByComparingTo("2000.00");
        assertThat(amount(r, ComponentType.DEDUCTION, "LOAN-B")).isEqualByComparingTo("5000.00");
        assertThat(amount(r, ComponentType.DEDUCTION, "LOAN-C")).isNull();
        assertThat(r.items().stream().filter(i -> i.source() == Source.LOAN).map(Item::sourceId)).containsExactly(1L, 2L);
        assertThat(r.net()).isEqualByComparingTo("43000.00");
    }

    @Test
    void roundingIsHalfUpToTwoDecimalsPerComponent() {
        // 8% of 33333.33 = 2666.6664 -> 2666.67 ; 3% of 33333.33 = 999.9999 -> 1000.00 ; 0.125% -> 41.6666625 -> 41.67
        Result r = PayrollCalculator.calculate(new Input(bd("33333.333"), null, List.of(
                new Deduction("EPF", "EPF", Source.STATUTORY, Basis.PERCENT_OF_BASIC, bd("8"), Basis.PERCENT_OF_BASIC, bd("3")),
                new Deduction("Y", "Y", Source.OTHER, Basis.PERCENT_OF_BASIC, bd("0.125"), Basis.NONE, null)),
                null, null, D30, null, null));
        assertThat(r.basic()).isEqualByComparingTo("33333.33");
        assertThat(amount(r, ComponentType.DEDUCTION, "EPF")).isEqualByComparingTo("2666.67");
        assertThat(amount(r, ComponentType.EMPLOYER, "EPF")).isEqualByComparingTo("1000.00");
        assertThat(amount(r, ComponentType.DEDUCTION, "Y")).isEqualByComparingTo("41.67");
        assertThat(r.net()).isEqualByComparingTo("30624.99");
        assertThat(PayrollCalculator.round(bd("10.005"))).isEqualByComparingTo("10.01");
        assertThat(PayrollCalculator.round(bd("10.004"))).isEqualByComparingTo("10.00");
    }

    @Test
    void benefitsAndAdjustmentsAreApplied() {
        Result r = PayrollCalculator.calculate(new Input(bd("40000"), null, null, null, null, D30,
                List.of(new FixedDeduction(9L, "INS-AGRAHARA", "Agrahara premium", bd("225"))),
                List.of(new Adjustment(5L, true, "ARREARS", "Arrears", bd("1200")),
                        new Adjustment(6L, false, "OVERPAY", "Overpayment recovery", bd("300")))));
        assertThat(r.gross()).isEqualByComparingTo("41200.00");
        assertThat(r.totalDeductions()).isEqualByComparingTo("525.00");
        assertThat(r.net()).isEqualByComparingTo("40675.00");
    }

    @Test
    void negativeNetIsFlaggedAndInvalidInputRejected() {
        Result r = PayrollCalculator.calculate(new Input(bd("1000"), null, null, null, null, D30, null,
                List.of(new Adjustment(1L, false, "REC", "Recovery", bd("5000")))));
        assertThat(r.net()).isEqualByComparingTo("-4000.00");
        assertThat(r.warnings()).anyMatch(w -> w.contains("negative"));
        assertThatThrownBy(() -> PayrollCalculator.calculate(new Input(bd("1000"), null, null, null, bd("1"), BigDecimal.ZERO, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
