package lk.gov.ciaboc.hrms.payroll;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.Employee;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup.EmployeeBrief;
import lk.gov.ciaboc.hrms.payroll.PayrollCalculator.Basis;
import lk.gov.ciaboc.hrms.payroll.PayrollEntities.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static lk.gov.ciaboc.hrms.payroll.PayrollSupportUtil.*;

/**
 * Salary administration and payroll configuration (FR-PAY-001..003, FR-PAY-010..012): effective-dated scales,
 * allowance and deduction types with rate history, employee salary placements, allowance assignments, scheme
 * memberships and bulk annual increments. Nothing is overwritten: changes close the current record and open a new one.
 */
@Service
@RequiredArgsConstructor
public class SalaryService {
    public static final Set<String> REASONS = Set.of("INITIAL", "INCREMENT", "REVISION", "PROMOTION", "CORRECTION");

    private final SalaryScaleRepository scales;
    private final AllowanceTypeRepository allowanceTypes;
    private final AllowanceRateRepository allowanceRates;
    private final DeductionTypeRepository deductionTypes;
    private final DeductionRateRepository deductionRates;
    private final EmployeeSalaryRepository salaries;
    private final EmployeeAllowanceRepository employeeAllowances;
    private final DeductionMembershipRepository memberships;
    private final EmployeeLoanRepository loans;
    private final EmployeeLookup lookup;
    private final AuditService audit;

    // ===================================================================== DTOs

    public record ScaleRequest(String code, String name, String grade, BigDecimal initialSalary, BigDecimal incrementAmount,
                               Integer maxSteps, LocalDate effectiveFrom, String notes, Boolean revisePlacements) {
    }

    public record TypeRequest(String code, String name, String nameSi, String nameTa, Boolean appliesToAll,
                              String category, Boolean membershipRequired, Boolean pensionRelated, Boolean active,
                              String description) {
    }

    public record AllowanceRateRequest(String basis, BigDecimal value, LocalDate effectiveFrom, String notes) {
    }

    public record DeductionRateRequest(String employeeBasis, BigDecimal employeeValue, String employerBasis,
                                       BigDecimal employerValue, LocalDate effectiveFrom, Boolean toBeConfirmed, String notes) {
    }

    public record AllowanceTypeView(AllowanceType type, AllowanceRate currentRate) {
    }

    public record DeductionTypeView(DeductionType type, DeductionRate currentRate) {
    }

    public record PlacementRequest(String scaleCode, Integer step, LocalDate effectiveFrom, String reason, String reference,
                                   String remarks) {
    }

    public record EmployeeAllowanceRequest(Long allowanceTypeId, BigDecimal amountOverride, LocalDate effectiveFrom,
                                           LocalDate effectiveTo, String remarks) {
    }

    public record MembershipRequest(Long deductionTypeId, String memberNo, BigDecimal employeeValueOverride,
                                    BigDecimal employerValueOverride, LocalDate effectiveFrom, String remarks) {
    }

    public record EndRequest(LocalDate effectiveTo, String remarks) {
    }

    public record EmployeeAllowanceView(Long id, Long allowanceTypeId, String code, String name, BigDecimal amountOverride,
                                        LocalDate effectiveFrom, LocalDate effectiveTo, String remarks, boolean current) {
    }

    public record MembershipView(Long id, Long employeeId, String employeeNo, String employeeName, Long deductionTypeId,
                                 String code, String name, String category, String memberNo,
                                 BigDecimal employeeValueOverride, BigDecimal employerValueOverride,
                                 LocalDate effectiveFrom, LocalDate effectiveTo, String remarks, boolean current) {
    }

    public record SalaryOverview(EmployeeBrief employee, EmployeeSalary current, SalaryScale scale,
                                 List<EmployeeSalary> history, List<EmployeeAllowanceView> allowances,
                                 List<MembershipView> memberships, int activeLoans, Integer incrementMonth) {
    }

    public record IncrementRow(Long employeeId, String employeeNo, String name, String scaleCode, Integer currentStep,
                               BigDecimal currentBasic, Integer newStep, BigDecimal newBasic, String status, String note) {
    }

    public record IncrementPostRequest(Integer month, LocalDate effectiveDate, List<Long> employeeIds) {
    }

    public record IncrementPostResult(int posted, int skipped, List<IncrementRow> rows) {
    }

    // ===================================================================== salary scales

