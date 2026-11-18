package lk.gov.ciaboc.hrms.reports;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A tabular report backed by one SQL query. Modules contribute definitions through {@link ReportProvider} beans.
 *
 * <p>SQL conventions (named parameters, always bound, possibly null - cast them when testing for null):
 * <ul>
 *   <li>{@code :from}, {@code :to}, {@code :asOf} (date; asOf defaults to today), {@code :period} (varchar 'YYYY-MM'),
 *       {@code :designationId} (bigint), {@code :status} (varchar).</li>
 *   <li>{@code /*SCOPE:e*}{@code /} is replaced by the caller's data-scope and restricted-record filter on employee alias
 *       {@code e}. Every report that lists employees MUST include it.</li>
 *   <li>{@code /*ORG:expr*}{@code /} is replaced by the org-unit filter (selected unit and its sub-units) on the given
 *       column expression.</li>
 * </ul>
 * Example: {@code ... WHERE 1=1 /*SCOPE:e*}{@code / /*ORG:e.org_unit_code*}{@code / AND (CAST(:status AS varchar) IS NULL OR e.status = :status)}
 */
public record ReportDefinition(String code, String category, String title, String description,
                               List<Column> columns, String sql, Set<Filter> filters, String permission,
                               boolean sensitive) {

    public enum Filter {DATE_RANGE, AS_OF, PERIOD, ORG_UNIT, DESIGNATION, STATUS}

    /** type: text | number | money | date | datetime | boolean */
    public record Column(String key, String label, String type) {
    }

    public static Builder builder(String code, String category, String title) {
        return new Builder(code, category, title);
    }

    public static final class Builder {
        private final String code;
        private final String category;
        private final String title;
        private String description = "";
        private final List<Column> columns = new ArrayList<>();
        private String sql;
        private final Set<Filter> filters = EnumSet.noneOf(Filter.class);
        private String permission = "RPT_VIEW";
        private boolean sensitive;

        private Builder(String code, String category, String title) {
            this.code = code;
            this.category = category;
            this.title = title;
        }

        public Builder description(String d) {
            this.description = d;
            return this;
        }

        public Builder col(String key, String label) {
            return col(key, label, "text");
        }

        public Builder col(String key, String label, String type) {
            columns.add(new Column(key, label, type));
            return this;
        }

        public Builder sql(String sql) {
            this.sql = sql;
            return this;
        }

        public Builder filters(Filter... f) {
            filters.addAll(List.of(f));
            return this;
        }

        /** Module permission required in addition to RPT_VIEW. */
        public Builder permission(String p) {
            this.permission = p;
            return this;
        }

        /** Sensitive reports (payroll, performance) additionally require RPT_SENSITIVE (RP-02). */
        public Builder sensitive() {
            this.sensitive = true;
            return this;
        }

        public ReportDefinition build() {
            return new ReportDefinition(code, category, title, description, List.copyOf(columns), sql,
                    Set.copyOf(filters), permission, sensitive);
        }
    }
}
