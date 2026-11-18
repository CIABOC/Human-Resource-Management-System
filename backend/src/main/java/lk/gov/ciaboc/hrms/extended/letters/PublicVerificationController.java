package lk.gov.ciaboc.hrms.extended.letters;

import jakarta.servlet.http.HttpServletRequest;
import lk.gov.ciaboc.hrms.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Public (unauthenticated) verification of issued letters and certificates (FR-EXT-005). Returns ONLY: valid/revoked,
 * document type, issue date and the holder's name with initials. Rate-limited per client IP. /api/public/** is
 * permitted without login by SecurityConfig.
 */
@RestController
@RequiredArgsConstructor
public class PublicVerificationController {
    private final LetterService letters;
    private final VerificationRateLimiter limiter;

    @GetMapping(value = "/api/public/verify/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LetterService.Verification> verify(@PathVariable String code, HttpServletRequest req) {
        throttle(req);
        LetterService.Verification v = letters.verify(code, req.getRemoteAddr())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No document matches this verification code"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v);
    }

    /** Human-readable page printed on documents (no scripts, strict CSP). */
    @GetMapping(value = "/api/public/verify/{code}/page", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> page(@PathVariable String code, HttpServletRequest req) {
        throttle(req);
        LetterService.Verification v = letters.verify(code, req.getRemoteAddr()).orElse(null);
        byte[] body = DocumentHtml.verificationPage(letters.orgName(), v).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(v == null ? HttpStatus.NOT_FOUND : HttpStatus.OK)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", DocumentHtml.CSP)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.CONTENT_LANGUAGE, "en")
                .body(body);
    }

    private void throttle(HttpServletRequest req) {
        if (!limiter.tryAcquire(req.getRemoteAddr()))
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many verification requests. Please try again later.");
    }
}
