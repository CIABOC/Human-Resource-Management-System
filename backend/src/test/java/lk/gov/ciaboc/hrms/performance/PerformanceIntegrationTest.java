package lk.gov.ciaboc.hrms.performance;

import com.fasterxml.jackson.databind.JsonNode;
import lk.gov.ciaboc.hrms.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Evaluation by the division head → HR review → finalized, visible only to the right people (FR-PER-009). */
class PerformanceIntegrationTest extends IntegrationTestBase {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void evaluationLifecycleAndConfidentiality() throws Exception {
        String hr = login("hr", "Demo@12345");
        String head = login("head", "Demo@12345");
        String employee = login("employee", "Demo@12345");
        Long empId = jdbc.queryForObject("SELECT id FROM employee WHERE employee_no = 'CIA-0008'", Long.class);

        String code = "T" + (System.nanoTime() % 100_000_000);
        MvcResult pr = postJson(hr, "/api/performance/periods", Map.of("code", code, "name", "Test period " + code,
                "startDate", LocalDate.now().minusMonths(11).toString(), "endDate", LocalDate.now().plusDays(10).toString(),
                "dueDate", LocalDate.now().plusDays(20).toString()));
        expectStatus(pr, 200);
        long periodId = body(pr).get("id").asLong();

        // The model comes from the employee's category (INVESTIGATION → sample investigation model); default evaluator = division head.
        MvcResult cr = postJson(hr, "/api/performance/evaluations", Map.of("periodId", periodId, "employeeId", empId));
        expectStatus(cr, 200);
        JsonNode ev = body(cr);
        long id = ev.get("id").asLong();
        assertThat(ev.get("model").get("code").asText()).isEqualTo("SAMPLE-INV");
        assertThat(ev.get("evaluatorName").asText()).contains("Director Investigation");
        expectStatus(postJson(hr, "/api/performance/evaluations", Map.of("periodId", periodId, "employeeId", empId)), 409);

        // Only the evaluator may rate; the employee cannot even see a draft.
        expectStatus(putJson(hr, "/api/performance/evaluations/" + id, Map.of("ratings", List.of())), 403);
        assertThat(status(employee, "/api/performance/evaluations/" + id)).isEqualTo(404);

        JsonNode detail = getJson(head, "/api/performance/evaluations/" + id);
        List<Map<String, Object>> ratings = new ArrayList<>();
        for (JsonNode l : detail.get("lines")) ratings.add(Map.of("criterionId", l.get("criterionId").asLong(), "rating", 4, "comment", "Good"));
        // Out-of-scale rating rejected.
        expectStatus(putJson(head, "/api/performance/evaluations/" + id, Map.of("ratings",
                List.of(Map.of("criterionId", ratings.get(0).get("criterionId"), "rating", 9)))), 400);
        // Incomplete evaluation cannot be submitted.
        expectStatus(putJson(head, "/api/performance/evaluations/" + id, Map.of("ratings", ratings.subList(0, 1), "evaluatorComments", "x")), 200);
        expectStatus(postJson(head, "/api/performance/evaluations/" + id + "/submit", Map.of()), 422);

        MvcResult saved = putJson(head, "/api/performance/evaluations/" + id, Map.of("ratings", ratings,
                "evaluatorComments", "Consistently good investigative work."));
        expectStatus(saved, 200);
        assertThat(body(saved).get("overallScore").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(body(saved).get("overallPercent").decimalValue()).isEqualByComparingTo("75.00");
        MvcResult sub = postJson(head, "/api/performance/evaluations/" + id + "/submit", Map.of());
        expectStatus(sub, 200);
        long wf = body(sub).get("workflowInstanceId").asLong();
        assertThat(status(employee, "/api/performance/evaluations/" + id)).isEqualTo(404);

        // HR review approves → finalized.
        expectStatus(postJson(hr, "/api/workflow/instances/" + wf + "/act", Map.of("decision", "APPROVE", "comment", "Reviewed")), 200);

        JsonNode mine = getJson(employee, "/api/performance/evaluations/" + id);
        assertThat(mine.get("status").asText()).isEqualTo("FINALIZED");
        assertThat(mine.get("canAcknowledge").asBoolean()).isTrue();
        assertThat(getJson(employee, "/api/performance/my/evaluations").findValues("id").stream().map(JsonNode::asLong).toList()).contains(id);
        MvcResult ack = postJson(employee, "/api/performance/evaluations/" + id + "/acknowledge", Map.of("comments", "Thank you."));
        expectStatus(ack, 200);
        assertThat(body(ack).get("acknowledgedAt").isNull()).isFalse();
        expectStatus(postJson(employee, "/api/performance/evaluations/" + id + "/acknowledge", Map.of()), 422);

        // Strict confidentiality: other employees (even with broad data scope but no PER_VIEW) cannot see it.
        for (String other : List.of("finance", "auditor")) {
            String t = login(other, "Demo@12345");
            assertThat(status(t, "/api/performance/evaluations/" + id)).isEqualTo(404);
            assertThat(status(t, "/api/performance/evaluations")).isEqualTo(403);
        }
        // HR (PER_VIEW, acted on the workflow) and the evaluator can.
        assertThat(status(hr, "/api/performance/evaluations/" + id)).isEqualTo(200);
        assertThat(status(head, "/api/performance/evaluations/" + id)).isEqualTo(200);
    }

    private int status(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", token)).andReturn().getResponse().getStatus();
    }
}