    @Transactional(readOnly = true)
    public List<SalaryScale> scales(LocalDate asOf, boolean all) {
        if (all) return scales.findAll().stream()
                .sorted(Comparator.comparing(SalaryScale::getCode).thenComparing(SalaryScale::getEffectiveFrom, Comparator.reverseOrder())).toList();
        return scales.effectiveOn(asOf == null ? LocalDate.now() : asOf);
    }

    @Transactional(readOnly = true)
    public List<SalaryScale> scaleHistory(String code) {
        return scales.findByCodeOrderByEffectiveFromDesc(code);
    }

    @Transactional
    public SalaryScale createScale(ScaleRequest r) {
        String code = code(r.code());
        if (scales.existsByCode(code)) throw ApiException.conflict("Salary scale " + code + " already exists; use revise to add a new version");
        SalaryScale s = new SalaryScale();
        s.setCode(code);
        applyScale(s, r);
        scales.save(s);
        audit.log(AuditAction.CREATE, "SalaryScale", s.getId(), "Salary scale " + code + " created", null, scaleSnapshot(s));
        return s;
    }

    /**
     * Adds a new version of a scale from a date (salary revision). Optionally re-places every employee on the scale at the
     * same step (capped at the new maximum) from the same date.
     */
    @Transactional
    public SalaryScale reviseScale(String code, ScaleRequest r) {
        LocalDate from = required(r.effectiveFrom(), "Effective from");
        List<SalaryScale> versions = scales.findByCodeOrderByEffectiveFromDesc(code);
        if (versions.isEmpty()) throw ApiException.notFound("Salary scale", code);
        SalaryScale latest = versions.get(0);
        if (!from.isAfter(latest.getEffectiveFrom()))
            throw ApiException.rule("EFFECTIVE_DATE", "A revision must start after the latest version (" + latest.getEffectiveFrom() + ")");
        if (latest.getEffectiveTo() != null && latest.getEffectiveTo().isBefore(from))
            throw ApiException.rule("EFFECTIVE_DATE", "The scale was discontinued on " + latest.getEffectiveTo());
        SalaryScale s = new SalaryScale();
        s.setCode(code);
        applyScale(s, r);
        Map<String, Object> before = scaleSnapshot(latest);
        latest.setEffectiveTo(from);
        scales.saveAndFlush(latest);
        scales.save(s);
        audit.log(AuditAction.UPDATE, "SalaryScale", s.getId(), "Salary scale " + code + " revised from " + from, before, scaleSnapshot(s));
        if (Boolean.TRUE.equals(r.revisePlacements())) {
            int n = 0;
            for (EmployeeSalary es : salaries.openOnScale(code, from)) {
                if (!es.getEffectiveFrom().isBefore(from)) continue;
                if (!salaries.startingAfter(es.getEmployeeId(), from.minusDays(1)).isEmpty()) continue;
                int step = Math.min(es.getStep(), s.getMaxSteps());
                place(es.getEmployeeId(), s, step, from, "REVISION", "Scale revision " + code, "Automatic re-placement on scale revision");
                n++;
            }
            audit.log(AuditAction.PROCESS, "SalaryScale", s.getId(), n + " employees re-placed on revised scale " + code);
        }
        return s;
    }

    private void applyScale(SalaryScale s, ScaleRequest r) {
        s.setName(required(r.name(), "Name"));
        s.setGrade(blank(r.grade()));
        s.setInitialSalary(money(r.initialSalary(), "Initial salary"));
        s.setIncrementAmount(money(r.incrementAmount(), "Increment amount"));
        if (r.maxSteps() == null || r.maxSteps() < 0 || r.maxSteps() > 100) throw ApiException.badRequest("Number of increment steps must be between 0 and 100");
        s.setMaxSteps(r.maxSteps());
        s.setEffectiveFrom(required(r.effectiveFrom(), "Effective from"));
        s.setNotes(blank(r.notes()));
    }

    // ===================================================================== allowance types and rates

