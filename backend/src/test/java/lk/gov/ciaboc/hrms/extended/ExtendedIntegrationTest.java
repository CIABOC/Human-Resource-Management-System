package lk.gov.ciaboc.hrms.extended;

import com.fasterxml.jackson.databind.JsonNode;
import lk.gov.ciaboc.hrms.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Release 5 extended scope: transfers/promotions, offboarding, letters with public verification, digitization. */
class ExtendedIntegrationTest extends IntegrationTestBase {
    private static final String PW = "Demo@12345";

    @Autowired
    JdbcTemplate jdbc;

    private long designation(String code) {
        return jdbc.queryForObject("SELECT id FROM designation WHERE code = ?", Long.class, code);
    }

    /** Registers a fresh employee through the employee module so tests never disturb shared demo records. */
    private long newEmployee(String hr, String unit, String designationCode) throws Exception {
        String no = "EXT-" + (100000 + new Random().nextInt(899999));
        String nic = String.valueOf(195000000000L + (long) (new Random().nextDouble() * 9_000_000_000L));
        Map<String, Object> req = new HashMap<>();
        req.put("employeeNo", no);
        req.put("title", "MR");
        req.put("fullName", "Extended Test Officer " + no);
        req.put("nameWithInitials", "E. T. Officer");
        req.put("nic", nic);
        req.put("dateOfBirth", "1980-03-15");
        req.put("gender", "MALE");
        req.put("status", "ACTIVE");
        req.put("employmentType", "PERMANENT");
        req.put("employeeCategory", "EXECUTIVE");
        req.put("serviceCategory", "COMMISSION_SERVICE");
        req.put("appointmentDate", "2021-01-05");
        req.put("orgUnitCode", unit);
        req.put("designationId", designation(designationCode));
        MvcResult r = postJson(hr, "/api/employees", req);
        expectStatus(r, 200);
        return body(r).get("id").asLong();
    }

    @Test
    void promotionIsReviewedByAnotherHrOfficerApprovedByDgAndApplied() throws Exception {
        String hr = login("hr", PW);
        String hr2 = login("hr2", PW);
        String dg = login("dg", PW);
        long emp = newEmployee(hr, "INV-HO", "IO");
        long adg = designation("ADG");

        // Cadre preview (warn-only by default).
        JsonNode check = body(postJson(hr, "/api/extended/movements/cadre-check", Map.of(
                "requestType", "PROMOTION", "employeeId", emp, "targetOrgUnitCode", "INV", "targetDesignationId", adg)));
        assertThat(check.get("applicable").asBoolean()).isTrue();

        // Validation: a promotion to the same designation is refused.
        expectStatus(postJson(hr, "/api/extended/movements", Map.of("requestType", "PROMOTION", "employeeId", emp,
                "targetDesignationId", designation("IO"), "effectiveDate", LocalDate.now().toString(), "reason", "x")), 422);

        MvcResult created = postJson(hr, "/api/extended/movements", Map.of("requestType", "PROMOTION", "employeeId", emp,
                "targetOrgUnitCode", "INV", "targetDesignationId", adg, "effectiveDate", LocalDate.now().toString(),
                "reason", "Selected through the promotion scheme", "reference", "CIABOC/PROM/2026/01"));
        expectStatus(created, 200);
        long id = body(created).get("id").asLong();
        long wi = body(created).get("workflowInstanceId").asLong();
        // Duplicate open request refused.
        expectStatus(postJson(hr, "/api/extended/movements", Map.of("requestType", "TRANSFER", "employeeId", emp,
                "targetOrgUnitCode", "INTEL", "effectiveDate", LocalDate.now().toString(), "reason", "x")), 422);

        // The requester cannot review it; hr2 (HR review) then the DG approve.
        expectStatus(postJson(hr, "/api/workflow/instances/" + wi + "/act", Map.of("decision", "APPROVE")), 422);
        expectStatus(postJson(dg, "/api/workflow/instances/" + wi + "/act", Map.of("decision", "APPROVE")), 403);
        expectStatus(postJson(hr2, "/api/workflow/instances/" + wi + "/act", Map.of("decision", "APPROVE", "comment", "Reviewed")), 200);
        assertThat(getJson(hr, "/api/extended/movements/" + id).get("status").asText()).isEqualTo("PENDING");
        expectStatus(postJson(dg, "/api/workflow/instances/" + wi + "/act", Map.of("decision", "APPROVE", "comment", "Approved")), 200);

        assertThat(getJson(hr, "/api/extended/movements/" + id).get("status").asText()).isEqualTo("APPLIED");
        JsonNode view = getJson(hr, "/api/employees/" + emp).get("employee");
        assertThat(view.get("designationId").asLong()).isEqualTo(adg);
        assertThat(view.get("orgUnitCode").asText()).isEqualTo("INV");
        JsonNode history = getJson(hr, "/api/employees/" + emp + "/service-history");
        assertThat(history.get("events").findValuesAsText("eventType")).contains("PROMOTION");
        assertThat(history.get("assignments").size()).isEqualTo(2);

        // The register report is available to HR.
        expectStatus(postJson(hr, "/api/reports/EXT_MOVEMENTS/run", Map.of()), 200);
    }

