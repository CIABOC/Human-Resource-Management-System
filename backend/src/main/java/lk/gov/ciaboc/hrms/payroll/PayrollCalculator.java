package lk.gov.ciaboc.hrms.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure payroll calculation for one employee and one period (FR-PAY-030). It has no Spring or database dependencies so
 * the rules can be unit-tested and reviewed by Finance in isolation. All rates come in as data.
 *
 * <p>Rules:
 * <ul>
 *   <li>Gross = basic + allowances (fixed or % of basic) + earning adjustments.</li>
 *   <li>Statutory/scheme/other deductions and employer contributions: fixed, % of basic or % of gross
 *       (contribution base to be confirmed with Finance).</li>
 *   <li>Loan instalments are capped at the outstanding balance.</li>
 *   <li>No-pay = basic / divisor x approved no-pay days.</li>
 *   <li>Benefit premiums and deduction adjustments are deducted as given.</li>
 *   <li>Every component is rounded HALF_UP to 2 decimal places; totals are sums of rounded components.</li>
 * </ul>
 */
public final class PayrollCalculator {
    public static final int SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public enum Basis {NONE, FIXED, PERCENT_OF_BASIC, PERCENT_OF_GROSS}

    public enum ComponentType {EARNING, DEDUCTION, EMPLOYER}

    /** Where a component came from; used for history, reports and finalization (loans, adjustments). */
    public enum Source {BASIC, ALLOWANCE, STATUTORY, SCHEME, OTHER, LOAN, NOPAY, BENEFIT, ADJUSTMENT}

    public record Allowance(String code, String name, Basis basis, BigDecimal value, BigDecimal amountOverride) {
    }

    public record Deduction(String code, String name, Source category, Basis employeeBasis, BigDecimal employeeValue,
                            Basis employerBasis, BigDecimal employerValue) {
    }

    public record Loan(Long loanId, String code, String name, BigDecimal instalment, BigDecimal outstanding) {
    }

    public record FixedDeduction(Long sourceId, String code, String name, BigDecimal amount) {
    }

    public record Adjustment(Long adjustmentId, boolean earning, String code, String name, BigDecimal amount) {
    }

    public record Input(BigDecimal basic, List<Allowance> allowances, List<Deduction> deductions, List<Loan> loans,
                        BigDecimal noPayDays, BigDecimal noPayDivisor, List<FixedDeduction> benefits,
                        List<Adjustment> adjustments) {
        public Input {
            allowances = allowances == null ? List.of() : allowances;
            deductions = deductions == null ? List.of() : deductions;
            loans = loans == null ? List.of() : loans;
            benefits = benefits == null ? List.of() : benefits;
            adjustments = adjustments == null ? List.of() : adjustments;
            noPayDays = noPayDays == null ? BigDecimal.ZERO : noPayDays;
        }
    }

    public record Item(ComponentType type, Source source, Long sourceId, String code, String description, BigDecimal amount) {
    }

    public record Result(BigDecimal basic, BigDecimal gross, BigDecimal totalDeductions, BigDecimal net,
                         BigDecimal employerContributions, BigDecimal noPayDays, List<Item> items, List<String> warnings) {
    }

    private PayrollCalculator() {
    }

