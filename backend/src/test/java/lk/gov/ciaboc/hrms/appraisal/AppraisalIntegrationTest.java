package lk.gov.ciaboc.hrms.appraisal;

import com.fasterxml.jackson.databind.JsonNode;
import lk.gov.ciaboc.hrms.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Performance appraisal end to end: demo data for the Director General's management dashboard, then one appraisal
 * walked through employee progress, evidence, self-assessment, supervisor verification and behavioural assessment,
 * reviewing officer approval, acknowledgement and appeal - with the rules on the way.
 */
class AppraisalIntegrationTest extends IntegrationTestBase {
    private static final String PW = "Demo@12345";

    @Test
    void directorGeneralSeesAggregatedAppraisalDashboard() throws Exception {
        String dg = login("dg", PW);
        JsonNode ctx = getJson(dg, "/api/appraisal/context");
        assertThat(ctx.get("overview").asBoolean()).isTrue();
        assertThat(ctx.get("report").asBoolean()).isTrue();

        JsonNode ov = getJson(dg, "/api/appraisal/overview");
        assertThat(ov.get("totalAppraisals").asInt()).isGreaterThan(0);
        assertThat(ov.get("completed").asInt()).isGreaterThan(0);
        assertThat(ov.get("completion").size()).isGreaterThan(0);
        assertThat(ov.get("averageScore").isNull()).isFalse();
        assertThat(ov.get("canDrillDown").asBoolean()).isTrue();

        String dept = ov.get("completion").get(0).get("unitCode").asText();
        getJson(dg, "/api/appraisal/overview/drilldown?departmentCode=" + dept);

        JsonNode dash = getJson(dg, "/api/dashboard");
        boolean tile = false;
        for (JsonNode t : dash.get("tiles")) if (t.get("key").asText().equals("per-apr-completion")) tile = true;
        assertThat(tile).as("DG dashboard shows the appraisal completion tile").isTrue();

        // An ordinary employee cannot see the aggregated dashboard.
        String emp = login("employee", PW);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/appraisal/overview")
                .header("Authorization", emp)).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void appraisalRunsFromProgressToAppeal() throws Exception {
        String emp = login("employee", PW);
        JsonNode my = getJson(emp, "/api/appraisal/my");
        JsonNode a = my.get("appraisal");
        assertThat(a.isNull()).isFalse();
        long id = a.get("id").asLong();
        assertThat(a.get("agreementStatus").asText()).isEqualTo("FINAL");
        assertThat(a.get("status").asText()).isEqualTo("DRAFT");
        long supervisorId = a.get("supervisorUserId").asLong();

        // Submit for verification needs evidence.
        long kpi = a.get("kpis").get(2).get("id").asLong();
        expectStatus(postJson(emp, "/api/appraisal/kpis/" + kpi + "/submit", Map.of()), 422);
        MockMultipartFile file = new MockMultipartFile("file", "evidence.pdf", "application/pdf", "%PDF-1.4 demo".getBytes());
        MvcResult up = mvc.perform(multipart("/api/appraisal/kpis/" + kpi + "/evidence").file(file).header("Authorization", emp)).andReturn();
        expectStatus(up, 200);
        expectStatus(putJson(emp, "/api/appraisal/kpis/" + kpi + "/progress", Map.of("selfProgress", 61, "qualityMeasure", "Minor corrections",
                "delayExplanation", "Two reports awaited external input")), 200);
        MvcResult sub = postJson(emp, "/api/appraisal/kpis/" + kpi + "/submit", Map.of());
        expectStatus(sub, 200);
        assertThat(findKpi(body(sub), kpi).get("progressStatus").asText()).isEqualTo("SUBMITTED");

        // Self-assessment.
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode k : body(sub).get("kpis")) lines.add(Map.of("kpiId", k.get("id").asLong(), "selfProgress", 80, "note", "ok"));
        MvcResult sa = putJson(emp, "/api/appraisal/appraisals/" + id + "/self-assessment",
                Map.of("lines", lines, "achievements", "A", "challenges", "B", "comments", "C", "submit", true));
        expectStatus(sa, 200);
        assertThat(body(sa).get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(body(sa).get("behaviour").size()).as("employee does not see the behavioural assessment").isZero();

        // Supervisor: completing verification needs a decision on every KPI.
        String sup = loginUser(supervisorId);
        expectStatus(putJson(sup, "/api/appraisal/appraisals/" + id + "/verification", Map.of("lines", List.of(), "complete", true)), 422);
        List<Map<String, Object>> ver = new ArrayList<>();
        for (JsonNode k : body(sa).get("kpis")) ver.add(Map.of("kpiId", k.get("id").asLong(), "decision", "VERIFIED", "verifiedValue", 80));
        MvcResult v = putJson(sup, "/api/appraisal/appraisals/" + id + "/verification", Map.of("lines", ver, "complete", true));
        expectStatus(v, 200);
        assertThat(body(v).get("kpiScore").decimalValue()).isEqualByComparingTo("80");

        // Behavioural: every rating needs a justification.
        JsonNode crit = body(v).get("behaviour");
        List<Map<String, Object>> noJust = new ArrayList<>();
        List<Map<String, Object>> ok = new ArrayList<>();
        for (JsonNode c : crit) {
            noJust.add(Map.of("criterionId", c.get("criterionId").asLong(), "rating", 5, "notApplicable", false));
            boolean na = c.get("code").asText().equals("LEADERSHIP");
            Map<String, Object> m = new HashMap<>();
            m.put("criterionId", c.get("criterionId").asLong());
            m.put("rating", na ? null : 4);
            m.put("notApplicable", na);
            m.put("justification", na ? null : "Observed");
            ok.add(m);
        }
        expectStatus(putJson(sup, "/api/appraisal/appraisals/" + id + "/behaviour", Map.of("ratings", noJust, "submit", true)), 422);
        MvcResult b = putJson(sup, "/api/appraisal/appraisals/" + id + "/behaviour", Map.of("ratings", ok, "strengths", "S", "improvements", "I", "submit", true));
        expectStatus(b, 200);
        JsonNode verified = body(b);
        assertThat(verified.get("status").asText()).isEqualTo("VERIFIED");
        assertThat(verified.get("behaviourScore").decimalValue()).isEqualByComparingTo("75"); // rating 4 of 5, N/A left out
        // 80 x 70% + 75 x 30% = 78.5
        assertThat(verified.get("overallScore").decimalValue()).isEqualByComparingTo("78.5");

        // Reviewing officer (or HR when none): an adjustment needs a justification.
        String rev = verified.get("reviewerUserId").isNull() ? login("hr2", PW) : loginUser(verified.get("reviewerUserId").asLong());
        expectStatus(postJson(rev, "/api/appraisal/appraisals/" + id + "/approve", Map.of("adjustment", 3)), 422);
        MvcResult ap = postJson(rev, "/api/appraisal/appraisals/" + id + "/approve", Map.of("adjustment", 3, "justification", "Moderation", "comments", "Good"));
        expectStatus(ap, 200);
        assertThat(body(ap).get("finalScore").decimalValue()).isEqualByComparingTo("81.5");
        assertThat(body(ap).get("category").asText()).isEqualTo("Very good");

        // Appeals open only after acknowledgement.
        expectStatus(mvc.perform(multipart("/api/appraisal/appraisals/" + id + "/appeals").param("grounds", "x").header("Authorization", emp)).andReturn(), 422);
        MvcResult ack = postJson(emp, "/api/appraisal/appraisals/" + id + "/acknowledge", Map.of("comment", "Seen"));
        expectStatus(ack, 200);
        assertThat(body(ack).get("status").asText()).isEqualTo("FINALIZED");
        assertThat(body(ack).get("behaviour").size()).as("employee sees the finalized assessment").isGreaterThan(0);
        MvcResult appeal = mvc.perform(multipart("/api/appraisal/appraisals/" + id + "/appeals").param("grounds", "Evidence was overlooked")
                .header("Authorization", emp)).andReturn();
        expectStatus(appeal, 200);
        assertThat(getJson(emp, "/api/appraisal/my/appeals").size()).isEqualTo(1);
    }

