package lk.gov.ciaboc.hrms.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditLog;
import lk.gov.ciaboc.hrms.audit.AuditLogRepository;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.files.StoredFile;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {
    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuditLogRepository auditLogs;
    private final AuditService audit;
    private final AuthService auth;
    private final FileStorageService files;

    public record Preferences(String preferredLanguage, String preferredTheme, boolean notifySystem, boolean notifyEmail,
                              boolean notifySms) {
    }

    public record PreferencesRequest(@Pattern(regexp = "en|si|ta") String preferredLanguage,
                                     @Pattern(regexp = "LIGHT|DARK") String preferredTheme, Boolean notifySystem,
                                     Boolean notifyEmail, Boolean notifySms) {
    }

    public record LoginRecord(Instant occurredAt, String action, String ipAddress, String summary) {
    }

    public record SessionRecord(Long id, Instant createdAt, Instant expiresAt) {
    }

    @GetMapping("/preferences")
    public Preferences preferences() {
        return preferencesOf(currentUser());
    }

    @PutMapping("/preferences")
    @Transactional
    public Preferences updatePreferences(@Valid @RequestBody PreferencesRequest request) {
        AppUser user = currentUser();
        Map<String, Object> before = preferenceSnapshot(user);
        if (request.preferredLanguage() != null) user.setPreferredLanguage(request.preferredLanguage());
        if (request.preferredTheme() != null) user.setPreferredTheme(request.preferredTheme());
        if (request.notifySystem() != null) user.setNotifySystem(request.notifySystem());
        if (request.notifyEmail() != null) user.setNotifyEmail(request.notifyEmail());
        if (request.notifySms() != null) user.setNotifySms(request.notifySms());
        Map<String, Object> after = preferenceSnapshot(user);
        audit.log(AuditAction.CONFIG_CHANGE, "AccountPreferences", user.getId(), "Account preferences updated", before, after);
        return preferencesOf(user);
    }

    @GetMapping("/login-history")
    public List<LoginRecord> loginHistory() {
        List<AuditLog> rows = auditLogs.findByUserIdAndActionInOrderByOccurredAtDesc(CurrentUser.get().getId(),
                List.of(AuditAction.LOGIN.name(), AuditAction.LOGIN_FAILED.name()), PageRequest.of(0, 50));
        return rows.stream().map(row -> new LoginRecord(row.getOccurredAt(), row.getAction(), row.getIpAddress(),
                row.getSummary())).toList();
    }

    @GetMapping("/sessions")
    public List<SessionRecord> activeSessions() {
        return refreshTokens.findByUserIdAndRevokedFalseAndExpiresAtAfterOrderByCreatedAtDesc(CurrentUser.get().getId(),
                        Instant.now()).stream()
                .map(token -> new SessionRecord(token.getId(), token.getCreatedAt(), token.getExpiresAt())).toList();
    }

    @PostMapping("/sessions/logout-all")
    public void logoutAll() {
        auth.logoutAll();
    }

    @PostMapping(value = "/profile-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String, Long> uploadProfilePhoto(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 5 * 1024 * 1024)
            throw ApiException.badRequest("Profile photo must be 5 MB or smaller");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")))
            throw ApiException.rule("FILE_TYPE", "Profile photo must be a JPG or PNG image");
        String contentType = file.getContentType();
        if (contentType == null || !(contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/png")))
            throw ApiException.rule("FILE_TYPE", "Profile photo must be a JPG or PNG image");
        StoredFile stored = files.store(file, "ACCOUNT_PHOTO");
        AppUser user = currentUser();
        Long oldFileId = user.getProfilePhotoFileId();
        user.setProfilePhotoFileId(stored.getId());
        audit.log(AuditAction.UPDATE, "AccountProfilePhoto", user.getId(), "Profile photo updated",
                oldFileId, stored.getId());
        return Map.of("fileId", stored.getId());
    }

    @GetMapping("/profile-photo")
    public ResponseEntity<Resource> profilePhoto() {
        Long fileId = currentUser().getProfilePhotoFileId();
        if (fileId == null) throw ApiException.notFound("Profile photo", CurrentUser.get().getId());
        ResponseEntity<Resource> response = files.download(fileId);
        return ResponseEntity.ok()
                .contentType(response.getHeaders().getContentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM : response.getHeaders().getContentType())
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(response.getBody());
    }

    @DeleteMapping("/profile-photo")
    @Transactional
    public void deleteProfilePhoto() {
        AppUser user = currentUser();
        Long oldFileId = user.getProfilePhotoFileId();
        if (oldFileId != null) {
            user.setProfilePhotoFileId(null);
            audit.log(AuditAction.UPDATE, "AccountProfilePhoto", user.getId(), "Profile photo removed", oldFileId, null);
        }
    }

    private AppUser currentUser() {
        return users.findById(CurrentUser.get().getId()).orElseThrow(() -> ApiException.notFound("User", CurrentUser.get().getId()));
    }

    private static Preferences preferencesOf(AppUser user) {
        return new Preferences(user.getPreferredLanguage(), user.getPreferredTheme(), user.isNotifySystem(),
                user.isNotifyEmail(), user.isNotifySms());
    }

    private static Map<String, Object> preferenceSnapshot(AppUser user) {
        return Map.of("preferredLanguage", user.getPreferredLanguage(), "preferredTheme", user.getPreferredTheme(),
                "notifySystem", user.isNotifySystem(), "notifyEmail", user.isNotifyEmail(), "notifySms", user.isNotifySms());
    }
}