    public static BigDecimal round(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static Result calculate(Input in) {
        if (in.basic() == null || in.basic().signum() < 0) throw new IllegalArgumentException("Basic salary must be zero or positive");
        BigDecimal basic = round(in.basic());
        List<Item> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ---- earnings
        items.add(new Item(ComponentType.EARNING, Source.BASIC, null, "BASIC", "Basic salary", basic));
        BigDecimal gross = basic;
        for (Allowance a : in.allowances()) {
            BigDecimal amt = a.amountOverride() != null ? round(a.amountOverride()) : amount(a.basis(), a.value(), basic, basic);
            if (amt.signum() <= 0) continue;
            items.add(new Item(ComponentType.EARNING, Source.ALLOWANCE, null, a.code(), a.name(), amt));
            gross = gross.add(amt);
        }
        for (Adjustment adj : in.adjustments()) {
            if (!adj.earning()) continue;
            BigDecimal amt = round(adj.amount());
            items.add(new Item(ComponentType.EARNING, Source.ADJUSTMENT, adj.adjustmentId(), adj.code(), adj.name(), amt));
            gross = gross.add(amt);
        }

        // ---- statutory, scheme and other deductions (+ employer contributions)
        BigDecimal deductions = BigDecimal.ZERO;
        BigDecimal employer = BigDecimal.ZERO;
        for (Deduction d : in.deductions()) {
            BigDecimal emp = amount(d.employeeBasis(), d.employeeValue(), basic, gross);
            if (emp.signum() > 0) {
                items.add(new Item(ComponentType.DEDUCTION, d.category(), null, d.code(), d.name(), emp));
                deductions = deductions.add(emp);
            }
            BigDecimal er = amount(d.employerBasis(), d.employerValue(), basic, gross);
            if (er.signum() > 0) {
                items.add(new Item(ComponentType.EMPLOYER, d.category(), null, d.code(), d.name() + " (employer)", er));
                employer = employer.add(er);
            }
        }

        // ---- loans: instalment capped at outstanding balance (FR-PAY-021)
        for (Loan l : in.loans()) {
            BigDecimal outstanding = l.outstanding() == null ? BigDecimal.ZERO : round(l.outstanding());
            BigDecimal inst = l.instalment() == null ? BigDecimal.ZERO : round(l.instalment());
            BigDecimal amt = inst.min(outstanding);
            if (amt.signum() <= 0) continue;
            items.add(new Item(ComponentType.DEDUCTION, Source.LOAN, l.loanId(), l.code(), l.name(), amt));
            deductions = deductions.add(amt);
        }

        // ---- attendance no-pay
        BigDecimal noPayDays = in.noPayDays();
        if (noPayDays.signum() > 0) {
            if (in.noPayDivisor() == null || in.noPayDivisor().signum() <= 0)
                throw new IllegalArgumentException("No-pay divisor must be positive");
            BigDecimal amt = round(basic.multiply(noPayDays).divide(in.noPayDivisor(), 10, RoundingMode.HALF_UP));
            if (amt.compareTo(basic) > 0) {
                warnings.add("No-pay amount exceeded basic salary and was limited to basic");
                amt = basic;
            }
            items.add(new Item(ComponentType.DEDUCTION, Source.NOPAY, null, "NOPAY",
                    "No-pay (" + noPayDays.stripTrailingZeros().toPlainString() + " days)", amt));
            deductions = deductions.add(amt);
        }

        // ---- approved benefit deductions (insurance premiums)
        for (FixedDeduction b : in.benefits()) {
            BigDecimal amt = b.amount() == null ? BigDecimal.ZERO : round(b.amount());
            if (amt.signum() <= 0) continue;
            items.add(new Item(ComponentType.DEDUCTION, Source.BENEFIT, b.sourceId(), b.code(), b.name(), amt));
            deductions = deductions.add(amt);
        }

        // ---- controlled deduction adjustments
        for (Adjustment adj : in.adjustments()) {
            if (adj.earning()) continue;
            BigDecimal amt = round(adj.amount());
            items.add(new Item(ComponentType.DEDUCTION, Source.ADJUSTMENT, adj.adjustmentId(), adj.code(), adj.name(), amt));
            deductions = deductions.add(amt);
        }

        BigDecimal net = gross.subtract(deductions);
        if (net.signum() < 0) warnings.add("Net pay is negative; review deductions before submission");
        if (basic.signum() == 0) warnings.add("No basic salary placement for the period");
        return new Result(basic, round(gross), round(deductions), round(net), round(employer), noPayDays, List.copyOf(items),
                List.copyOf(warnings));
    }

    static BigDecimal amount(Basis basis, BigDecimal value, BigDecimal basic, BigDecimal gross) {
        if (basis == null || value == null) return BigDecimal.ZERO.setScale(SCALE);
        return switch (basis) {
            case NONE -> BigDecimal.ZERO.setScale(SCALE);
            case FIXED -> round(value);
            case PERCENT_OF_BASIC -> round(basic.multiply(value).divide(HUNDRED, 10, RoundingMode.HALF_UP));
            case PERCENT_OF_GROSS -> round(gross.multiply(value).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        };
    }
}
