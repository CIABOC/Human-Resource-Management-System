package lk.gov.ciaboc.hrms;

import com.fasterxml.jackson.databind.JsonNode;
import lk.gov.ciaboc.hrms.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** REQ 7.6 org chart, §5 cadre split, REQ 6.11 reconciliation, D-1/D-6 dashboard and REQ 7.4 nesting checks. */
class OrgChartIntegrationTest extends IntegrationTestBase {

    private JsonNode find(JsonNode nodes, String code) {
        for (JsonNode n : nodes) {
            if (n.get("code").asText().equals(code)) return n;
            JsonNode inner = find(n.get("children"), code);
            if (inner != null) return inner;
        }
        return null;
    }

    @Test
    void orgChartShowsHeadsAndRollUpsAndCadreReconciles() throws Exception {
        String est = login("establishment", "Demo@12345");
        JsonNode chart = getJson(est, "/api/establishment/org-chart");
        JsonNode dg = find(chart, "DG_OFFICE");
        assertThat(dg).isNotNull();
        assertThat(dg.get("headEmployeeName").asText()).isEqualTo("D. D. General");
        JsonNode inv = find(chart, "INV");
        assertThat(inv.get("headTitle").isNull()).isFalse();
        assertThat(inv.get("headEmployeeName").asText()).isEqualTo("D. I. Director");
        // Roll-ups include sub-units.
        assertThat(inv.get("rollupApproved").asInt()).isGreaterThanOrEqualTo(inv.get("approvedCadre").asInt());
        assertThat(inv.get("rollupApproved").asInt())
                .isEqualTo(inv.get("approvedCadre").asInt() + sum(inv.get("children"), "rollupApproved"));

        // Cadre rows carry the filled / acting split and vacant = approved - filled - acting.
        JsonNode rows = getJson(est, "/api/establishment/cadre");
        for (JsonNode r : rows) {
            long occupied = r.get("filled").asLong() + r.get("acting").asLong();
            assertThat(r.get("occupied").asLong()).isEqualTo(occupied);
            assertThat(r.get("vacant").asLong()).isEqualTo(Math.max(0, r.get("approved").asLong() - occupied));
        }

        // The seeded cadre matrix reconciles with the 971-post control total; the DG is statutory and excluded.
        JsonNode recon = getJson(est, "/api/establishment/cadre/reconciliation");
        assertThat(recon.get("organizationControl").asInt()).isEqualTo(971);
        assertThat(recon.get("matrixTotal").asInt()).isEqualTo(971);
        assertThat(recon.get("statutoryExcluded").asInt()).isEqualTo(1);
        for (JsonNode l : recon.get("units")) assertThat(l.get("difference").asInt()).as(l.get("refCode").asText()).isZero();
        for (JsonNode l : recon.get("designations")) assertThat(l.get("difference").asInt()).as(l.get("refCode").asText()).isZero();

        JsonNode dash = getJson(est, "/api/establishment/dashboard");
        assertThat(dash.get("approved").asInt()).isGreaterThanOrEqualTo(971);
        assertThat(dash.get("divisions").size()).isGreaterThan(3);
    }

    @Test
    void categoryNestingRejectsCircularAndClosedTargets() throws Exception {
        String est = login("establishment", "Demo@12345");
        JsonNode chart = getJson(est, "/api/establishment/categorization");
        long legal = 0, inv = 0, legacy = 0;
        for (JsonNode c : chart.get("categories")) {
            switch (c.get("code").asText()) {
                case "LEGAL" -> legal = c.get("id").asLong();
                case "INVESTIGATION" -> inv = c.get("id").asLong();
                case "LEGACY" -> legacy = c.get("id").asLong();
                default -> { }
            }
        }
        JsonNode self = getJson(est, "/api/establishment/categorization/check-category-move?categoryId=" + legal + "&toParentId=" + legal);
        assertThat(self.get("allowed").asBoolean()).isFalse();
        JsonNode closed = getJson(est, "/api/establishment/categorization/check-category-move?categoryId=" + legal + "&toParentId=" + legacy);
        assertThat(closed.get("allowed").asBoolean()).isFalse();
        JsonNode ok = getJson(est, "/api/establishment/categorization/check-category-move?categoryId=" + legal + "&toParentId=" + inv);
        assertThat(ok.get("allowed").asBoolean()).isTrue();
    }

    private static int sum(JsonNode nodes, String field) {
        int s = 0;
        for (JsonNode n : nodes) s += n.get(field).asInt();
        return s;
    }
}
