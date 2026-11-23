package lk.gov.ciaboc.hrms.config;

import lk.gov.ciaboc.hrms.employee.EmployeeRepository;
import lk.gov.ciaboc.hrms.security.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * First start-up: creates the initial System Administrator from configuration. With demo users enabled (development
 * profile only) it also creates one account per proposed role, linked to the sample employees.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapRunner implements ApplicationRunner {
    private static final String DEMO_PASSWORD = "Demo@12345";

    private final AppProperties props;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final EmployeeRepository employees;
    private final PasswordEncoder encoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Bootstrap b = props.bootstrap();
        if (users.count() == 0) {
            if (b.adminPassword() == null || b.adminPassword().isBlank()) {
                log.warn("No users exist and BOOTSTRAP_ADMIN_PASSWORD is not set; no administrator was created.");
            } else {
                create(b.adminUsername(), "System Administrator", b.adminEmail(), null, DataScope.ALL, b.adminPassword(),
                        List.of("SYSTEM_ADMIN"));
                log.info("Initial administrator '{}' created. Change the password after first sign-in.", b.adminUsername());
            }
        } else if (b.resetExistingAdminPassword()) {
            if (b.adminPassword() == null || b.adminPassword().isBlank()) {
                log.warn("Existing administrator password reset was enabled, but BOOTSTRAP_ADMIN_PASSWORD is not set.");
            } else {
                var existingAdmin = users.findByUsernameIgnoreCase(b.adminUsername());
                if (existingAdmin.isEmpty()) {
                    log.warn("No existing administrator '{}' was found to reset.", b.adminUsername());
                } else {
                    AppUser admin = existingAdmin.get();
                    admin.setPasswordHash(encoder.encode(b.adminPassword()));
                    admin.setPasswordChangedAt(Instant.now());
                    users.save(admin);
                    log.warn("Password for configured administrator '{}' was reset from bootstrap configuration.",
                            b.adminUsername());
                }
            }
        }
        if (b.demoUsers()) createDemoUsers();
    }

    private void createDemoUsers() {
        record Demo(String username, String name, String empNo, DataScope scope, List<String> roleCodes) {
        }
        List<Demo> demos = List.of(
                new Demo("hr", "HR Administrator (demo)", "CIA-0003", DataScope.ALL, List.of("HR_ADMIN", "EMPLOYEE")),
                new Demo("establishment", "Establishment Officer (demo)", "CIA-0004", DataScope.ALL, List.of("ESTABLISHMENT_OFFICER", "EMPLOYEE")),
                new Demo("adminofficer", "Administrative Officer (demo)", "CIA-0005", DataScope.ALL, List.of("ADMIN_OFFICER", "EMPLOYEE")),
                new Demo("finance", "Finance Officer (demo)", "CIA-0006", DataScope.ALL, List.of("FINANCE_OFFICER", "EMPLOYEE")),
                new Demo("finance2", "Senior Accountant (demo checker)", "CIA-0011", DataScope.ALL, List.of("FINANCE_OFFICER", "EMPLOYEE")),
                new Demo("head", "Director Investigation (demo)", "CIA-0007", DataScope.DIVISION, List.of("DIVISION_HEAD", "EMPLOYEE")),
                new Demo("employee", "Investigating Officer (demo)", "CIA-0008", DataScope.SELF, List.of("EMPLOYEE")),
                new Demo("dg", "Director General (demo)", "CIA-0001", DataScope.ALL, List.of("MANAGEMENT", "EMPLOYEE")),
                new Demo("auditor", "Internal Auditor (demo)", "CIA-0010", DataScope.ALL, List.of("AUDITOR", "EMPLOYEE")),
                new Demo("hr2", "HR Assistant (demo checker)", "CIA-0012", DataScope.ALL, List.of("HR_ADMIN", "EMPLOYEE")));
        for (Demo d : demos) {
            if (users.existsByUsernameIgnoreCase(d.username())) continue;
            Long empId = employees.findByEmployeeNo(d.empNo()).map(e -> e.getId()).orElse(null);
            if (empId != null && users.findByEmployeeId(empId).isPresent()) empId = null;
            AppUser u = create(d.username(), d.name(), d.username() + "@demo.ciaboc.lk", empId, d.scope(), DEMO_PASSWORD, d.roleCodes());
            log.info("Demo user '{}' created ({})", u.getUsername(), String.join(",", d.roleCodes()));
        }
    }

    private AppUser create(String username, String name, String email, Long employeeId, DataScope scope, String password,
                           List<String> roleCodes) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setFullName(name);
        u.setEmail(email);
        u.setEmployeeId(employeeId);
        u.setDataScope(scope);
        u.setPasswordHash(encoder.encode(password));
        u.setPasswordChangedAt(Instant.now());
        Set<Role> rs = new HashSet<>();
        for (String c : roleCodes) roles.findByCode(c).ifPresent(rs::add);
        u.setRoles(rs);
        return users.save(u);
    }
}
