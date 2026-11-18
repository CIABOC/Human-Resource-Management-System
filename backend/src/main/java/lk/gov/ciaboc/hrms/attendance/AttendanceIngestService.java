package lk.gov.ciaboc.hrms.attendance;

import jakarta.persistence.criteria.Predicate;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.PageResponse;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeRepository;
import lk.gov.ciaboc.hrms.integration.IntegrationService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Ingestion of check-in / check-out events from attendance devices (INT-04, HW-01) by JSON batch or CSV import, with
 * flagging of suspicious events for review (FR-ATT-001..005). Events are never altered; reviewers accept or reject them.
 */
@Service
@RequiredArgsConstructor
public class AttendanceIngestService {
    static final int MAX_BATCH = 5000;
    static final int MAX_CSV_ROWS = 50_000;
    private static final int MAX_ERRORS = 50;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final AttendanceEventRepository events;
    private final EmployeeRepository employees;
    private final EmployeeLookup lookup;
    private final IntegrationService integrations;
    private final SettingsService settings;
    private final AuditService audit;
    private final AttendanceProcessingService processing;

    public record RawEvent(String employeeNo, String timestamp, String direction, String deviceId) {
    }

    public record DeviceBatch(String batchRef, List<RawEvent> events) {
    }

    public record IngestResult(String batchRef, int received, int accepted, int flagged, int skippedDuplicates, int rejected,
                               int reprocessedEmployeeDays, List<String> errors) {
    }

    public record ReviewRequest(String decision, String comment) {
    }

    public record EventView(Long id, Long employeeId, String employeeNo, String employeeName, String orgUnitCode, Instant eventTime,
                            LocalDate workDate, String direction, String source, String deviceId, String batchRef, boolean flagged,
                            String flagReason, String reviewStatus, String reviewComment, String reviewedBy, Instant reviewedAt) {
    }

    // ------------------------------------------------------------------ ingestion

    @Transactional
    public IngestResult ingestDeviceBatch(DeviceBatch b) {
        if (!integrations.isEnabled(IntegrationService.ATTENDANCE_DEVICE))
            throw ApiException.rule("INTEGRATION_DISABLED", "The attendance device integration (INT-04) is disabled. "
                    + "Enable it under Integrations, or use the CSV import.");
        if (b == null || b.events() == null || b.events().isEmpty()) throw ApiException.badRequest("The batch contains no events");
        if (b.events().size() > MAX_BATCH) throw ApiException.badRequest("A batch may contain at most " + MAX_BATCH + " events");
        String ref = b.batchRef() == null || b.batchRef().isBlank() ? "DEV-" + Instant.now().toEpochMilli() : b.batchRef().trim();
        if (ref.length() > 60) throw ApiException.badRequest("Batch reference is too long (maximum 60 characters)");
        IngestResult r = ingest(b.events(), AttendanceEvent.Source.DEVICE, ref, 1);
        audit.log(AuditAction.IMPORT, "AttendanceEvent", ref, summary("Device batch", r));
        return r;
    }

