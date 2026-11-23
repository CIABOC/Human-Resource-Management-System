package lk.gov.ciaboc.hrms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String timezone, Cors cors, Security security, Files files,
                            Notifications notifications, Bootstrap bootstrap) {

    public record Cors(List<String> allowedOrigins) {
    }

    public record Security(Jwt jwt, Lockout lockout) {
    }

    public record Jwt(String secret, String issuer, int accessTokenMinutes, int refreshTokenHours) {
    }

    public record Lockout(int maxFailedAttempts, int lockMinutes) {
    }

    public record Files(String storagePath, List<String> allowedExtensions, Clamav clamav) {
    }

    public record Clamav(boolean enabled, String host, int port) {
    }

    public record Notifications(String mailFrom, long dispatchIntervalMs, int maxAttempts) {
    }

    public record Bootstrap(String adminUsername, String adminPassword, String adminEmail, boolean demoUsers,
                            boolean resetExistingAdminPassword) {
    }
}
