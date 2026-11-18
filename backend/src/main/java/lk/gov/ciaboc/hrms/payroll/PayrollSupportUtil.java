package lk.gov.ciaboc.hrms.payroll;

import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup.EmployeeBrief;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Small validation and formatting helpers shared by the payroll services. */
final class PayrollSupportUtil {
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_-]{2,40}$");
    static final String RESTRICTED_NAME = "Restricted record";

    private PayrollSupportUtil() {
    }

    static YearMonth period(String p) {
        if (p == null || !p.matches("^\\d{4}-(0[1-9]|1[0-2])$")) throw ApiException.badRequest("Period must be in the form YYYY-MM");
        try {
            return YearMonth.parse(p);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Period must be in the form YYYY-MM");
        }
    }

    static String code(String c) {
        String v = c == null ? "" : c.trim().toUpperCase();
        if (!CODE.matcher(v).matches()) throw ApiException.badRequest("Code must be 2-40 characters: letters, digits, '_' or '-'");
        return v;
    }

    static String required(String v, String label) {
        if (v == null || v.isBlank()) throw ApiException.badRequest(label + " is required");
        return v.trim();
    }

    static LocalDate required(LocalDate v, String label) {
        if (v == null) throw ApiException.badRequest(label + " is required");
        return v;
    }

    static BigDecimal money(BigDecimal v, String label) {
        if (v == null) throw ApiException.badRequest(label + " is required");
        if (v.signum() < 0) throw ApiException.badRequest(label + " cannot be negative");
        if (v.compareTo(new BigDecimal("999999999999.99")) > 0) throw ApiException.badRequest(label + " is too large");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal rate(BigDecimal v, String label) {
        if (v == null) throw ApiException.badRequest(label + " is required");
        if (v.signum() < 0) throw ApiException.badRequest(label + " cannot be negative");
        if (v.compareTo(new BigDecimal("9999999999.9999")) > 0) throw ApiException.badRequest(label + " is too large");
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** True if the brief's identity must be masked for the current user (restricted record, FR-EMP-015). */
    static boolean masked(EmployeeBrief b) {
        if (b == null || !b.restricted()) return false;
        UserPrincipal me = CurrentUser.get();
        return !me.has(Permission.EMP_RESTRICTED) && !b.id().equals(me.getEmployeeId());
    }

    static boolean masked(boolean restricted, Long employeeId) {
        if (!restricted) return false;
        UserPrincipal me = CurrentUser.get();
        return !me.has(Permission.EMP_RESTRICTED) && !employeeId.equals(me.getEmployeeId());
    }

    /** Neutralises spreadsheet formula injection in exported cells. */
    static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("^-?\\d+(\\.\\d+)?$")) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
