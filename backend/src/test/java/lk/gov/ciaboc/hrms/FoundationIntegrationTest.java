package lk.gov.ciaboc.hrms;

import com.fasterxml.jackson.databind.JsonNode;
import lk.gov.ciaboc.hrms.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class FoundationIntegrationTest extends IntegrationTestBase {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        MvcResult r = mvc.perform(get("/api/employees")).andReturn();
        expectStatus(r, 401);
    }

    @Test
    void wrongPasswordAndWrongOtpAreRejected() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "employee", "password", "nope")))).andReturn();
        expectStatus(r, 401);
        MvcResult ok = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "employee", "password", "Demo@12345")))).andReturn();
        String challenge = body(ok).get("challengeId").asText();
        MvcResult bad = mvc.perform(post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("challengeId", challenge, "code", "000000x")))).andReturn();
        expectStatus(bad, 401);
    }

    @Test
    void refreshTokenRotates() throws Exception {
        MvcResult ok = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "auditor", "password", "Demo@12345")))).andReturn();
        JsonNode ch = body(ok);
        MvcResult v = mvc.perform(post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("challengeId", ch.get("challengeId").asText(), "code", ch.get("devOtp").asText())))).andReturn();
        String refresh = body(v).get("refreshToken").asText();
        MvcResult r1 = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", refresh)))).andReturn();
        expectStatus(r1, 200);
        MvcResult reuse = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", refresh)))).andReturn();
        expectStatus(reuse, 401);
    }

    @Test
    void rbacAndDataScopeAreEnforced() throws Exception {
        String employee = login("employee", "Demo@12345");
        expectStatus(mvc.perform(get("/api/employees").header("Authorization", employee)).andReturn(), 403);
        JsonNode me = getJson(employee, "/api/employees/me");
        assertThat(me.get("employee").get("employeeNo").asText()).isEqualTo("CIA-0008");

        // Division head sees only the Investigation division, and never the restricted intelligence officer.
        String head = login("head", "Demo@12345");
        JsonNode list = getJson(head, "/api/employees?size=100");
        List<String> units = list.get("content").findValuesAsText("orgUnitCode");
        assertThat(units).allMatch(u -> u.startsWith("INV"));
        assertThat(list.get("content").findValuesAsText("employeeNo")).doesNotContain("CIA-0009", "CIA-0003");

        // HR sees everyone except restricted records (no EMP_RESTRICTED permission).
        String hr = login("hr", "Demo@12345");
        JsonNode all = getJson(hr, "/api/employees?size=100");
        assertThat(all.get("content").findValuesAsText("employeeNo")).contains("CIA-0001", "CIA-0015").doesNotContain("CIA-0009");
    }

    @Test
    void establishmentCadreChangeIsAppliedOnlyAfterApproval() throws Exception {
        String est = login("establishment", "Demo@12345");
        Long icto = jdbc.queryForObject("SELECT id FROM designation WHERE code = 'ICTO'", Long.class);
        MvcResult submitted = postJson(est, "/api/establishment/changes", Map.of(
                "changeType", "CADRE_SET", "effectiveDate", LocalDate.now().toString(), "reason", "Additional ICT capacity",
                "payload", Map.of("designationId", icto, "orgUnitCode", "ICT", "approvedQty", 6)));
        expectStatus(submitted, 200);
        long changeId = body(submitted).get("id").asLong();
        long instanceId = body(submitted).get("workflowInstanceId").asLong();

        JsonNode cadre = getJson(est, "/api/establishment/cadre?orgUnitCode=ICT");
        assertThat(cadre.get(0).get("approved").asInt()).isEqualTo(4);
        assertThat(cadre.get(0).get("proposed").asInt()).isEqualTo(6);

        // The submitter cannot approve; the DG (MANAGEMENT) can.
        expectStatus(postJson(est, "/api/workflow/instances/" + instanceId + "/act", Map.of("decision", "APPROVE")), 422);
        // A different user without an approver role for this stage is refused.
        String hr = login("hr", "Demo@12345");
        expectStatus(postJson(hr, "/api/workflow/instances/" + instanceId + "/act", Map.of("decision", "APPROVE")), 403);
        String dg = login("dg", "Demo@12345");
        JsonNode inbox = getJson(dg, "/api/workflow/inbox");
        assertThat(inbox.findValuesAsText("entityType")).contains("EstablishmentChange");
        expectStatus(postJson(dg, "/api/workflow/instances/" + instanceId + "/act", Map.of("decision", "APPROVE", "comment", "OK")), 200);

        JsonNode after = getJson(est, "/api/establishment/cadre?orgUnitCode=ICT");
        assertThat(after.get(0).get("approved").asInt()).isEqualTo(6);
        assertThat(getJson(est, "/api/establishment/changes/" + changeId).get("status").asText()).isEqualTo("APPLIED");
    }

    @Test
    void orgUnitReorganizationKeepsHistory() throws Exception {
        String est = login("establishment", "Demo@12345");
        String dg = login("dg", "Demo@12345");
        MvcResult s = postJson(est, "/api/establishment/changes", Map.of(
                "changeType", "ORG_UNIT_UPDATE", "targetRef", "PREV", "effectiveDate", LocalDate.now().plusDays(1).toString(),
                "reason", "Rename", "payload", Map.of("name", "Prevention and Education Division", "unitType", "DIVISION", "parentCode", "DG_OFFICE")));
        expectStatus(s, 200);
        expectStatus(postJson(dg, "/api/workflow/instances/" + body(s).get("workflowInstanceId").asLong() + "/act",
                Map.of("decision", "APPROVE")), 200);
        JsonNode history = getJson(est, "/api/establishment/org-units/PREV/history");
        assertThat(history.size()).isEqualTo(2);
        JsonNode today = getJson(est, "/api/establishment/org-units");
        assertThat(today.findValuesAsText("name")).contains("Prevention Division");
        JsonNode tomorrow = getJson(est, "/api/establishment/org-units?asOf=" + LocalDate.now().plusDays(1));
        assertThat(tomorrow.findValuesAsText("name")).contains("Prevention and Education Division");
    }

    @Test
    void cyclesInStructureAreRejected() throws Exception {
        String est = login("establishment", "Demo@12345");
        MvcResult s = postJson(est, "/api/establishment/changes", Map.of(
                "changeType", "ORG_UNIT_UPDATE", "targetRef", "INV", "effectiveDate", LocalDate.now().toString(),
                "reason", "Bad move", "payload", Map.of("name", "Investigation Division", "unitType", "DIVISION", "parentCode", "INV-OPS")));
        expectStatus(s, 422);
    }

    @Test
    void employeeCreateValidatesAndRecordsServiceHistory() throws Exception {
        String hr = login("hr", "Demo@12345");
        Long ao = jdbc.queryForObject("SELECT id FROM designation WHERE code = 'AO'", Long.class);
        Map<String, Object> req = new java.util.HashMap<>(Map.of(
                "employeeNo", "CIA-T001", "fullName", "Test Person", "nameWithInitials", "T. Person", "nic", "123",
                "dateOfBirth", "1991-01-01", "status", "ACTIVE", "employmentType", "PERMANENT", "employeeCategory", "EXECUTIVE",
                "appointmentDate", "2024-01-01", "orgUnitCode", "ADMIN"));
        req.put("designationId", ao);
        expectStatus(postJson(hr, "/api/employees", req), 422); // invalid NIC
        req.put("nic", "199100101234");
        MvcResult created = postJson(hr, "/api/employees", req);
        expectStatus(created, 200);
        long id = body(created).get("id").asLong();
        JsonNode hist = getJson(hr, "/api/employees/" + id + "/service-history");
        assertThat(hist.get("assignments").size()).isEqualTo(1);
        assertThat(hist.get("events").get(0).get("eventType").asText()).isEqualTo("APPOINTMENT");
        req.put("employeeNo", "CIA-T002");
        expectStatus(postJson(hr, "/api/employees", req), 409); // duplicate NIC
    }

    @Test
    void sensitiveFieldChangeNeedsSecondPerson() throws Exception {
        String hr = login("hr", "Demo@12345");
        long id = jdbc.queryForObject("SELECT id FROM employee WHERE employee_no = 'CIA-0013'", Long.class);
        JsonNode view = getJson(hr, "/api/employees/" + id).get("employee");
        Map<String, Object> req = json.convertValue(view, Map.class);
        req.put("bankAccountNo", "9999999999");
        req.put("mobile", "0711111111");
        MvcResult r = putJson(hr, "/api/employees/" + id, req);
        expectStatus(r, 200);
        assertThat(body(r).get("pendingChangeRequestId").isNull()).isFalse();
        assertThat(body(r).get("employee").get("bankAccountNo").asText()).isEqualTo("8000000013");
        assertThat(body(r).get("employee").get("mobile").asText()).isEqualTo("0711111111");

        long instance = jdbc.queryForObject("SELECT workflow_instance_id FROM profile_change_request WHERE employee_id = ?", Long.class, id);
        expectStatus(postJson(hr, "/api/workflow/instances/" + instance + "/act", Map.of("decision", "APPROVE")), 422); // own request
        String hr2 = login("hr2", "Demo@12345");
        expectStatus(postJson(hr2, "/api/workflow/instances/" + instance + "/act", Map.of("decision", "APPROVE")), 200);
        assertThat(jdbc.queryForObject("SELECT bank_account_no FROM employee WHERE id = ?", String.class, id)).isEqualTo("9999999999");
    }

    @Test
    void reportsRunAndExportWithScope() throws Exception {
        String head = login("head", "Demo@12345");
        MvcResult r = postJson(head, "/api/reports/EMP_DETAILS/run", Map.of());
        expectStatus(r, 200);
        assertThat(body(r).get("rows").findValuesAsText("org_unit_code")).allMatch(u -> u.startsWith("INV"));

        String est = login("establishment", "Demo@12345");
        JsonNode catalogue = getJson(est, "/api/reports");
        for (JsonNode c : catalogue) {
            MvcResult run = postJson(est, "/api/reports/" + c.get("code").asText() + "/run", Map.of());
            expectStatus(run, 200);
        }
        for (String fmt : List.of("csv", "xlsx", "pdf")) {
            MvcResult x = postJson(est, "/api/reports/EST_CADRE_VACANCY/export?format=" + fmt, Map.of());
            expectStatus(x, 200);
            assertThat(x.getResponse().getContentAsByteArray().length).isGreaterThan(50);
        }
        String auditor = login("auditor", "Demo@12345");
        for (String code : List.of("AUD_AUDIT_LOG", "AUD_ACCESS_REVIEW", "AUD_APPROVAL_HISTORY")) {
            expectStatus(postJson(auditor, "/api/reports/" + code + "/run", Map.of()), 200);
        }
    }

    @Test
    void auditLogIsChainedAndImmutable() throws Exception {
        String auditor = login("auditor", "Demo@12345");
        JsonNode verify = getJson(auditor, "/api/audit/verify");
        assertThat(verify.get("valid").asBoolean()).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET summary = 'tampered' WHERE id = (SELECT MIN(id) FROM audit_log)"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log")).hasMessageContaining("append-only");
    }

    @Test
    void dashboardAndMasterData() throws Exception {
        String dg = login("dg", "Demo@12345");
        JsonNode d = getJson(dg, "/api/dashboard");
        assertThat(d.get("tiles").size()).isGreaterThan(2);
        String admin = login("admin", "Admin@12345");
        JsonNode statuses = getJson(admin, "/api/master-data/EMPLOYEE_STATUS");
        long activeId = 0;
        for (JsonNode s : statuses) if (s.get("code").asText().equals("ACTIVE")) activeId = s.get("id").asLong();
        // In use by employees -> cannot be deactivated.
        expectStatus(mvc.perform(post("/api/master-data/items/" + activeId + "/status?active=false")
                .header("Authorization", admin)).andReturn(), 422);
        // System admin has no HR content access by default.
        expectStatus(mvc.perform(get("/api/employees").header("Authorization", admin)).andReturn(), 403);
    }
}