    @Transactional(readOnly = true)
    public List<AllowanceTypeView> allowanceTypes(LocalDate asOf) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        Map<Long, AllowanceRate> rates = allowanceRates.effectiveOn(d).stream()
                .collect(Collectors.toMap(AllowanceRate::getAllowanceTypeId, Function.identity(), (a, b) -> a));
        return allowanceTypes.findAllByOrderByCodeAsc().stream().map(t -> new AllowanceTypeView(t, rates.get(t.getId()))).toList();
    }

    @Transactional
    public AllowanceType createAllowanceType(TypeRequest r) {
        String code = code(r.code());
        if (allowanceTypes.existsByCode(code)) throw ApiException.conflict("Allowance type " + code + " already exists");
        AllowanceType t = new AllowanceType();
        t.setCode(code);
        applyAllowanceType(t, r);
        allowanceTypes.save(t);
        audit.log(AuditAction.CREATE, "AllowanceType", t.getId(), "Allowance type " + code + " created");
        return t;
    }

    @Transactional
    public AllowanceType updateAllowanceType(Long id, TypeRequest r) {
        AllowanceType t = allowanceTypes.findById(id).orElseThrow(() -> ApiException.notFound("Allowance type", id));
        String before = t.getName() + "|" + t.isAppliesToAll() + "|" + t.isActive();
        applyAllowanceType(t, r);
        audit.log(AuditAction.CONFIG_CHANGE, "AllowanceType", id, "Allowance type " + t.getCode() + " updated", before,
                t.getName() + "|" + t.isAppliesToAll() + "|" + t.isActive());
        return t;
    }

    private static void applyAllowanceType(AllowanceType t, TypeRequest r) {
        t.setName(required(r.name(), "Name"));
        t.setNameSi(blank(r.nameSi()));
        t.setNameTa(blank(r.nameTa()));
        t.setAppliesToAll(Boolean.TRUE.equals(r.appliesToAll()));
        t.setActive(r.active() == null || r.active());
        t.setDescription(blank(r.description()));
    }

    @Transactional(readOnly = true)
    public List<AllowanceRate> allowanceRates(Long typeId) {
        return allowanceRates.findByAllowanceTypeIdOrderByEffectiveFromDesc(typeId);
    }

    @Transactional
    public AllowanceRate addAllowanceRate(Long typeId, AllowanceRateRequest r) {
        AllowanceType t = allowanceTypes.findById(typeId).orElseThrow(() -> ApiException.notFound("Allowance type", typeId));
        Basis basis = basis(r.basis());
        if (basis != Basis.FIXED && basis != Basis.PERCENT_OF_BASIC) throw ApiException.badRequest("Allowance basis must be FIXED or PERCENT_OF_BASIC");
        BigDecimal value = rate(r.value(), "Value");
        if (basis == Basis.PERCENT_OF_BASIC && value.compareTo(BigDecimal.valueOf(100)) > 0) throw ApiException.badRequest("Percentage cannot exceed 100");
        LocalDate from = required(r.effectiveFrom(), "Effective from");
        List<AllowanceRate> history = allowanceRates.findByAllowanceTypeIdOrderByEffectiveFromDesc(typeId);
        String before = closeLatest(history, from);
        AllowanceRate rate = new AllowanceRate();
        rate.setAllowanceTypeId(typeId);
        rate.setBasis(basis);
        rate.setRateValue(value);
        rate.setEffectiveFrom(from);
        rate.setNotes(blank(r.notes()));
        allowanceRates.save(rate);
        audit.log(AuditAction.CONFIG_CHANGE, "AllowanceRate", rate.getId(), "Allowance " + t.getCode() + " rate from " + from,
                before, basis + " " + value.toPlainString());
        return rate;
    }

    // ===================================================================== deduction types and rates

    @Transactional(readOnly = true)
    public List<DeductionTypeView> deductionTypes(LocalDate asOf) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        Map<Long, DeductionRate> rates = deductionRates.effectiveOn(d).stream()
                .collect(Collectors.toMap(DeductionRate::getDeductionTypeId, Function.identity(), (a, b) -> a));
        return deductionTypes.findAllByOrderByCategoryAscCodeAsc().stream().map(t -> new DeductionTypeView(t, rates.get(t.getId()))).toList();
    }

    @Transactional
    public DeductionType createDeductionType(TypeRequest r) {
        String code = code(r.code());
        if (deductionTypes.existsByCode(code)) throw ApiException.conflict("Deduction type " + code + " already exists");
        DeductionType t = new DeductionType();
        t.setCode(code);
        applyDeductionType(t, r);
        deductionTypes.save(t);
        audit.log(AuditAction.CREATE, "DeductionType", t.getId(), "Deduction type " + code + " created");
        return t;
    }

    @Transactional
    public DeductionType updateDeductionType(Long id, TypeRequest r) {
        DeductionType t = deductionTypes.findById(id).orElseThrow(() -> ApiException.notFound("Deduction type", id));
        String before = t.getName() + "|" + t.getCategory() + "|" + t.isMembershipRequired() + "|" + t.isActive();
        applyDeductionType(t, r);
        audit.log(AuditAction.CONFIG_CHANGE, "DeductionType", id, "Deduction type " + t.getCode() + " updated", before,
                t.getName() + "|" + t.getCategory() + "|" + t.isMembershipRequired() + "|" + t.isActive());
        return t;
    }

    private static void applyDeductionType(DeductionType t, TypeRequest r) {
        t.setName(required(r.name(), "Name"));
        t.setNameSi(blank(r.nameSi()));
        t.setNameTa(blank(r.nameTa()));
        try {
            t.setCategory(DeductionType.Category.valueOf(required(r.category(), "Category")));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Category must be STATUTORY, SCHEME or OTHER");
        }
        t.setMembershipRequired(r.membershipRequired() == null || r.membershipRequired());
        t.setPensionRelated(Boolean.TRUE.equals(r.pensionRelated()));
        t.setActive(r.active() == null || r.active());
        t.setDescription(blank(r.description()));
    }

    @Transactional(readOnly = true)
    public List<DeductionRate> deductionRates(Long typeId) {
        return deductionRates.findByDeductionTypeIdOrderByEffectiveFromDesc(typeId);
    }

    @Transactional
    public DeductionRate addDeductionRate(Long typeId, DeductionRateRequest r) {
        DeductionType t = deductionTypes.findById(typeId).orElseThrow(() -> ApiException.notFound("Deduction type", typeId));
        Basis eb = basis(r.employeeBasis());
        Basis rb = basis(r.employerBasis());
        BigDecimal ev = eb == Basis.NONE ? BigDecimal.ZERO : rate(r.employeeValue(), "Employee rate");
        BigDecimal rv = rb == Basis.NONE ? BigDecimal.ZERO : rate(r.employerValue(), "Employer rate");
        if ((eb == Basis.PERCENT_OF_BASIC || eb == Basis.PERCENT_OF_GROSS) && ev.compareTo(BigDecimal.valueOf(100)) > 0
                || (rb == Basis.PERCENT_OF_BASIC || rb == Basis.PERCENT_OF_GROSS) && rv.compareTo(BigDecimal.valueOf(100)) > 0)
            throw ApiException.badRequest("Percentage cannot exceed 100");
        LocalDate from = required(r.effectiveFrom(), "Effective from");
        String before = closeLatest(deductionRates.findByDeductionTypeIdOrderByEffectiveFromDesc(typeId), from);
        DeductionRate rate = new DeductionRate();
        rate.setDeductionTypeId(typeId);
        rate.setEmployeeBasis(eb);
        rate.setEmployeeValue(ev);
        rate.setEmployerBasis(rb);
        rate.setEmployerValue(rv);
        rate.setEffectiveFrom(from);
        rate.setToBeConfirmed(Boolean.TRUE.equals(r.toBeConfirmed()));
        rate.setNotes(blank(r.notes()));
        deductionRates.save(rate);
        audit.log(AuditAction.CONFIG_CHANGE, "DeductionRate", rate.getId(), "Deduction " + t.getCode() + " rate from " + from,
                before, eb + " " + ev.toPlainString() + " / employer " + rb + " " + rv.toPlainString());
        return rate;
    }

    /** Closes the open rate at {@code from}. Rejects back-dating before the latest version (history is append-only). */
    private static <T extends lk.gov.ciaboc.hrms.common.EffectiveDated> String closeLatest(List<T> history, LocalDate from) {
        if (history.isEmpty()) return null;
        T latest = history.get(0);
        if (!from.isAfter(latest.getEffectiveFrom()))
            throw ApiException.rule("EFFECTIVE_DATE", "A new rate must start after the latest rate (" + latest.getEffectiveFrom() + ")");
        if (latest.getEffectiveTo() == null || latest.getEffectiveTo().isAfter(from)) {
            if (latest instanceof AllowanceRate a) a.setEffectiveTo(from);
            if (latest instanceof DeductionRate d) d.setEffectiveTo(from);
        }
        if (latest instanceof AllowanceRate a) return a.getBasis() + " " + a.getRateValue().toPlainString();
        if (latest instanceof DeductionRate d) return d.getEmployeeBasis() + " " + d.getEmployeeValue().toPlainString()
                + " / employer " + d.getEmployerBasis() + " " + d.getEmployerValue().toPlainString();
        return null;
    }

    private static Basis basis(String b) {
        try {
            return Basis.valueOf(required(b, "Basis"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Basis must be one of NONE, FIXED, PERCENT_OF_BASIC, PERCENT_OF_GROSS");
        }
    }

    // ===================================================================== employee salary placement (FR-PAY-003)

    @Transactional(readOnly = true)
    public SalaryOverview overview(Long employeeId) {
        Employee e = lookup.requireAccess(employeeId);
        LocalDate today = LocalDate.now();
        EmployeeSalary current = salaries.effectiveOn(employeeId, today).orElse(null);
        SalaryScale scale = current == null ? null : scales.effectiveOn(current.getScaleCode(), today).orElse(null);
        Map<Long, AllowanceType> types = allowanceTypes.findAll().stream().collect(Collectors.toMap(AllowanceType::getId, Function.identity()));
        List<EmployeeAllowanceView> allowances = employeeAllowances.findByEmployeeIdOrderByEffectiveFromDesc(employeeId).stream()
                .map(a -> {
                    AllowanceType t = types.get(a.getAllowanceTypeId());
                    return new EmployeeAllowanceView(a.getId(), a.getAllowanceTypeId(), t == null ? null : t.getCode(),
                            t == null ? null : t.getName(), a.getAmountOverride(), a.getEffectiveFrom(), a.getEffectiveTo(),
                            a.getRemarks(), a.isEffectiveOn(today));
                }).toList();
        int activeLoans = (int) loans.findByEmployeeIdOrderByStartDateDesc(employeeId).stream()
                .filter(l -> l.getStatus() == EmployeeLoan.Status.ACTIVE).count();
        audit.log(AuditAction.VIEW, "EmployeeSalary", employeeId, "Viewed salary details of " + e.getEmployeeNo());
        return new SalaryOverview(lookup.brief(employeeId), current, scale, salaries.findByEmployeeIdOrderByEffectiveFromDesc(employeeId),
                allowances, membershipViews(memberships.findByEmployeeIdOrderByEffectiveFromDesc(employeeId)), activeLoans,
                e.getIncrementMonth());
    }

    @Transactional
    public EmployeeSalary placeEmployee(Long employeeId, PlacementRequest r) {
        Employee e = lookup.requireAccess(employeeId);
        LocalDate from = required(r.effectiveFrom(), "Effective from");
        String scaleCode = required(r.scaleCode(), "Salary scale").toUpperCase();
        SalaryScale scale = scales.effectiveOn(scaleCode, from)
                .orElseThrow(() -> ApiException.rule("NO_SCALE", "Salary scale " + scaleCode + " is not in force on " + from));
        if (r.step() == null || r.step() < 0 || r.step() > scale.getMaxSteps())
            throw ApiException.badRequest("Step must be between 0 and " + scale.getMaxSteps() + " for scale " + scaleCode);
        String reason = r.reason() == null || r.reason().isBlank() ? "REVISION" : r.reason().trim().toUpperCase();
        if (!REASONS.contains(reason)) throw ApiException.badRequest("Reason must be one of " + REASONS);
        if (e.getAppointmentDate() != null && from.isBefore(e.getAppointmentDate()))
            throw ApiException.rule("EFFECTIVE_DATE", "A salary placement cannot start before the appointment date");
        return place(employeeId, scale, r.step(), from, reason, blank(r.reference()), blank(r.remarks()));
    }

    /** Opens a new salary version from {@code from} and closes the one in force. */
    EmployeeSalary place(Long employeeId, SalaryScale scale, int step, LocalDate from, String reason, String reference, String remarks) {
        if (!salaries.startingAfter(employeeId, from.minusDays(1)).isEmpty())
            throw ApiException.rule("LATER_RECORD_EXISTS", "A salary record already starts on or after " + from
                    + "; history can only be extended forward");
        EmployeeSalary current = salaries.effectiveOn(employeeId, from).orElse(null);
        Map<String, Object> before = null;
        if (current != null) {
            before = salarySnapshot(current);
            current.setEffectiveTo(from);
            salaries.saveAndFlush(current);
        }
        EmployeeSalary s = new EmployeeSalary();
        s.setEmployeeId(employeeId);
        s.setScaleCode(scale.getCode());
        s.setStep(step);
        s.setBasicSalary(PayrollCalculator.round(scale.salaryAtStep(step)));
        s.setEffectiveFrom(from);
        s.setReason(reason);
        s.setReference(reference);
        s.setRemarks(remarks);
        salaries.save(s);
        audit.log(AuditAction.CREATE, "EmployeeSalary", s.getId(), "Salary placement (" + reason + ") " + scale.getCode()
                + " step " + step + " from " + from, before, salarySnapshot(s));
        return s;
    }

    // ===================================================================== allowance assignments

    @Transactional
    public EmployeeAllowance assignAllowance(Long employeeId, EmployeeAllowanceRequest r) {
        lookup.requireAccess(employeeId);
        if (r.allowanceTypeId() == null) throw ApiException.badRequest("Allowance type is required");
        AllowanceType t = allowanceTypes.findById(r.allowanceTypeId()).orElseThrow(() -> ApiException.notFound("Allowance type", r.allowanceTypeId()));
        if (!t.isActive()) throw ApiException.rule("INACTIVE", "Allowance type " + t.getCode() + " is not active");
        LocalDate from = required(r.effectiveFrom(), "Effective from");
        if (r.effectiveTo() != null && !r.effectiveTo().isAfter(from)) throw ApiException.badRequest("End date must be after the start date");
        for (EmployeeAllowance o : employeeAllowances.findByEmployeeIdOrderByEffectiveFromDesc(employeeId)) {
            if (o.getAllowanceTypeId().equals(t.getId()) && overlaps(o.getEffectiveFrom(), o.getEffectiveTo(), from, r.effectiveTo()))
                throw ApiException.rule("OVERLAP", "The employee already has " + t.getName() + " for an overlapping period");
        }
        EmployeeAllowance a = new EmployeeAllowance();
        a.setEmployeeId(employeeId);
        a.setAllowanceTypeId(t.getId());
        a.setAmountOverride(r.amountOverride() == null ? null : money(r.amountOverride(), "Amount"));
        a.setEffectiveFrom(from);
        a.setEffectiveTo(r.effectiveTo());
        a.setRemarks(blank(r.remarks()));
        employeeAllowances.save(a);
        audit.log(AuditAction.CREATE, "EmployeeAllowance", a.getId(), "Allowance " + t.getCode() + " assigned to employee " + employeeId);
        return a;
    }

    @Transactional
    public EmployeeAllowance endAllowance(Long id, EndRequest r) {
        EmployeeAllowance a = employeeAllowances.findById(id).orElseThrow(() -> ApiException.notFound("Allowance assignment", id));
        lookup.requireAccess(a.getEmployeeId());
        LocalDate to = required(r.effectiveTo(), "End date");
        if (!to.isAfter(a.getEffectiveFrom())) throw ApiException.badRequest("End date must be after the start date");
        if (a.getEffectiveTo() != null && !to.isBefore(a.getEffectiveTo())) throw ApiException.badRequest("The assignment already ends earlier");
        LocalDate old = a.getEffectiveTo();
        a.setEffectiveTo(to);
        audit.log(AuditAction.UPDATE, "EmployeeAllowance", id, "Allowance assignment ended " + to, old, to);
        return a;
    }

    // ===================================================================== scheme membership (FR-PAY-012)

    @Transactional(readOnly = true)
    public List<MembershipView> memberships(Long deductionTypeId, Long employeeId) {
        List<DeductionMembership> rows;
        if (employeeId != null) {
            lookup.requireAccess(employeeId);
            rows = memberships.findByEmployeeIdOrderByEffectiveFromDesc(employeeId);
        } else if (deductionTypeId != null) {
            rows = memberships.findByDeductionTypeIdOrderByEmployeeIdAscEffectiveFromDesc(deductionTypeId);
        } else {
            rows = memberships.findAll();
        }
        Set<Long> visible = lookup.visibleEmployeeIds();
        return membershipViews(rows.stream().filter(m -> visible == null || visible.contains(m.getEmployeeId()))
                .filter(m -> deductionTypeId == null || deductionTypeId.equals(m.getDeductionTypeId())).toList());
    }

    @Transactional
    public DeductionMembership addMembership(Long employeeId, MembershipRequest r) {
        lookup.requireAccess(employeeId);
        if (r.deductionTypeId() == null) throw ApiException.badRequest("Deduction / scheme is required");
        DeductionType t = deductionTypes.findById(r.deductionTypeId()).orElseThrow(() -> ApiException.notFound("Deduction type", r.deductionTypeId()));
        if (!t.isActive()) throw ApiException.rule("INACTIVE", t.getName() + " is not active");
        LocalDate from = required(r.effectiveFrom(), "Effective from");
        for (DeductionMembership o : memberships.findByEmployeeIdOrderByEffectiveFromDesc(employeeId)) {
            if (o.getDeductionTypeId().equals(t.getId()) && overlaps(o.getEffectiveFrom(), o.getEffectiveTo(), from, null))
                throw ApiException.rule("OVERLAP", "The employee is already a member of " + t.getName() + " for an overlapping period; end it first");
        }
        DeductionMembership m = new DeductionMembership();
        m.setEmployeeId(employeeId);
        m.setDeductionTypeId(t.getId());
        m.setMemberNo(blank(r.memberNo()));
        m.setEmployeeValueOverride(r.employeeValueOverride() == null ? null : rate(r.employeeValueOverride(), "Employee rate override"));
        m.setEmployerValueOverride(r.employerValueOverride() == null ? null : rate(r.employerValueOverride(), "Employer rate override"));
        m.setEffectiveFrom(from);
        m.setRemarks(blank(r.remarks()));
        memberships.save(m);
        audit.log(AuditAction.CREATE, "DeductionMembership", m.getId(), "Employee " + employeeId + " joined " + t.getCode() + " from " + from);
        return m;
    }

    @Transactional
    public DeductionMembership endMembership(Long id, EndRequest r) {
        DeductionMembership m = memberships.findById(id).orElseThrow(() -> ApiException.notFound("Membership", id));
        lookup.requireAccess(m.getEmployeeId());
        LocalDate to = required(r.effectiveTo(), "End date");
        if (!to.isAfter(m.getEffectiveFrom())) throw ApiException.badRequest("End date must be after the start date");
        if (m.getEffectiveTo() != null && !to.isBefore(m.getEffectiveTo())) throw ApiException.badRequest("The membership already ends earlier");
        LocalDate old = m.getEffectiveTo();
        m.setEffectiveTo(to);
        if (r.remarks() != null && !r.remarks().isBlank()) m.setRemarks(r.remarks().trim());
        audit.log(AuditAction.UPDATE, "DeductionMembership", id, "Membership ended " + to, old, to);
        return m;
    }

    private List<MembershipView> membershipViews(List<DeductionMembership> rows) {
        Map<Long, DeductionType> types = deductionTypes.findAll().stream().collect(Collectors.toMap(DeductionType::getId, Function.identity()));
        Map<Long, EmployeeBrief> briefs = lookup.briefs(rows.stream().map(DeductionMembership::getEmployeeId).toList());
        LocalDate today = LocalDate.now();
        return rows.stream().map(m -> {
            DeductionType t = types.get(m.getDeductionTypeId());
            EmployeeBrief b = briefs.get(m.getEmployeeId());
            return new MembershipView(m.getId(), m.getEmployeeId(), b == null ? null : b.employeeNo(),
                    b == null ? null : (masked(b) ? RESTRICTED_NAME : b.nameWithInitials()), m.getDeductionTypeId(),
                    t == null ? null : t.getCode(), t == null ? null : t.getName(), t == null ? null : t.getCategory().name(),
                    m.getMemberNo(), m.getEmployeeValueOverride(), m.getEmployerValueOverride(), m.getEffectiveFrom(),
                    m.getEffectiveTo(), m.getRemarks(), m.isEffectiveOn(today));
        }).toList();
    }

    // ===================================================================== bulk annual increments (FR-PAY-003)

    @Transactional(readOnly = true)
    public List<IncrementRow> incrementPreview(int month, LocalDate effectiveDate) {
        if (month < 1 || month > 12) throw ApiException.badRequest("Month must be between 1 and 12");
        LocalDate date = effectiveDate == null ? LocalDate.of(LocalDate.now().getYear(), month, 1) : effectiveDate;
        List<IncrementRow> rows = new ArrayList<>();
        for (EmployeeBrief b : lookup.inService()) {
            Employee e = lookup.require(b.id());
            if (e.getIncrementMonth() == null || e.getIncrementMonth() != month) continue;
            String name = masked(b) ? RESTRICTED_NAME : b.nameWithInitials();
            EmployeeSalary cur = salaries.effectiveOn(b.id(), date.minusDays(1)).orElse(null);
            EmployeeSalary onDate = salaries.effectiveOn(b.id(), date).orElse(null);
            if (onDate != null && onDate.getEffectiveFrom().equals(date) && "INCREMENT".equals(onDate.getReason())) {
                rows.add(new IncrementRow(b.id(), b.employeeNo(), name, onDate.getScaleCode(), cur == null ? null : cur.getStep(),
                        cur == null ? null : cur.getBasicSalary(), onDate.getStep(), onDate.getBasicSalary(), "ALREADY_POSTED", "Increment already posted"));
                continue;
            }
            if (cur == null) {
                rows.add(new IncrementRow(b.id(), b.employeeNo(), name, null, null, null, null, null, "NO_PLACEMENT", "No salary placement"));
                continue;
            }
            if (!salaries.startingAfter(b.id(), date.minusDays(1)).isEmpty()) {
                rows.add(new IncrementRow(b.id(), b.employeeNo(), name, cur.getScaleCode(), cur.getStep(), cur.getBasicSalary(), null, null,
                        "LATER_RECORD", "A salary record already exists on or after the increment date"));
                continue;
            }
            SalaryScale scale = scales.effectiveOn(cur.getScaleCode(), date).orElse(null);
            if (scale == null) {
                rows.add(new IncrementRow(b.id(), b.employeeNo(), name, cur.getScaleCode(), cur.getStep(), cur.getBasicSalary(), null, null,
                        "NO_SCALE", "Scale not in force on the increment date"));
                continue;
            }
            if (cur.getStep() >= scale.getMaxSteps()) {
                rows.add(new IncrementRow(b.id(), b.employeeNo(), name, cur.getScaleCode(), cur.getStep(), cur.getBasicSalary(), null, null,
                        "AT_MAXIMUM", "Already at the maximum of the scale"));
                continue;
            }
            int next = cur.getStep() + 1;
            rows.add(new IncrementRow(b.id(), b.employeeNo(), name, cur.getScaleCode(), cur.getStep(), cur.getBasicSalary(), next,
                    PayrollCalculator.round(scale.salaryAtStep(next)), "ELIGIBLE", null));
        }
        return rows;
    }

    @Transactional
    public IncrementPostResult postIncrements(IncrementPostRequest r) {
        if (r.month() == null) throw ApiException.badRequest("Month is required");
        LocalDate date = r.effectiveDate() == null ? LocalDate.of(LocalDate.now().getYear(), r.month(), 1) : r.effectiveDate();
        Set<Long> selected = r.employeeIds() == null ? Set.of() : new HashSet<>(r.employeeIds());
        if (selected.isEmpty()) throw ApiException.badRequest("Select at least one employee");
        List<IncrementRow> preview = incrementPreview(r.month(), date);
        int posted = 0;
        int skipped = 0;
        String ref = "INC-" + date.getYear() + "-" + String.format("%02d", date.getMonthValue());
        for (IncrementRow row : preview) {
            if (!selected.contains(row.employeeId())) continue;
            if (!"ELIGIBLE".equals(row.status())) {
                skipped++;
                continue;
            }
            SalaryScale scale = scales.effectiveOn(row.scaleCode(), date).orElseThrow();
            place(row.employeeId(), scale, row.newStep(), date, "INCREMENT", ref, "Annual increment");
            posted++;
        }
        audit.log(AuditAction.PROCESS, "EmployeeSalary", null, "Annual increments " + ref + ": " + posted + " posted, " + skipped + " skipped");
        return new IncrementPostResult(posted, skipped, incrementPreview(r.month(), date));
    }

    // ===================================================================== helpers

    private static boolean overlaps(LocalDate aFrom, LocalDate aTo, LocalDate bFrom, LocalDate bTo) {
        return (bTo == null || aFrom.isBefore(bTo)) && (aTo == null || bFrom.isBefore(aTo));
    }

    private static Map<String, Object> scaleSnapshot(SalaryScale s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.getCode());
        m.put("name", s.getName());
        m.put("grade", s.getGrade());
        m.put("initialSalary", s.getInitialSalary());
        m.put("incrementAmount", s.getIncrementAmount());
        m.put("maxSteps", s.getMaxSteps());
        m.put("effectiveFrom", s.getEffectiveFrom());
        m.put("effectiveTo", s.getEffectiveTo());
        return m;
    }

    private static Map<String, Object> salarySnapshot(EmployeeSalary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scaleCode", s.getScaleCode());
        m.put("step", s.getStep());
        m.put("basicSalary", s.getBasicSalary());
        m.put("effectiveFrom", s.getEffectiveFrom());
        m.put("effectiveTo", s.getEffectiveTo());
        m.put("reason", s.getReason());
        return m;
    }
}
