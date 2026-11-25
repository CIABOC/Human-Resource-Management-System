package lk.gov.ciaboc.hrms;

import com.fasterxml.jackson.databind.JsonNode;
import lk.gov.ciaboc.hrms.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CategorizationIntegrationTest extends IntegrationTestBase {
    @Autowired
    JdbcTemplate jdbc;

    private JsonNode column(JsonNode chart, String code) {
        for (JsonNode c : chart.get("categories")) if (c.get("code").asText().equals(code)) return c;
        throw new AssertionError("No category " + code);
    }

    @Test
    void movesApplyOnlyAfterApprovalAndClosedCategoriesRejectDrops() throws Exception {
        String est = login("establishment", "Demo@12345");
        String dg = login("headhr", "Demo@12345");
        JsonNode chart = getJson(est, "/api/establishment/categorization");
        assertThat(chart.get("rootName").asText()).isEqualTo("Commission");
        long legal = column(chart, "LEGAL").get("id").asLong();
        long legacy = column(chart, "LEGACY").get("id").asLong();
        long icto = jdbc.queryForObject("SELECT id FROM designation WHERE code = 'TO'", Long.class);
        assertThat(chart.get("unassigned").findValuesAsText("code")).contains("TO");

        // Closed category rejects the drop.
        MvcResult closed = postJson(est, "/api/establishment/categorization/batches", Map.of(
                "effectiveDate", LocalDate.now().toString(), "reason", "test",
                "moves", List.of(Map.of("designationId", icto, "toCategoryId", legacy))));
        expectStatus(closed, 422);

        MvcResult ok = postJson(est, "/api/establishment/categorization/batches", Map.of(
                "effectiveDate", LocalDate.now().toString(), "reason", "ICT officers handle legal e-discovery",
                "moves", List.of(Map.of("designationId", icto, "toCategoryId", legal))));
        expectStatus(ok, 200);
        long instance = body(ok).get("workflowInstanceId").asLong();

        // Draft submitted: still unassigned and flagged pending until approved.
        JsonNode pending = getJson(est, "/api/establishment/categorization");
        assertThat(pending.get("pendingBatches").size()).isEqualTo(1);
        // A second move of the same position while pending is refused.
        expectStatus(postJson(est, "/api/establishment/categorization/batches", Map.of(
                "reason", "dup", "moves", List.of(Map.of("designationId", icto, "toCategoryId", legal)))), 422);

        expectStatus(postJson(dg, "/api/workflow/instances/" + instance + "/act", Map.of("decision", "APPROVE")), 200);
        JsonNode after = getJson(est, "/api/establishment/categorization");
        assertThat(column(after, "LEGAL").get("positions").findValuesAsText("code")).contains("TO");
        assertThat(after.get("unassigned").findValuesAsText("code")).doesNotContain("TO");

        JsonNode history = getJson(est, "/api/establishment/categorization/history");
        assertThat(history.get(0).get("to").asText()).isEqualTo("Legal");
        assertThat(history.get(0).get("status").asText()).isEqualTo("APPLIED");
        expectStatus(postJson(est, "/api/reports/EST_CATEGORIZATION_HISTORY/run", Map.of()), 200);

        // Employees without establishment rights cannot submit.
        String employee = login("employee", "Demo@12345");
        expectStatus(postJson(employee, "/api/establishment/categorization/batches", Map.of("reason", "x", "moves", List.of())), 403);
    }
}
