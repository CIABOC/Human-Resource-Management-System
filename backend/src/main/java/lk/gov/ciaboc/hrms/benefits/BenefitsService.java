package lk.gov.ciaboc.hrms.benefits;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.benefits.BenefitsEntities.InsuranceClaim;
import lk.gov.ciaboc.hrms.benefits.BenefitsEntities.InsuranceCoverage;
import lk.gov.ciaboc.hrms.benefits.BenefitsEntities.InsuranceScheme;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup.EmployeeBrief;
import lk.gov.ciaboc.hrms.integration.IntegrationService;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.notification.NotificationService;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Permission;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Employee benefits and insurance (FR-BEN-001..006): schemes, coverage/policies, eligibility, claims, expiry
 * notifications and the premiums that payroll recovers as approved benefit deductions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BenefitsService {
    private static final String EXPIRY_TEMPLATE = "BEN_POLICY_EXPIRY";
    private static final String EXPIRY_TEMPLATE_HR = "BEN_POLICY_EXPIRY_HR";
    private static final String HR_ROLE = "HR_ADMIN";

    private final InsuranceSchemeRepository schemes;
    private final InsuranceCoverageRepository coverages;
    private final InsuranceClaimRepository claims;
    private final EmployeeLookup lookup;
    private final MasterDataService masterData;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final IntegrationService integrations;
    private final ObjectProvider<InsuranceProviderClient> providerClients;
    private final AuditService audit;

    // ===================================================================== DTOs

    public record SchemeRequest(String code, String name, String nameSi, String nameTa, String schemeType, String category,
                                String provider, BigDecimal employeePremium, BigDecimal employerPremium,
                                BigDecimal coverageAmount, Boolean payrollDeduction, Boolean claimsEnabled,
                                String eligibleEmploymentTypes, Integer minServiceMonths, String eligibilityNotes,
                                Boolean active) {
    }

    public record CoverageRequest(Long employeeId, Long schemeId, String policyNo, LocalDate startDate, LocalDate expiryDate,
                                  BigDecimal employeePremium, BigDecimal employerPremium, BigDecimal coverageAmount,
                                  String status, String remarks) {
    }

    public record CoverageView(Long id, Long employeeId, String employeeNo, String employeeName, Long schemeId,
                               String schemeCode, String schemeName, String schemeType, String policyNo,
                               LocalDate startDate, LocalDate expiryDate, BigDecimal employeePremium,
                               BigDecimal employerPremium, BigDecimal coverageAmount, String status,
                               LocalDate expiryNotifiedOn, String remarks, Long daysToExpiry) {
    }

    public record ClaimRequest(Long coverageId, LocalDate claimDate, LocalDate incidentDate, String claimType,
                               BigDecimal amountClaimed, String description) {
    }

    public record ClaimStatusRequest(String status, BigDecimal amountApproved, String providerRef, String remarks) {
    }

    public record ClaimView(Long id, Long coverageId, Long employeeId, String employeeNo, String employeeName,
                            String schemeCode, String schemeName, String policyNo, LocalDate claimDate,
                            LocalDate incidentDate, String claimType, BigDecimal amountClaimed,
                            BigDecimal amountApproved, String status, String providerRef, String description,
                            String remarks) {
    }

    public record Eligibility(Long schemeId, String schemeCode, String schemeName, String schemeType, boolean eligible,
                              boolean covered, List<String> reasons) {
    }

    public record MyBenefits(List<CoverageView> coverages, List<ClaimView> claims, List<Eligibility> eligibility) {
    }

    /** Employee premium recovered through payroll for one coverage. */
    public record PremiumDeduction(Long employeeId, Long coverageId, String schemeCode, String schemeName, BigDecimal amount) {
    }

    public record ExpiryCheckResult(int expiredMarked, int notified) {
    }

    public record ProviderCheck(boolean enabled, String provider, boolean found, String message,
                                InsuranceProviderClient.PolicyStatus policy) {
    }

    // ===================================================================== schemes

    @Transactional(readOnly = true)
    public List<InsuranceScheme> schemes(boolean activeOnly) {
        return activeOnly ? schemes.findByActiveTrueOrderByCodeAsc() : schemes.findAllByOrderByCodeAsc();
    }

    @Transactional
    public InsuranceScheme createScheme(SchemeRequest r) {
        String code = requireCode(r.code());
        if (schemes.existsByCode(code)) throw ApiException.conflict("Scheme code " + code + " already exists");
        InsuranceScheme s = new InsuranceScheme();
        s.setCode(code);
        applyScheme(s, r);
        schemes.save(s);
        audit.log(AuditAction.CREATE, "InsuranceScheme", s.getId(), "Insurance scheme " + code + " created", null, snapshot(s));
        return s;
    }

    @Transactional
    public InsuranceScheme updateScheme(Long id, SchemeRequest r) {
        InsuranceScheme s = scheme(id);
        Map<String, Object> before = snapshot(s);
        applyScheme(s, r);
        audit.log(AuditAction.UPDATE, "InsuranceScheme", id, "Insurance scheme " + s.getCode() + " updated", before, snapshot(s));
        return s;
    }

    private void applyScheme(InsuranceScheme s, SchemeRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Scheme name is required");
        InsuranceScheme.Type type;
        try {
            type = InsuranceScheme.Type.valueOf(r.schemeType() == null ? "" : r.schemeType());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Scheme type must be AGRAHARA or OTHER");
        }
        masterData.requireActive(MasterDataCategory.INSURANCE_CATEGORY, blank(r.category()), "Insurance category");
        String types = normalizeList(r.eligibleEmploymentTypes());
        if (types != null) for (String t : types.split(","))
            masterData.requireActive(MasterDataCategory.EMPLOYMENT_TYPE, t, "Eligible employment type");
        s.setName(r.name().trim());
        s.setNameSi(blank(r.nameSi()));
        s.setNameTa(blank(r.nameTa()));
        s.setSchemeType(type);
        s.setCategory(blank(r.category()));
        s.setProvider(blank(r.provider()));
        s.setEmployeePremium(money(r.employeePremium(), "Employee premium", false));
        s.setEmployerPremium(money(r.employerPremium(), "Employer premium", false));
        s.setCoverageAmount(r.coverageAmount() == null ? null : money(r.coverageAmount(), "Coverage amount", false));
        s.setPayrollDeduction(r.payrollDeduction() == null || r.payrollDeduction());
        s.setClaimsEnabled(r.claimsEnabled() == null || r.claimsEnabled());
        s.setEligibleEmploymentTypes(types);
        int months = r.minServiceMonths() == null ? 0 : r.minServiceMonths();
        if (months < 0 || months > 600) throw ApiException.badRequest("Minimum service months must be between 0 and 600");
        s.setMinServiceMonths(months);
        s.setEligibilityNotes(blank(r.eligibilityNotes()));
        s.setActive(r.active() == null || r.active());
    }

    // ===================================================================== eligibility (FR-BEN-003)

    @Transactional(readOnly = true)
    public List<Eligibility> eligibility(Long employeeId) {
        Employee e = lookup.requireAccess(employeeId);
        return eligibilityFor(e);
    }

    private List<Eligibility> eligibilityFor(Employee e) {
        List<InsuranceCoverage> mine = coverages.findByEmployeeIdOrderByStartDateDesc(e.getId());
        List<Eligibility> out = new ArrayList<>();
        for (InsuranceScheme s : schemes.findByActiveTrueOrderByCodeAsc()) {
            List<String> reasons = check(s, e);
            boolean covered = mine.stream().anyMatch(c -> c.getSchemeId().equals(s.getId()) && c.getStatus() == InsuranceCoverage.Status.ACTIVE);
            out.add(new Eligibility(s.getId(), s.getCode(), s.getName(), s.getSchemeType().name(), reasons.isEmpty(), covered, reasons));
        }
        return out;
    }

    static List<String> check(InsuranceScheme s, Employee e) {
        List<String> reasons = new ArrayList<>();
        if (!EmployeeLookup.IN_SERVICE.contains(e.getStatus())) reasons.add("Employee is not in service");
        String types = s.getEligibleEmploymentTypes();
        if (types != null && !types.isBlank()
                && Arrays.stream(types.split(",")).map(String::trim).noneMatch(t -> t.equals(e.getEmploymentType())))
            reasons.add("Employment type " + e.getEmploymentType() + " is not eligible");
        if (s.getMinServiceMonths() > 0 && e.getAppointmentDate() != null
                && ChronoUnit.MONTHS.between(e.getAppointmentDate(), LocalDate.now()) < s.getMinServiceMonths())
            reasons.add("Requires at least " + s.getMinServiceMonths() + " months of service");
        return reasons;
    }

    // ===================================================================== coverage / policies (FR-BEN-002)

    @Transactional(readOnly = true)
    public List<CoverageView> coverages(Long schemeId, String status, Long employeeId) {
        Set<Long> visible = lookup.visibleEmployeeIds();
        if (employeeId != null) lookup.requireAccess(employeeId);
        List<InsuranceCoverage> rows = coverages.findAllByOrderByIdDesc().stream()
                .filter(c -> visible == null || visible.contains(c.getEmployeeId()))
                .filter(c -> schemeId == null || schemeId.equals(c.getSchemeId()))
                .filter(c -> status == null || status.isBlank() || c.getStatus().name().equals(status))
                .filter(c -> employeeId == null || employeeId.equals(c.getEmployeeId()))
                .toList();
        return toViews(rows);
    }

    @Transactional(readOnly = true)
    public List<CoverageView> expiring(int days) {
        if (days < 0 || days > 3650) throw ApiException.badRequest("Days must be between 0 and 3650");
        Set<Long> visible = lookup.visibleEmployeeIds();
        LocalDate today = LocalDate.now();
        return toViews(coverages.expiringBetween(today, today.plusDays(days)).stream()
                .filter(c -> visible == null || visible.contains(c.getEmployeeId())).toList());
    }

    @Transactional
    public CoverageView createCoverage(CoverageRequest r) {
        if (r.employeeId() == null || r.schemeId() == null) throw ApiException.badRequest("Employee and scheme are required");
        Employee e = lookup.requireAccess(r.employeeId());
        InsuranceScheme s = scheme(r.schemeId());
        if (!s.isActive()) throw ApiException.rule("SCHEME_INACTIVE", "Scheme " + s.getCode() + " is not active");
        List<String> reasons = check(s, e);
        if (!reasons.isEmpty()) throw ApiException.rule("NOT_ELIGIBLE", "Employee is not eligible: " + String.join("; ", reasons));
        InsuranceCoverage c = new InsuranceCoverage();
        c.setEmployeeId(e.getId());
        c.setSchemeId(s.getId());
        c.setStatus(InsuranceCoverage.Status.ACTIVE);
        applyCoverage(c, r, s);
        requireNoOverlap(c);
        coverages.save(c);
        audit.log(AuditAction.CREATE, "InsuranceCoverage", c.getId(), "Coverage " + s.getCode() + " for " + e.getEmployeeNo(), null, snapshot(c));
        return toViews(List.of(c)).get(0);
    }

    @Transactional
    public CoverageView updateCoverage(Long id, CoverageRequest r) {
        InsuranceCoverage c = coverage(id);
        lookup.requireAccess(c.getEmployeeId());
        InsuranceScheme s = scheme(c.getSchemeId());
        Map<String, Object> before = snapshot(c);
        applyCoverage(c, r, s);
        if (r.status() != null && !r.status().isBlank()) {
            try {
                InsuranceCoverage.Status st = InsuranceCoverage.Status.valueOf(r.status());
                if (st != c.getStatus() && st == InsuranceCoverage.Status.ACTIVE) c.setExpiryNotifiedOn(null);
                c.setStatus(st);
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("Invalid coverage status");
            }
        }
        if (c.getStatus() == InsuranceCoverage.Status.ACTIVE) requireNoOverlap(c);
        // A renewed (extended) policy must be notified again before its new expiry date.
        if (!Objects.equals(before.get("expiryDate"), c.getExpiryDate())) c.setExpiryNotifiedOn(null);
        audit.log(AuditAction.UPDATE, "InsuranceCoverage", id, "Coverage updated", before, snapshot(c));
        return toViews(List.of(c)).get(0);
    }

    private void applyCoverage(InsuranceCoverage c, CoverageRequest r, InsuranceScheme s) {
        if (r.startDate() == null) throw ApiException.badRequest("Start date is required");
        if (r.expiryDate() != null && r.expiryDate().isBefore(r.startDate()))
            throw ApiException.badRequest("Expiry date cannot be before the start date");
        c.setPolicyNo(blank(r.policyNo()));
        c.setStartDate(r.startDate());
        c.setExpiryDate(r.expiryDate());
        c.setEmployeePremium(r.employeePremium() == null ? s.getEmployeePremium() : money(r.employeePremium(), "Employee premium", false));
        c.setEmployerPremium(r.employerPremium() == null ? s.getEmployerPremium() : money(r.employerPremium(), "Employer premium", false));
        c.setCoverageAmount(r.coverageAmount() == null ? s.getCoverageAmount() : money(r.coverageAmount(), "Coverage amount", false));
        c.setRemarks(blank(r.remarks()));
    }

    private void requireNoOverlap(InsuranceCoverage c) {
        for (InsuranceCoverage o : coverages.findByEmployeeIdAndSchemeIdAndStatus(c.getEmployeeId(), c.getSchemeId(), InsuranceCoverage.Status.ACTIVE)) {
            if (o.getId() != null && o.getId().equals(c.getId())) continue;
            boolean overlap = !o.getStartDate().isAfter(c.getExpiryDate() == null ? LocalDate.MAX : c.getExpiryDate())
                    && !c.getStartDate().isAfter(o.getExpiryDate() == null ? LocalDate.MAX : o.getExpiryDate());
            if (overlap) throw ApiException.rule("COVERAGE_OVERLAP", "The employee already has an active policy under this scheme for the period");
        }
    }

    /** Optional verification with the provider (FR-BEN-006); a no-op until the integration is enabled. */
    @Transactional(readOnly = true)
    public ProviderCheck verifyWithProvider(Long coverageId) {
        InsuranceCoverage c = coverage(coverageId);
        lookup.requireAccess(c.getEmployeeId());
        InsuranceScheme s = scheme(c.getSchemeId());
        if (!integrations.isEnabled(IntegrationService.INSURANCE_PROVIDER))
            return new ProviderCheck(false, null, false, "The insurance provider integration (INT-06) is not enabled.", null);
        InsuranceProviderClient client = providerClients.orderedStream().findFirst().orElse(new NoOpInsuranceProviderClient());
        if (c.getPolicyNo() == null) return new ProviderCheck(true, client.name(), false, "The coverage has no policy number.", null);
        var result = client.lookupPolicy(s.getCode(), c.getPolicyNo());
        audit.log(AuditAction.PROCESS, "InsuranceCoverage", coverageId, "Policy verified with provider " + client.name());
        return new ProviderCheck(true, client.name(), result.isPresent(),
                result.map(PolicyStatusMessage::of).orElse("The provider returned no information for this policy."), result.orElse(null));
    }

    private static final class PolicyStatusMessage {
        static String of(InsuranceProviderClient.PolicyStatus p) {
            return p.message() != null ? p.message() : "Policy status: " + p.status();
        }
    }

    // ===================================================================== claims (FR-BEN-005)

    @Transactional(readOnly = true)
    public List<ClaimView> claims(String status) {
        Set<Long> visible = lookup.visibleEmployeeIds();
        return toClaimViews(claims.findAllByOrderByIdDesc().stream()
                .filter(c -> visible == null || visible.contains(c.getEmployeeId()))
                .filter(c -> status == null || status.isBlank() || c.getStatus().name().equals(status)).toList());
    }

    /** HR records a claim for any accessible employee; employees may lodge claims on their own coverage. */
    @Transactional
    public ClaimView createClaim(ClaimRequest r, boolean self) {
        if (r.coverageId() == null) throw ApiException.badRequest("Coverage is required");
        InsuranceCoverage c = coverage(r.coverageId());
        if (self) {
            if (!c.getEmployeeId().equals(CurrentUser.employeeId())) throw ApiException.notFound("Coverage", r.coverageId());
        } else {
            lookup.requireAccess(c.getEmployeeId());
        }
        InsuranceScheme s = scheme(c.getSchemeId());
        if (!s.isClaimsEnabled()) throw ApiException.rule("CLAIMS_DISABLED", "Claims are not handled through HRMS for " + s.getName());
        if (r.claimType() == null || r.claimType().isBlank()) throw ApiException.badRequest("Claim type is required");
        if (r.amountClaimed() == null || r.amountClaimed().signum() <= 0) throw ApiException.badRequest("Claimed amount must be positive");
        LocalDate claimDate = r.claimDate() == null ? LocalDate.now() : r.claimDate();
        LocalDate incident = r.incidentDate() == null ? claimDate : r.incidentDate();
        if (incident.isAfter(LocalDate.now())) throw ApiException.badRequest("Incident date cannot be in the future");
        if (incident.isBefore(c.getStartDate()) || (c.getExpiryDate() != null && incident.isAfter(c.getExpiryDate())))
            throw ApiException.rule("NOT_COVERED", "The incident date is outside the policy period");
        if (c.getStatus() == InsuranceCoverage.Status.CANCELLED) throw ApiException.rule("NOT_COVERED", "The policy is cancelled");
        if (c.getCoverageAmount() != null && r.amountClaimed().compareTo(c.getCoverageAmount()) > 0)
            throw ApiException.rule("EXCEEDS_COVER", "Claimed amount exceeds the coverage amount");
        InsuranceClaim cl = new InsuranceClaim();
        cl.setCoverageId(c.getId());
        cl.setEmployeeId(c.getEmployeeId());
        cl.setClaimDate(claimDate);
        cl.setIncidentDate(r.incidentDate());
        cl.setClaimType(r.claimType().trim());
        cl.setAmountClaimed(PayMoney.round(r.amountClaimed()));
        cl.setDescription(blank(r.description()));
        cl.setStatus(InsuranceClaim.Status.SUBMITTED);
        claims.save(cl);
        audit.log(AuditAction.CREATE, "InsuranceClaim", cl.getId(), "Insurance claim lodged (" + s.getCode() + ")");
        return toClaimViews(List.of(cl)).get(0);
    }

    @Transactional
    public ClaimView updateClaimStatus(Long id, ClaimStatusRequest r) {
        InsuranceClaim cl = claims.findById(id).orElseThrow(() -> ApiException.notFound("Claim", id));
        lookup.requireAccess(cl.getEmployeeId());
        InsuranceClaim.Status to;
        try {
            to = InsuranceClaim.Status.valueOf(r.status() == null ? "" : r.status());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid claim status");
        }
        InsuranceClaim.Status from = cl.getStatus();
        boolean ok = switch (from) {
            case SUBMITTED -> to == InsuranceClaim.Status.IN_REVIEW || to == InsuranceClaim.Status.APPROVED || to == InsuranceClaim.Status.REJECTED;
            case IN_REVIEW -> to == InsuranceClaim.Status.APPROVED || to == InsuranceClaim.Status.REJECTED;
            case APPROVED -> to == InsuranceClaim.Status.PAID;
            case REJECTED, PAID -> false;
        };
        if (!ok) throw ApiException.rule("INVALID_TRANSITION", "A claim cannot move from " + from + " to " + to);
        if (to == InsuranceClaim.Status.APPROVED) {
            if (r.amountApproved() == null || r.amountApproved().signum() < 0)
                throw ApiException.badRequest("Approved amount is required");
            if (r.amountApproved().compareTo(cl.getAmountClaimed()) > 0)
                throw ApiException.rule("EXCEEDS_CLAIM", "Approved amount cannot exceed the claimed amount");
            cl.setAmountApproved(PayMoney.round(r.amountApproved()));
        }
        if (to == InsuranceClaim.Status.REJECTED && (r.remarks() == null || r.remarks().isBlank()))
            throw ApiException.badRequest("Remarks are required when rejecting a claim");
        if (r.providerRef() != null && !r.providerRef().isBlank()) cl.setProviderRef(r.providerRef().trim());
        if (r.remarks() != null && !r.remarks().isBlank()) cl.setRemarks(r.remarks().trim());
        cl.setStatus(to);
        audit.log(AuditAction.UPDATE, "InsuranceClaim", id, "Claim status " + from + " -> " + to, from.name(), to.name());
        return toClaimViews(List.of(cl)).get(0);
    }

    // ===================================================================== self-service

    @Transactional(readOnly = true)
    public MyBenefits mine() {
        Long me = CurrentUser.employeeId();
        Employee e = lookup.require(me);
        audit.log(AuditAction.VIEW, "InsuranceCoverage", me, "Viewed own benefits");
        return new MyBenefits(toViews(coverages.findByEmployeeIdOrderByStartDateDesc(me)),
                toClaimViews(claims.findByEmployeeIdOrderByIdDesc(me)), eligibilityFor(e));
    }

    // ===================================================================== payroll contract

    /** Employee premiums of active coverages recovered through payroll for a period (FR-PAY-030). */
    @Transactional(readOnly = true)
    public List<PremiumDeduction> payrollPremiums(LocalDate periodStart, LocalDate periodEnd) {
        Map<Long, InsuranceScheme> byId = schemes.findAll().stream().collect(Collectors.toMap(InsuranceScheme::getId, Function.identity()));
        List<PremiumDeduction> out = new ArrayList<>();
        for (InsuranceCoverage c : coverages.activeBetween(periodStart, periodEnd)) {
            InsuranceScheme s = byId.get(c.getSchemeId());
            if (s == null || !s.isPayrollDeduction() || c.getEmployeePremium() == null || c.getEmployeePremium().signum() <= 0) continue;
            out.add(new PremiumDeduction(c.getEmployeeId(), c.getId(), s.getCode(), s.getName() + " premium", c.getEmployeePremium()));
        }
        return out;
    }

    // ===================================================================== expiry notifications (FR-BEN-004)

    @Scheduled(cron = "${app.benefits.expiry-cron:0 30 6 * * *}")
    public void scheduledExpiryCheck() {
        try {
            ExpiryCheckResult r = runExpiryCheck();
            log.info("Policy expiry check: {} lapsed, {} notified", r.expiredMarked(), r.notified());
        } catch (RuntimeException e) {
            log.error("Policy expiry check failed", e);
        }
    }

    @Transactional
    public ExpiryCheckResult runExpiryCheck() {
        LocalDate today = LocalDate.now();
        int expired = 0;
        for (InsuranceCoverage c : coverages.lapsedBefore(today)) {
            c.setStatus(InsuranceCoverage.Status.EXPIRED);
            expired++;
        }
        int days = Math.max(0, settings.getInt("benefits.expiry.notice-days", 30));
        Map<Long, InsuranceScheme> byId = schemes.findAll().stream().collect(Collectors.toMap(InsuranceScheme::getId, Function.identity()));
        List<InsuranceCoverage> due = coverages.expiringBetween(today, today.plusDays(days)).stream()
                .filter(c -> c.getExpiryNotifiedOn() == null).toList();
        Map<Long, EmployeeBrief> briefs = lookup.briefs(due.stream().map(InsuranceCoverage::getEmployeeId).toList());
        for (InsuranceCoverage c : due) {
            InsuranceScheme s = byId.get(c.getSchemeId());
            EmployeeBrief b = briefs.get(c.getEmployeeId());
            Map<String, Object> vars = new HashMap<>();
            vars.put("scheme", s == null ? "" : s.getName());
            vars.put("policyNo", c.getPolicyNo() == null ? "" : c.getPolicyNo());
            vars.put("expiryDate", c.getExpiryDate().toString());
            notifications.notifyEmployee(c.getEmployeeId(), EXPIRY_TEMPLATE, vars, "/self/benefits");
            Map<String, Object> hrVars = new HashMap<>(vars);
            hrVars.put("employeeNo", b == null ? "" : b.employeeNo());
            notifications.notifyRole(HR_ROLE, EXPIRY_TEMPLATE_HR, hrVars, "/benefits/expiring");
            c.setExpiryNotifiedOn(today);
        }
        if (expired > 0 || !due.isEmpty())
            audit.log(AuditAction.PROCESS, "InsuranceCoverage", null, "Policy expiry check: " + expired + " lapsed, " + due.size() + " notified");
        return new ExpiryCheckResult(expired, due.size());
    }

    // ===================================================================== helpers

    private List<CoverageView> toViews(List<InsuranceCoverage> rows) {
        Map<Long, InsuranceScheme> byId = schemes.findAll().stream().collect(Collectors.toMap(InsuranceScheme::getId, Function.identity()));
        Map<Long, EmployeeBrief> briefs = lookup.briefs(rows.stream().map(InsuranceCoverage::getEmployeeId).toList());
        LocalDate today = LocalDate.now();
        return rows.stream().map(c -> {
            InsuranceScheme s = byId.get(c.getSchemeId());
            EmployeeBrief b = briefs.get(c.getEmployeeId());
            return new CoverageView(c.getId(), c.getEmployeeId(), b == null ? null : b.employeeNo(), b == null ? null : b.nameWithInitials(),
                    c.getSchemeId(), s == null ? null : s.getCode(), s == null ? null : s.getName(),
                    s == null ? null : s.getSchemeType().name(), c.getPolicyNo(), c.getStartDate(), c.getExpiryDate(),
                    c.getEmployeePremium(), c.getEmployerPremium(), c.getCoverageAmount(), c.getStatus().name(),
                    c.getExpiryNotifiedOn(), c.getRemarks(),
                    c.getExpiryDate() == null ? null : ChronoUnit.DAYS.between(today, c.getExpiryDate()));
        }).toList();
    }

    private List<ClaimView> toClaimViews(List<InsuranceClaim> rows) {
        Map<Long, InsuranceScheme> schemeById = schemes.findAll().stream().collect(Collectors.toMap(InsuranceScheme::getId, Function.identity()));
        Map<Long, InsuranceCoverage> cov = coverages.findAllById(rows.stream().map(InsuranceClaim::getCoverageId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(InsuranceCoverage::getId, Function.identity()));
        Map<Long, EmployeeBrief> briefs = lookup.briefs(rows.stream().map(InsuranceClaim::getEmployeeId).toList());
        return rows.stream().map(cl -> {
            InsuranceCoverage c = cov.get(cl.getCoverageId());
            InsuranceScheme s = c == null ? null : schemeById.get(c.getSchemeId());
            EmployeeBrief b = briefs.get(cl.getEmployeeId());
            return new ClaimView(cl.getId(), cl.getCoverageId(), cl.getEmployeeId(), b == null ? null : b.employeeNo(),
                    b == null ? null : b.nameWithInitials(), s == null ? null : s.getCode(), s == null ? null : s.getName(),
                    c == null ? null : c.getPolicyNo(), cl.getClaimDate(), cl.getIncidentDate(), cl.getClaimType(),
                    cl.getAmountClaimed(), cl.getAmountApproved(), cl.getStatus().name(), cl.getProviderRef(),
                    cl.getDescription(), cl.getRemarks());
        }).toList();
    }

    private InsuranceScheme scheme(Long id) {
        return schemes.findById(id).orElseThrow(() -> ApiException.notFound("Insurance scheme", id));
    }

    private InsuranceCoverage coverage(Long id) {
        return coverages.findById(id).orElseThrow(() -> ApiException.notFound("Coverage", id));
    }

    /** Whether the current user may manage benefits (used by the controller for self vs. managed claims). */
    static boolean canManage() {
        UserPrincipal me = CurrentUser.get();
        return me.has(Permission.BEN_MANAGE);
    }

    private static String requireCode(String code) {
        if (code == null || !code.trim().toUpperCase().matches("^[A-Z0-9_-]{2,40}$"))
            throw ApiException.badRequest("Code must be 2-40 characters: letters, digits, '_' or '-'");
        return code.trim().toUpperCase();
    }

    private static BigDecimal money(BigDecimal v, String label, boolean required) {
        if (v == null) {
            if (required) throw ApiException.badRequest(label + " is required");
            return BigDecimal.ZERO.setScale(2);
        }
        if (v.signum() < 0) throw ApiException.badRequest(label + " cannot be negative");
        return PayMoney.round(v);
    }

    private static String normalizeList(String s) {
        if (s == null || s.isBlank()) return null;
        String joined = Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).distinct().collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Map<String, Object> snapshot(InsuranceScheme s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.getCode());
        m.put("name", s.getName());
        m.put("schemeType", s.getSchemeType());
        m.put("category", s.getCategory());
        m.put("provider", s.getProvider());
        m.put("employeePremium", s.getEmployeePremium());
        m.put("employerPremium", s.getEmployerPremium());
        m.put("coverageAmount", s.getCoverageAmount());
        m.put("payrollDeduction", s.isPayrollDeduction());
        m.put("eligibleEmploymentTypes", s.getEligibleEmploymentTypes());
        m.put("minServiceMonths", s.getMinServiceMonths());
        m.put("active", s.isActive());
        return m;
    }

    private static Map<String, Object> snapshot(InsuranceCoverage c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employeeId", c.getEmployeeId());
        m.put("schemeId", c.getSchemeId());
        m.put("policyNo", c.getPolicyNo());
        m.put("startDate", c.getStartDate());
        m.put("expiryDate", c.getExpiryDate());
        m.put("employeePremium", c.getEmployeePremium());
        m.put("employerPremium", c.getEmployerPremium());
        m.put("coverageAmount", c.getCoverageAmount());
        m.put("status", c.getStatus());
        return m;
    }

    /** Money rounding shared with payroll conventions (2dp, HALF_UP). */
    static final class PayMoney {
        private PayMoney() {
        }

        static BigDecimal round(BigDecimal v) {
            return v.setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
}
