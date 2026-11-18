package lk.gov.ciaboc.hrms.security;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.config.AppProperties;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Two-step sign-in: password, then a one-time code sent to the registered e-mail or mobile. Only then are a short-lived
 * JWT access token and a rotating refresh token issued. Designed so the password step can later be replaced by the
 * CIABOC SSO/IAM platform (INT-01) without changing the token flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private static final Pattern PASSWORD_POLICY =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,128}$");
    private static final String INVALID = "Invalid username or password";

    private final AppUserRepository users;
    private final OtpChallengeRepository otps;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final NotificationService notificationService;
    private final AuditService audit;
    private final AppProperties props;

    public record LoginChallenge(String challengeId, String deliveredTo, int expiresInSeconds, String devOtp) {
    }

    public record TokenPair(String accessToken, String refreshToken, int expiresInSeconds) {
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginChallenge login(String username, String password) {
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

        String code = Hashing.randomDigits(props.security().otp().length());
        OtpChallenge c = new OtpChallenge();
        c.setId(UUID.randomUUID().toString());
        c.setUserId(u.getId());
        c.setCodeHash(Hashing.sha256(c.getId() + ":" + code));
        c.setCreatedAt(Instant.now());
        c.setSentAt(Instant.now());
        c.setExpiresAt(Instant.now().plusSeconds(props.security().otp().expirySeconds()));
        otps.save(c);
        String deliveredTo = notificationService.sendOtp(u, code, Math.max(1, props.security().otp().expirySeconds() / 60));
        return new LoginChallenge(c.getId(), deliveredTo, props.security().otp().expirySeconds(),
                props.security().otp().devEcho() ? code : null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginChallenge resendOtp(String challengeId) {
        OtpChallenge c = activeChallenge(challengeId);
        if (c.getSentAt().isAfter(Instant.now().minusSeconds(30))) {
            throw ApiException.rule("OTP_RESEND_TOO_SOON", "Please wait 30 seconds before requesting a new code.");
        }
        AppUser u = users.findById(c.getUserId()).orElseThrow(() -> ApiException.unauthorized(INVALID));
        String code = Hashing.randomDigits(props.security().otp().length());
        c.setCodeHash(Hashing.sha256(c.getId() + ":" + code));
        c.setSentAt(Instant.now());
        c.setAttempts(0);
        c.setExpiresAt(Instant.now().plusSeconds(props.security().otp().expirySeconds()));
        String deliveredTo = notificationService.sendOtp(u, code, Math.max(1, props.security().otp().expirySeconds() / 60));
        return new LoginChallenge(c.getId(), deliveredTo, props.security().otp().expirySeconds(),
                props.security().otp().devEcho() ? code : null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair verifyOtp(String challengeId, String code) {
        OtpChallenge c = activeChallenge(challengeId);
        if (c.getAttempts() >= props.security().otp().maxAttempts()) {
            c.setConsumed(true);
            throw ApiException.unauthorized("Too many incorrect codes. Please sign in again.");
        }
        AppUser u = users.findById(c.getUserId()).filter(AppUser::isActive)
                .orElseThrow(() -> ApiException.unauthorized(INVALID));
        if (code == null || !Hashing.constantTimeEquals(c.getCodeHash(), Hashing.sha256(c.getId() + ":" + code.trim()))) {
            c.setAttempts(c.getAttempts() + 1);
            audit.logAs(u.getId(), u.getUsername(), AuditAction.OTP_FAILED, "AppUser", u.getId(), "Incorrect verification code");
            throw ApiException.unauthorized("Incorrect verification code");
        }
        c.setConsumed(true);
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
        audit.log(AuditAction.PASSWORD_CHANGE, "AppUser", u.getId(), "Password changed");
    }

    public void setPassword(AppUser u, String raw) {
        if (raw == null || !PASSWORD_POLICY.matcher(raw).matches()) {
            throw ApiException.rule("WEAK_PASSWORD",
                    "Password must be at least 10 characters and include upper and lower case letters, a digit and a symbol.");
        }
        u.setPasswordHash(encoder.encode(raw));
        u.setPasswordChangedAt(Instant.now());
    }

    private OtpChallenge activeChallenge(String id) {
        OtpChallenge c = otps.findById(id == null ? "" : id)
                .orElseThrow(() -> ApiException.unauthorized("Verification session not found. Please sign in again."));
        if (c.isConsumed() || c.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("Verification code expired. Please sign in again.");
        }
        return c;
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
        int a = otps.deleteExpired(cutoff);
        int b = refreshTokens.deleteExpired(cutoff);
        log.debug("Purged {} OTP challenges and {} refresh tokens", a, b);
    }
}
