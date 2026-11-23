package lk.gov.ciaboc.hrms.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService auth;
    private final SettingsService settings;

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 128) String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LogoutRequest(String refreshToken) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record Me(Long id, String username, String fullName, Long employeeId, String dataScope, String language,
                     String preferredTheme, Set<String> roles, Set<String> permissions, int sessionIdleMinutes) {
    }

    @PostMapping("/login")
    public AuthService.TokenPair login(@Valid @RequestBody LoginRequest r) {
        return auth.login(r.username(), r.password());
    }

    @PostMapping("/refresh")
    public AuthService.TokenPair refresh(@Valid @RequestBody RefreshRequest r) {
        return auth.refresh(r.refreshToken());
    }

    @PostMapping("/logout")
    public void logout(@RequestBody(required = false) LogoutRequest r) {
        auth.logout(r == null ? null : r.refreshToken());
    }

    @PostMapping("/change-password")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest r) {
        auth.changePassword(r.currentPassword(), r.newPassword());
    }

    @GetMapping("/me")
    public Me me() {
        UserPrincipal p = CurrentUser.get();
        return new Me(p.getId(), p.getUsername(), p.getFullName(), p.getEmployeeId(), p.getDataScope().name(),
                p.getLanguage(), p.getPreferredTheme(), p.getRoles(), p.getPermissions(),
                settings.getInt("session.idle-minutes", 15));
    }

    @GetMapping("/permissions")
    public List<String> permissionCatalogue() {
        return java.util.Arrays.stream(Permission.values()).map(Enum::name).toList();
    }
}
