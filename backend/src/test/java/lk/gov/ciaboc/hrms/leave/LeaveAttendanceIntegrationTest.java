package lk.gov.ciaboc.hrms.leave;

import com.fasterxml.jackson.databind.JsonNode;
import lk.gov.ciaboc.hrms.common.contracts.NoPaySource;
import lk.gov.ciaboc.hrms.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * End-to-end tests of leave (FR-LEV) and attendance (FR-ATT): approval chain and balances, validation rules, daily
 * processing statuses, corrections, and the approved no-pay feed to payroll.
 */
class LeaveAttendanceIntegrationTest extends IntegrationTestBase {
    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    NoPaySource noPaySource;

    private long typeId(String code) {
        return jdbc.queryForObject("SELECT id FROM leave_type WHERE code = ?", Long.class, code);
    }

    private long employeeId(String no) {
        return jdbc.queryForObject("SELECT id FROM employee WHERE employee_no = ?", Long.class, no);
    }

    private Set<LocalDate> holidays(String token, int year) throws Exception {
        Set<LocalDate> out = new HashSet<>();
        for (JsonNode h : getJson(token, "/api/attendance/holidays?year=" + year)) out.add(LocalDate.parse(h.get("holidayDate").asText()));
        return out;
    }

    private boolean working(LocalDate d, Set<LocalDate> hol) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !hol.contains(d);
    }

    private JsonNode balance(String token, int year, String code) throws Exception {
        for (JsonNode b : getJson(token, "/api/leave/my/balances?year=" + year))
            if (code.equals(b.get("leaveTypeCode").asText())) return b;
        throw new AssertionError("No balance for " + code);
    }

    private void act(String token, long instanceId, String decision, int expected) throws Exception {
        expectStatus(postJson(token, "/api/workflow/instances/" + instanceId + "/act", Map.of("decision", decision, "comment", "ok")), expected);
    }

    private static String code(MvcResult r) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getResponse().getContentAsString()).path("code").asText();
    }

    @Test
    void leaveIsApprovedByDivisionHeadAndHrAndBalancesFollow() throws Exception {
        String employee = login("employee", "Demo@12345");
        String head = login("head", "Demo@12345");
        String hr = login("hr", "Demo@12345");
        long vacation = typeId("VACATION");
        long casual = typeId("CASUAL");

        // A Monday-Tuesday pair at least two weeks ahead, both working days in the same year.
        LocalDate mon = LocalDate.now(ZONE).plusDays(14);
        while (true) {
            while (mon.getDayOfWeek() != DayOfWeek.MONDAY) mon = mon.plusDays(1);
            Set<LocalDate> hol = holidays(employee, mon.getYear());
            if (working(mon, hol) && working(mon.plusDays(1), hol) && mon.getYear() == mon.plusDays(1).getYear()) break;
            mon = mon.plusDays(7);
        }
        LocalDate tue = mon.plusDays(1);
        int year = mon.getYear();
        BigDecimal before = balance(employee, year, "VACATION").get("available").decimalValue();

        MvcResult applied = postJson(employee, "/api/leave/applications", Map.of("leaveTypeId", vacation,
                "startDate", mon.toString(), "endDate", tue.toString(), "reason", "Family function"));
        expectStatus(applied, 200);
        JsonNode app = body(applied);
        assertThat(app.get("days").decimalValue()).isEqualByComparingTo("2");
        assertThat(app.get("status").asText()).isEqualTo("PENDING");
        long appId = app.get("id").asLong();
        long wi = app.get("workflowInstanceId").asLong();

        JsonNode pendingBal = balance(employee, year, "VACATION");
        assertThat(pendingBal.get("pending").decimalValue()).isEqualByComparingTo("2");
        assertThat(pendingBal.get("available").decimalValue()).isEqualByComparingTo(before.subtract(BigDecimal.valueOf(2)));

        // Requester cannot approve; HR cannot act at the division-head stage; the division head can.
        act(employee, wi, "APPROVE", 422);
        act(hr, wi, "APPROVE", 403);
        act(head, wi, "APPROVE", 200);
        assertThat(getJson(employee, "/api/leave/applications/" + appId).get("status").asText()).isEqualTo("PENDING");
        act(hr, wi, "APPROVE", 200);

        assertThat(getJson(employee, "/api/leave/applications/" + appId).get("status").asText()).isEqualTo("APPROVED");
        JsonNode after = balance(employee, year, "VACATION");
        assertThat(after.get("used").decimalValue()).isEqualByComparingTo("2");
        assertThat(after.get("pending").decimalValue()).isEqualByComparingTo("0");
        assertThat(after.get("available").decimalValue()).isEqualByComparingTo(before.subtract(BigDecimal.valueOf(2)));

        // Division head sees it on the team calendar; the plain employee cannot use the management views.
        JsonNode cal = getJson(head, "/api/leave/team-calendar?from=" + mon + "&to=" + tue);
        assertThat(cal.get("entries").findValuesAsText("applicationId")).contains(String.valueOf(appId));
        expectStatus(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/leave/applications")
                .header("Authorization", employee)).andReturn(), 403);

        // Overlapping application is refused.
        MvcResult overlap = postJson(employee, "/api/leave/applications", Map.of("leaveTypeId", casual,
                "startDate", tue.toString(), "endDate", tue.toString()));
        expectStatus(overlap, 422);
        assertThat(code(overlap)).isEqualTo("OVERLAP");

        // HR reduces the balance to one day (audited adjustment); a two-day application is then refused.
        long empId = employeeId("CIA-0008");
        BigDecimal avail = after.get("available").decimalValue();
        expectStatus(postJson(hr, "/api/leave/balances/adjust", Map.of("employeeId", empId, "leaveTypeId", vacation, "year", year,
                "days", BigDecimal.ONE.subtract(avail), "reason", "Correction of carried-forward days")), 200);
        assertThat(balance(employee, year, "VACATION").get("available").decimalValue()).isEqualByComparingTo("1");
        LocalDate nextMon = mon.plusDays(7);
        MvcResult insufficient = postJson(employee, "/api/leave/applications", Map.of("leaveTypeId", vacation,
                "startDate", nextMon.toString(), "endDate", nextMon.plusDays(1).toString()));
        expectStatus(insufficient, 422);
        assertThat(code(insufficient)).isEqualTo("INSUFFICIENT_BALANCE");

        // Cancellation of the approved leave through LEAVE_CANCEL restores the used days.
        MvcResult cancel = postJson(employee, "/api/leave/applications/" + appId + "/cancel-request", Map.of("comment", "Function postponed"));
        expectStatus(cancel, 200);
        long cwi = body(cancel).get("cancelWorkflowInstanceId").asLong();
        act(head, cwi, "APPROVE", 200);
        act(hr, cwi, "APPROVE", 200);
        assertThat(getJson(employee, "/api/leave/applications/" + appId).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(balance(employee, year, "VACATION").get("used").decimalValue()).isEqualByComparingTo("0");

        // Leave reports run with the caller's scope.
        for (String rpt : List.of("LEAVE_BALANCES", "LEAVE_UTILIZATION", "LEAVE_PENDING", "LEAVE_APPROVED", "LEAVE_REJECTED", "LEAVE_APPLICATIONS")) {
            expectStatus(postJson(hr, "/api/reports/" + rpt + "/run", Map.of()), 200);
        }
        expectStatus(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/dashboard")
                .header("Authorization", employee)).andReturn(), 200);
    }

    @Test
    void attendanceProcessingCorrectionsAndNoPayFeedPayroll() throws Exception {
        String employee = login("employee", "Demo@12345");
        String head = login("head", "Demo@12345");
        String hr = login("hr", "Demo@12345");
        String hr2 = login("hr2", "Demo@12345");
        jdbc.update("UPDATE integration_config SET enabled = TRUE WHERE code = 'ATTENDANCE_DEVICE'");

        // Two most recent past working days (D1 before D2), at least two days ago.
        LocalDate today = LocalDate.now(ZONE);
        Set<LocalDate> hol = new HashSet<>(holidays(hr, today.getYear()));
        hol.addAll(holidays(hr, today.getYear() - 1));
        LocalDate d2 = today.minusDays(2);
        while (!working(d2, hol)) d2 = d2.minusDays(1);
        LocalDate d1 = d2.minusDays(1);
        while (!working(d1, hol)) d1 = d1.minusDays(1);

        List<Map<String, String>> evs = List.of(
                Map.of("employeeNo", "CIA-0008", "timestamp", d1 + " 08:50", "direction", "IN", "deviceId", "GATE-01"),
                Map.of("employeeNo", "CIA-0008", "timestamp", d1 + " 08:51", "direction", "IN", "deviceId", "GATE-01"),
                Map.of("employeeNo", "CIA-0008", "timestamp", d1 + " 16:30", "direction", "OUT", "deviceId", "GATE-01"),
                Map.of("employeeNo", "CIA-0015", "timestamp", d1 + " 08:20", "direction", "IN", "deviceId", "GATE-01"),
                Map.of("employeeNo", "CIA-0015", "timestamp", d1 + " 16:20", "direction", "OUT", "deviceId", "GATE-01"),
                Map.of("employeeNo", "CIA-9999", "timestamp", d1 + " 09:00", "direction", "IN", "deviceId", "GATE-01"));
        expectStatus(postJson(employee, "/api/attendance/device-events", Map.of("events", evs)), 403);
        MvcResult ing = postJson(hr, "/api/attendance/device-events", Map.of("batchRef", "TEST-" + d1, "events", evs));
        expectStatus(ing, 200);
        assertThat(body(ing).get("accepted").asInt()).isEqualTo(6);
        assertThat(body(ing).get("flagged").asInt()).isEqualTo(2); // near-duplicate + unknown employee
        // Re-sending the same batch is idempotent.
        assertThat(body(postJson(hr, "/api/attendance/device-events", Map.of("events", evs))).get("skippedDuplicates").asInt()).isEqualTo(6);
        // CSV import of one more event for CIA-0015 on D2 is rejected only if malformed.
        MockMultipartFile csv = new MockMultipartFile("file", "events.csv", "text/csv",
                ("employeeNo,timestamp,direction,deviceId\nCIA-0013," + d1 + " 08:15,IN,GATE-02\nbad-row\n").getBytes(StandardCharsets.UTF_8));
        JsonNode imp = body(mvc.perform(multipart("/api/attendance/import").file(csv).header("Authorization", hr)).andReturn());
        assertThat(imp.get("accepted").asInt()).isEqualTo(1);
        assertThat(imp.get("rejected").asInt()).isEqualTo(1);
        JsonNode awaiting = getJson(hr, "/api/attendance/events?awaitingReview=true&from=" + d1 + "&to=" + d1);
        assertThat(awaiting.get("content").findValuesAsText("employeeNo")).contains("CIA-0008", "CIA-9999");

        // Employee takes back-dated medical leave on D2 (approved by head and HR).
        MvcResult med = postJson(employee, "/api/leave/applications", Map.of("leaveTypeId", typeId("MEDICAL"),
                "startDate", d2.toString(), "endDate", d2.toString(), "reason", "Fever"));
        expectStatus(med, 200);
        long medWi = body(med).get("workflowInstanceId").asLong();
        act(head, medWi, "APPROVE", 200);
        act(hr, medWi, "APPROVE", 200);

        expectStatus(postJson(hr, "/api/attendance/process", Map.of("date", d1.toString(), "to", d2.toString())), 200);
        Map<String, JsonNode> day1 = byEmp(getJson(hr, "/api/attendance/daily?date=" + d1));
        assertThat(day1.get("CIA-0008").get("status").asText()).isEqualTo("LATE");
        assertThat(day1.get("CIA-0008").get("lateMinutes").asInt()).isEqualTo(20);
        assertThat(day1.get("CIA-0015").get("status").asText()).isEqualTo("PRESENT");
        Map<String, JsonNode> day2 = byEmp(getJson(hr, "/api/attendance/daily?date=" + d2));
        assertThat(day2.get("CIA-0008").get("status").asText()).isEqualTo("ON_LEAVE");
        assertThat(day2.get("CIA-0015").get("status").asText()).isEqualTo("ABSENT");

        // The division head's team view is limited to the Investigation division.
        JsonNode team = getJson(head, "/api/attendance/daily?date=" + d1);
        for (JsonNode r : team) assertThat(r.get("employee").get("orgUnitCode").asText()).startsWith("INV");

        // Correction of the late check-in, approved by the division head, re-evaluates the day.
        MvcResult corr = postJson(employee, "/api/attendance/corrections", Map.of("workDate", d1.toString(), "checkIn", "08:25",
                "reason", "Device failed to read card at 08:25; arrival confirmed by duty officer"));
        expectStatus(corr, 200);
        act(head, body(corr).get("workflowInstanceId").asLong(), "APPROVE", 200);
        JsonNode corrected = byEmp(getJson(hr, "/api/attendance/daily?date=" + d1)).get("CIA-0008");
        assertThat(corrected.get("status").asText()).isEqualTo("PRESENT");
        assertThat(corrected.get("corrected").asBoolean()).isTrue();

        // Monthly no-pay: calculated, reviewed and approved by another officer, then visible to payroll.
        YearMonth period = YearMonth.from(d2);
        MvcResult calc = postJson(hr, "/api/attendance/nopay/calculate", Map.of("period", period.toString()));
        expectStatus(calc, 200);
        long runId = body(calc).get("run").get("id").asLong();
        assertThat(noPaySource.approvedNoPayDays(period)).isEmpty();
        MvcResult sub = postJson(hr, "/api/attendance/nopay/runs/" + runId + "/submit", Map.of());
        expectStatus(sub, 200);
        long npWi = body(sub).get("run").get("workflowInstanceId").asLong();
        act(hr, npWi, "APPROVE", 422);
        act(hr2, npWi, "APPROVE", 200);
        Map<Long, BigDecimal> released = noPaySource.approvedNoPayDays(period);
        assertThat(released.get(employeeId("CIA-0015"))).isNotNull();
        assertThat(released.get(employeeId("CIA-0015"))).isGreaterThanOrEqualTo(BigDecimal.ONE);
        assertThat(released).doesNotContainKey(employeeId("CIA-0008"));
        expectStatus(postJson(hr, "/api/attendance/nopay/calculate", Map.of("period", period.toString())), 409);

        for (String rpt : List.of("ATT_DAILY", "ATT_MONTHLY", "ATT_ABSENCES", "ATT_LATE", "ATT_EARLY", "ATT_EXCEPTIONS", "ATT_NOPAY_SUMMARY")) {
            expectStatus(postJson(hr, "/api/reports/" + rpt + "/run", Map.of("period", period.toString(), "asOf", d1.toString())), 200);
        }
        expectStatus(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/dashboard")
                .header("Authorization", hr)).andReturn(), 200);
    }

    @Test
    void schedulesAreEffectiveDatedAndDeviceIngestionRequiresIntegration() throws Exception {
        String hr = login("hr", "Demo@12345");
        String employee = login("employee", "Demo@12345");
        LocalDate from = LocalDate.now(ZONE).plusDays(30);
        MvcResult created = postJson(hr, "/api/attendance/schedules", Map.of("code", "INV-SHIFT", "name", "Investigation shift",
                "startTime", "07:30", "endTime", "15:15", "lateGraceMinutes", 5, "earlyGraceMinutes", 5,
                "workDays", List.of("MON", "TUE", "WED", "THU", "FRI", "SAT"), "orgUnitCode", "INV", "effectiveFrom", from.toString()));
        expectStatus(created, 200);
        expectStatus(putJson(hr, "/api/attendance/schedules/INV-SHIFT", Map.of("name", "Investigation shift (revised)",
                "startTime", "07:45", "endTime", "15:30", "lateGraceMinutes", 5, "earlyGraceMinutes", 5,
                "workDays", List.of("MON", "TUE", "WED", "THU", "FRI"), "orgUnitCode", "INV", "effectiveFrom", from.plusDays(10).toString())), 200);
        assertThat(getJson(hr, "/api/attendance/schedules/INV-SHIFT/history").size()).isEqualTo(2);
        expectStatus(postJson(employee, "/api/attendance/schedules", Map.of("code", "X")), 403);
        MvcResult bad = postJson(hr, "/api/attendance/schedules", Map.of("code", "BAD", "name", "Bad", "startTime", "17:00",
                "endTime", "08:00", "workDays", List.of("MON"), "effectiveFrom", from.toString()));
        expectStatus(bad, 400);
        // Device batch ingestion is refused while the INT-04 integration is disabled.
        Boolean was = jdbc.queryForObject("SELECT enabled FROM integration_config WHERE code = 'ATTENDANCE_DEVICE'", Boolean.class);
        jdbc.update("UPDATE integration_config SET enabled = FALSE WHERE code = 'ATTENDANCE_DEVICE'");
        try {
            MvcResult off = postJson(hr, "/api/attendance/device-events", Map.of("events", List.of(Map.of("employeeNo", "CIA-0008",
                    "timestamp", LocalDate.now(ZONE).minusDays(1) + " 08:00", "direction", "IN"))));
            expectStatus(off, 422);
            assertThat(code(off)).isEqualTo("INTEGRATION_DISABLED");
        } finally {
            jdbc.update("UPDATE integration_config SET enabled = ? WHERE code = 'ATTENDANCE_DEVICE'", was);
        }
        // Leave-type configuration requires LEAVE_CONFIG.
        expectStatus(postJson(employee, "/api/leave/types", Map.of("code", "STUDY", "name", "Study leave")), 403);
    }

    private static Map<String, JsonNode> byEmp(JsonNode rows) {
        Map<String, JsonNode> m = new HashMap<>();
        for (JsonNode r : rows) m.put(r.get("employee").get("employeeNo").asText(), r);
        return m;
    }
}
