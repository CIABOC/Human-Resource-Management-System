package lk.gov.ciaboc.hrms.recruitment;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.establishment.Designation;
import lk.gov.ciaboc.hrms.establishment.DesignationRepository;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.establishment.OrgUnit;
import lk.gov.ciaboc.hrms.establishment.Position;
import lk.gov.ciaboc.hrms.establishment.PositionRepository;
import lk.gov.ciaboc.hrms.files.FileStorageService;
import lk.gov.ciaboc.hrms.files.StoredFile;
import lk.gov.ciaboc.hrms.masterdata.MasterDataCategory;
import lk.gov.ciaboc.hrms.masterdata.MasterDataService;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.*;
import lk.gov.ciaboc.hrms.recruitment.sor.SorEntities.Sor;
import lk.gov.ciaboc.hrms.recruitment.sor.SorEntities.SorStatus;
import lk.gov.ciaboc.hrms.recruitment.sor.SorRepository;
import lk.gov.ciaboc.hrms.recruitment.vacancy.TriggerConfigRepository;
import lk.gov.ciaboc.hrms.recruitment.vacancy.VacancyEntities.TriggerConfig;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Vacancy register, announcements, applicants, examinations and interviews (FR-REC-001..006, spec v2.1 REQ 5).
 * Automatic vacancies and alerts are in {@code recruitment.vacancy}; SORs in {@code recruitment.sor}; publishing in
 * {@code recruitment.publish}. Selection and appointment are in {@link SelectionService}; correspondence in
 * {@link CorrespondenceService}.
 */