    /** CSV with columns employeeNo,timestamp,direction,deviceId (header row optional). */
    @Transactional
    public IngestResult importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("No file was uploaded");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".csv") && !name.endsWith(".txt")) throw ApiException.rule("FILE_TYPE", "Upload a .csv file");
        List<RawEvent> rows = new ArrayList<>();
        int firstLine = 1;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = in.readLine()) != null) {
                if (first) {
                    first = false;
                    if (line.startsWith("﻿")) line = line.substring(1);
                    if (line.toLowerCase(Locale.ROOT).replace("\"", "").startsWith("employeeno")) {
                        firstLine = 2;
                        continue;
                    }
                }
                if (line.isBlank()) {
                    rows.add(null);
                    continue;
                }
                String[] c = line.split(",", -1);
                rows.add(new RawEvent(cell(c, 0), cell(c, 1), cell(c, 2), cell(c, 3)));
                if (rows.size() > MAX_CSV_ROWS) throw ApiException.badRequest("A file may contain at most " + MAX_CSV_ROWS + " rows");
            }
        } catch (IOException e) {
            throw ApiException.badRequest("The file could not be read");
        }
        if (rows.stream().allMatch(Objects::isNull)) throw ApiException.badRequest("The file contains no events");
        String ref = "CSV-" + Instant.now().toEpochMilli();
        IngestResult r = ingest(rows, AttendanceEvent.Source.IMPORT, ref, firstLine);
        audit.log(AuditAction.IMPORT, "AttendanceEvent", ref, summary("CSV import " + file.getOriginalFilename(), r));
        return r;
    }

    private IngestResult ingest(List<RawEvent> rows, AttendanceEvent.Source source, String batchRef, int firstLine) {
        ZoneId zone = processing.zone();
        int window = Math.max(0, settings.getInt("attendance.duplicate-window-minutes", 2));
        int futureMinutes = Math.max(0, settings.getInt("attendance.max-future-minutes", 5));
        LocalTime validStart = time("attendance.valid-hours.start", LocalTime.of(5, 0));
        LocalTime validEnd = time("attendance.valid-hours.end", LocalTime.of(22, 0));
        Map<String, Optional<Employee>> cache = new HashMap<>();
        Map<LocalDate, Set<Long>> touched = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        int received = 0, accepted = 0, flagged = 0, skipped = 0, rejected = 0;
        for (int i = 0; i < rows.size(); i++) {
            RawEvent raw = rows.get(i);
            if (raw == null) continue;
            received++;
            String where = (source == AttendanceEvent.Source.IMPORT ? "Line " : "Event ") + (i + firstLine) + ": ";
            String no = raw.employeeNo() == null ? "" : raw.employeeNo().trim();
            if (no.isEmpty() || no.length() > 30) {
                rejected++;
                addError(errors, where + "employee number is missing or too long");
                continue;
            }
            AttendanceEvent.Direction dir = direction(raw.direction());
            if (dir == null) {
                rejected++;
                addError(errors, where + "direction must be IN or OUT");
                continue;
            }
            Instant t = parseTime(raw.timestamp(), zone);
            if (t == null) {
                rejected++;
                addError(errors, where + "invalid timestamp '" + raw.timestamp() + "' (use yyyy-MM-dd HH:mm[:ss] or ISO-8601)");
                continue;
            }
            if (t.isAfter(Instant.now().plus(Duration.ofMinutes(futureMinutes)))) {
                rejected++;
                addError(errors, where + "timestamp is in the future");
                continue;
            }
            String device = raw.deviceId() == null || raw.deviceId().isBlank() ? null : raw.deviceId().trim();
            if (device != null && device.length() > 60) device = device.substring(0, 60);
            if (events.existsByEmployeeNoAndEventTimeAndDirection(no, t, dir)) {
                skipped++;
                continue;
            }
            Optional<Employee> emp = cache.computeIfAbsent(no, employees::findByEmployeeNo);
            List<String> flags = new ArrayList<>();
            if (emp.isEmpty()) flags.add("Unknown employee number");
            else if (!EmployeeLookup.IN_SERVICE.contains(emp.get().getStatus())) flags.add("Employee not in service (" + emp.get().getStatus() + ")");
            ZonedDateTime local = t.atZone(zone);
            if (local.toLocalTime().isBefore(validStart) || local.toLocalTime().isAfter(validEnd))
                flags.add("Outside valid hours (" + validStart.format(HM) + "-" + validEnd.format(HM) + ")");
            if (window > 0 && events.countNear(no, dir, t.minus(Duration.ofMinutes(window)), t.plus(Duration.ofMinutes(window))) > 0)
                flags.add("Possible duplicate within " + window + " min");
            AttendanceEvent e = new AttendanceEvent();
            e.setEmployeeId(emp.map(Employee::getId).orElse(null));
            e.setEmployeeNo(no);
            e.setEventTime(t);
            e.setWorkDate(local.toLocalDate());
            e.setDirection(dir);
            e.setSource(source);
            e.setDeviceId(device);
            e.setBatchRef(batchRef);
            e.setFlagged(!flags.isEmpty());
            e.setFlagReason(flags.isEmpty() ? null : String.join("; ", flags));
            events.saveAndFlush(e);
            accepted++;
            if (e.isFlagged()) flagged++;
            else touched.computeIfAbsent(e.getWorkDate(), k -> new HashSet<>()).add(e.getEmployeeId());
        }
        int re = processing.reprocessIfProcessed(touched);
        return new IngestResult(batchRef, received, accepted, flagged, skipped, rejected, re, errors);
    }

    // ------------------------------------------------------------------ review of flagged events (FR-ATT-004)

    @Transactional
    public EventView review(Long id, ReviewRequest r) {
        AttendanceEvent e = events.findById(id).orElseThrow(() -> ApiException.notFound("Attendance event", id));
        if (e.getEmployeeId() != null) lookup.requireAccess(e.getEmployeeId());
        else if (lookup.scopeUnits() != null) throw ApiException.notFound("Attendance event", id);
        if (!e.isFlagged()) throw ApiException.rule("NOT_FLAGGED", "Only flagged events need review");
        if (e.getReviewStatus() != null) throw ApiException.rule("ALREADY_REVIEWED", "This event has already been reviewed");
        String decision = r == null || r.decision() == null ? "" : r.decision().trim().toUpperCase(Locale.ROOT);
        String comment = r == null || r.comment() == null ? null : r.comment().trim();
        switch (decision) {
            case "ACCEPT" -> {
                if (e.getEmployeeId() == null)
                    throw ApiException.rule("UNKNOWN_EMPLOYEE", "Events of unknown employees cannot be accepted; correct the employee number at the device and re-import");
                e.setReviewStatus(AttendanceEvent.ReviewStatus.ACCEPTED);
            }
            case "REJECT" -> {
                if (comment == null || comment.isEmpty()) throw ApiException.badRequest("A comment is required when rejecting an event");
                e.setReviewStatus(AttendanceEvent.ReviewStatus.REJECTED);
            }
            default -> throw ApiException.badRequest("Decision must be ACCEPT or REJECT");
        }
        if (comment != null && comment.length() > 500) throw ApiException.badRequest("Comment is too long (maximum 500 characters)");
        e.setReviewComment(comment);
        e.setReviewedBy(CurrentUser.username());
        e.setReviewedAt(Instant.now());
        audit.log(AuditAction.UPDATE, "AttendanceEvent", id, "Flagged event " + decision.toLowerCase() + "ed: " + e.getEmployeeNo() + " "
                + e.getDirection() + " " + e.getEventTime() + (comment == null ? "" : " - " + comment));
        if (e.getEmployeeId() != null) processing.reprocessIfProcessed(Map.of(e.getWorkDate(), Set.of(e.getEmployeeId())));
        return view(e, e.getEmployeeId() == null ? null : lookup.brief(e.getEmployeeId()));
    }

    @Transactional(readOnly = true)
    public PageResponse<EventView> search(LocalDate from, LocalDate to, Long employeeId, boolean flaggedOnly, boolean awaitingReview,
                                          int page, int size) {
        Set<Long> visible = lookup.visibleEmployeeIds();
        boolean seeUnknown = lookup.scopeUnits() == null;
        if (employeeId != null) lookup.requireAccess(employeeId);
        Specification<AttendanceEvent> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (visible != null) {
                Predicate mine = visible.isEmpty() ? cb.disjunction() : root.get("employeeId").in(visible);
                p.add(seeUnknown ? cb.or(mine, cb.isNull(root.get("employeeId"))) : mine);
            }
            if (employeeId != null) p.add(cb.equal(root.get("employeeId"), employeeId));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("workDate"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.get("workDate"), to));
            if (flaggedOnly || awaitingReview) p.add(cb.isTrue(root.get("flagged")));
            if (awaitingReview) p.add(cb.isNull(root.get("reviewStatus")));
            return cb.and(p.toArray(new Predicate[0]));
        };
        var res = events.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500),
                Sort.by(Sort.Direction.DESC, "eventTime")));
        Map<Long, EmployeeLookup.EmployeeBrief> briefs = lookup.briefs(res.getContent().stream()
                .map(AttendanceEvent::getEmployeeId).filter(Objects::nonNull).distinct().toList());
        return PageResponse.of(res, e -> view(e, e.getEmployeeId() == null ? null : briefs.get(e.getEmployeeId())));
    }

    @Transactional(readOnly = true)
    public boolean deviceIntegrationEnabled() {
        return integrations.isEnabled(IntegrationService.ATTENDANCE_DEVICE);
    }

    // ------------------------------------------------------------------ helpers

    static EventView view(AttendanceEvent e, EmployeeLookup.EmployeeBrief b) {
        return new EventView(e.getId(), e.getEmployeeId(), e.getEmployeeNo(), b == null ? null : b.nameWithInitials(),
                b == null ? null : b.orgUnitCode(), e.getEventTime(), e.getWorkDate(), e.getDirection().name(), e.getSource().name(),
                e.getDeviceId(), e.getBatchRef(), e.isFlagged(), e.getFlagReason(),
                e.getReviewStatus() == null ? null : e.getReviewStatus().name(), e.getReviewComment(), e.getReviewedBy(), e.getReviewedAt());
    }

    private LocalTime time(String key, LocalTime def) {
        try {
            return LocalTime.parse(settings.get(key, def.format(HM)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static AttendanceEvent.Direction direction(String s) {
        if (s == null) return null;
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "IN", "I", "CHECKIN", "CHECK-IN", "0" -> AttendanceEvent.Direction.IN;
            case "OUT", "O", "CHECKOUT", "CHECK-OUT", "1" -> AttendanceEvent.Direction.OUT;
            default -> null;
        };
    }

    /** Accepts ISO-8601 with offset/zone, or local "yyyy-MM-dd HH:mm[:ss]" interpreted in the organization time zone. */
    static Instant parseTime(String s, ZoneId zone) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim().replace('/', '-');
        if (v.length() >= 16 && v.charAt(10) == ' ') v = v.substring(0, 10) + 'T' + v.substring(11);
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return ZonedDateTime.parse(v).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(v).atZone(zone).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String cell(String[] c, int i) {
        if (i >= c.length) return null;
        String v = c[i].trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1).trim();
        return v;
    }

    private static void addError(List<String> errors, String msg) {
        if (errors.size() < MAX_ERRORS) errors.add(msg);
        else if (errors.size() == MAX_ERRORS) errors.add("... further errors omitted");
    }

    private static String summary(String what, IngestResult r) {
        return what + " " + r.batchRef() + ": " + r.received() + " received, " + r.accepted() + " stored (" + r.flagged()
                + " flagged), " + r.skippedDuplicates() + " duplicates skipped, " + r.rejected() + " rejected";
    }
}