    @Test
    void policyChangesNeedApprovalBySomeoneElseAndNeverBySystemAdmin() throws Exception {
        String hr = login("hr", PW);
        JsonNode screen = getJson(hr, "/api/appraisal/policy");
        List<Map<String, Object>> bands = new ArrayList<>();
        for (JsonNode bnd : screen.get("approved").get("bands"))
            bands.add(Map.of("label", bnd.get("label").asText(), "ratingValue", bnd.get("ratingValue").asInt(), "category", bnd.get("category").asText(),
                    "minScore", bnd.get("minScore").decimalValue(), "maxScore", bnd.get("maxScore").decimalValue()));
        expectStatus(putJson(hr, "/api/appraisal/policy/draft", Map.of("kpiWeight", 60, "behaviourWeight", 30, "bands", bands)), 422);
        MvcResult d = putJson(hr, "/api/appraisal/policy/draft", Map.of("kpiWeight", 60, "behaviourWeight", 40, "bands", bands));
        expectStatus(d, 200);
        long pid = body(d).get("id").asLong();
        expectStatus(postJson(hr, "/api/appraisal/policy/" + pid + "/submit", Map.of()), 200);
        expectStatus(postJson(login("admin", "Admin@12345"), "/api/appraisal/policy/" + pid + "/approve", Map.of()), 403);
        MvcResult ok = postJson(login("dg", PW), "/api/appraisal/policy/" + pid + "/approve", Map.of());
        expectStatus(ok, 200);
        assertThat(body(ok).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void syncIsAdministratorOnlyAndRecordsStatus() throws Exception {
        expectStatus(postJson(login("dg", PW), "/api/appraisal/sync/all", Map.of()), 403);
        MvcResult r = postJson(login("admin", "Admin@12345"), "/api/appraisal/sync/all", Map.of());
        expectStatus(r, 200);
        for (JsonNode m : body(r).get("modules")) assertThat(m.get("status").asText()).isEqualTo("SUCCESS");
    }

    private static JsonNode findKpi(JsonNode view, long kpiId) {
        for (JsonNode k : view.get("kpis")) if (k.get("id").asLong() == kpiId) return k;
        throw new AssertionError("KPI not found");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private lk.gov.ciaboc.hrms.security.AppUserRepository users;

    private String loginUser(long userId) throws Exception {
        return login(users.findById(userId).orElseThrow().getUsername(), PW);
    }
}