@Service
@RequiredArgsConstructor
public class RecruitmentService {
    /** Vacant posts under (or awaiting) recruitment: counted against the vacant cadre. */
    public static final Set<VacancyStatus> ACTIVE_VACANCY = EnumSet.of(VacancyStatus.OPEN, VacancyStatus.ADVERTISED, VacancyStatus.CLOSED);
    /** Vacancies that are not yet filled, frozen or cancelled (alerts run for FORECAST and OPEN only). */
    public static final Set<VacancyStatus> LIVE_VACANCY = EnumSet.of(VacancyStatus.FORECAST, VacancyStatus.OPEN, VacancyStatus.ADVERTISED, VacancyStatus.CLOSED);
    private static final Pattern NIC = Pattern.compile("^(\\d{9}[VX]|\\d{12})$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9/_-]{1,40}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Manual applicant status changes. Selection outcomes are set only by the approval workflow. */
    private static final Map<ApplicantStatus, Set<ApplicantStatus>> MANUAL_TRANSITIONS = Map.of(
            ApplicantStatus.RECEIVED, EnumSet.of(ApplicantStatus.SHORTLISTED, ApplicantStatus.REJECTED, ApplicantStatus.WITHDRAWN),
            ApplicantStatus.SHORTLISTED, EnumSet.of(ApplicantStatus.RECEIVED, ApplicantStatus.REJECTED, ApplicantStatus.WITHDRAWN),
            ApplicantStatus.REJECTED, EnumSet.of(ApplicantStatus.RECEIVED),
            ApplicantStatus.SELECTED, EnumSet.of(ApplicantStatus.WITHDRAWN));

    /**
     * Manual vacancy status changes (REQ 5.4). ADVERTISED is reached only through publishing with an approved SOR
     * (REQ 5.5); FILLED only through appointment or placement (REQ 5.7).
     */
    private static final Map<VacancyStatus, Set<VacancyStatus>> VACANCY_TRANSITIONS = Map.of(
            VacancyStatus.FORECAST, EnumSet.of(VacancyStatus.OPEN, VacancyStatus.FROZEN, VacancyStatus.CANCELLED),
            VacancyStatus.OPEN, EnumSet.of(VacancyStatus.FROZEN, VacancyStatus.CANCELLED),
            VacancyStatus.ADVERTISED, EnumSet.of(VacancyStatus.CLOSED),
            VacancyStatus.CLOSED, EnumSet.of(VacancyStatus.ADVERTISED, VacancyStatus.OPEN, VacancyStatus.CANCELLED),
            VacancyStatus.FROZEN, EnumSet.of(VacancyStatus.OPEN, VacancyStatus.CANCELLED));

    private final VacancyRepository vacancies;
    private final AnnouncementRepository announcements;
    private final ApplicantRepository applicants;
    private final ApplicantStatusChangeRepository statusChanges;
    private final ApplicantDocumentRepository documents;
    private final RecruitmentExamRepository exams;
    private final ExamResultRepository examResults;
    private final InterviewRepository interviews;
    private final EstablishmentService establishment;
    private final DesignationRepository designations;
    private final PositionRepository positions;
    private final EmployeeLookup employeeLookup;
    private final SorRepository sors;
    private final TriggerConfigRepository triggers;
    private final MasterDataService masterData;
    private final SettingsService settings;
    private final FileStorageService files;
    private final AuditService audit;

    // ===================================================================== vacancies (FR-REC-001, -002, REQ 5.4)

    public record VacancyRequest(String referenceNo, String title, Long designationId, String orgUnitCode, Integer numPosts,
                                 LocalDate openingDate, LocalDate closingDate, String description, String requirements,
                                 BigDecimal examWeight, BigDecimal interviewWeight, LocalDate expectedDate, Long positionId,
                                 Long sorId) {
    }

    public record VacancyView(Long id, String referenceNo, String title, Long designationId, String designationName,
                              String grade, String orgUnitCode, String orgUnitName, Long positionId, String positionCode,
                              int numPosts, String status, String triggerCode, String triggerName, String source,
                              LocalDate expectedDate, LocalDate openingDate, LocalDate closingDate, String description,
                              String requirements, BigDecimal examWeight, BigDecimal interviewWeight, Long sorId,
                              String sorRef, String sorStatus, Long vacatedByEmployeeId, String vacatedByEmployeeNo,
                              Long cadreChangeId, int filledPosts, LocalDate filledOn, String statusReason,
                              boolean dyingCadre, Map<String, Long> applicantsByStatus, long applicants, long appointed,
                              Instant createdAt, String createdBy) {
    }

    public record VacancySuggestion(Long designationId, String designationName, String grade, String orgUnitCode,
                                    String orgUnitName, int approved, long occupied, long vacant, long underRecruitment,
                                    long suggestedPosts) {
    }

    /** Vacant cadre (approved minus occupied) less posts already being recruited for (FR-REC-001). Dying cadres excluded. */
    @Transactional(readOnly = true)
    public List<VacancySuggestion> suggestions() {
        Map<String, Long> inRecruitment = new HashMap<>();
        for (Vacancy v : vacancies.findByStatusIn(ACTIVE_VACANCY)) {
            inRecruitment.merge(v.getDesignationId() + "|" + v.getOrgUnitCode(), remainingPosts(v), Long::sum);
        }
        Set<Long> dying = designations.findAll().stream().filter(Designation::isDyingCadre).map(Designation::getId).collect(Collectors.toSet());
        List<VacancySuggestion> out = new ArrayList<>();
        for (EstablishmentService.CadreRow r : establishment.cadreSummary(LocalDate.now(), null)) {
            if (r.vacant() <= 0 || dying.contains(r.designationId())) continue;
            long under = inRecruitment.getOrDefault(r.designationId() + "|" + r.orgUnitCode(), 0L);
            out.add(new VacancySuggestion(r.designationId(), r.designationName(), r.grade(), r.orgUnitCode(), r.orgUnitName(),
                    r.approved(), r.occupied(), r.vacant(), under, Math.max(0, r.vacant() - under)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<VacancyView> listVacancies(String status, String triggerCode, String orgUnitCode) {
        Set<VacancyStatus> wanted = new HashSet<>();
        if (!blank(status)) for (String s : status.split(",")) wanted.add(parse(VacancyStatus.class, s, "vacancy status"));
        List<Vacancy> rows = vacancies.findAllByOrderByIdDesc().stream()
                .filter(v -> wanted.isEmpty() || wanted.contains(v.getStatus()))
                .filter(v -> blank(triggerCode) || triggerCode.equals(v.getTriggerCode()))
                .filter(v -> blank(orgUnitCode) || orgUnitCode.equals(v.getOrgUnitCode()))
                .toList();
        ViewContext ctx = context(rows);
        return rows.stream().map(v -> view(v, ctx)).toList();
    }

    @Transactional(readOnly = true)
    public VacancyView vacancyView(Long id) {
        return view(vacancy(id));
    }

    public record AvailablePosition(Long id, String referenceNo, String title, String designationName, String orgUnitName,
                                    int numPosts, LocalDate closingDate, String description, String requirements) {
    }

    /** Advertised positions visible to all staff (FR-REC-002). Only non-confidential vacancy details are returned. */
    @Transactional(readOnly = true)
    public List<AvailablePosition> availablePositions() {
        LocalDate today = LocalDate.now();
        Map<Long, String> des = designationNames();
        Map<String, String> units = unitNames();
        return vacancies.findByStatusOrderByClosingDateAsc(VacancyStatus.ADVERTISED).stream()
                .filter(v -> v.getClosingDate() == null || !v.getClosingDate().isBefore(today))
                .map(v -> new AvailablePosition(v.getId(), v.getReferenceNo(), v.getTitle(), des.get(v.getDesignationId()),
                        units.getOrDefault(v.getOrgUnitCode(), v.getOrgUnitCode()), v.getNumPosts(), v.getClosingDate(),
                        v.getDescription(), v.getRequirements()))
                .toList();
    }

    /** Manual vacancy (REQ 5.1 "manual vacancy too"): OPEN, or FORECAST when the expected date is in the future. */
    @Transactional
    public VacancyView createVacancy(VacancyRequest r) {
        Vacancy v = new Vacancy();
        String ref = blank(r.referenceNo()) ? nextReference() : r.referenceNo().trim();
        if (!REF.matcher(ref).matches()) throw ApiException.badRequest("Reference number may contain letters, digits, '/', '-' and '_' only");
        if (vacancies.existsByReferenceNo(ref)) throw ApiException.conflict("Vacancy reference " + ref + " already exists");
        v.setReferenceNo(ref);
        v.setSource(VacancySource.MANUAL);
        v.setTriggerCode("MANUAL");
        v.setExamWeight(defaultExamWeight());
        v.setInterviewWeight(defaultInterviewWeight());
        LocalDate expected = r.expectedDate() == null ? LocalDate.now() : r.expectedDate();
        v.setExpectedDate(expected);
        v.setStatus(expected.isAfter(LocalDate.now()) ? VacancyStatus.FORECAST : VacancyStatus.OPEN);
        apply(v, r);
        if (r.sorId() != null) v.setSorId(checkSor(r.sorId(), v.getDesignationId()).getId());
        else if (settings.getBool("vacancy.auto-link-sor", true)) currentApprovedSor(v.getDesignationId()).ifPresent(s -> v.setSorId(s.getId()));
        vacancies.save(v);
        audit.log(AuditAction.CREATE, "Vacancy", v.getId(), "Vacancy " + ref + " created manually: " + v.getTitle() + " ("
                + v.getNumPosts() + " posts, " + v.getStatus() + ")");
        return view(v);
    }

    @Transactional
    public VacancyView updateVacancy(Long id, VacancyRequest r) {
        Vacancy v = vacancy(id);
        if (v.getStatus() == VacancyStatus.FILLED || v.getStatus() == VacancyStatus.CANCELLED)
            throw ApiException.rule("VACANCY_CLOSED", "A filled or cancelled vacancy cannot be changed");
        String before = describe(v);
        if (applicants.countByVacancyId(id) > 0
                && (!Objects.equals(r.designationId(), v.getDesignationId()) || !Objects.equals(r.orgUnitCode(), v.getOrgUnitCode())))
            throw ApiException.rule("HAS_APPLICANTS", "Designation and unit cannot be changed once applications are recorded");
        if (v.getSource() == VacancySource.AUTO
                && (!Objects.equals(r.designationId(), v.getDesignationId()) || !Objects.equals(r.orgUnitCode(), v.getOrgUnitCode())))
            throw ApiException.rule("AUTO_VACANCY", "The post of an automatically created vacancy cannot be changed");
        apply(v, r);
        if (r.expectedDate() != null && (v.getStatus() == VacancyStatus.FORECAST || v.getStatus() == VacancyStatus.OPEN))
            v.setExpectedDate(r.expectedDate());
        audit.log(AuditAction.UPDATE, "Vacancy", id, "Vacancy " + v.getReferenceNo() + " updated", before, describe(v));
        return view(v);
    }

    private void apply(Vacancy v, VacancyRequest r) {
        require(r.title(), "Title");
        if (r.designationId() == null) throw ApiException.badRequest("Designation is required");
        if (blank(r.orgUnitCode())) throw ApiException.badRequest("Org unit is required");
        if (r.numPosts() == null || r.numPosts() < 1) throw ApiException.badRequest("Number of posts must be at least 1");
        Designation des = designations.findById(r.designationId()).filter(Designation::isActive)
                .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Designation is not active"));
        if (des.isDyingCadre())
            throw ApiException.rule("DYING_CADRE", des.getName() + " is a dying cadre: no fresh recruitment; posts lapse as holders leave (REQ 6.10)");
        establishment.orgUnit(r.orgUnitCode(), LocalDate.now())
                .orElseThrow(() -> ApiException.rule("INVALID_REFERENCE", "Org unit " + r.orgUnitCode() + " is not active"));
        if (r.openingDate() != null && r.closingDate() != null && r.closingDate().isBefore(r.openingDate()))
            throw ApiException.badRequest("Closing date cannot be before the opening date");
        if (r.positionId() != null) {
            Position p = positions.findById(r.positionId()).orElseThrow(() -> ApiException.notFound("Post", r.positionId()));
            if (!p.getOrgUnitCode().equals(r.orgUnitCode()) || !p.getDesignationId().equals(r.designationId()))
                throw ApiException.rule("POSITION_MISMATCH", "Post " + p.getCode() + " does not belong to the selected unit and designation");
            if (!p.isActive() || !"ACTIVE".equals(p.getPostStatus()))
                throw ApiException.rule("POST_NOT_ACTIVE", "Post " + p.getCode() + " is " + p.getPostStatus().toLowerCase().replace('_', ' '));
            if (r.numPosts() != 1) throw ApiException.badRequest("A vacancy for a single post must have exactly one post");
            vacancies.findByPositionIdAndStatusIn(p.getId(), LIVE_VACANCY).stream().filter(o -> !o.getId().equals(v.getId())).findAny()
                    .ifPresent(o -> { throw ApiException.conflict("Post " + p.getCode() + " already has vacancy " + o.getReferenceNo()); });
        }
        if (r.examWeight() != null) v.setExamWeight(weight(r.examWeight(), "Examination weight"));
        if (r.interviewWeight() != null) v.setInterviewWeight(weight(r.interviewWeight(), "Interview weight"));
        if (v.getExamWeight().add(v.getInterviewWeight()).signum() <= 0)
            throw ApiException.badRequest("At least one of the examination or interview weights must be positive");
        if (settings.getBool("recruitment.enforce-cadre", true) && v.getStatus() != VacancyStatus.FORECAST) {
            long vacant = establishment.cadreSummary(LocalDate.now(), null).stream()
                    .filter(c -> c.designationId().equals(r.designationId()) && c.orgUnitCode().equals(r.orgUnitCode()))
                    .mapToLong(EstablishmentService.CadreRow::vacant).sum();
            long others = vacancies.findByDesignationIdAndOrgUnitCodeAndStatusIn(r.designationId(), r.orgUnitCode(), ACTIVE_VACANCY)
                    .stream().filter(o -> !o.getId().equals(v.getId())).mapToLong(this::remainingPosts).sum();
            long filledHere = v.getId() == null ? 0 : applicants.countByVacancyIdAndStatus(v.getId(), ApplicantStatus.APPOINTED) + v.getFilledPosts();
            // Posts already filled through this vacancy are now "occupied" in the cadre; only the remainder needs headroom.
            if (r.numPosts() - filledHere > vacant - others)
                throw ApiException.rule("CADRE_EXCEEDED", "Only " + Math.max(0, vacant - others)
                        + " post(s) of this designation in this unit are vacant in the approved cadre and not already under recruitment");
        }
        v.setTitle(r.title().trim());
        v.setDesignationId(r.designationId());
        v.setOrgUnitCode(r.orgUnitCode());
        v.setPositionId(r.positionId());
        v.setNumPosts(r.numPosts());
        v.setOpeningDate(r.openingDate());
        v.setClosingDate(r.closingDate());
        v.setDescription(r.description());
        v.setRequirements(r.requirements());
    }

    @Transactional
    public VacancyView changeVacancyStatus(Long id, String status, String remarks) {
        Vacancy v = vacancy(id);
        VacancyStatus to = parse(VacancyStatus.class, status, "vacancy status");
        if (to == null) throw ApiException.badRequest("Status is required");
        VacancyStatus from = v.getStatus();
        if (to == VacancyStatus.ADVERTISED && from == VacancyStatus.OPEN)
            throw ApiException.rule("PUBLISH_REQUIRED", "A vacancy is advertised only by publishing it with an approved SOR (Publisher role)");
        if (to == VacancyStatus.FILLED)
            throw ApiException.rule("INVALID_TRANSITION", "A vacancy is filled automatically when the post is filled by appointment or placement");
        if (!VACANCY_TRANSITIONS.getOrDefault(from, Set.of()).contains(to))
            throw ApiException.rule("INVALID_TRANSITION", "A " + from + " vacancy cannot be changed to " + to);
        if ((to == VacancyStatus.FROZEN || to == VacancyStatus.CANCELLED) && blank(remarks))
            throw ApiException.badRequest("A reason is required to " + (to == VacancyStatus.FROZEN ? "freeze" : "cancel") + " a vacancy");
        if (to == VacancyStatus.CANCELLED && applicants.countByVacancyIdAndStatus(id, ApplicantStatus.APPOINTED) > 0)
            throw ApiException.rule("HAS_APPOINTMENTS", "A vacancy with appointments cannot be cancelled");
        if (from == VacancyStatus.CLOSED && to == VacancyStatus.ADVERTISED && v.getClosingDate() != null && v.getClosingDate().isBefore(LocalDate.now()))
            throw ApiException.rule("CLOSING_DATE_PASSED", "The advertised closing date has passed; publish the vacancy again with new dates");
        if (from == VacancyStatus.FROZEN && to == VacancyStatus.OPEN) {
            if (v.getPositionId() != null) {
                Position p = positions.findById(v.getPositionId()).orElseThrow();
                if (!p.isActive() || !"ACTIVE".equals(p.getPostStatus()))
                    throw ApiException.rule("POST_NOT_ACTIVE", "Post " + p.getCode() + " is " + p.getPostStatus().toLowerCase()
                            + "; restore it through a Cadre Change Request first");
            }
            if (designations.findById(v.getDesignationId()).map(Designation::isDyingCadre).orElse(false))
                throw ApiException.rule("DYING_CADRE", "The designation is a dying cadre: no fresh recruitment (REQ 6.10)");
        }
        if (to == VacancyStatus.OPEN && v.getExpectedDate() != null && v.getExpectedDate().isAfter(LocalDate.now()) && from == VacancyStatus.FORECAST)
            v.setStatusReason("Opened before the expected date" + (blank(remarks) ? "" : ": " + remarks.trim()));
        else if (!blank(remarks)) v.setStatusReason(remarks.trim().length() > 1000 ? remarks.trim().substring(0, 1000) : remarks.trim());
        v.setStatus(to);
        audit.log(AuditAction.UPDATE, "Vacancy", id, "Vacancy " + v.getReferenceNo() + " " + from + " → " + to
                + (blank(remarks) ? "" : ": " + remarks), from.name(), to.name());
        return view(v);
    }

    /** Links a Scheme of Recruitment version of the same designation (REQ 5.4). */
    @Transactional
    public VacancyView linkSor(Long id, Long sorId) {
        Vacancy v = vacancy(id);
        if (!LIVE_VACANCY.contains(v.getStatus()) && v.getStatus() != VacancyStatus.FROZEN)
            throw ApiException.rule("VACANCY_CLOSED", "A filled or cancelled vacancy cannot be changed");
        if (v.getStatus() == VacancyStatus.ADVERTISED)
            throw ApiException.rule("ADVERTISED", "Withdraw the publication before changing the SOR of an advertised vacancy");
        Long before = v.getSorId();
        if (sorId == null) {
            v.setSorId(null);
        } else {
            v.setSorId(checkSor(sorId, v.getDesignationId()).getId());
        }
        audit.log(AuditAction.UPDATE, "Vacancy", id, "SOR of vacancy " + v.getReferenceNo() + " changed", before, v.getSorId());
        return view(v);
    }

    private Sor checkSor(Long sorId, Long designationId) {
        Sor s = sors.findById(sorId).orElseThrow(() -> ApiException.notFound("SOR", sorId));
        if (!s.getDesignationId().equals(designationId))
            throw ApiException.rule("SOR_MISMATCH", "SOR " + s.getSorNo() + " is for a different designation");
        if (s.getStatus() == SorStatus.REJECTED || s.getStatus() == SorStatus.SUPERSEDED)
            throw ApiException.rule("SOR_NOT_USABLE", "SOR " + s.getSorNo() + " v" + s.getVersionNo() + " is " + s.getStatus().name().toLowerCase());
        return s;
    }

    public Optional<Sor> currentApprovedSor(Long designationId) {
        return sors.findByDesignationIdAndStatusOrderByApprovedAtDesc(designationId, SorStatus.APPROVED).stream().findFirst();
    }

    /** Marks the vacancy FILLED once all posts are filled by appointment or placement; alerts stop (REQ 5.7). */
    public void refreshFilled(Vacancy v) {
        if (v.getStatus() != VacancyStatus.FILLED && remainingPosts(v) <= 0) {
            VacancyStatus from = v.getStatus();
            v.setStatus(VacancyStatus.FILLED);
            v.setFilledOn(LocalDate.now());
            audit.log(AuditAction.UPDATE, "Vacancy", v.getId(), "Vacancy " + v.getReferenceNo() + " filled", from.name(), "FILLED");
        }
    }

    public long remainingPosts(Vacancy v) {
        return Math.max(0, v.getNumPosts() - v.getFilledPosts() - applicants.countByVacancyIdAndStatus(v.getId(), ApplicantStatus.APPOINTED));
    }

    public BigDecimal defaultExamWeight() {
        return settings.getDecimal("recruitment.default-exam-weight", BigDecimal.valueOf(60));
    }

    public BigDecimal defaultInterviewWeight() {
        return settings.getDecimal("recruitment.default-interview-weight", BigDecimal.valueOf(40));
    }

    // ------------------------------------------------------------------ views

    private record ViewContext(Map<Long, Designation> designations, Map<String, String> units, Map<Long, String> positionCodes,
                               Map<Long, Sor> sors, Map<String, String> triggers, Map<Long, Map<String, Long>> applicants,
                               Map<Long, String> employeeNos) {
    }

    private ViewContext context(Collection<Vacancy> rows) {
        Map<Long, Designation> des = designations.findAll().stream().collect(Collectors.toMap(Designation::getId, d -> d));
        Set<Long> posIds = rows.stream().map(Vacancy::getPositionId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> pos = posIds.isEmpty() ? Map.of() : positions.findAllById(posIds).stream()
                .collect(Collectors.toMap(Position::getId, Position::getCode));
        Set<Long> sorIds = rows.stream().map(Vacancy::getSorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Sor> sorMap = sorIds.isEmpty() ? Map.of() : sors.findAllById(sorIds).stream().collect(Collectors.toMap(Sor::getId, s -> s));
        Map<String, String> trig = triggers.findAll().stream().collect(Collectors.toMap(TriggerConfig::getCode, TriggerConfig::getName));
        Map<Long, Map<String, Long>> apps = new HashMap<>();
        if (rows.size() == 1) {
            Vacancy only = rows.iterator().next();
            apps.put(only.getId(), new TreeMap<>(applicants.findByVacancyIdOrderByIdAsc(only.getId()).stream()
                    .collect(Collectors.groupingBy(a -> a.getStatus().name(), Collectors.counting()))));
        } else {
            for (Object[] row : applicants.countByVacancyAndStatus())
                apps.computeIfAbsent((Long) row[0], k -> new TreeMap<>()).put(String.valueOf(row[1]), (Long) row[2]);
        }
        Set<Long> empIds = rows.stream().map(Vacancy::getVacatedByEmployeeId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> emps = new HashMap<>();
        employeeLookup.briefs(empIds).forEach((k, b) -> emps.put(k, b.employeeNo()));
        return new ViewContext(des, unitNames(), pos, sorMap, trig, apps, emps);
    }

    public VacancyView view(Vacancy v) {
        return view(v, context(List.of(v)));
    }

    private VacancyView view(Vacancy v, ViewContext c) {
        Map<String, Long> byStatus = c.applicants().getOrDefault(v.getId(), Map.of());
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        Designation d = c.designations().get(v.getDesignationId());
        Sor s = v.getSorId() == null ? null : c.sors().get(v.getSorId());
        return new VacancyView(v.getId(), v.getReferenceNo(), v.getTitle(), v.getDesignationId(), d == null ? null : d.getName(),
                d == null ? null : d.getGrade(), v.getOrgUnitCode(), c.units().getOrDefault(v.getOrgUnitCode(), v.getOrgUnitCode()),
                v.getPositionId(), v.getPositionId() == null ? null : c.positionCodes().get(v.getPositionId()), v.getNumPosts(),
                v.getStatus().name(), v.getTriggerCode(), c.triggers().getOrDefault(v.getTriggerCode(), v.getTriggerCode()),
                v.getSource().name(), v.getExpectedDate(), v.getOpeningDate(), v.getClosingDate(), v.getDescription(),
                v.getRequirements(), v.getExamWeight(), v.getInterviewWeight(), v.getSorId(),
                s == null ? null : s.getSorNo() + " v" + s.getVersionNo(), s == null ? null : s.getStatus().name(),
                v.getVacatedByEmployeeId(), v.getVacatedByEmployeeId() == null ? null : c.employeeNos().get(v.getVacatedByEmployeeId()),
                v.getCadreChangeId(), v.getFilledPosts(), v.getFilledOn(), v.getStatusReason(), d != null && d.isDyingCadre(),
                byStatus, total, byStatus.getOrDefault("APPOINTED", 0L), v.getCreatedAt(), v.getCreatedBy());
    }

    /** Next free reference VAC-yyyy-nnn. */
    public String nextReference() {
        int year = LocalDate.now().getYear();
        long n = vacancies.count() + 1;
        String ref;
        do {
            ref = String.format("VAC-%d-%03d", year, n++);
        } while (vacancies.existsByReferenceNo(ref));
        return ref;
    }

    private static String describe(Vacancy v) {
        return v.getTitle() + " | des " + v.getDesignationId() + " | " + v.getOrgUnitCode() + " | post " + v.getPositionId()
                + " | posts " + v.getNumPosts() + " | expected " + v.getExpectedDate() + " | " + v.getOpeningDate() + "→" + v.getClosingDate()
                + " | weights " + v.getExamWeight() + "/" + v.getInterviewWeight();
    }

    public Vacancy vacancy(Long id) {
        return vacancies.findById(id).orElseThrow(() -> ApiException.notFound("Vacancy", id));
    }

    // ===================================================================== announcements (FR-REC-003)

    public record AnnouncementRequest(Long vacancyId, String title, String content, LocalDate publishDate, LocalDate closingDate) {
    }

    @Transactional(readOnly = true)
    public List<Announcement> announcements() {
        return announcements.findAllByOrderByPublishDateDescIdDesc();
    }

    /** Published, current announcements for all staff. */
    @Transactional(readOnly = true)
    public List<Announcement> publishedAnnouncements() {
        LocalDate today = LocalDate.now();
        return announcements.findByPublishedTrueOrderByPublishDateDesc().stream()
                .filter(a -> !a.getPublishDate().isAfter(today))
                .filter(a -> a.getClosingDate() == null || !a.getClosingDate().isBefore(today))
                .toList();
    }

    @Transactional
    public Announcement saveAnnouncement(Long id, AnnouncementRequest r) {
        Announcement a = id == null ? new Announcement() : announcement(id);
        if (a.isPublished()) throw ApiException.rule("PUBLISHED", "Unpublish the announcement before editing it");
        require(r.title(), "Title");
        require(r.content(), "Content");
        if (r.publishDate() == null) throw ApiException.badRequest("Publish date is required");
        if (r.closingDate() != null && r.closingDate().isBefore(r.publishDate()))
            throw ApiException.badRequest("Closing date cannot be before the publish date");
        if (r.vacancyId() != null) vacancy(r.vacancyId());
        a.setVacancyId(r.vacancyId());
        a.setTitle(r.title().trim());
        a.setContent(r.content());
        a.setPublishDate(r.publishDate());
        a.setClosingDate(r.closingDate());
        announcements.save(a);
        audit.log(id == null ? AuditAction.CREATE : AuditAction.UPDATE, "Announcement", a.getId(), "Announcement saved: " + a.getTitle());
        return a;
    }

    /** Publishing is restricted to REC_MANAGE holders at the controller ("publish where authorized", FR-REC-003). */
    @Transactional
    public Announcement publish(Long id, boolean publish) {
        Announcement a = announcement(id);
        if (publish && a.getVacancyId() != null) {
            Vacancy v = vacancy(a.getVacancyId());
            if (v.getStatus() != VacancyStatus.ADVERTISED)
                throw ApiException.rule("VACANCY_NOT_OPEN", "Vacancy " + v.getReferenceNo() + " must be advertised (published with an approved SOR) before its announcement is published");
        }
        a.setPublished(publish);
        a.setPublishedAt(publish ? Instant.now() : null);
        a.setPublishedBy(publish ? CurrentUser.username() : null);
        audit.log(AuditAction.UPDATE, "Announcement", id, (publish ? "Published: " : "Unpublished: ") + a.getTitle());
        return a;
    }

    private Announcement announcement(Long id) {
        return announcements.findById(id).orElseThrow(() -> ApiException.notFound("Announcement", id));
    }

    // ===================================================================== applicants (FR-REC-004, -005)

    public record ApplicantRequest(Long vacancyId, String title, String fullName, String nameWithInitials, String nic,
                                   LocalDate dateOfBirth, String gender, String civilStatus, String email, String mobile,
                                   String address, String qualifications, LocalDate receivedDate, String remarks) {
    }

    public record ApplicantListItem(Long id, String applicationNo, Long vacancyId, String vacancyRef, String fullName,
                                    String nameWithInitials, String nic, String email, String mobile, LocalDate receivedDate,
                                    String status, Long employeeId) {
    }

    @Transactional(readOnly = true)
    public List<ApplicantListItem> searchApplicants(Long vacancyId, String status, String q) {
        ApplicantStatus s = parse(ApplicantStatus.class, status, "applicant status");
        Map<Long, String> refs = vacancies.findAll().stream().collect(Collectors.toMap(Vacancy::getId, Vacancy::getReferenceNo));
        audit.log(AuditAction.SEARCH, "Applicant", vacancyId, "Applicant search" + (blank(q) ? "" : ": " + q));
        return applicants.search(vacancyId, s, blank(q) ? null : q.trim()).stream()
                .map(a -> new ApplicantListItem(a.getId(), a.getApplicationNo(), a.getVacancyId(), refs.get(a.getVacancyId()),
                        a.getFullName(), a.getNameWithInitials(), a.getNic(), a.getEmail(), a.getMobile(), a.getReceivedDate(),
                        a.getStatus().name(), a.getEmployeeId()))
                .toList();
    }

    public record ExamResultView(Long examId, String examTitle, String examType, LocalDate examDate, BigDecimal maxMarks,
                                 BigDecimal passMarks, BigDecimal marks, boolean absent, String remarks) {
    }

    public record ApplicantDetail(Applicant applicant, VacancyView vacancy, List<ApplicantStatusChange> history,
                                  List<ApplicantDocument> documents, List<ExamResultView> examResults,
                                  List<Interview> interviews, SelectionScoring.Score score) {
    }

    @Transactional(readOnly = true)
    public ApplicantDetail applicantDetail(Long id) {
        Applicant a = applicant(id);
        Vacancy v = vacancy(a.getVacancyId());
        Map<Long, ExamResult> mine = examResults.findByApplicantId(id).stream()
                .collect(Collectors.toMap(ExamResult::getExamId, r -> r, (x, y) -> x));
        List<ExamResultView> results = exams.findByVacancyIdOrderByExamDateAscIdAsc(v.getId()).stream()
                .filter(e -> e.getStatus() != RecruitmentExam.Status.CANCELLED)
                .map(e -> {
                    ExamResult r = mine.get(e.getId());
                    return new ExamResultView(e.getId(), e.getTitle(), e.getExamType().name(), e.getExamDate(), e.getMaxMarks(),
                            e.getPassMarks(), r == null ? null : r.getMarks(), r != null && r.isAbsent(), r == null ? null : r.getRemarks());
                }).toList();
        audit.log(AuditAction.VIEW, "Applicant", id, "Viewed applicant " + a.getApplicationNo());
        return new ApplicantDetail(a, view(v), statusChanges.findByApplicantIdOrderByIdAsc(id),
                documents.findByApplicantIdOrderByIdDesc(id), results,
                interviews.findByApplicantIdOrderByInterviewDateAsc(id), score(a, v));
    }

    @Transactional
    public Applicant createApplicant(ApplicantRequest r) {
        if (r.vacancyId() == null) throw ApiException.badRequest("Vacancy is required");
        Vacancy v = vacancy(r.vacancyId());
        if (v.getStatus() != VacancyStatus.ADVERTISED && v.getStatus() != VacancyStatus.CLOSED)
            throw ApiException.rule("VACANCY_NOT_OPEN", "Applications can only be recorded for advertised (or recently closed) vacancies (REQ 5.6)");
        Applicant a = new Applicant();
        a.setVacancyId(v.getId());
        applyApplicant(a, r);
        if (applicants.existsByVacancyIdAndNic(v.getId(), a.getNic()))
            throw ApiException.conflict("An application with NIC " + a.getNic() + " is already recorded for this vacancy");
        a.setApplicationNo(nextApplicationNo(v));
        a.setStatus(ApplicantStatus.RECEIVED);
        a.setSource("HR_ENTERED");
        applicants.save(a);
        recordStatus(a, null, ApplicantStatus.RECEIVED, "Application recorded");
        audit.log(AuditAction.CREATE, "Applicant", a.getId(), "Application " + a.getApplicationNo() + " recorded for " + v.getReferenceNo());
        return a;
    }

    @Transactional
    public Applicant updateApplicant(Long id, ApplicantRequest r) {
        Applicant a = applicant(id);
        if (a.getStatus() == ApplicantStatus.APPOINTED) throw ApiException.rule("APPOINTED", "An appointed applicant's record is now the employee file");
        String before = a.getFullName() + " | " + a.getNic() + " | " + a.getEmail() + " | " + a.getMobile();
        applyApplicant(a, r);
        applicants.findByVacancyIdOrderByIdAsc(a.getVacancyId()).stream()
                .filter(o -> !o.getId().equals(id) && o.getNic().equals(a.getNic())).findAny()
                .ifPresent(o -> { throw ApiException.conflict("Another application for this vacancy has NIC " + a.getNic()); });
        audit.log(AuditAction.UPDATE, "Applicant", id, "Applicant " + a.getApplicationNo() + " updated", before,
                a.getFullName() + " | " + a.getNic() + " | " + a.getEmail() + " | " + a.getMobile());
        return a;
    }

    private void applyApplicant(Applicant a, ApplicantRequest r) {
        require(r.fullName(), "Full name");
        require(r.nameWithInitials(), "Name with initials");
        require(r.nic(), "NIC");
        String nic = r.nic().trim().toUpperCase();
        if (!NIC.matcher(nic).matches()) throw ApiException.rule("INVALID_NIC", "NIC must be 9 digits followed by V/X, or 12 digits");
        if (r.dateOfBirth() == null) throw ApiException.badRequest("Date of birth is required");
        if (r.dateOfBirth().isAfter(LocalDate.now().minusYears(16))) throw ApiException.rule("INVALID_DOB", "Date of birth is not plausible");
        if (!blank(r.email()) && !EMAIL.matcher(r.email().trim()).matches()) throw ApiException.badRequest("E-mail address is not valid");
        masterData.requireActive(MasterDataCategory.TITLE, r.title(), "Title");
        masterData.requireActive(MasterDataCategory.GENDER, r.gender(), "Gender");
        masterData.requireActive(MasterDataCategory.CIVIL_STATUS, r.civilStatus(), "Civil status");
        a.setTitle(blankToNull(r.title()));
        a.setFullName(r.fullName().trim());
        a.setNameWithInitials(r.nameWithInitials().trim());
        a.setNic(nic);
        a.setDateOfBirth(r.dateOfBirth());
        a.setGender(blankToNull(r.gender()));
        a.setCivilStatus(blankToNull(r.civilStatus()));
        a.setEmail(blankToNull(r.email()));
        a.setMobile(blankToNull(r.mobile()));
        a.setAddress(blankToNull(r.address()));
        a.setQualifications(r.qualifications());
        a.setReceivedDate(r.receivedDate() == null ? LocalDate.now() : r.receivedDate());
        if (a.getReceivedDate().isAfter(LocalDate.now())) throw ApiException.badRequest("Received date cannot be in the future");
        a.setRemarks(r.remarks());
    }

    @Transactional
    public Applicant changeApplicantStatus(Long id, String status, String remarks) {
        Applicant a = applicant(id);
        ApplicantStatus to = parse(ApplicantStatus.class, status, "applicant status");
        if (to == null) throw ApiException.badRequest("Status is required");
        if (!MANUAL_TRANSITIONS.getOrDefault(a.getStatus(), Set.of()).contains(to))
            throw ApiException.rule("INVALID_TRANSITION", "Status " + a.getStatus() + " cannot be changed to " + to
                    + (to == ApplicantStatus.SELECTED || to == ApplicantStatus.NOT_SELECTED || to == ApplicantStatus.RECOMMENDED
                    ? " manually; selections are decided through the approved selection list" : ""));
        if ((to == ApplicantStatus.REJECTED || to == ApplicantStatus.WITHDRAWN) && blank(remarks))
            throw ApiException.badRequest("A reason is required");
        ApplicantStatus from = a.getStatus();
        a.setStatus(to);
        recordStatus(a, from, to, remarks);
        audit.log(AuditAction.UPDATE, "Applicant", id, "Applicant " + a.getApplicationNo() + " " + from + " → " + to, from.name(), to.name());
        return a;
    }

    void recordStatus(Applicant a, ApplicantStatus from, ApplicantStatus to, String remarks) {
        ApplicantStatusChange c = new ApplicantStatusChange();
        c.setApplicantId(a.getId());
        c.setFromStatus(from == null ? null : from.name());
        c.setToStatus(to.name());
        c.setChangedAt(Instant.now());
        c.setChangedBy(CurrentUser.username());
        c.setRemarks(remarks == null ? null : remarks.length() > 1000 ? remarks.substring(0, 1000) : remarks);
        statusChanges.save(c);
    }

    @Transactional
    public ApplicantDocument uploadDocument(Long applicantId, String documentType, String title, MultipartFile file) {
        Applicant a = applicant(applicantId);
        require(documentType, "Document type");
        require(title, "Title");
        StoredFile f = files.store(file, "RECRUITMENT");
        ApplicantDocument d = new ApplicantDocument();
        d.setApplicantId(a.getId());
        d.setDocumentType(documentType.trim().length() > 60 ? documentType.trim().substring(0, 60) : documentType.trim());
        d.setTitle(title.trim());
        d.setFileId(f.getId());
        d.setFileName(f.getOriginalName());
        documents.save(d);
        audit.log(AuditAction.UPLOAD, "ApplicantDocument", d.getId(), "Document '" + title + "' filed for applicant " + a.getApplicationNo());
        return d;
    }

    @Transactional(readOnly = true)
    public ApplicantDocument documentForDownload(Long applicantId, Long docId) {
        ApplicantDocument d = documents.findById(docId).filter(x -> x.getApplicantId().equals(applicantId))
                .orElseThrow(() -> ApiException.notFound("Document", docId));
        audit.log(AuditAction.DOWNLOAD, "ApplicantDocument", docId, "Downloaded '" + d.getTitle() + "' of applicant " + applicantId);
        return d;
    }

    private String nextApplicationNo(Vacancy v) {
        long n = applicants.findByVacancyIdOrderByIdAsc(v.getId()).size() + 1;
        String no = v.getReferenceNo() + "-A" + String.format("%04d", n);
        return no.length() > 40 ? "APP-" + v.getId() + "-" + n : no;
    }

    Applicant applicant(Long id) {
        return applicants.findById(id).orElseThrow(() -> ApiException.notFound("Applicant", id));
    }

    // ===================================================================== examinations (FR-REC-006)

    public record ExamRequest(Long vacancyId, String title, String examType, LocalDate examDate, LocalTime startTime,
                              String venue, BigDecimal maxMarks, BigDecimal passMarks, String status) {
    }

    public record ResultEntry(Long applicantId, BigDecimal marks, Boolean absent, String remarks) {
    }

    public record ExamResultRow(Long applicantId, String applicationNo, String name, String applicantStatus,
                                BigDecimal marks, boolean absent, String remarks, Boolean passed) {
    }

    @Transactional(readOnly = true)
    public List<RecruitmentExam> exams(Long vacancyId) {
        return exams.findByVacancyIdOrderByExamDateAscIdAsc(vacancyId);
    }

    @Transactional
    public RecruitmentExam saveExam(Long id, ExamRequest r) {
        RecruitmentExam e = id == null ? new RecruitmentExam() : exam(id);
        if (id == null) {
            if (r.vacancyId() == null) throw ApiException.badRequest("Vacancy is required");
            Vacancy v = vacancy(r.vacancyId());
            if (!ACTIVE_VACANCY.contains(v.getStatus())) throw ApiException.rule("VACANCY_CLOSED", "The vacancy is no longer active");
            e.setVacancyId(v.getId());
        }
        require(r.title(), "Title");
        RecruitmentExam.ExamType type = parse(RecruitmentExam.ExamType.class, r.examType(), "examination type");
        if (type == null) throw ApiException.badRequest("Examination type is required");
        if (r.examDate() == null) throw ApiException.badRequest("Examination date is required");
        if (r.maxMarks() == null || r.maxMarks().signum() <= 0) throw ApiException.badRequest("Maximum marks must be positive");
        if (r.passMarks() != null && (r.passMarks().signum() < 0 || r.passMarks().compareTo(r.maxMarks()) > 0))
            throw ApiException.badRequest("Pass marks must be between 0 and the maximum marks");
        if (id != null) {
            BigDecimal highest = examResults.findByExamId(id).stream().map(ExamResult::getMarks).filter(Objects::nonNull)
                    .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            if (r.maxMarks().compareTo(highest) < 0) throw ApiException.rule("MARKS_EXCEED", "Recorded marks exceed the new maximum");
        }
        RecruitmentExam.Status st = parse(RecruitmentExam.Status.class, r.status(), "examination status");
        e.setTitle(r.title().trim());
        e.setExamType(type);
        e.setExamDate(r.examDate());
        e.setStartTime(r.startTime());
        e.setVenue(blankToNull(r.venue()));
        e.setMaxMarks(r.maxMarks());
        e.setPassMarks(r.passMarks());
        if (st != null) e.setStatus(st);
        exams.save(e);
        audit.log(id == null ? AuditAction.CREATE : AuditAction.UPDATE, "RecruitmentExam", e.getId(),
                "Examination '" + e.getTitle() + "' on " + e.getExamDate() + " (" + e.getStatus() + ")");
        return e;
    }

    @Transactional(readOnly = true)
    public List<ExamResultRow> examResults(Long examId) {
        RecruitmentExam e = exam(examId);
        Map<Long, ExamResult> byApplicant = examResults.findByExamId(examId).stream()
                .collect(Collectors.toMap(ExamResult::getApplicantId, r -> r, (x, y) -> x));
        return applicants.findByVacancyIdOrderByIdAsc(e.getVacancyId()).stream()
                .filter(a -> byApplicant.containsKey(a.getId()) || scoreable(a.getStatus()))
                .map(a -> {
                    ExamResult r = byApplicant.get(a.getId());
                    BigDecimal m = r == null ? null : r.getMarks();
                    Boolean passed = r == null || e.getPassMarks() == null ? null
                            : !r.isAbsent() && m != null && m.compareTo(e.getPassMarks()) >= 0;
                    return new ExamResultRow(a.getId(), a.getApplicationNo(), a.getNameWithInitials(), a.getStatus().name(),
                            m, r != null && r.isAbsent(), r == null ? null : r.getRemarks(), passed);
                }).toList();
    }

    @Transactional
    public List<ExamResultRow> recordResults(Long examId, List<ResultEntry> entries) {
        RecruitmentExam e = exam(examId);
        if (e.getStatus() == RecruitmentExam.Status.CANCELLED) throw ApiException.rule("EXAM_CANCELLED", "The examination was cancelled");
        if (entries == null || entries.isEmpty()) throw ApiException.badRequest("No results were supplied");
        int n = 0;
        for (ResultEntry r : entries) {
            Applicant a = applicant(r.applicantId());
            if (!a.getVacancyId().equals(e.getVacancyId())) throw ApiException.badRequest("Applicant " + a.getApplicationNo() + " did not apply for this vacancy");
            if (!scoreable(a.getStatus())) throw ApiException.rule("NOT_ELIGIBLE", "Applicant " + a.getApplicationNo() + " is " + a.getStatus());
            boolean absent = Boolean.TRUE.equals(r.absent());
            if (!absent && r.marks() == null) continue; // not yet marked
            if (r.marks() != null && (r.marks().signum() < 0 || r.marks().compareTo(e.getMaxMarks()) > 0))
                throw ApiException.badRequest("Marks for " + a.getApplicationNo() + " must be between 0 and " + e.getMaxMarks());
            ExamResult res = examResults.findByExamIdAndApplicantId(examId, a.getId()).orElseGet(ExamResult::new);
            String before = res.getId() == null ? null : res.isAbsent() ? "absent" : String.valueOf(res.getMarks());
            res.setExamId(examId);
            res.setApplicantId(a.getId());
            res.setAbsent(absent);
            res.setMarks(absent ? null : r.marks());
            res.setRemarks(blankToNull(r.remarks()));
            examResults.save(res);
            String after = absent ? "absent" : String.valueOf(r.marks());
            if (!after.equals(before)) {
                audit.log(before == null ? AuditAction.CREATE : AuditAction.UPDATE, "ExamResult", res.getId(),
                        "Result of " + a.getApplicationNo() + " in '" + e.getTitle() + "'", before, after);
            }
            n++;
        }
        if (n > 0 && e.getStatus() == RecruitmentExam.Status.SCHEDULED && !e.getExamDate().isAfter(LocalDate.now()))
            e.setStatus(RecruitmentExam.Status.COMPLETED);
        return examResults(examId);
    }

    private RecruitmentExam exam(Long id) {
        return exams.findById(id).orElseThrow(() -> ApiException.notFound("Examination", id));
    }

    static boolean scoreable(ApplicantStatus s) {
        return s == ApplicantStatus.SHORTLISTED || s == ApplicantStatus.RECOMMENDED;
    }

    // ===================================================================== interviews (FR-REC-006)

    public record InterviewRequest(Long applicantId, LocalDate interviewDate, LocalTime interviewTime, String venue,
                                   String panel, BigDecimal maxMarks) {
    }

    public record InterviewResultRequest(String status, BigDecimal marks, String remarks) {
    }

    public record InterviewRow(Long id, Long applicantId, String applicationNo, String applicantName, LocalDate interviewDate,
                               LocalTime interviewTime, String venue, String panel, BigDecimal maxMarks, BigDecimal marks,
                               String remarks, String status) {
    }

    @Transactional(readOnly = true)
    public List<InterviewRow> interviews(Long vacancyId) {
        Map<Long, Applicant> apps = applicants.findByVacancyIdOrderByIdAsc(vacancyId).stream()
                .collect(Collectors.toMap(Applicant::getId, a -> a));
        return interviews.findByVacancyIdOrderByInterviewDateAscInterviewTimeAsc(vacancyId).stream().map(i -> {
            Applicant a = apps.get(i.getApplicantId());
            return new InterviewRow(i.getId(), i.getApplicantId(), a == null ? null : a.getApplicationNo(),
                    a == null ? null : a.getNameWithInitials(), i.getInterviewDate(), i.getInterviewTime(), i.getVenue(),
                    i.getPanel(), i.getMaxMarks(), i.getMarks(), i.getRemarks(), i.getStatus().name());
        }).toList();
    }

    @Transactional
    public Interview scheduleInterview(InterviewRequest r) {
        if (r.applicantId() == null) throw ApiException.badRequest("Applicant is required");
        Applicant a = applicant(r.applicantId());
        if (!scoreable(a.getStatus())) throw ApiException.rule("NOT_ELIGIBLE", "Only shortlisted applicants can be called for interview");
        if (r.interviewDate() == null) throw ApiException.badRequest("Interview date is required");
        require(r.panel(), "Interview panel");
        if (r.maxMarks() == null || r.maxMarks().signum() <= 0) throw ApiException.badRequest("Maximum marks must be positive");
        Interview i = new Interview();
        i.setVacancyId(a.getVacancyId());
        i.setApplicantId(a.getId());
        i.setInterviewDate(r.interviewDate());
        i.setInterviewTime(r.interviewTime());
        i.setVenue(blankToNull(r.venue()));
        i.setPanel(r.panel().trim());
        i.setMaxMarks(r.maxMarks());
        i.setStatus(Interview.Status.SCHEDULED);
        interviews.save(i);
        audit.log(AuditAction.CREATE, "Interview", i.getId(), "Interview scheduled for " + a.getApplicationNo() + " on " + r.interviewDate());
        return i;
    }

    @Transactional
    public Interview recordInterview(Long id, InterviewResultRequest r) {
        Interview i = interviews.findById(id).orElseThrow(() -> ApiException.notFound("Interview", id));
        Interview.Status st = parse(Interview.Status.class, r.status(), "interview status");
        if (st == null) st = Interview.Status.COMPLETED;
        if (st == Interview.Status.COMPLETED) {
            if (r.marks() == null) throw ApiException.badRequest("Marks are required for a completed interview");
            if (r.marks().signum() < 0 || r.marks().compareTo(i.getMaxMarks()) > 0)
                throw ApiException.badRequest("Marks must be between 0 and " + i.getMaxMarks());
        }
        Applicant a = applicant(i.getApplicantId());
        if (!scoreable(a.getStatus()) && st != Interview.Status.CANCELLED)
            throw ApiException.rule("NOT_ELIGIBLE", "Applicant " + a.getApplicationNo() + " is " + a.getStatus());
        String before = i.getStatus() + " " + i.getMarks();
        i.setStatus(st);
        i.setMarks(st == Interview.Status.COMPLETED ? r.marks() : null);
        i.setRemarks(r.remarks());
        audit.log(AuditAction.UPDATE, "Interview", id, "Interview of " + a.getApplicationNo() + " recorded", before, st + " " + i.getMarks());
        return i;
    }

    // ===================================================================== scoring

    SelectionScoring.Score score(Applicant a, Vacancy v) {
        Map<Long, ExamResult> mine = examResults.findByApplicantId(a.getId()).stream()
                .collect(Collectors.toMap(ExamResult::getExamId, r -> r, (x, y) -> x));
        List<SelectionScoring.Mark> exm = new ArrayList<>();
        for (RecruitmentExam e : exams.findByVacancyIdOrderByExamDateAscIdAsc(v.getId())) {
            if (e.getStatus() == RecruitmentExam.Status.CANCELLED) continue;
            ExamResult r = mine.get(e.getId());
            if (r != null) exm.add(new SelectionScoring.Mark(r.getMarks(), e.getMaxMarks(), r.isAbsent()));
        }
        List<SelectionScoring.Mark> inm = interviews.findByApplicantIdOrderByInterviewDateAsc(a.getId()).stream()
                .filter(i -> i.getStatus() == Interview.Status.COMPLETED || i.getStatus() == Interview.Status.ABSENT)
                .map(i -> new SelectionScoring.Mark(i.getMarks(), i.getMaxMarks(), i.getStatus() == Interview.Status.ABSENT))
                .toList();
        return SelectionScoring.score(exm, inm, v.getExamWeight(), v.getInterviewWeight());
    }

    // ===================================================================== helpers

    private Map<Long, String> designationNames() {
        return designations.findAll().stream().collect(Collectors.toMap(Designation::getId, Designation::getName));
    }

    private Map<String, String> unitNames() {
        return establishment.orgUnits(LocalDate.now()).stream().collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getName, (a, b) -> a));
    }

    private static BigDecimal weight(BigDecimal w, String label) {
        if (w.signum() < 0 || w.compareTo(BigDecimal.valueOf(100)) > 0) throw ApiException.badRequest(label + " must be between 0 and 100");
        return w;
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown " + label + ": " + value);
        }
    }

    static void require(String v, String label) {
        if (v == null || v.isBlank()) throw ApiException.badRequest(label + " is required");
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
