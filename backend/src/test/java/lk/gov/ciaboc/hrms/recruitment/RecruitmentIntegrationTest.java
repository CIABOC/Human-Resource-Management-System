package lk.gov.ciaboc.hrms.recruitment;

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

/** Vacancy → applicant → exam/interview → ranked selection approved by Management → appointment as employee. */
class RecruitmentIntegrationTest extends IntegrationTestBase {
    @Autowired
    JdbcTemplate jdbc;

    private static String uniqueNic() {
        long n = Math.abs(System.nanoTime()) % 10_000_000_000L;
        return "19" + String.format("%010d", n);
    }

    @Test
    void vacancyToAppointmentWithApprovedSelection() throws Exception {
        String hr = login("hr", "Demo@12345");
        String dg = login("dg", "Demo@12345");
        Long io = jdbc.queryForObject("SELECT id FROM designation WHERE code = 'IO'", Long.class);

        // Cadre-based suggestions include the vacant Investigating Officer posts in INV-OPS.
        JsonNode sugg = getJson(hr, "/api/recruitment/vacancies/suggestions");
        boolean found = false;
        for (JsonNode s : sugg) if (s.get("designationId").asLong() == io && "INV-OPS".equals(s.get("orgUnitCode").asText())) found = true;
        assertThat(found).isTrue();

        // A vacancy for more posts than the cadre leaves vacant is rejected.
        MvcResult tooMany = postJson(hr, "/api/recruitment/vacancies", Map.of("title", "IO", "designationId", io,
                "orgUnitCode", "INV-OPS", "numPosts", 500));
        expectStatus(tooMany, 422);

        MvcResult vr = postJson(hr, "/api/recruitment/vacancies", Map.of("title", "Investigating Officer (test)", "designationId", io,
                "orgUnitCode", "INV-OPS", "numPosts", 1, "closingDate", LocalDate.now().plusDays(30).toString()));
        expectStatus(vr, 200);
        long vacancyId = body(vr).get("id").asLong();
        expectStatus(postJson(hr, "/api/recruitment/vacancies/" + vacancyId + "/status", Map.of("status", "OPEN")), 200);

        // Visible to all staff as an available position.
        String emp = login("employee", "Demo@12345");
        JsonNode positions = getJson(emp, "/api/recruitment/positions");
        assertThat(positions.findValues("id").stream().map(JsonNode::asLong).toList()).contains(vacancyId);
        assertThat(mvc.perform(get("/api/recruitment/vacancies").header("Authorization", emp)).andReturn().getResponse().getStatus()).isEqualTo(403);

        // Two applicants.
        long a1 = applicant(hr, vacancyId, "Applicant One Perera", "A. O. Perera");
        long a2 = applicant(hr, vacancyId, "Applicant Two Silva", "A. T. Silva");
        for (long a : List.of(a1, a2))
            expectStatus(postJson(hr, "/api/recruitment/applicants/" + a + "/status", Map.of("status", "SHORTLISTED")), 200);
        // Selection outcomes can never be set by hand.
        expectStatus(postJson(hr, "/api/recruitment/applicants/" + a1 + "/status", Map.of("status", "SELECTED")), 422);

        MvcResult ex = postJson(hr, "/api/recruitment/exams", Map.of("vacancyId", vacancyId, "title", "Written test",
                "examType", "WRITTEN", "examDate", LocalDate.now().toString(), "venue", "CIABOC HQ", "maxMarks", 100));
        expectStatus(ex, 200);
        long examId = body(ex).get("id").asLong();
        expectStatus(putJson(hr, "/api/recruitment/exams/" + examId + "/results", List.of(
                Map.of("applicantId", a1, "marks", 85), Map.of("applicantId", a2, "marks", 60))), 200);
        // Marks above the maximum are rejected.
        expectStatus(putJson(hr, "/api/recruitment/exams/" + examId + "/results", List.of(Map.of("applicantId", a1, "marks", 101))), 400);

        for (long a : List.of(a1, a2)) {
            MvcResult iv = postJson(hr, "/api/recruitment/interviews", Map.of("applicantId", a, "interviewDate", LocalDate.now().toString(),
                    "panel", "Director Investigation; HR Officer", "venue", "Board room", "maxMarks", 50));
            expectStatus(iv, 200);
            expectStatus(putJson(hr, "/api/recruitment/interviews/" + body(iv).get("id").asLong() + "/result",
                    Map.of("status", "COMPLETED", "marks", a == a1 ? 40 : 45, "remarks", "ok")), 200);
        }

        // System ranks; the panel decides.
        MvcResult sel = postJson(hr, "/api/recruitment/vacancies/" + vacancyId + "/selections", Map.of());
        expectStatus(sel, 200);
        JsonNode s = body(sel);
        long selId = s.get("id").asLong();
        assertThat(s.get("items").get(0).get("applicantId").asLong()).isEqualTo(a1); // 85%*60 + 80%*40 = 83 vs 36+36 = 72
        assertThat(s.get("items").get(0).get("rankNo").asInt()).isEqualTo(1);

        // Recommending the second-ranked applicant over the first requires a justification.
        expectStatus(putJson(hr, "/api/recruitment/selections/" + selId, Map.of("items", List.of(
                Map.of("applicantId", a2, "recommended", true), Map.of("applicantId", a1, "recommended", false)))), 200);
        expectStatus(postJson(hr, "/api/recruitment/selections/" + selId + "/submit", Map.of()), 422);
        expectStatus(putJson(hr, "/api/recruitment/selections/" + selId, Map.of("remarks", "Panel recommends rank 1", "items", List.of(
                Map.of("applicantId", a1, "recommended", true), Map.of("applicantId", a2, "recommended", false)))), 200);
        MvcResult sub = postJson(hr, "/api/recruitment/selections/" + selId + "/submit", Map.of());
        expectStatus(sub, 200);
        long wf = body(sub).get("workflowInstanceId").asLong();
        assertThat(getJson(hr, "/api/recruitment/applicants/" + a1).get("applicant").get("status").asText()).isEqualTo("RECOMMENDED");

        // The requester cannot approve; Management (dg) approves.
        expectStatus(postJson(hr, "/api/workflow/instances/" + wf + "/act", Map.of("decision", "APPROVE")), 422);
        expectStatus(postJson(dg, "/api/workflow/instances/" + wf + "/act", Map.of("decision", "APPROVE", "comment", "Approved")), 200);
        assertThat(getJson(hr, "/api/recruitment/applicants/" + a1).get("applicant").get("status").asText()).isEqualTo("SELECTED");
        assertThat(getJson(hr, "/api/recruitment/applicants/" + a2).get("applicant").get("status").asText()).isEqualTo("NOT_SELECTED");

        // Appointment converts the applicant into an employee without re-entering data (FR-REC-008).
        String empNo = "TR-" + (System.nanoTime() % 1_000_000);
        MvcResult ap = postJson(hr, "/api/recruitment/applicants/" + a1 + "/appoint", Map.of("employeeNo", empNo,
                "appointmentDate", LocalDate.now().toString(), "employmentType", "PERMANENT", "employeeCategory", "INVESTIGATION"));
        expectStatus(ap, 200);
        long employeeId = body(ap).get("employeeId").asLong();
        JsonNode e = getJson(hr, "/api/employees/" + employeeId);
        assertThat(e.toString()).contains("Applicant One Perera").contains("INV-OPS");
        JsonNode history = getJson(hr, "/api/employees/" + employeeId + "/service-history");
        assertThat(history.get("assignments").size()).isEqualTo(1);
        assertThat(history.get("events").findValues("eventType").stream().map(JsonNode::asText).toList())
                .contains("APPOINTMENT", "RECRUITMENT");
        assertThat(getJson(hr, "/api/recruitment/vacancies/" + vacancyId).get("status").asText()).isEqualTo("FILLED");
        // Cannot appoint twice.
        expectStatus(postJson(hr, "/api/recruitment/applicants/" + a1 + "/appoint", Map.of("employeeNo", empNo + "X",
                "employmentType", "PERMANENT", "employeeCategory", "INVESTIGATION")), 422);

        // Appointment letter from the configurable template, logged and queued for e-mail.
        long templateId = -1;
        for (JsonNode t : getJson(hr, "/api/recruitment/letter-templates"))
            if ("APPOINTMENT".equals(t.get("code").asText()) && "en".equals(t.get("language").asText())) templateId = t.get("id").asLong();
        MvcResult pv = postJson(hr, "/api/recruitment/applicants/" + a1 + "/letters/preview", Map.of("templateId", templateId));
        expectStatus(pv, 200);
        assertThat(body(pv).get("html").asText()).contains("Investigating Officer").contains(empNo);
        MvcResult letter = postJson(hr, "/api/recruitment/applicants/" + a1 + "/letters", Map.of("templateId", templateId, "sendEmail", true));
        expectStatus(letter, 200);
        assertThat(body(letter).get("status").asText()).isIn("QUEUED", "PREPARED");
        assertThat(getJson(hr, "/api/recruitment/applicants/" + a1 + "/letters").size()).isEqualTo(1);
        // Regret letters in bulk for those not selected.
        long regret = -1;
        for (JsonNode t : getJson(hr, "/api/recruitment/letter-templates"))
            if ("REGRET".equals(t.get("code").asText()) && "en".equals(t.get("language").asText())) regret = t.get("id").asLong();
        MvcResult bulk = postJson(hr, "/api/recruitment/vacancies/" + vacancyId + "/letters", Map.of("templateId", regret, "applicantStatus", "NOT_SELECTED"));
        expectStatus(bulk, 200);
        assertThat(body(bulk).get("generated").asInt()).isEqualTo(1);
    }

    private long applicant(String token, long vacancyId, String name, String initials) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("vacancyId", vacancyId);
        m.put("title", "MR");
        m.put("fullName", name);
        m.put("nameWithInitials", initials);
        m.put("nic", uniqueNic());
        m.put("dateOfBirth", "1995-03-04");
        m.put("gender", "MALE");
        m.put("email", "applicant" + Math.abs(System.nanoTime() % 100000) + "@example.lk");
        m.put("mobile", "0771234567");
        m.put("address", "12 Main Street, Colombo");
        MvcResult r = postJson(token, "/api/recruitment/applicants", m);
        expectStatus(r, 200);
        return body(r).get("id").asLong();
    }
}
