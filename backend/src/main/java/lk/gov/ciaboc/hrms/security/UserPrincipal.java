package lk.gov.ciaboc.hrms.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Authenticated user as seen by the rest of the application. */
@Getter
public class UserPrincipal implements UserDetails {
    private final Long id;
    private final String username;
    private final String fullName;
    private final Long employeeId;
    private final DataScope dataScope;
    private final String language;
    private final Set<String> roles;
    private final Set<String> permissions;
    private final Set<GrantedAuthority> authorities;

    public UserPrincipal(AppUser u) {
        this.id = u.getId();
        this.username = u.getUsername();
        this.fullName = u.getFullName();
        this.employeeId = u.getEmployeeId();
        this.dataScope = u.getDataScope();
        this.language = u.getPreferredLanguage();
        this.roles = new HashSet<>();
        this.permissions = new HashSet<>();
        u.getRoles().stream().filter(Role::isActive).forEach(r -> {
            roles.add(r.getCode());
            permissions.addAll(r.getPermissions());
        });
        this.authorities = new HashSet<>();
        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
    }

    public boolean has(Permission p) {
        return permissions.contains(p.name());
    }

    public boolean hasRole(String roleCode) {
        return roles.contains(roleCode);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return null;
    }
}
