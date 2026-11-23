package lk.gov.ciaboc.hrms.security;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * Password sign-in with short-lived JWT access tokens and rotating refresh tokens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private static final Pattern PASSWORD_POLICY =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,128}$");
    private static final String INVALID = "Invalid username or password";

    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final AppProperties props;

    public record TokenPair(String accessToken, String refreshToken, int expiresInSeconds) {
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair login(String username, String password) {
        AppUser u = users.findByUsernameIgnoreCase(username.trim()).orElse(null);
        if (u == null || !u.isActive() || u.getPasswordHash() == null) {
            encoder.matches(password, "$2a$12$C6UzMDM.H6dfI/f/IKcEeO1o5c1HnTRv3bIu0QnqTvH0nEoOv0mE2"); // equalize timing
            audit.logAs(u == null ? null : u.getId(), username, AuditAction.LOGIN_FAILED, "AppUser", null,
                    u == null ? "Unknown user" : "Inactive account");
            throw ApiException.unauthorized(INVALID);
        }
        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                    "Account temporarily locked after repeated failed sign-in attempts. Try again later.");
        }
        if (!encoder.matches(password, u.getPasswordHash())) {
            u.setFailedAttempts(u.getFailedAttempts() + 1);
            if (u.getFailedAttempts() >= props.security().lockout().maxFailedAttempts()) {
                u.setLockedUntil(Instant.now().plus(props.security().lockout().lockMinutes(), ChronoUnit.MINUTES));
                u.setFailedAttempts(0);
                audit.logAs(u.getId(), u.getUsername(), AuditAction.ACCOUNT_LOCKED, "AppUser", u.getId(), "Locked after failed attempts");
            }
            audit.logAs(u.getId(), u.getUsername(), AuditAction.LOGIN_FAILED, "AppUser", u.getId(), "Wrong password");
            throw ApiException.unauthorized(INVALID);
        }
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        u.setLastLoginAt(Instant.now());
        audit.logAs(u.getId(), u.getUsername(), AuditAction.LOGIN, "AppUser", u.getId(), "Signed in");
        return issueTokens(u);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair refresh(String refreshToken) {
        RefreshToken t = refreshTokens.findByTokenHash(Hashing.sha256(refreshToken))
                .orElseThrow(() -> ApiException.unauthorized("Session expired. Please sign in again."));
        if (t.isRevoked()) {
            // Re-use of a rotated token suggests theft: revoke the whole family for this user.
            refreshTokens.revokeAllForUser(t.getUserId());
            throw ApiException.unauthorized("Session expired. Please sign in again.");
        }
        if (t.getExpiresAt().isBefore(Instant.now())) throw ApiException.unauthorized("Session expired. Please sign in again.");
        AppUser u = users.findById(t.getUserId()).filter(AppUser::isActive)
                .orElseThrow(() -> ApiException.unauthorized("Session expired. Please sign in again."));
        t.setRevoked(true);
        return issueTokens(u);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null) {
            refreshTokens.findByTokenHash(Hashing.sha256(refreshToken)).ifPresent(t -> t.setRevoked(true));
        }
        CurrentUser.find().ifPresent(p -> audit.log(AuditAction.LOGOUT, "AppUser", p.getId(), "Signed out"));
    }

    @Transactional
    public void changePassword(String current, String next) {
        AppUser u = users.findById(CurrentUser.get().getId()).orElseThrow();
        if (u.getPasswordHash() == null || !encoder.matches(current, u.getPasswordHash())) {
            throw ApiException.rule("WRONG_PASSWORD", "Current password is incorrect");
        }
        setPassword(u, next);
        refreshTokens.revokeAllForUser(u.getId());
        u.setTokenVersion(u.getTokenVersion() + 1);
        audit.log(AuditAction.PASSWORD_CHANGE, "AppUser", u.getId(), "Password changed");
    }

    @Transactional
    public void logoutAll() {
        AppUser user = users.findById(CurrentUser.get().getId()).orElseThrow();
        refreshTokens.revokeAllForUser(user.getId());
        user.setTokenVersion(user.getTokenVersion() + 1);
        audit.log(AuditAction.LOGOUT, "AppUser", user.getId(), "Signed out from all devices");
    }

    public void setPassword(AppUser u, String raw) {
        if (raw == null || !PASSWORD_POLICY.matcher(raw).matches()) {
            throw ApiException.rule("WEAK_PASSWORD",
                    "Password must be at least 10 characters and include upper and lower case letters, a digit and a symbol.");
        }
        u.setPasswordHash(encoder.encode(raw));
        u.setPasswordChangedAt(Instant.now());
    }

    private TokenPair issueTokens(AppUser u) {
        String raw = Hashing.randomToken(48);
        RefreshToken rt = new RefreshToken();
        rt.setTokenHash(Hashing.sha256(raw));
        rt.setUserId(u.getId());
        rt.setCreatedAt(Instant.now());
        rt.setExpiresAt(Instant.now().plus(props.security().jwt().refreshTokenHours(), ChronoUnit.HOURS));
        refreshTokens.save(rt);
        return new TokenPair(jwt.issueAccessToken(u), raw, jwt.accessTokenSeconds());
    }

    @Scheduled(cron = "0 15 2 * * *")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        int b = refreshTokens.deleteExpired(cutoff);
        log.debug("Purged {} refresh tokens", b);
    }
}
