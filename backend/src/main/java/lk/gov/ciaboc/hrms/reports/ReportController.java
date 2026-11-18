package lk.gov.ciaboc.hrms.reports;

import lk.gov.ciaboc.hrms.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('RPT_VIEW')")
public class ReportController {
    private final ReportService service;
    private final ReportExporter exporter;

    @GetMapping
    public List<ReportService.CatalogueEntry> catalogue() {
        return service.catalogue();
    }

    @PostMapping("/{code}/run")
    public ReportService.ReportResult run(@PathVariable String code, @RequestBody(required = false) ReportService.ReportFilters f) {
        return service.run(code, f);
    }

    @PostMapping("/{code}/export")
    public ResponseEntity<byte[]> export(@PathVariable String code, @RequestParam String format,
                                         @RequestBody(required = false) ReportService.ReportFilters f) {
        String fmt = format.toLowerCase();
        if (!List.of("csv", "xlsx", "pdf").contains(fmt)) throw ApiException.badRequest("Format must be csv, xlsx or pdf");
        ReportService.ReportResult r = service.runForExport(code, f, fmt);
        byte[] body = switch (fmt) {
            case "csv" -> exporter.csv(r);
            case "xlsx" -> exporter.xlsx(r);
            default -> exporter.pdf(r);
        };
        MediaType type = switch (fmt) {
            case "csv" -> new MediaType("text", "csv", StandardCharsets.UTF_8);
            case "xlsx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default -> MediaType.APPLICATION_PDF;
        };
        String name = code.toLowerCase() + "-" + LocalDate.now() + "." + fmt;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(type)
                .body(body);
    }
}
