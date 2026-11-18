package lk.gov.ciaboc.hrms.extended.letters;

import lk.gov.ciaboc.hrms.extended.letters.LetterEntities.IssuedDocument;
import lk.gov.ciaboc.hrms.extended.letters.LetterEntities.LetterRequest;
import lk.gov.ciaboc.hrms.extended.letters.LetterEntities.LetterTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/extended")
@RequiredArgsConstructor
public class LetterController {
    private final LetterService service;

    public record ReasonInput(String reason) {
    }

    // ---------------------------------------------------------------- self-service

    @GetMapping("/my-letters")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public LetterService.MyLetters myLetters() {
        return service.myLetters();
    }

    @PostMapping("/my-letters/requests")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public LetterService.RequestView request(@RequestBody LetterService.RequestInput in) {
        return service.request(in);
    }

    @PostMapping("/my-letters/requests/{id}/withdraw")
    @PreAuthorize("hasAuthority('SELF_SERVICE')")
    public LetterService.RequestView withdraw(@PathVariable Long id) {
        return service.cancelMine(id);
    }

    // ---------------------------------------------------------------- documents (holder or HR)

    @GetMapping("/documents/{id}")
    public LetterService.DocumentView document(@PathVariable Long id) {
        return service.documentDetail(id); // holder (self-service) or EXT_VIEW within scope
    }

    @GetMapping(value = "/documents/{id}/print", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> print(@PathVariable Long id) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", DocumentHtml.CSP)
                .header("X-Content-Type-Options", "nosniff")
                .body(service.printable(id).getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- HR

    @GetMapping("/letters/requests")
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public List<LetterService.RequestView> requests(@RequestParam(required = false) LetterRequest.Status status) {
        return service.requests(status);
    }

    @GetMapping("/letters/documents")
    @PreAuthorize("hasAuthority('EXT_VIEW') or hasAuthority('EXT_MANAGE')")
    public List<LetterService.DocumentView> register(@RequestParam(required = false) IssuedDocument.Status status) {
        return service.register(status);
    }

    @GetMapping("/letters/options")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public List<LetterService.TemplateOption> options() {
        return service.allOptions();
    }

    @PostMapping("/letters/preview")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public LetterService.PreviewResult preview(@RequestBody LetterService.IssueInput in) {
        return service.preview(in);
    }

    @PostMapping("/letters/issue")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public LetterService.DocumentView issue(@RequestBody LetterService.IssueInput in) {
        return service.issue(in);
    }

    @PostMapping("/letters/documents/{id}/revoke")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public LetterService.DocumentView revoke(@PathVariable Long id, @RequestBody ReasonInput in) {
        return service.revoke(id, in.reason());
    }

    // ---------------------------------------------------------------- templates (administration)

    @GetMapping("/letter-templates")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public List<LetterTemplate> templates() {
        return service.templates();
    }

    @PostMapping("/letter-templates")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public LetterTemplate create(@RequestBody LetterService.TemplateInput in) {
        return service.saveTemplate(null, in);
    }

    @PutMapping("/letter-templates/{id}")
    @PreAuthorize("hasAuthority('EXT_MANAGE')")
    public LetterTemplate update(@PathVariable Long id, @RequestBody LetterService.TemplateInput in) {
        return service.saveTemplate(id, in);
    }
}
