package lk.gov.ciaboc.hrms.workflow;

import lk.gov.ciaboc.hrms.workflow.WorkflowModel.Clause;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.Condition;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.Join;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.Operator;

import java.math.BigDecimal;
import java.util.*;

/**
 * Safe evaluation of routing conditions (REQ 10.6): a closed set of comparison operators over named attributes,
 * combined with AND/OR groups. No expressions, scripts or reflection — values are compared as numbers when both sides
 * are numeric, as booleans for IS_TRUE/IS_FALSE, otherwise as case-insensitive text.
 */
public final class ConditionEvaluator {
    /** Maximum nesting of condition groups accepted by the designer. */
    public static final int MAX_DEPTH = 4;

    private ConditionEvaluator() {
    }

    /** Outcome plus a human-readable trace of every clause (shown by the path simulator). */
    public record Result(boolean matched, List<String> trace) {
    }

    public static Result evaluate(Condition c, Map<String, Object> attributes) {
        List<String> trace = new ArrayList<>();
        boolean m = c == null || c.isEmpty() || eval(c, attributes, trace, 0);
        if (c == null || c.isEmpty()) trace.add("(no condition — always applies)");
        return new Result(m, trace);
    }

    private static boolean eval(Condition c, Map<String, Object> attrs, List<String> trace, int depth) {
        if (depth > MAX_DEPTH) {
            trace.add("condition nested too deeply — treated as not matching");
            return false;
        }
        if (c.isEmpty()) return true;
        boolean and = c.join() != Join.OR;
        boolean acc = and;
        for (Clause cl : c.clauses()) {
            boolean r = clause(cl, attrs, trace);
            acc = and ? acc && r : acc || r;
        }
        for (Condition g : c.groups()) {
            boolean r = eval(g, attrs, trace, depth + 1);
            acc = and ? acc && r : acc || r;
        }
        return acc;
    }

    static boolean clause(Clause cl, Map<String, Object> attrs, List<String> trace) {
        if (cl == null || cl.attribute() == null || cl.operator() == null) {
            trace.add("incomplete clause — not matching");
            return false;
        }
        Object actual = attrs.get(cl.attribute());
        boolean r = test(actual, cl.operator(), cl.value());
        trace.add(cl.attribute() + " " + symbol(cl.operator()) + (needsValue(cl.operator()) ? " " + cl.value() : "")
                + " → actual " + display(actual) + " → " + (r ? "true" : "false"));
        return r;
    }

    public static boolean needsValue(Operator op) {
        return switch (op) {
            case IS_SET, IS_NOT_SET, IS_TRUE, IS_FALSE -> false;
            default -> true;
        };
    }

    public static String symbol(Operator op) {
        return switch (op) {
            case EQ -> "=";
            case NE -> "≠";
            case GT -> ">";
            case GE -> "≥";
            case LT -> "<";
            case LE -> "≤";
            case IN -> "in";
            case NOT_IN -> "not in";
            case CONTAINS -> "contains";
            case NOT_CONTAINS -> "does not contain";
            case IS_SET -> "is set";
            case IS_NOT_SET -> "is not set";
            case IS_TRUE -> "is true";
            case IS_FALSE -> "is false";
        };
    }

    static boolean test(Object actual, Operator op, String value) {
        return switch (op) {
            case IS_SET -> isSet(actual);
            case IS_NOT_SET -> !isSet(actual);
            case IS_TRUE -> truthy(actual);
            case IS_FALSE -> isSet(actual) && !truthy(actual);
            case EQ -> isSet(actual) && anyElement(actual, a -> equalsLoose(a, value));
            case NE -> !isSet(actual) || !anyElement(actual, a -> equalsLoose(a, value));
            case GT, GE, LT, LE -> compareNumbers(actual, op, value);
            case IN -> isSet(actual) && anyElement(actual, a -> list(value).stream().anyMatch(v -> equalsLoose(a, v)));
            case NOT_IN -> !isSet(actual) || !anyElement(actual, a -> list(value).stream().anyMatch(v -> equalsLoose(a, v)));
            case CONTAINS -> contains(actual, value);
            case NOT_CONTAINS -> !contains(actual, value);
        };
    }

    private static boolean contains(Object actual, String value) {
        if (!isSet(actual) || value == null) return false;
        if (actual instanceof Collection<?> c) return c.stream().anyMatch(a -> equalsLoose(a, value));
        return String.valueOf(actual).toLowerCase(Locale.ROOT).contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean compareNumbers(Object actual, Operator op, String value) {
        BigDecimal a = number(actual);
        BigDecimal v = number(value);
        if (a == null || v == null) return false;
        int c = a.compareTo(v);
        return switch (op) {
            case GT -> c > 0;
            case GE -> c >= 0;
            case LT -> c < 0;
            case LE -> c <= 0;
            default -> false;
        };
    }

    private static boolean anyElement(Object actual, java.util.function.Predicate<Object> p) {
        if (actual instanceof Collection<?> c) return c.stream().anyMatch(p);
        return p.test(actual);
    }

    static boolean equalsLoose(Object actual, String value) {
        if (actual == null || value == null) return false;
        BigDecimal a = number(actual);
        BigDecimal v = number(value);
        if (a != null && v != null) return a.compareTo(v) == 0;
        if (actual instanceof Boolean b) return b == truthy(value);
        return String.valueOf(actual).trim().equalsIgnoreCase(value.trim());
    }

    static List<String> list(String value) {
        if (value == null) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static BigDecimal number(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        if (o instanceof Boolean) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || s.length() > 40) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean truthy(Object o) {
        if (o instanceof Boolean b) return b;
        if (o == null) return false;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("1");
    }

    private static boolean isSet(Object o) {
        if (o == null) return false;
        if (o instanceof Collection<?> c) return !c.isEmpty();
        return !String.valueOf(o).isBlank();
    }

    static String display(Object o) {
        if (o == null) return "(not set)";
        if (o instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return String.valueOf(o);
    }
}
