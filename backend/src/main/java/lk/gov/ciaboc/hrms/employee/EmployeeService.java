package lk.gov.ciaboc.hrms.employee;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.PageResponse;
import lk.gov.ciaboc.hrms.establishment.*;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.files.StoredFile;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lk.gov.ciaboc.hrms.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeService implements WorkflowHandler {
    public static final String PROFILE_PROCESS = "PROFILE_CHANGE";
    private static final Pattern NIC = Pattern.compile("^(\\d{9}[VX]|\\d{12})$");
    private static final Pattern EMP_NO = Pattern.compile("^[A-Za-z0-9/_-]{1,30}$");
    private static final Set<String> SEPARATION_STATUSES = Set.of("RETIRED", "RESIGNED", "TERMINATED", "TRANSFERRED");
    /** Fields that go through maker-checker when enabled (FR-EMP-012). */
    private static final Set<String> SENSITIVE_FIELDS = Set.of("fullName", "nic", "dateOfBirth", "bankCode", "bankBranch", "bankAccountNo");
    /** Fields an employee may request to change through self-service (FR-EMP-013). */
    private static final Set<String> SELF_FIELDS = Set.of("phone", "mobile", "email", "residentialAddress", "permanentAddress", "civilStatus");
    private static final Set<String> SELF_HR_REQUEST_TYPES = Set.of("QUALIFICATION_UPDATE", "SERVICE_RECORD_CORRECTION");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final EmployeeRepository employees;
    private final EmployeeAssignmentRepository assignments;
    private final QualificationRepository qualifications;
    private final PreviousEmploymentRepository previousEmployment;
    private final EmergencyContactRepository emergencyContacts;
    private final EmployeeDocumentRepository documents;
    private final ServiceEventRepository events;
    private final ProfileChangeRequestRepository changeRequests;
    private final EmployeeLookup lookup;
    private final EstablishmentService establishment;
    private final DesignationRepository designations;
    private final PositionRepository positions;
    private final MasterDataService masterData;
    private final SettingsService settings;
    private final FileStorageService files;
    private final AppUserRepository users;
    private final WorkflowService workflow;
    private final AuditService audit;

    // ===================================================================== DTOs

    public record EmployeeRequest(String employeeNo, String title, String fullName, String nameWithInitials, String nameSi,
                                  String nameTa, String nic, LocalDate dateOfBirth, String gender, String civilStatus,
                                  String email, String phone, String mobile, String permanentAddress,
                                  String residentialAddress, String status, String employmentType,
                                  String employeeCategory, String serviceCategory, LocalDate appointmentDate,
                                  LocalDate confirmationDate, LocalDate retirementDate, Integer incrementMonth,
                                  String secondedFrom, LocalDate secondmentEndDate, String bankCode, String bankBranch,
                                  String bankAccountNo, Boolean restricted,
                                  // initial placement (create only)
                                  String orgUnitCode, Long designationId, Long positionId) {
    }

    public record EmployeeListItem(Long id, String employeeNo, String fullName, String nameWithInitials, String nic,
                                   String designation, String orgUnitCode, String orgUnitName, String status,
                                   String employeeCategory, String employmentType, LocalDate appointmentDate) {
    }

    public record AssignRequest(String orgUnitCode, Long designationId, Long positionId, LocalDate effectiveDate,
                                String reason, String reference, String remarks) {
    }

    public record StatusRequest(String status, LocalDate effectiveDate, String separationReason, String remarks) {
    }

    public record SaveResult(Employee employee, Long pendingChangeRequestId) {
    }

    // ===================================================================== search & read

    @Transactional(readOnly = true)
    public PageResponse<EmployeeListItem> search(String q, String orgUnitCode, Long designationId, String status,
                                                 String category, int page, int size) {
        UserPrincipal me = CurrentUser.get();
        Set<String> scope = lookup.scopeUnits();
        Specification<Employee> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("fullName")), like), cb.like(cb.lower(root.get("nameWithInitials")), like),
                        cb.like(cb.lower(root.get("employeeNo")), like), cb.like(cb.lower(root.get("nic")), like)));
            }
            if (orgUnitCode != null && !orgUnitCode.isBlank())
                ps.add(root.get("orgUnitCode").in(establishment.unitAndDescendants(orgUnitCode, LocalDate.now())));
            if (designationId != null) ps.add(cb.equal(root.get("designationId"), designationId));
            if (status != null && !status.isBlank()) ps.add(cb.equal(root.get("status"), status));
            if (category != null && !category.isBlank()) ps.add(cb.equal(root.get("employeeCategory"), category));
            if (scope != null) {
                Predicate inScope = scope.isEmpty() ? cb.disjunction() : root.get("orgUnitCode").in(scope);
                ps.add(me.getEmployeeId() == null ? inScope : cb.or(inScope, cb.equal(root.get("id"), me.getEmployeeId())));
            }
            if (!me.has(Permission.EMP_RESTRICTED)) ps.add(cb.isFalse(root.get("restricted")));
            return cb.and(ps.toArray(Predicate[]::new));
        };
        var result = employees.findAll(spec, PageRequest.of(page, Math.min(size, 200), Sort.by("employeeNo")));
        Map<Long, String> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, Designation::getName));
        Map<String, String> units = establishment.orgUnits(LocalDate.now()).stream()
                .collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getName, (a, b) -> a));
        audit.log(AuditAction.SEARCH, "Employee", null, "Employee search" + (q == null ? "" : ": " + q));
        return PageResponse.of(result, e -> new EmployeeListItem(e.getId(), e.getEmployeeNo(), e.getFullName(),
                e.getNameWithInitials(), e.getNic(), des.get(e.getDesignationId()), e.getOrgUnitCode(),
                units.get(e.getOrgUnitCode()), e.getStatus(), e.getEmployeeCategory(), e.getEmploymentType(),
                e.getAppointmentDate()));
    }

    public record EmployeeView(Employee employee, String designationName, String orgUnitName, String positionTitle,
                               String grade, String reportingOfficerName, boolean hasUserAccount) {
    }

    @Transactional(readOnly = true)
    public EmployeeView view(Long id) {
        Employee e = lookup.requireAccess(id);
        UserPrincipal me = CurrentUser.get();
        if (!e.getId().equals(me.getEmployeeId()) && !me.has(Permission.EMP_VIEW)) throw ApiException.notFound("Employee", id);
        audit.log(AuditAction.VIEW, "Employee", id, "Viewed employee " + e.getEmployeeNo());
        return toView(e);
    }

    private EmployeeView toView(Employee e) {
        String des = e.getDesignationId() == null ? null : designations.findById(e.getDesignationId()).map(Designation::getName).orElse(null);
        String unit = e.getOrgUnitCode() == null ? null : establishment.orgUnit(e.getOrgUnitCode(), LocalDate.now()).map(OrgUnit::getName).orElse(e.getOrgUnitCode());
        Designation designation = e.getDesignationId() == null ? null : designations.findById(e.getDesignationId()).orElse(null);
        Position position = e.getPositionId() == null ? null : positions.findById(e.getPositionId()).orElse(null);
        String reportingOfficer = null;
        if (position != null && position.getReportsToPositionCode() != null) {
            Position reportsTo = positions.findByCode(position.getReportsToPositionCode()).orElse(null);
            if (reportsTo != null) {
                reportingOfficer = assignments.employeesInPositionOn(reportsTo.getId(), LocalDate.now()).stream()
                        .findFirst().flatMap(a -> employees.findById(a.getEmployeeId()))
                        .filter(officer -> !officer.isRestricted() || CurrentUser.get().has(Permission.EMP_RESTRICTED))
                        .map(Employee::getNameWithInitials).orElse(null);
            }
        }
        return new EmployeeView(e, des, unit, position == null ? null : position.getTitle(),
                designation == null ? null : designation.getGrade(), reportingOfficer,
                users.findByEmployeeId(e.getId()).isPresent());
    }

    // ===================================================================== create / update

    @Transactional
    public Employee create(EmployeeRequest r) {
        if (r.employeeNo() == null || !EMP_NO.matcher(r.employeeNo()).matches()) throw ApiException.badRequest("A valid employee number is required");
        if (employees.existsByEmployeeNo(r.employeeNo().trim())) throw ApiException.conflict("Employee number " + r.employeeNo() + " already exists");
        Employee e = new Employee();
        e.setEmployeeNo(r.employeeNo().trim());
        applyAll(e, r);
        if (r.appointmentDate() == null) throw ApiException.badRequest("Appointment date is required");
        if (r.orgUnitCode() == null || r.designationId() == null) throw ApiException.badRequest("Initial org unit and designation are required");
        employees.save(e);
        placement(e, r.orgUnitCode(), r.designationId(), r.positionId(), r.appointmentDate(), "APPOINTMENT", null, null);
        event(e.getId(), "APPOINTMENT", r.appointmentDate(), "Appointed as " + designationName(r.designationId()) + " in " + r.orgUnitCode(), null);
        audit.log(AuditAction.CREATE, "Employee", e.getId(), "Employee " + e.getEmployeeNo() + " registered", null, snapshot(e));
        return e;
    }

    /**
     * Updates personal and employment details. When maker-checker is enabled, changes to sensitive fields are held in a
     * change request that a second authorized person must approve (FR-EMP-012).
     */
    @Transactional
    public SaveResult update(Long id, EmployeeRequest r) {
        Employee e = lookup.requireAccess(id);
        Map<String, Object> before = snapshot(e);
        Map<String, Object> requested = requestToMap(r);
        Map<String, Map<String, Object>> held = new LinkedHashMap<>();
        if (settings.getBool("employee.maker-checker.enabled", true)) {
            for (String f : SENSITIVE_FIELDS) {
                Object oldV = before.get(f);
                Object newV = requested.get(f);
                if (newV != null && !Objects.equals(String.valueOf(oldV), String.valueOf(newV))) {
                    held.put(f, mapOf("old", oldV, "new", newV));
                    requested.put(f, oldV); // keep old value until approved
                }
            }
        }
        EmployeeRequest effective = JSON.convertValue(requested, EmployeeRequest.class);
        applyAll(e, effective);
        audit.log(AuditAction.UPDATE, "Employee", id, "Employee " + e.getEmployeeNo() + " updated", before, snapshot(e));
        Long crId = null;
        if (!held.isEmpty()) crId = openChangeRequest(e, held, "Sensitive field change (maker-checker)", "HR").getId();
        return new SaveResult(e, crId);
    }

    private void applyAll(Employee e, EmployeeRequest r) {
        require(r.fullName(), "Full name");
        require(r.nameWithInitials(), "Name with initials");
        require(r.nic(), "NIC");
        if (r.dateOfBirth() == null) throw ApiException.badRequest("Date of birth is required");
        String nic = r.nic().trim().toUpperCase();
        if (!NIC.matcher(nic).matches()) throw ApiException.rule("INVALID_NIC", "NIC must be 9 digits followed by V/X, or 12 digits");
        employees.findByNic(nic).filter(o -> !o.getId().equals(e.getId()))
                .ifPresent(o -> { throw ApiException.conflict("Another employee already has NIC " + nic); });
        if (r.dateOfBirth().isAfter(LocalDate.now().minusYears(16))) throw ApiException.rule("INVALID_DOB", "Date of birth is not plausible");
        require(r.status(), "Status");
        require(r.employmentType(), "Employment type");
        require(r.employeeCategory(), "Employee category");
        masterData.requireActive(MasterDataCategory.EMPLOYEE_STATUS, r.status(), "Status");
        masterData.requireActive(MasterDataCategory.EMPLOYMENT_TYPE, r.employmentType(), "Employment type");
        masterData.requireActive(MasterDataCategory.EMPLOYEE_CATEGORY, r.employeeCategory(), "Employee category");
        masterData.requireActive(MasterDataCategory.SERVICE_CATEGORY, r.serviceCategory(), "Service category");
        masterData.requireActive(MasterDataCategory.TITLE, r.title(), "Title");
        masterData.requireActive(MasterDataCategory.GENDER, r.gender(), "Gender");
        masterData.requireActive(MasterDataCategory.CIVIL_STATUS, r.civilStatus(), "Civil status");
        masterData.requireActive(MasterDataCategory.BANK, r.bankCode(), "Bank");
        if (r.email() != null && !r.email().isBlank() && !r.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw ApiException.badRequest("E-mail address is not valid");
        if (r.incrementMonth() != null && (r.incrementMonth() < 1 || r.incrementMonth() > 12))
            throw ApiException.badRequest("Increment month must be between 1 and 12");
        if (r.confirmationDate() != null && r.appointmentDate() != null && r.confirmationDate().isBefore(r.appointmentDate()))
            throw ApiException.badRequest("Confirmation date cannot be before appointment date");
        e.setTitle(r.title());
        e.setFullName(r.fullName().trim());
        e.setNameWithInitials(r.nameWithInitials().trim());
        e.setNameSi(r.nameSi());
        e.setNameTa(r.nameTa());
        e.setNic(nic);
        e.setDateOfBirth(r.dateOfBirth());
        e.setGender(r.gender());
        e.setCivilStatus(r.civilStatus());
        e.setEmail(blankToNull(r.email()));
        e.setPhone(blankToNull(r.phone()));
        e.setMobile(blankToNull(r.mobile()));
        e.setPermanentAddress(r.permanentAddress());
        e.setResidentialAddress(r.residentialAddress());
        e.setStatus(r.status());
        e.setEmploymentType(r.employmentType());
        e.setEmployeeCategory(r.employeeCategory());
        e.setServiceCategory(blankToNull(r.serviceCategory()));
        if (r.appointmentDate() != null) e.setAppointmentDate(r.appointmentDate());
        e.setConfirmationDate(r.confirmationDate());
        int retirementAge = settings.getInt("employee.retirement-age", 60);
        e.setRetirementDate(r.retirementDate() != null ? r.retirementDate() : r.dateOfBirth().plusYears(retirementAge));
        e.setIncrementMonth(r.incrementMonth());
        e.setSecondedFrom(r.secondedFrom());
        e.setSecondmentEndDate(r.secondmentEndDate());
        e.setBankCode(blankToNull(r.bankCode()));
        e.setBankBranch(blankToNull(r.bankBranch()));
        e.setBankAccountNo(blankToNull(r.bankAccountNo()));
        if (r.restricted() != null) {
            if (r.restricted() != e.isRestricted() && !CurrentUser.get().has(Permission.EMP_RESTRICTED))
                throw ApiException.forbidden("Only authorized officers can change the restricted classification");
            e.setRestricted(r.restricted());
        }
    }

    // ===================================================================== placement, status, service history

    /** Moves an employee to a new unit/designation/position from a date, closing the previous placement (FR-EMP-005). */
    @Transactional
    public EmployeeAssignment assign(Long id, AssignRequest r) {
        Employee e = lookup.requireAccess(id);
        if (SEPARATION_STATUSES.contains(e.getStatus())) throw ApiException.rule("SEPARATED", "Employee is no longer in service");
        require(r.reason(), "Reason");
        LocalDate d = r.effectiveDate() == null ? LocalDate.now() : r.effectiveDate();
        EmployeeAssignment a = placement(e, r.orgUnitCode(), r.designationId(), r.positionId(), d, r.reason(), r.reference(), r.remarks());
        String type = switch (r.reason()) {
            case "PROMOTION", "TRANSFER", "SECONDMENT", "CONFIRMATION" -> r.reason();
            default -> "OTHER";
        };
        event(id, type, d, r.reason().charAt(0) + r.reason().substring(1).toLowerCase().replace('_', ' ') + " to "
                + designationName(r.designationId()) + " in " + r.orgUnitCode() + (r.remarks() == null ? "" : " — " + r.remarks()), r.reference());
        audit.log(AuditAction.UPDATE, "Employee", id, "Placement changed (" + r.reason() + ") effective " + d);
        return a;
    }

    EmployeeAssignment placement(Employee e, String unitCode, Long designationId, Long positionId, LocalDate d,
                                 String reason, String reference, String remarks) {
        establishment.orgUnit(unitCode, d).orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Org unit " + unitCode + " is not active on " + d));
        Designation des = designations.findById(designationId).filter(Designation::isActive)
                .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Designation is not active"));
        if (positionId != null) {
            Position p = positions.findById(positionId).filter(Position::isActive)
                    .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Position is not active"));
            if (!p.getOrgUnitCode().equals(unitCode) || !p.getDesignationId().equals(designationId))
                throw ApiException.rule("POSITION_MISMATCH", "Position " + p.getCode() + " does not belong to the selected unit and designation");
            assignments.findAll().stream()
                    .filter(a -> positionId.equals(a.getPositionId()) && !a.getEmployeeId().equals(e.getId()) && a.isEffectiveOn(d))
                    .findAny().ifPresent(a -> { throw ApiException.rule("POSITION_FILLED", "Position " + p.getCode() + " is already filled"); });
        }
        assignments.findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(e.getId()).ifPresent(cur -> {
            if (!d.isAfter(cur.getEffectiveFrom())) throw ApiException.rule("EFFECTIVE_DATE", "Effective date must be after the current placement start (" + cur.getEffectiveFrom() + ")");
            cur.setEffectiveTo(d);
        });
        EmployeeAssignment a = new EmployeeAssignment();
        a.setEmployeeId(e.getId());
        a.setOrgUnitCode(unitCode);
        a.setDesignationId(des.getId());
        a.setPositionId(positionId);
        a.setEffectiveFrom(d);
        a.setReason(reason);
        a.setReference(reference);
        a.setRemarks(remarks);
        assignments.save(a);
        if (!d.isAfter(LocalDate.now())) {
            e.setOrgUnitCode(unitCode);
            e.setDesignationId(des.getId());
            e.setPositionId(positionId);
        }
        return a;
    }

    /** Status change; separation statuses close the placement and deactivate the user account (FR-EMP-008, -009). */
    @Transactional
    public Employee changeStatus(Long id, StatusRequest r) {
        Employee e = lookup.requireAccess(id);
        require(r.status(), "Status");
        masterData.requireActive(MasterDataCategory.EMPLOYEE_STATUS, r.status(), "Status");
        LocalDate d = r.effectiveDate() == null ? LocalDate.now() : r.effectiveDate();
        String old = e.getStatus();
        e.setStatus(r.status());
        if (SEPARATION_STATUSES.contains(r.status())) {
            masterData.requireActive(MasterDataCategory.SEPARATION_REASON, r.separationReason(), "Separation reason");
            e.setSeparationDate(d);
            e.setSeparationReason(r.separationReason());
            e.setSeparationRemarks(r.remarks());
            assignments.findFirstByEmployeeIdAndEffectiveToIsNullOrderByEffectiveFromDesc(id).ifPresent(a -> {
                if (!d.isAfter(a.getEffectiveFrom())) throw ApiException.rule("EFFECTIVE_DATE", "Separation date must be after the current placement start");
                a.setEffectiveTo(d);
            });
            e.setPositionId(null);
            users.findByEmployeeId(id).ifPresent(u -> u.setActive(false)); // joiner-mover-leaver: remove access
            event(id, "SEPARATION", d, "Separated: " + r.status() + (r.remarks() == null ? "" : " — " + r.remarks()), null);
        } else {
            event(id, "STATUS_CHANGE", d, "Status changed from " + old + " to " + r.status() + (r.remarks() == null ? "" : " — " + r.remarks()), null);
        }
        audit.log(AuditAction.UPDATE, "Employee", id, "Status " + old + " → " + r.status(), old, r.status());
        return e;
    }

    public void event(Long employeeId, String type, LocalDate date, String description, String reference) {
        EmployeeDetails.ServiceEvent ev = new EmployeeDetails.ServiceEvent();
        ev.setEmployeeId(employeeId);
        ev.setEventType(type);
        ev.setEventDate(date);
        ev.setDescription(description.length() > 1000 ? description.substring(0, 1000) : description);
        ev.setReference(reference);
        events.save(ev);
    }

    public record AssignmentView(Long id, String orgUnitCode, String orgUnitName, String designation, String position,
                                 LocalDate effectiveFrom, LocalDate effectiveTo, String reason, String reference,
                                 String remarks) {
    }

    public record ServiceHistory(List<AssignmentView> assignments, List<EmployeeDetails.ServiceEvent> events) {
    }

    @Transactional(readOnly = true)
    public ServiceHistory serviceHistory(Long id) {
        selfOrView(id);
        Map<Long, String> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, Designation::getName));
        Map<Long, String> pos = positions.findAll().stream().collect(Collectors.toMap(Position::getId, Position::getTitle));
        List<AssignmentView> av = assignments.findByEmployeeIdOrderByEffectiveFromDesc(id).stream()
                .map(a -> new AssignmentView(a.getId(), a.getOrgUnitCode(),
                        establishment.orgUnit(a.getOrgUnitCode(), a.getEffectiveFrom()).map(OrgUnit::getName).orElse(a.getOrgUnitCode()),
                        des.get(a.getDesignationId()), a.getPositionId() == null ? null : pos.get(a.getPositionId()),
                        a.getEffectiveFrom(), a.getEffectiveTo(), a.getReason(), a.getReference(), a.getRemarks()))
                .toList();
        return new ServiceHistory(av, events.findByEmployeeIdOrderByEventDateDescIdDesc(id));
    }

    // ===================================================================== child records

    @Transactional(readOnly = true)
    public Map<String, Object> details(Long id) {
        selfOrView(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qualifications", qualifications.findByEmployeeIdOrderByYearObtainedDesc(id));
        m.put("previousEmployment", previousEmployment.findByEmployeeIdOrderByFromDateDesc(id));
        m.put("emergencyContacts", emergencyContacts.findByEmployeeIdOrderByIdAsc(id));
        return m;
    }

    @Transactional
    public EmployeeDetails.Qualification saveQualification(Long empId, Long id, EmployeeDetails.Qualification r) {
        lookup.requireAccess(empId);
        masterData.requireActive(MasterDataCategory.QUALIFICATION_TYPE, r.getQualificationType(), "Qualification type");
        require(r.getTitle(), "Title");
        EmployeeDetails.Qualification q = id == null ? new EmployeeDetails.Qualification()
                : qualifications.findById(id).filter(x -> x.getEmployeeId().equals(empId)).orElseThrow(() -> ApiException.notFound("Qualification", id));
        q.setEmployeeId(empId);
        q.setQualificationType(r.getQualificationType());
        q.setTitle(r.getTitle());
        q.setInstitution(r.getInstitution());
        q.setYearObtained(r.getYearObtained());
        q.setResult(r.getResult());
        q.setRemarks(r.getRemarks());
        qualifications.save(q);
        audit.log(id == null ? AuditAction.CREATE : AuditAction.UPDATE, "Qualification", q.getId(), "Qualification saved for employee " + empId);
        return q;
    }

    @Transactional
    public void deleteQualification(Long empId, Long id) {
        lookup.requireAccess(empId);
        EmployeeDetails.Qualification q = qualifications.findById(id).filter(x -> x.getEmployeeId().equals(empId))
                .orElseThrow(() -> ApiException.notFound("Qualification", id));
        qualifications.delete(q);
        audit.log(AuditAction.DELETE, "Qualification", id, "Qualification removed for employee " + empId, q.getTitle(), null);
    }

    @Transactional
    public EmployeeDetails.PreviousEmployment savePreviousEmployment(Long empId, Long id, EmployeeDetails.PreviousEmployment r) {
        lookup.requireAccess(empId);
        require(r.getOrganization(), "Organization");
        if (r.getFromDate() != null && r.getToDate() != null && r.getToDate().isBefore(r.getFromDate()))
            throw ApiException.badRequest("To-date cannot be before from-date");
        EmployeeDetails.PreviousEmployment p = id == null ? new EmployeeDetails.PreviousEmployment()
                : previousEmployment.findById(id).filter(x -> x.getEmployeeId().equals(empId)).orElseThrow(() -> ApiException.notFound("Previous employment", id));
        p.setEmployeeId(empId);
        p.setOrganization(r.getOrganization());
        p.setDesignation(r.getDesignation());
        p.setFromDate(r.getFromDate());
        p.setToDate(r.getToDate());
        p.setRemarks(r.getRemarks());
        previousEmployment.save(p);
        audit.log(id == null ? AuditAction.CREATE : AuditAction.UPDATE, "PreviousEmployment", p.getId(), "Previous employment saved for employee " + empId);
        return p;
    }

    @Transactional
    public void deletePreviousEmployment(Long empId, Long id) {
        lookup.requireAccess(empId);
        EmployeeDetails.PreviousEmployment p = previousEmployment.findById(id).filter(x -> x.getEmployeeId().equals(empId))
                .orElseThrow(() -> ApiException.notFound("Previous employment", id));
        previousEmployment.delete(p);
        audit.log(AuditAction.DELETE, "PreviousEmployment", id, "Previous employment removed for employee " + empId);
    }

    @Transactional
    public EmployeeDetails.EmergencyContact saveEmergencyContact(Long empId, Long id, EmployeeDetails.EmergencyContact r) {
        lookup.requireAccess(empId);
        require(r.getName(), "Name");
        require(r.getPhone(), "Phone");
        masterData.requireActive(MasterDataCategory.RELATIONSHIP, r.getRelationship(), "Relationship");
        EmployeeDetails.EmergencyContact c = id == null ? new EmployeeDetails.EmergencyContact()
                : emergencyContacts.findById(id).filter(x -> x.getEmployeeId().equals(empId)).orElseThrow(() -> ApiException.notFound("Emergency contact", id));
        c.setEmployeeId(empId);
        c.setName(r.getName());
        c.setRelationship(r.getRelationship());
        c.setPhone(r.getPhone());
        c.setAddress(r.getAddress());
        emergencyContacts.save(c);
        audit.log(id == null ? AuditAction.CREATE : AuditAction.UPDATE, "EmergencyContact", c.getId(), "Emergency contact saved for employee " + empId);
        return c;
    }

    @Transactional
    public void deleteEmergencyContact(Long empId, Long id) {
        lookup.requireAccess(empId);
        EmployeeDetails.EmergencyContact c = emergencyContacts.findById(id).filter(x -> x.getEmployeeId().equals(empId))
                .orElseThrow(() -> ApiException.notFound("Emergency contact", id));
        emergencyContacts.delete(c);
        audit.log(AuditAction.DELETE, "EmergencyContact", id, "Emergency contact removed for employee " + empId);
    }

    // ===================================================================== digital personal file

    @Transactional(readOnly = true)
    public List<EmployeeDetails.Document> documents(Long empId) {
        selfOrView(empId);
        boolean restrictedOk = CurrentUser.get().has(Permission.EMP_RESTRICTED);
        boolean self = empId.equals(CurrentUser.get().getEmployeeId());
        return documents.findByEmployeeIdOrderByIdDesc(empId).stream()
                .filter(d -> restrictedOk || d.getClassification() != EmployeeDetails.Document.Classification.HIGHLY_CONFIDENTIAL)
                .filter(d -> !self || CurrentUser.get().has(Permission.EMP_VIEW) || d.getClassification() != EmployeeDetails.Document.Classification.HIGHLY_CONFIDENTIAL)
                .toList();
    }

    @Transactional
    public EmployeeDetails.Document upload(Long empId, String documentType, String title, LocalDate documentDate,
                                           EmployeeDetails.Document.Classification classification, String remarks,
                                           MultipartFile file) {
        lookup.requireAccess(empId);
        masterData.requireActive(MasterDataCategory.DOCUMENT_TYPE, documentType, "Document type");
        require(documentType, "Document type");
        require(title, "Title");
        EmployeeDetails.Document.Classification cls = classification == null ? EmployeeDetails.Document.Classification.CONFIDENTIAL : classification;
        if (cls == EmployeeDetails.Document.Classification.HIGHLY_CONFIDENTIAL && !CurrentUser.get().has(Permission.EMP_RESTRICTED))
            throw ApiException.forbidden("Only authorized officers can file highly confidential documents");
        StoredFile f = files.store(file, "EMPLOYEE");
        EmployeeDetails.Document d = new EmployeeDetails.Document();
        d.setEmployeeId(empId);
        d.setDocumentType(documentType);
        d.setTitle(title);
        d.setDocumentDate(documentDate);
        d.setClassification(cls);
        d.setFileId(f.getId());
        d.setFileName(f.getOriginalName());
        d.setRemarks(remarks);
        documents.save(d);
        audit.log(AuditAction.UPLOAD, "EmployeeDocument", d.getId(), "Document '" + title + "' filed for employee " + empId);
        return d;
    }

    @Transactional(readOnly = true)
    public EmployeeDetails.Document documentForDownload(Long empId, Long docId) {
        EmployeeDetails.Document d = documents(empId).stream().filter(x -> x.getId().equals(docId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Document", docId));
        audit.log(AuditAction.DOWNLOAD, "EmployeeDocument", docId, "Downloaded '" + d.getTitle() + "' of employee " + empId);
        return d;
    }

    // ===================================================================== self-service & maker-checker

    @Transactional
    public EmployeeDetails.ProfileChangeRequest requestSelfChange(Map<String, String> changes, String reason) {
        Long empId = CurrentUser.employeeId();
        Employee e = lookup.require(empId);
        if (changes == null || changes.isEmpty()) throw ApiException.badRequest("No changes were requested");
        Map<String, Object> current = snapshot(e);
        Map<String, Map<String, Object>> diff = new LinkedHashMap<>();
        for (var en : changes.entrySet()) {
            if (!SELF_FIELDS.contains(en.getKey())) throw ApiException.badRequest("Field " + en.getKey() + " cannot be changed through self-service");
            if (!Objects.equals(String.valueOf(current.get(en.getKey())), String.valueOf(en.getValue())))
                diff.put(en.getKey(), mapOf("old", current.get(en.getKey()), "new", en.getValue()));
        }
        if (diff.isEmpty()) throw ApiException.badRequest("The requested values are the same as the current ones");
        if (changeRequests.existsByEmployeeIdAndStatusIn(empId, List.of("PENDING", "RETURNED")))
            throw ApiException.rule("OPEN_REQUEST", "You already have a profile change request in progress");
        return openChangeRequest(e, diff, reason, "SELF");
    }

    @Transactional
    public EmployeeDetails.ProfileChangeRequest requestSelfHrAction(String requestType, String details, String reason) {
        Long empId = CurrentUser.employeeId();
        Employee e = lookup.require(empId);
        if (!SELF_HR_REQUEST_TYPES.contains(requestType))
            throw ApiException.badRequest("Unsupported HR request type");
        require(details, "Request details");
        if (details.length() > 4000) throw ApiException.badRequest("Request details must be 4000 characters or fewer");
        if (reason != null && reason.length() > 1000) throw ApiException.badRequest("Reason must be 1000 characters or fewer");
        if (changeRequests.existsByEmployeeIdAndRequestTypeAndStatusIn(empId, requestType, List.of("PENDING", "RETURNED")))
            throw ApiException.rule("OPEN_REQUEST", "You already have an open request of this type");

        EmployeeDetails.ProfileChangeRequest cr = new EmployeeDetails.ProfileChangeRequest();
        cr.setEmployeeId(empId);
        cr.setChanges("{}");
        cr.setRequestType(requestType);
        cr.setRequestDetails(details.trim());
        cr.setReason(reason == null || reason.isBlank() ? null : reason.trim());
        cr.setSource("SELF");
        cr.setStatus("PENDING");
        changeRequests.save(cr);
        WorkflowInstance wi = workflow.start(PROFILE_PROCESS, "ProfileChangeRequest", cr.getId(),
                requestType.replace('_', ' ') + " for " + e.getEmployeeNo(), "/me", empId);
        cr.setWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.SUBMIT, "ProfileChangeRequest", cr.getId(),
                "Employee submitted " + requestType + " request", null, Map.of("details", cr.getRequestDetails()));
        return cr;
    }

    private EmployeeDetails.ProfileChangeRequest openChangeRequest(Employee e, Map<String, Map<String, Object>> diff,
                                                                    String reason, String source) {
        EmployeeDetails.ProfileChangeRequest cr = new EmployeeDetails.ProfileChangeRequest();
        cr.setEmployeeId(e.getId());
        try {
            cr.setChanges(JSON.writeValueAsString(diff));
        } catch (Exception ex) {
            throw ApiException.badRequest("Invalid change");
        }
        cr.setReason(reason);
        cr.setSource(source);
        cr.setStatus("PENDING");
        cr.setRequestType("PROFILE_CHANGE");
        changeRequests.save(cr);
        WorkflowInstance wi = workflow.start(PROFILE_PROCESS, "ProfileChangeRequest", cr.getId(),
                "Profile change for " + e.getEmployeeNo() + " (" + String.join(", ", diff.keySet()) + ")",
                "/employees/" + e.getId(), e.getId());
        cr.setWorkflowInstanceId(wi.getId());
        audit.log(AuditAction.SUBMIT, "ProfileChangeRequest", cr.getId(), "Profile change request submitted",
                null, Map.of("source", source, "changes", diff, "reason", reason == null ? "" : reason));
        return cr;
    }

    @Transactional(readOnly = true)
    public List<EmployeeDetails.ProfileChangeRequest> changeRequests(Long empId) {
        selfOrView(empId);
        return changeRequests.findByEmployeeIdOrderByIdDesc(empId);
    }

    @Override
    public String processCode() {
        return PROFILE_PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        EmployeeDetails.ProfileChangeRequest cr = cr(wi);
        if ("PROFILE_CHANGE".equals(cr.getRequestType())) {
            Employee e = lookup.require(cr.getEmployeeId());
            Map<String, Object> before = snapshot(e);
            Map<String, Object> merged = new LinkedHashMap<>(before);
            readChanges(cr).forEach((f, v) -> merged.put(f, v.get("new")));
            applyAll(e, JSON.convertValue(merged, EmployeeRequest.class));
            if (readChanges(cr).keySet().stream().anyMatch(Set.of("email", "mobile")::contains)) {
                users.findByEmployeeId(e.getId()).ifPresent(account -> {
                    Map<String, Object> oldContact = Map.of(
                            "email", account.getEmail() == null ? "" : account.getEmail(),
                            "mobile", account.getMobile() == null ? "" : account.getMobile());
                    account.setEmail(e.getEmail());
                    account.setMobile(e.getMobile());
                    audit.log(AuditAction.UPDATE, "AppUser", account.getId(), "Approved contact details synchronized",
                            oldContact, Map.of("email", e.getEmail() == null ? "" : e.getEmail(),
                                    "mobile", e.getMobile() == null ? "" : e.getMobile()));
                });
            }
            audit.log(AuditAction.UPDATE, "Employee", e.getId(), "Approved profile change applied", before, snapshot(e));
        }
        cr.setStatus("APPROVED");
        audit.log(AuditAction.APPROVE, "ProfileChangeRequest", cr.getId(), "Self-service request approved");
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        cr(wi).setStatus("REJECTED");
    }

    @Override
    public void onReturned(WorkflowInstance wi) {
        cr(wi).setStatus("RETURNED");
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        cr(wi).setStatus("PENDING");
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        cr(wi).setStatus("CANCELLED");
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        EmployeeDetails.ProfileChangeRequest cr = cr(wi);
        Employee e = lookup.require(cr.getEmployeeId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Employee", e.getEmployeeNo() + " — " + e.getNameWithInitials());
        m.put("Source", "SELF".equals(cr.getSource()) ? "Employee self-service" : "HR (maker-checker)");
        m.put("Request type", cr.getRequestType());
        m.put("Reason", cr.getReason());
        if (cr.getRequestDetails() != null) {
            m.put("Request details", cr.getRequestDetails());
        } else {
            readChanges(cr).forEach((f, v) -> m.put(f, v.get("old") + " → " + v.get("new")));
        }
        return m;
    }

    private EmployeeDetails.ProfileChangeRequest cr(WorkflowInstance wi) {
        return changeRequests.findById(wi.getEntityId()).orElseThrow(() -> ApiException.notFound("Change request", wi.getEntityId()));
    }

    private static Map<String, Map<String, Object>> readChanges(EmployeeDetails.ProfileChangeRequest cr) {
        try {
            return JSON.readValue(cr.getChanges(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw ApiException.badRequest("Corrupt change request");
        }
    }

    // ===================================================================== bulk import (FR-EMP-014)

    public record ImportRowError(int row, String employeeNo, String message) {
    }

    public record ImportResult(boolean committed, int totalRows, int validRows, List<ImportRowError> errors,
                               Map<String, Long> byOrgUnit, Map<String, Long> byStatus) {
    }

    static final List<String> IMPORT_COLUMNS = List.of("employeeNo", "title", "fullName", "nameWithInitials", "nic",
            "dateOfBirth", "gender", "email", "mobile", "status", "employmentType", "employeeCategory", "serviceCategory",
            "appointmentDate", "orgUnitCode", "designationCode");

    /**
     * Validates every row; commits only when {@code commit} is true and no row has errors (all-or-nothing), and returns
     * a reconciliation summary for the data owner to sign off.
     */
    @Transactional
    public ImportResult importCsv(MultipartFile file, boolean commit) {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) throw ApiException.badRequest("The file is empty");
            if (header.startsWith("﻿")) header = header.substring(1);
            List<String> cols = Arrays.stream(header.split(",", -1)).map(String::trim).toList();
            if (!cols.containsAll(List.of("employeeNo", "fullName", "nameWithInitials", "nic", "dateOfBirth", "status",
                    "employmentType", "employeeCategory", "appointmentDate", "orgUnitCode", "designationCode")))
                throw ApiException.badRequest("Missing required columns. Expected: " + String.join(",", IMPORT_COLUMNS));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> vals = parseCsvLine(line);
                Map<String, String> m = new HashMap<>();
                for (int i = 0; i < cols.size(); i++) m.put(cols.get(i), i < vals.size() ? vals.get(i).trim() : "");
                rows.add(m);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Could not read the CSV file: " + e.getMessage());
        }
        List<ImportRowError> errors = new ArrayList<>();
        Set<String> seenNo = new HashSet<>();
        Set<String> seenNic = new HashSet<>();
        List<EmployeeRequest> valid = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> m = rows.get(i);
            String no = m.get("employeeNo");
            try {
                Designation des = designations.findByCode(m.get("designationCode"))
                        .orElseThrow(() -> ApiException.badRequest("Unknown designation code " + m.get("designationCode")));
                EmployeeRequest r = new EmployeeRequest(no, emptyNull(m.get("title")), m.get("fullName"), m.get("nameWithInitials"),
                        null, null, m.get("nic"), date(m.get("dateOfBirth")), emptyNull(m.get("gender")), null,
                        emptyNull(m.get("email")), null, emptyNull(m.get("mobile")), null, null, m.get("status"),
                        m.get("employmentType"), m.get("employeeCategory"), emptyNull(m.get("serviceCategory")),
                        date(m.get("appointmentDate")), null, null, null, null, null, null, null, null, false,
                        m.get("orgUnitCode"), des.getId(), null);
                if (!seenNo.add(no)) throw ApiException.badRequest("Duplicate employee number in file");
                if (r.nic() != null && !seenNic.add(r.nic().toUpperCase())) throw ApiException.badRequest("Duplicate NIC in file");
                if (no == null || !EMP_NO.matcher(no).matches()) throw ApiException.badRequest("Invalid employee number");
                if (employees.existsByEmployeeNo(no)) throw ApiException.badRequest("Employee number already exists");
                if (r.appointmentDate() == null) throw ApiException.badRequest("Appointment date is required");
                if (establishment.orgUnit(r.orgUnitCode(), r.appointmentDate()).isEmpty())
                    throw ApiException.badRequest("Org unit " + r.orgUnitCode() + " not active on appointment date");
                // Run full field validation against a throwaway entity.
                applyAll(new Employee(), r);
                valid.add(r);
            } catch (ApiException ex) {
                errors.add(new ImportRowError(i + 2, no, ex.getMessage()));
            }
        }
        Map<String, Long> byUnit = valid.stream().collect(Collectors.groupingBy(EmployeeRequest::orgUnitCode, TreeMap::new, Collectors.counting()));
        Map<String, Long> byStatus = valid.stream().collect(Collectors.groupingBy(EmployeeRequest::status, TreeMap::new, Collectors.counting()));
        boolean doCommit = commit && errors.isEmpty() && !valid.isEmpty();
        if (doCommit) {
            for (EmployeeRequest r : valid) create(r);
            audit.log(AuditAction.IMPORT, "Employee", null, "Bulk import of " + valid.size() + " employees from " + file.getOriginalFilename());
        }
        return new ImportResult(doCommit, rows.size(), valid.size(), errors, byUnit, byStatus);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else if (c == '"') q = false;
                else sb.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid date '" + s + "' (use YYYY-MM-DD)");
        }
    }

    // ===================================================================== helpers

    private void selfOrView(Long empId) {
        UserPrincipal me = CurrentUser.get();
        if (empId.equals(me.getEmployeeId())) return;
        if (!me.has(Permission.EMP_VIEW)) throw ApiException.notFound("Employee", empId);
        lookup.requireAccess(empId);
    }

    private String designationName(Long id) {
        return id == null ? "" : designations.findById(id).map(Designation::getName).orElse(String.valueOf(id));
    }

    static Map<String, Object> snapshot(Employee e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employeeNo", e.getEmployeeNo());
        m.put("title", e.getTitle());
        m.put("fullName", e.getFullName());
        m.put("nameWithInitials", e.getNameWithInitials());
        m.put("nameSi", e.getNameSi());
        m.put("nameTa", e.getNameTa());
        m.put("nic", e.getNic());
        m.put("dateOfBirth", e.getDateOfBirth() == null ? null : e.getDateOfBirth().toString());
        m.put("gender", e.getGender());
        m.put("civilStatus", e.getCivilStatus());
        m.put("email", e.getEmail());
        m.put("phone", e.getPhone());
        m.put("mobile", e.getMobile());
        m.put("permanentAddress", e.getPermanentAddress());
        m.put("residentialAddress", e.getResidentialAddress());
        m.put("status", e.getStatus());
        m.put("employmentType", e.getEmploymentType());
        m.put("employeeCategory", e.getEmployeeCategory());
        m.put("serviceCategory", e.getServiceCategory());
        m.put("appointmentDate", e.getAppointmentDate() == null ? null : e.getAppointmentDate().toString());
        m.put("confirmationDate", e.getConfirmationDate() == null ? null : e.getConfirmationDate().toString());
        m.put("retirementDate", e.getRetirementDate() == null ? null : e.getRetirementDate().toString());
        m.put("incrementMonth", e.getIncrementMonth());
        m.put("secondedFrom", e.getSecondedFrom());
        m.put("secondmentEndDate", e.getSecondmentEndDate() == null ? null : e.getSecondmentEndDate().toString());
        m.put("bankCode", e.getBankCode());
        m.put("bankBranch", e.getBankBranch());
        m.put("bankAccountNo", e.getBankAccountNo());
        m.put("restricted", e.isRestricted());
        return m;
    }

    private static Map<String, Object> requestToMap(EmployeeRequest r) {
        Map<String, Object> m = JSON.convertValue(r, new TypeReference<LinkedHashMap<String, Object>>() {
        });
        return m;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static void require(String v, String label) {
        if (v == null || v.isBlank()) throw ApiException.badRequest(label + " is required");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String emptyNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
