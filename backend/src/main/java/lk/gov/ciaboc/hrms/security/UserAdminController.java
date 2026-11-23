package lk.gov.ciaboc.hrms.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditLog;
import lk.gov.ciaboc.hrms.audit.AuditLogRepository;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/** User and Access Management (FR-UAM-002..008). */
@RestController
@RequiredArgsConstructor
public class UserAdminController {
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final AuthService authService;
    private final AuditService audit;
    private final AuditLogRepository auditLogs;
    private final lk.gov.ciaboc.hrms.employee.EmployeeRepository employees;

    public record UserView(Long id, String username, String fullName, String email, String mobile, Long employeeId,
                           boolean active, String dataScope, String preferredLanguage,
                           List<String> roles, Instant lastLoginAt, boolean locked) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getMobile(),
                    u.getEmployeeId(), u.isActive(), u.getDataScope().name(), u.getPreferredLanguage(),
                    u.getRoles().stream().map(Role::getCode).sorted().toList(), u.getLastLoginAt(),
                    u.getLockedUntil() != null && u.getLockedUntil().isAfter(Instant.now()));
        }
    }

    public record UserRequest(@NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
                              @NotBlank @Size(max = 200) String fullName,
                              @Email @Size(max = 200) String email,
                              @Size(max = 30) String mobile,
                              Long employeeId,
                              @NotNull DataScope dataScope,
                              @Pattern(regexp = "en|si|ta") String preferredLanguage,
                              Set<String> roleCodes,
                              String password) {
    }

    @GetMapping("/api/admin/users")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    public PageResponse<UserView> list(@RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(users.search(q == null || q.isBlank() ? null : q.trim(),
                PageRequest.of(page, Math.min(size, 100), Sort.by("username"))), UserView::of);
    }

    @GetMapping("/api/admin/users/{id}")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    public UserView get(@PathVariable Long id) {
        return UserView.of(find(id));
    }

    @PostMapping("/api/admin/users")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    @Transactional
    public UserView create(@Valid @RequestBody UserRequest r) {
        if (users.existsByUsernameIgnoreCase(r.username())) throw ApiException.conflict("Username already exists");
        if (r.employeeId() != null && users.findByEmployeeId(r.employeeId()).isPresent())
            throw ApiException.conflict("That employee already has a user account");
        AppUser u = new AppUser();
        u.setUsername(r.username().trim());
        apply(u, r);
        if (r.password() != null && !r.password().isBlank()) authService.setPassword(u, r.password());
        users.save(u);
        audit.log(AuditAction.CREATE, "AppUser", u.getId(), "User " + u.getUsername() + " created", null, UserView.of(u));
        return UserView.of(u);
    }

    @PutMapping("/api/admin/users/{id}")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    @Transactional
    public UserView update(@PathVariable Long id, @Valid @RequestBody UserRequest r) {
        AppUser u = find(id);
        UserView before = UserView.of(u);
        if (r.employeeId() != null && !Objects.equals(r.employeeId(), u.getEmployeeId())
                && users.findByEmployeeId(r.employeeId()).isPresent())
            throw ApiException.conflict("That employee already has a user account");
        apply(u, r);
        audit.log(AuditAction.UPDATE, "AppUser", id, "User " + u.getUsername() + " updated", before, UserView.of(u));
        return UserView.of(u);
    }

    private void apply(AppUser u, UserRequest r) {
        u.setFullName(r.fullName().trim());
        u.setEmail(r.email());
        u.setMobile(r.mobile());
        u.setEmployeeId(r.employeeId());
        u.setDataScope(r.dataScope());
        u.setPreferredLanguage(r.preferredLanguage() == null ? "en" : r.preferredLanguage());
        Set<Role> rs = new HashSet<>();
        if (r.roleCodes() != null) {
            for (String code : r.roleCodes()) rs.add(roles.findByCode(code).orElseThrow(() -> ApiException.notFound("Role", code)));
        }
        u.setRoles(rs);
    }

    @PostMapping("/api/admin/users/{id}/status")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    @Transactional
    public UserView setStatus(@PathVariable Long id, @RequestParam boolean active) {
        AppUser u = find(id);
        if (u.getId().equals(CurrentUser.get().getId()) && !active) throw ApiException.rule("SELF_DEACTIVATE", "You cannot deactivate your own account");
        u.setActive(active);
        if (!active) refreshTokens.revokeAllForUser(u.getId());
        audit.log(active ? AuditAction.ACTIVATE : AuditAction.DEACTIVATE, "AppUser", id,
                "User " + u.getUsername() + (active ? " activated" : " deactivated"));
        return UserView.of(u);
    }

    public record ResetPasswordRequest(@NotBlank String newPassword) {
    }

    @PostMapping("/api/admin/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    @Transactional
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest r) {
        AppUser u = find(id);
        authService.setPassword(u, r.newPassword());
        u.setLockedUntil(null);
        u.setFailedAttempts(0);
        refreshTokens.revokeAllForUser(u.getId());
        audit.log(AuditAction.PASSWORD_CHANGE, "AppUser", id, "Password reset by administrator for " + u.getUsername());
    }

    @PostMapping("/api/admin/users/{id}/unlock")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    @Transactional
    public UserView unlock(@PathVariable Long id) {
        AppUser u = find(id);
        u.setLockedUntil(null);
        u.setFailedAttempts(0);
        audit.log(AuditAction.UPDATE, "AppUser", id, "Account unlocked: " + u.getUsername());
        return UserView.of(u);
    }

    /** User activity history (FR-UAM-005). */
    @GetMapping("/api/admin/users/{id}/activity")
    @PreAuthorize("hasAuthority('UAM_MANAGE') or hasAuthority('AUDIT_VIEW')")
    public PageResponse<AuditLog> activity(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "25") int size) {
        Specification<AuditLog> spec = (root, q, cb) -> cb.equal(root.get("userId"), id);
        return PageResponse.of(auditLogs.findAll(spec, PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "id"))));
    }

    // ---- roles ----

    public record RoleView(Long id, String code, String name, String description, boolean systemRole, boolean active,
                           List<String> permissions) {
        static RoleView of(Role r) {
            return new RoleView(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.isSystemRole(), r.isActive(),
                    r.getPermissions().stream().sorted().toList());
        }
    }

    public record RoleRequest(@NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Z0-9_]+$") String code,
                              @NotBlank @Size(max = 150) String name, @Size(max = 500) String description,
                              boolean active, Set<String> permissions) {
    }

    @GetMapping("/api/admin/roles")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    public List<RoleView> roles() {
        return roles.findAll(Sort.by("code")).stream().map(RoleView::of).toList();
    }

    @PostMapping("/api/admin/roles")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    @Transactional
    public RoleView createRole(@Valid @RequestBody RoleRequest r) {
        if (roles.findByCode(r.code()).isPresent()) throw ApiException.conflict("Role code already exists");
        Role role = new Role();
        role.setCode(r.code());
        applyRole(role, r);
        roles.save(role);
        audit.log(AuditAction.CREATE, "Role", role.getId(), "Role " + role.getCode() + " created", null, RoleView.of(role));
        return RoleView.of(role);
    }

    @PutMapping("/api/admin/roles/{id}")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    @Transactional
    public RoleView updateRole(@PathVariable Long id, @Valid @RequestBody RoleRequest r) {
        Role role = roles.findById(id).orElseThrow(() -> ApiException.notFound("Role", id));
        RoleView before = RoleView.of(role);
        applyRole(role, r);
        audit.log(AuditAction.UPDATE, "Role", id, "Role " + role.getCode() + " updated", before, RoleView.of(role));
        return RoleView.of(role);
    }

    private void applyRole(Role role, RoleRequest r) {
        Set<String> valid = new HashSet<>();
        for (Permission p : Permission.values()) valid.add(p.name());
        Set<String> perms = r.permissions() == null ? Set.of() : r.permissions();
        for (String p : perms) if (!valid.contains(p)) throw ApiException.badRequest("Unknown permission " + p);
        role.setName(r.name());
        role.setDescription(r.description());
        role.setActive(r.active());
        role.setPermissions(new HashSet<>(perms));
    }

    public record PermissionView(String code, String description) {
    }

    @GetMapping("/api/admin/permissions")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    public List<PermissionView> permissions() {
        return Arrays.stream(Permission.values()).map(p -> new PermissionView(p.name(), p.getDescription())).toList();
    }

    /** Minimal employee directory for linking accounts: number and name only, no HR content. */
    public record EmployeeOption(Long id, String employeeNo, String name, boolean hasAccount) {
    }

    @GetMapping("/api/admin/users/employee-options")
    @PreAuthorize("hasAuthority('UAM_MANAGE')")
    public List<EmployeeOption> employeeOptions(@RequestParam(required = false) String q) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        Set<Long> linked = new HashSet<>();
        users.findAll().forEach(u -> {
            if (u.getEmployeeId() != null) linked.add(u.getEmployeeId());
        });
        return employees.findAll(Sort.by("employeeNo")).stream()
                .filter(e -> lk.gov.ciaboc.hrms.employee.EmployeeLookup.IN_SERVICE.contains(e.getStatus()))
                .filter(e -> needle.isEmpty() || e.getEmployeeNo().toLowerCase().contains(needle)
                        || e.getNameWithInitials().toLowerCase().contains(needle))
                .limit(50)
                .map(e -> new EmployeeOption(e.getId(), e.getEmployeeNo(), e.getNameWithInitials(), linked.contains(e.getId())))
                .toList();
    }

    private AppUser find(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User", id));
    }
}