    @Test
    void separationClearanceAndCompletionSetsStatusAndRemovesAccess() throws Exception {
        String hr = login("hr", PW);
        long emp = newEmployee(hr, "INV-HO", "IO");
        String username = "ext.leaver" + emp;
        jdbc.update("INSERT INTO app_user (version, created_at, created_by, username, password_hash, full_name, employee_id, active, data_scope) " +
                "SELECT 0, now(), 'test', ?, password_hash, 'Leaver', ?, TRUE, 'SELF' FROM app_user WHERE username = 'employee'", username, emp);
        jdbc.update("INSERT INTO user_role (user_id, role_id) SELECT u.id, r.id FROM app_user u, role r WHERE u.username = ? AND r.code = 'EMPLOYEE'", username);
        login(username, PW); // account works before separation

        MvcResult opened = postJson(hr, "/api/extended/separations", Map.of("employeeId", emp, "separationType", "RESIGNATION",
                "separationDate", LocalDate.now().toString(), "remarks", "Resignation accepted"));
        expectStatus(opened, 200);
        JsonNode c = body(opened);
        long caseId = c.get("summary").get("id").asLong();
        Map<String, Long> items = new HashMap<>();
        c.get("items").forEach(i -> items.put(i.get("itemCode").asText(), i.get("id").asLong()));
        assertThat(items.keySet()).contains("ID_CARD", "ICT_ASSETS", "FINANCE", "LIBRARY", "FILE_HANDOVER");
        // One open case per employee.
        expectStatus(postJson(hr, "/api/extended/separations", Map.of("employeeId", emp, "separationType", "RESIGNATION",
                "separationDate", LocalDate.now().toString())), 422);

        String finance = login("finance", PW);
        String adminOfficer = login("adminofficer", PW);
        String head = login("head", PW);
        // Only the responsible role may clear an item.
        expectStatus(postJson(hr, "/api/extended/clearance/items/" + items.get("FINANCE") + "/clear", Map.of()), 403);
        assertThat(getJson(finance, "/api/extended/clearance/my-tasks").findValuesAsText("itemCode")).contains("FINANCE");

        expectStatus(postJson(hr, "/api/extended/clearance/items/" + items.get("ID_CARD") + "/clear", Map.of("remarks", "Card returned")), 200);
        expectStatus(postJson(hr, "/api/extended/clearance/items/" + items.get("ICT_ASSETS") + "/waive", Map.of("remarks", "No ICT assets issued")), 200);
        expectStatus(postJson(finance, "/api/extended/clearance/items/" + items.get("FINANCE") + "/clear", Map.of("remarks", "No dues")), 200);
        expectStatus(postJson(adminOfficer, "/api/extended/clearance/items/" + items.get("LIBRARY") + "/clear", Map.of()), 200);

        // Completion is blocked while items or the settlement are outstanding.
        expectStatus(postJson(hr, "/api/extended/separations/" + caseId + "/complete", Map.of()), 422);
        expectStatus(postJson(head, "/api/extended/clearance/items/" + items.get("FILE_HANDOVER") + "/clear", Map.of("remarks", "Files handed over")), 200);
        expectStatus(postJson(hr, "/api/extended/separations/" + caseId + "/complete", Map.of()), 422);
        expectStatus(postJson(hr, "/api/extended/separations/" + caseId + "/settlement", Map.of("amount", 12500.5, "notes", "x")), 403);
        expectStatus(postJson(finance, "/api/extended/separations/" + caseId + "/settlement",
                Map.of("amount", 12500.5, "notes", "Leave encashment less festival advance")), 200);

        MvcResult done = postJson(hr, "/api/extended/separations/" + caseId + "/complete", Map.of());
        expectStatus(done, 200);
        assertThat(body(done).get("summary").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE id = ?", String.class, emp)).isEqualTo("RESIGNED");
        assertThat(jdbc.queryForObject("SELECT active FROM app_user WHERE username = ?", Boolean.class, username)).isFalse();
        expectStatus(postJson(hr, "/api/reports/EXT_SEPARATIONS/run", Map.of()), 200);
    }

    @Test
    void employeeRequestsLetterHrIssuesAndPublicVerificationIsMinimal() throws Exception {
        String employee = login("employee", PW);
        String hr = login("hr", PW);
        // Employees cannot use HR endpoints.
        expectStatus(mvc.perform(get("/api/extended/letters/requests").header("Authorization", employee)).andReturn(), 403);

        MvcResult req = postJson(employee, "/api/extended/my-letters/requests", Map.of("templateCode", "SERVICE_LETTER",
                "language", "en", "purpose", "a housing loan application", "addressee", "The Manager, Bank of Ceylon"));
        expectStatus(req, 200);
        long requestId = body(req).get("id").asLong();
        long wi = body(req).get("workflowInstanceId").asLong();
        // Not issuable before approval.
        expectStatus(postJson(hr, "/api/extended/letters/issue", Map.of("requestId", requestId)), 422);
        expectStatus(postJson(hr, "/api/workflow/instances/" + wi + "/act", Map.of("decision", "APPROVE")), 200);

        MvcResult issued = postJson(hr, "/api/extended/letters/issue", Map.of("requestId", requestId));
        expectStatus(issued, 200);
        long docId = body(issued).get("id").asLong();
        String code = body(issued).get("verificationCode").asText();
        assertThat(code).matches("^[A-Z2-7]{4}(-[A-Z2-7]{4}){5}$");

        JsonNode mine = getJson(employee, "/api/extended/my-letters");
        assertThat(mine.get("documents").findValuesAsText("verificationCode")).contains(code);
        assertThat(mine.get("requests").get(0).get("status").asText()).isEqualTo("ISSUED");
        MvcResult print = mvc.perform(get("/api/extended/documents/" + docId + "/print").header("Authorization", employee)).andReturn();
        expectStatus(print, 200);
        String html = print.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains(code).contains("CIA-0008").contains("housing loan");
        // Another employee's officer without EXT_VIEW cannot read it.
        String auditor = login("auditor", PW);
        expectStatus(mvc.perform(get("/api/extended/documents/" + docId + "/print").header("Authorization", auditor)).andReturn(), 404);

        // Public verification: no authentication, minimal data only.
        MvcResult pub = mvc.perform(get("/api/public/verify/" + code.toLowerCase())).andReturn();
        expectStatus(pub, 200);
        JsonNode v = body(pub);
        Set<String> keys = new TreeSet<>();
        v.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("valid", "status", "documentType", "issueDate", "holderName");
        assertThat(v.get("valid").asBoolean()).isTrue();
        assertThat(v.get("holderName").asText()).isEqualTo("D. I. Officer");
        assertThat(pub.getResponse().getContentAsString()).doesNotContain("CIA-0008").doesNotContain("199011201234");
        expectStatus(mvc.perform(get("/api/public/verify/AAAA-BBBB-CCCC-DDDD-EEEE-FFFF")).andReturn(), 404);
        expectStatus(mvc.perform(get("/api/public/verify/not-a-code")).andReturn(), 404);
        MvcResult page = mvc.perform(get("/api/public/verify/" + code + "/page")).andReturn();
        expectStatus(page, 200);
        assertThat(page.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'none'");

        // Revocation is reflected publicly.
        expectStatus(postJson(hr, "/api/extended/letters/documents/" + docId + "/revoke", Map.of("reason", "Issued in error")), 200);
        JsonNode after = body(mvc.perform(get("/api/public/verify/" + code)).andReturn());
        assertThat(after.get("valid").asBoolean()).isFalse();
        assertThat(after.get("status").asText()).isEqualTo("REVOKED");

        // Templates reject active content.
        expectStatus(postJson(hr, "/api/extended/letter-templates", Map.of("code", "BAD_TEMPLATE", "language", "en", "name", "Bad",
                "documentKind", "LETTER", "title", "Bad", "bodyHtml", "<p onclick=\"x()\">hi</p>")), 422);
        expectStatus(postJson(hr, "/api/reports/EXT_LETTERS/run", Map.of()), 200);
    }

    @Test
    void digitizationBatchIsVerifiedBySecondPersonThenFiled() throws Exception {
        String hr = login("hr", PW);
        String hr2 = login("hr2", PW);
        long emp = newEmployee(hr, "ADMIN", "AO");
        String empNo = jdbc.queryForObject("SELECT employee_no FROM employee WHERE id = ?", String.class, emp);

        MvcResult b = postJson(hr, "/api/extended/digitization/batches", Map.of("title", "Legacy files cabinet 7", "sourceLocation", "Record room"));
        expectStatus(b, 200);
        long batchId = body(b).get("batch").get("id").asLong();

        byte[] pdf = "%PDF-1.4\n% scanned appointment letter\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        MvcResult up = mvc.perform(multipart("/api/extended/digitization/batches/" + batchId + "/items")
                .file(new MockMultipartFile("file", "appointment-1995.pdf", "application/pdf", pdf))
                .param("employeeNo", empNo).param("documentType", "APPOINTMENT_LETTER")
                .param("title", "Appointment letter (legacy)").param("documentDate", "2021-02-01")
                .param("classification", "CONFIDENTIAL").header("Authorization", hr)).andReturn();
        expectStatus(up, 200);
        long itemId = body(up).get("id").asLong();
        // Unknown employee number is rejected.
        expectStatus(mvc.perform(multipart("/api/extended/digitization/batches/" + batchId + "/items")
                .file(new MockMultipartFile("file", "x.pdf", "application/pdf", pdf))
                .param("employeeNo", "NO-SUCH").param("documentType", "APPOINTMENT_LETTER").param("title", "x")
                .header("Authorization", hr)).andReturn(), 422);

        expectStatus(postJson(hr, "/api/extended/digitization/batches/" + batchId + "/submit", Map.of()), 200);
        // The uploader cannot QA their own scan.
        expectStatus(postJson(hr, "/api/extended/digitization/items/" + itemId + "/qa", Map.of("decision", "VERIFY")), 422);
        expectStatus(postJson(hr2, "/api/extended/digitization/items/" + itemId + "/qa", Map.of("decision", "VERIFY", "remarks", "Legible, index correct")), 200);

        MvcResult filed = postJson(hr, "/api/extended/digitization/batches/" + batchId + "/file", Map.of());
        expectStatus(filed, 200);
        assertThat(body(filed).get("batch").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(body(filed).get("items").get(0).get("status").asText()).isEqualTo("FILED");

        JsonNode docs = getJson(hr, "/api/employees/" + emp + "/documents");
        assertThat(docs.findValuesAsText("title")).contains("Appointment letter (legacy)");
        expectStatus(postJson(hr, "/api/reports/EXT_DIGITIZATION/run", Map.of()), 200);
    }

    @Test
    void retirementForecastAndNoticesAndDashboard() throws Exception {
        String hr = login("hr", PW);
        JsonNode f = getJson(hr, "/api/extended/retirements/forecast?months=12");
        assertThat(f.findValuesAsText("employeeNo")).contains("CIA-0015");
        JsonNode sent = body(postJson(hr, "/api/extended/retirements/run-notices", Map.of()));
        assertThat(sent.get("sent").asInt()).isGreaterThanOrEqualTo(1);
        // Idempotent: the same notice is not sent twice.
        assertThat(body(postJson(hr, "/api/extended/retirements/run-notices", Map.of())).get("sent").asInt()).isZero();
        expectStatus(postJson(hr, "/api/reports/EXT_RETIREMENT_FORECAST/run", Map.of()), 200);
        JsonNode dash = getJson(hr, "/api/dashboard");
        assertThat(dash.toString()).contains("ext-retirements-6m");
        // Employees without EXT_VIEW cannot see the forecast.
        String employee = login("employee", PW);
        expectStatus(mvc.perform(get("/api/extended/retirements/forecast").header("Authorization", employee)
                .accept(MediaType.APPLICATION_JSON)).andReturn(), 403);
    }
}
