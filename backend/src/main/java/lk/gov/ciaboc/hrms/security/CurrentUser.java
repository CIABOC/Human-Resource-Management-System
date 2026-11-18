package lk.gov.ciaboc.hrms.security;

import lk.gov.ciaboc.hrms.common.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class CurrentUser {
    private CurrentUser() {
    }

    public static Optional<UserPrincipal> find() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof UserPrincipal p) return Optional.of(p);
        return Optional.empty();
    }

    public static UserPrincipal get() {
        return find().orElseThrow(() -> ApiException.unauthorized("Authentication required"));
    }

    public static String username() {
        return find().map(UserPrincipal::getUsername).orElse("system");
    }

    /** Employee id of the current user, or an error if the account is not linked to an employee record. */
    public static Long employeeId() {
        Long id = get().getEmployeeId();
        if (id == null) throw ApiException.rule("NO_EMPLOYEE_LINK", "Your user account is not linked to an employee record.");
        return id;
    }
}
