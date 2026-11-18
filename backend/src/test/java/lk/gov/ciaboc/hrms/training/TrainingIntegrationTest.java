package lk.gov.ciaboc.hrms.training;

import com.fasterxml.jackson.databind.JsonNode;
import lk.gov.ciaboc.hrms.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Nomination with capacity control, two-stage approval (division head, HR) and completion. */
class TrainingIntegrationTest extends IntegrationTestBase {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void nominationCapacityApprovalAndCompletion() throws Exception {
        String hr = login("hr", "Demo@12345");
        Long e8 = jdbc.queryForObject("SELECT id FROM employee WHERE employee_no = 'CIA-0008'", Long.class);
        Long e15 = jdbc.queryForObject("SELECT id FROM employee WHERE employee_no = 'CIA-0015'", Long.class);

        String code = "TRN-T" + (System.nanoTime() % 100_000_000);
        MvcResult pr = postJson(hr, "/api/training/programmes", Map.of("code", code, "title", "Financial investigation techniques",
                "provider", "CIABOC Training Unit", "startDate", LocalDate.now().plusDays(10).toString(),
                "endDate", LocalDate.now().plusDays(12).toString(), "venue", "Colombo", "capacity", 1, "cost", 15000));
        expectStatus(pr, 200);
        long programmeId = body(pr).get("programme").get("id").asLong();
        expectStatus(postJson(hr, "/api/training/programmes/" + programmeId + "/status", Map.of("status", "OPEN")), 200);

        MvcResult n1 = postJson(hr, "/api/training/nominations", Map.of("programmeId", programmeId, "employeeId", e8, "reason", "Skills"));
        expectStatus(n1, 200);
        // Over capacity: rejected.
        MvcResult n2 = postJson(hr, "/api/training/nominations", Map.of("programmeId", programmeId, "employeeId", e15, "reason", "Skills"));
        expectStatus(n2, 422);
        assertThat(body(n2).get("code").asText()).isEqualTo("CAPACITY_FULL");

        // Stage 1: division head; stage 2: HR (another HR officer, since hr is the requester).
        long wf = body(n1).get("workflowInstanceId").asLong();
        expectStatus(postJson(login("head", "Demo@12345"), "/api/workflow/instances/" + wf + "/act", Map.of("decision", "APPROVE")), 200);
        expectStatus(postJson(login("hr2", "Demo@12345"), "/api/workflow/instances/" + wf + "/act", Map.of("decision", "APPROVE")), 200);

        String emp = login("employee", "Demo@12345");
        JsonNode mine = getJson(emp, "/api/training/my");
        JsonNode mineRow = null;
        for (JsonNode r : mine) if (r.get("programmeId").asLong() == programmeId) mineRow = r;
        assertThat(mineRow).isNotNull();
        assertThat(mineRow.get("status").asText()).isEqualTo("APPROVED");
        // Self-nomination to a full programme is rejected too.
        String other = login("auditor", "Demo@12345");
        expectStatus(postJson(other, "/api/training/my/nominations", Map.of("programmeId", programmeId, "reason", "Interested")), 422);

        // Completion cannot be recorded before the programme starts.
        long nomId = mineRow.get("id").asLong();
        expectStatus(putJson(hr, "/api/training/nominations/" + nomId + "/completion", Map.of("completionStatus", "COMPLETED")), 422);
        JsonNode history = getJson(hr, "/api/training/employees/" + e8 + "/history");
        assertThat(history.findValues("programmeId").stream().map(JsonNode::asLong).toList()).contains(programmeId);
    }
}
