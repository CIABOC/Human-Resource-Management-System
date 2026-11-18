package lk.gov.ciaboc.hrms.employee;

import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.establishment.PositionRepository;
import lk.gov.ciaboc.hrms.masterdata.MasterDataUsageChecker;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.workflow.OrgDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/** Resolves workflow approvers from the live organization structure, and guards master data in use. */
@Component
@RequiredArgsConstructor
public class EmployeeOrgDirectory implements OrgDirectory, MasterDataUsageChecker {
    private final EmployeeRepository employees;
    private final EmployeeAssignmentRepository assignments;
    private final PositionRepository positions;
    private final AppUserRepository users;
    private final EstablishmentService establishment;
    private final EmployeeDocumentRepository documents;

    @Override
    @Transactional(readOnly = true)
    public String orgUnitOfEmployee(Long employeeId) {
        return employees.findById(employeeId).map(Employee::getOrgUnitCode).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> userIdsHoldingPosition(String positionCode) {
        Set<Long> out = new LinkedHashSet<>();
        positions.findByCode(positionCode).ifPresent(pos -> {
            for (Employee e : employees.findAll((root, q, cb) -> cb.equal(root.get("positionId"), pos.getId()))) {
                users.findByEmployeeId(e.getId()).filter(AppUser::isActive).ifPresent(u -> out.add(u.getId()));
            }
        });
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> divisionHeadUserIdsFor(String orgUnitCode, Long excludeUserId) {
        List<String> chain = establishment.unitAndAncestors(orgUnitCode, LocalDate.now());
        List<AppUser> heads = users.findActiveByRole("DIVISION_HEAD");
        // Nearest level that has a head wins, so a section's request goes to its own head first.
        for (String unit : chain) {
            Set<Long> atLevel = new LinkedHashSet<>();
            for (AppUser u : heads) {
                if (u.getEmployeeId() == null || u.getId().equals(excludeUserId)) continue;
                String hisUnit = orgUnitOfEmployee(u.getEmployeeId());
                if (unit.equals(hisUnit)) atLevel.add(u.getId());
            }
            if (!atLevel.isEmpty()) return atLevel;
        }
        return Set.of();
    }

    @Override
    @Transactional(readOnly = true)
    public long usageCount(String category, String code) {
        return switch (category) {
            case "EMPLOYEE_STATUS" -> employees.countByStatus(code);
            case "EMPLOYMENT_TYPE" -> employees.countByEmploymentType(code);
            case "EMPLOYEE_CATEGORY" -> employees.countByEmployeeCategory(code);
            case "SERVICE_CATEGORY" -> employees.countByServiceCategory(code);
            case "DOCUMENT_TYPE" -> documents.countByDocumentType(code);
            default -> 0;
        };
    }
}
