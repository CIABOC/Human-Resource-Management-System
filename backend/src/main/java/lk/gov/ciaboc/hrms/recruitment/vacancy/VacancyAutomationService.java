package lk.gov.ciaboc.hrms.recruitment.vacancy;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.contracts.EstablishmentEvents;
import lk.gov.ciaboc.hrms.establishment.Designation;
import lk.gov.ciaboc.hrms.establishment.DesignationRepository;
import lk.gov.ciaboc.hrms.establishment.EstablishmentService;
import lk.gov.ciaboc.hrms.establishment.OrgUnit;
import lk.gov.ciaboc.hrms.establishment.Position;
import lk.gov.ciaboc.hrms.establishment.PositionRepository;
import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.Vacancy;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.VacancySource;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentEntities.VacancyStatus;
import lk.gov.ciaboc.hrms.recruitment.RecruitmentService;
import lk.gov.ciaboc.hrms.recruitment.VacancyRepository;
import lk.gov.ciaboc.hrms.recruitment.vacancy.VacancyEntities.TriggerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;

/**
 * Automatic vacancy register (spec v2.1 REQ 5.1, 5.7, 5.8). Vacancies are created when a post becomes (or will become)
 * unfilled — retirement, resignation, end of contract / secondment / service period, death, promotion or transfer out,
 * cadre increase — according to the configurable {@code rec_vacancy_trigger} rows. Cadre reductions record FROZEN
 * entries. Dying-cadre designations never get a vacancy (REQ 6.10). Placements into a vacant post fill the vacancy.
 * <p>
 * Event handlers run BEFORE_COMMIT so the vacancy is created atomically with the change that caused it; alerts are
 * dispatched after commit by {@link VacancyAlertService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VacancyAutomationService {
    /** Whitelisted employee date columns for forecasts (configured by rec_vacancy_trigger.forecast_date_field). */
    private static final Map<String, String> DATE_COLUMNS = Map.of("RETIREMENT_DATE", "retirement_date",
            "SECONDMENT_END_DATE", "secondment_end_date");
    private static final List<String> IN_SERVICE = List.of("ACTIVE", "ON_LEAVE", "SECONDED", "PROBATION");

    private final VacancyRepository vacancies;
    private final RecruitmentService recruitment;
    private final TriggerConfigRepository triggers;
    private final DesignationRepository designations;
    private final PositionRepository positions;
    private final EstablishmentService establishment;
    private final SettingsService settings;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    // ===================================================================== domain events

    /** REQ 5.8 / 6.5: increases create Forecast (future effective date) or Open vacancies; reductions create Frozen entries. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onCadreChanged(EstablishmentEvents.CadreChanged e) {
        Designation des = designations.findById(e.designationId()).orElse(null);
        if (des == null) return;
        String unit = e.orgUnitCode();
        LocalDate d = e.effectiveDate() == null ? LocalDate.now() : e.effectiveDate();
        String ccr = "CCR #" + e.changeId() + " (" + e.oldQty() + " → " + e.newQty() + ")";
        if (e.newQty() > e.oldQty()) {
            trigger(t -> "INCREASE".equals(t.getCadreEffect())).ifPresent(t -> {
                List<String> codes = e.newPositionCodes() == null ? List.of() : e.newPositionCodes();
                if (codes.isEmpty()) {
                    create(t, des, unit, null, e.newQty() - e.oldQty(), d, null, e.changeId(), "CCR:" + e.changeId(), false, ccr);
                } else {
                    for (String code : codes) {
                        Long posId = positions.findByCode(code).map(Position::getId).orElse(null);
                        create(t, des, unit, posId, 1, d, null, e.changeId(), "CCR:" + e.changeId() + ":" + code, false, ccr);
                    }
                }
            });
        }
        // Removed vacant posts: any live vacancy on the post is frozen (alerts stop), otherwise a Frozen entry is recorded.
        Optional<TriggerConfig> reduction = trigger(t -> "REDUCTION".equals(t.getCadreEffect()));
        for (String code : Optional.ofNullable(e.frozenPositionCodes()).orElse(List.of())) {
            Position p = positions.findByCode(code).orElse(null);
            if (p == null) continue;
            List<Vacancy> live = vacancies.findByPositionIdAndStatusIn(p.getId(), RecruitmentService.LIVE_VACANCY);
            live.forEach(v -> freeze(v, "Post " + code + " removed from the cadre by " + ccr));
            if (live.isEmpty()) reduction.ifPresent(t ->
                    create(t, des, unit, p.getId(), 1, d, null, e.changeId(), "CCR-FROZEN:" + e.changeId() + ":" + code, true,
                            "Post " + code + " removed from the cadre by " + ccr));
        }
        // Filled excess "to be abolished on vacation": a forecast for the post will not be recruited for.
        for (String code : Optional.ofNullable(e.toBeAbolishedPositionCodes()).orElse(List.of())) {
            positions.findByCode(code).ifPresent(p -> vacancies.findByPositionIdAndStatusIn(p.getId(), RecruitmentService.LIVE_VACANCY)
                    .forEach(v -> freeze(v, "Post " + code + " is to be abolished on vacation (" + ccr + ")")));
        }
    }

    /** REQ 5.1: retirement, resignation, end of secondment/contract/service, death, transfer out. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onEmployeeSeparated(EstablishmentEvents.EmployeeSeparated e) {
        if (e.designationId() == null || e.orgUnitCode() == null) return;
        Optional<TriggerConfig> t = trigger(c -> contains(c.getSeparationReasons(), e.separationReason()));
        if (t.isEmpty()) {
            audit.log(AuditAction.PROCESS, "Vacancy", null, "No vacancy trigger is enabled for separation reason "
                    + e.separationReason() + " of employee " + e.employeeId());
            return;
        }
        vacate(t.get(), e.employeeId(), e.designationId(), e.orgUnitCode(), e.positionId(),
                e.effectiveDate() == null ? LocalDate.now() : e.effectiveDate(), false,
                "Separation (" + e.status() + ", " + e.separationReason() + ")");
    }

    /** REQ 5.1 promotion / transfer out of a post, and REQ 5.7 placement into a vacant post. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onPlacementChanged(EstablishmentEvents.EmployeePlacementChanged e) {
        LocalDate d = e.effectiveDate() == null ? LocalDate.now() : e.effectiveDate();
        if (!e.acting() || settings.getBool("vacancy.fill-on-acting", false)) fill(e, d);
        boolean moved = !Objects.equals(e.fromOrgUnitCode(), e.toOrgUnitCode()) || !Objects.equals(e.fromDesignationId(), e.toDesignationId())
                || !Objects.equals(e.fromPositionId(), e.toPositionId());
        // An acting officer keeps the substantive post, so an acting placement does not vacate it.
        if (!moved || e.acting() || e.fromDesignationId() == null || e.fromOrgUnitCode() == null) return;
        trigger(c -> contains(c.getPlacementReasons(), e.reason())).ifPresent(t ->
                vacate(t, e.employeeId(), e.fromDesignationId(), e.fromOrgUnitCode(), e.fromPositionId(), d, false,
                        "Placement (" + e.reason() + ") to " + e.toOrgUnitCode()));
    }

    // ===================================================================== daily scan

    public record ScanResult(int forecastsCreated, int forecastsUpdated, int opened, int filled) {
    }

    /**
     * Forecasts vacancies from retirement / secondment / contract end dates within the configured horizon, opens
     * forecasts whose date has arrived and marks vacancies filled when their post has a holder.
     */
    @Transactional
    public ScanResult scan() {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(Math.max(0, settings.getInt("vacancy.forecast-horizon-days", 365)));
        int created = 0, updated = 0, opened = 0, filled = 0;
        for (TriggerConfig t : triggers.findAllByOrderBySortOrderAscCodeAsc()) {
            if (!t.isEnabled() || t.getForecastDateField() == null) continue;
            String col = DATE_COLUMNS.get(t.getForecastDateField());
            if (col == null) continue;
            List<String> types = split(t.getEmploymentTypes());
            String sql = "SELECT id, org_unit_code, designation_id, position_id, " + col + " AS d FROM employee "
                    + "WHERE status IN ('" + String.join("','", IN_SERVICE) + "') AND org_unit_code IS NOT NULL AND designation_id IS NOT NULL "
                    + "AND " + col + " > ? AND " + col + " <= ?"
                    + (types.isEmpty() ? "" : " AND employment_type IN (" + String.join(",", Collections.nCopies(types.size(), "?")) + ")")
                    + " ORDER BY " + col;
            List<Object> args = new ArrayList<>(List.of(Date.valueOf(today), Date.valueOf(horizon)));
            args.addAll(types);
            for (Map<String, Object> r : jdbc.queryForList(sql, args.toArray())) {
                Long emp = ((Number) r.get("id")).longValue();
                LocalDate d = ((Date) r.get("d")).toLocalDate();
                List<Vacancy> existing = vacancies.findByVacatedByEmployeeIdAndStatusIn(emp, RecruitmentService.LIVE_VACANCY);
                if (!existing.isEmpty()) {
                    for (Vacancy v : existing) {
                        if (v.getStatus() == VacancyStatus.FORECAST && t.getCode().equals(v.getTriggerCode()) && !d.equals(v.getExpectedDate())) {
                            audit.log(AuditAction.UPDATE, "Vacancy", v.getId(), "Forecast date of " + v.getReferenceNo() + " updated from the employee record",
                                    v.getExpectedDate(), d);
                            v.setExpectedDate(d);
                            updated++;
                        }
                    }
                    continue;
                }
                Long pos = r.get("position_id") == null ? null : ((Number) r.get("position_id")).longValue();
                if (vacate(t, emp, ((Number) r.get("designation_id")).longValue(), (String) r.get("org_unit_code"), pos, d, true,
                        "Forecast from " + t.getForecastDateField().toLowerCase().replace('_', ' ')).isPresent()) created++;
            }
        }
        for (Vacancy v : vacancies.findByStatusIn(EnumSet.of(VacancyStatus.FORECAST))) {
            if (v.getExpectedDate() != null && !v.getExpectedDate().isAfter(today)) {
                v.setStatus(VacancyStatus.OPEN);
                audit.log(AuditAction.UPDATE, "Vacancy", v.getId(), "Vacancy " + v.getReferenceNo() + " is now open (vacancy date reached)",
                        "FORECAST", "OPEN");
                opened++;
            }
        }
        // Reconcile: a post that now has a holder (other than the officer who vacated it) is filled (REQ 5.7).
        for (Vacancy v : vacancies.findByStatusIn(RecruitmentService.ACTIVE_VACANCY)) {
            if (v.getPositionId() == null) continue;
            List<Long> holders = jdbc.queryForList("SELECT employee_id FROM employee_assignment WHERE position_id = ? AND effective_from <= ? "
                    + "AND (effective_to IS NULL OR effective_to > ?)" + (settings.getBool("vacancy.fill-on-acting", false) ? "" : " AND acting = FALSE"),
                    Long.class, v.getPositionId(), Date.valueOf(today), Date.valueOf(today));
            if (holders.stream().anyMatch(h -> !h.equals(v.getVacatedByEmployeeId()))) {
                v.setFilledPosts(v.getFilledPosts() + 1);
                v.setStatusReason("Post holder recorded on " + today);
                recruitment.refreshFilled(v);
                filled++;
            }
        }
        if (created + updated + opened + filled > 0)
            audit.log(AuditAction.PROCESS, "Vacancy", null, "Vacancy scan: " + created + " forecast(s) created, " + updated + " updated, "
                    + opened + " opened, " + filled + " filled");
        return new ScanResult(created, updated, opened, filled);
    }

    // ===================================================================== core

    /**
     * Records that a post is being vacated by an employee. Reuses the employee's existing forecast (so a retirement
     * forecast is confirmed rather than duplicated); never creates a vacancy for a dying cadre, a post to be abolished,
     * or a post that is excess to the approved cadre.
     */
    Optional<Vacancy> vacate(TriggerConfig t, Long employeeId, Long designationId, String unit, Long positionId, LocalDate d,
                             boolean holderStillCounted, String note) {
        Designation des = designations.findById(designationId).orElse(null);
        if (des == null) return Optional.empty();
        if (des.isDyingCadre()) {
            audit.log(AuditAction.PROCESS, "Vacancy", null, "No vacancy for " + des.getName() + " in " + unit
                    + ": dying cadre, the post lapses (REQ 6.10). " + note);
            return Optional.empty();
        }
        VacancyStatus status = d.isAfter(LocalDate.now()) ? VacancyStatus.FORECAST : VacancyStatus.OPEN;
        if (employeeId != null) {
            for (Vacancy v : vacancies.findByVacatedByEmployeeIdAndStatusIn(employeeId, EnumSet.of(VacancyStatus.FORECAST, VacancyStatus.OPEN))) {
                if (!v.getDesignationId().equals(designationId) || !v.getOrgUnitCode().equals(unit)) continue;
                String before = v.getStatus() + " " + v.getTriggerCode() + " " + v.getExpectedDate();
                v.setTriggerCode(t.getCode());
                v.setExpectedDate(d);
                if (v.getStatus() == VacancyStatus.FORECAST) v.setStatus(status);
                v.setStatusReason(note);
                audit.log(AuditAction.UPDATE, "Vacancy", v.getId(), "Vacancy " + v.getReferenceNo() + " confirmed: " + note, before,
                        v.getStatus() + " " + v.getTriggerCode() + " " + v.getExpectedDate());
                return Optional.of(v);
            }
        }
        if (positionId != null) {
            Position p = positions.findById(positionId).orElse(null);
            if (p != null && (!"ACTIVE".equals(p.getPostStatus()))) {
                audit.log(AuditAction.PROCESS, "Vacancy", null, "No vacancy for post " + p.getCode() + ": post is "
                        + p.getPostStatus().toLowerCase().replace('_', ' ') + ". " + note);
                return Optional.empty();
            }
            if (p != null && !vacancies.findByPositionIdAndStatusIn(positionId, RecruitmentService.LIVE_VACANCY).isEmpty()) return Optional.empty();
        } else {
            // Not linked to a post record: only create a vacancy while the cadre has room for it.
            LocalDate asOf = d.isAfter(LocalDate.now()) ? d : LocalDate.now();
            EstablishmentService.CadreRow row = establishment.cadreSummary(asOf, unit).stream()
                    .filter(c -> c.designationId().equals(designationId) && c.orgUnitCode().equals(unit)).findFirst().orElse(null);
            long approved = row == null ? 0 : row.approved();
            long occupiedAfter = row == null ? 0 : Math.max(0, row.occupied() - (holderStillCounted ? 1 : 0));
            long existing = vacancies.findByDesignationIdAndOrgUnitCodeAndStatusIn(designationId, unit, RecruitmentService.LIVE_VACANCY)
                    .stream().mapToLong(recruitment::remainingPosts).sum();
            if (approved - occupiedAfter - existing <= 0) {
                audit.log(AuditAction.PROCESS, "Vacancy", null, "No vacancy for " + des.getName() + " in " + unit
                        + ": the post is excess to the approved cadre (" + approved + " approved, " + occupiedAfter + " held, "
                        + existing + " already vacant). " + note);
                return Optional.empty();
            }
        }
        return create(t, des, unit, positionId, 1, d, employeeId, null, null, false, note);
    }

    Optional<Vacancy> create(TriggerConfig t, Designation des, String unit, Long positionId, int posts, LocalDate d, Long employeeId,
                             Long changeId, String sourceKey, boolean frozen, String note) {
        if (des.isDyingCadre()) {
            audit.log(AuditAction.PROCESS, "Vacancy", null, "No vacancy for " + des.getName() + ": dying cadre (REQ 6.10). " + note);
            return Optional.empty();
        }
        if (sourceKey != null && vacancies.findBySourceKey(sourceKey).isPresent()) return Optional.empty();
        String unitName = establishment.orgUnit(unit, LocalDate.now()).map(OrgUnit::getName).orElse(unit);
        Vacancy v = new Vacancy();
        v.setReferenceNo(recruitment.nextReference());
        String title = des.getName() + " – " + unitName;
        v.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        v.setDesignationId(des.getId());
        v.setOrgUnitCode(unit);
        v.setPositionId(positionId);
        v.setNumPosts(Math.max(1, posts));
        v.setStatus(frozen ? VacancyStatus.FROZEN : d.isAfter(LocalDate.now()) ? VacancyStatus.FORECAST : VacancyStatus.OPEN);
        v.setTriggerCode(t.getCode());
        v.setSource(VacancySource.AUTO);
        v.setSourceKey(sourceKey);
        v.setExpectedDate(d);
        v.setVacatedByEmployeeId(employeeId);
        v.setCadreChangeId(changeId);
        v.setExamWeight(recruitment.defaultExamWeight());
        v.setInterviewWeight(recruitment.defaultInterviewWeight());
        v.setStatusReason(note.length() > 1000 ? note.substring(0, 1000) : note);
        if (!frozen && settings.getBool("vacancy.auto-link-sor", true))
            recruitment.currentApprovedSor(des.getId()).ifPresent(s -> v.setSorId(s.getId()));
        vacancies.save(v);
        audit.log(AuditAction.CREATE, "Vacancy", v.getId(), "Vacancy " + v.getReferenceNo() + " created automatically ("
                + t.getName() + ", " + v.getStatus() + ", expected " + d + "): " + note);
        return Optional.of(v);
    }

    private void freeze(Vacancy v, String reason) {
        if (v.getStatus() == VacancyStatus.FROZEN) return;
        String from = v.getStatus().name();
        v.setStatus(VacancyStatus.FROZEN);
        v.setStatusReason(reason);
        audit.log(AuditAction.UPDATE, "Vacancy", v.getId(), "Vacancy " + v.getReferenceNo() + " frozen: " + reason, from, "FROZEN");
    }

    /** A placement into a vacant post (or designation in a unit) fills the vacancy; alerts stop (REQ 5.7). */
    private void fill(EstablishmentEvents.EmployeePlacementChanged e, LocalDate d) {
        if (e.toDesignationId() == null || e.toOrgUnitCode() == null) return;
        List<Vacancy> candidates = new ArrayList<>();
        if (e.toPositionId() != null) candidates.addAll(vacancies.findByPositionIdAndStatusIn(e.toPositionId(), RecruitmentService.LIVE_VACANCY));
        if (candidates.isEmpty()) {
            vacancies.findByDesignationIdAndOrgUnitCodeAndStatusIn(e.toDesignationId(), e.toOrgUnitCode(), RecruitmentService.ACTIVE_VACANCY).stream()
                    .filter(v -> v.getPositionId() == null || e.toPositionId() == null)
                    .sorted(Comparator.comparing((Vacancy v) -> v.getPositionId() != null)
                            .thenComparing(v -> v.getExpectedDate() == null ? LocalDate.MAX : v.getExpectedDate()))
                    .forEach(candidates::add);
        }
        candidates.removeIf(v -> e.employeeId().equals(v.getVacatedByEmployeeId()) || recruitment.remainingPosts(v) <= 0);
        if (candidates.isEmpty()) return;
        Vacancy v = candidates.get(0);
        v.setFilledPosts(v.getFilledPosts() + 1);
        v.setStatusReason("Filled by placement (" + e.reason() + ") of employee " + e.employeeId() + " from " + d);
        audit.log(AuditAction.UPDATE, "Vacancy", v.getId(), "Post of vacancy " + v.getReferenceNo() + " filled by placement ("
                + e.reason() + ") effective " + d);
        recruitment.refreshFilled(v);
    }

    private Optional<TriggerConfig> trigger(Predicate<TriggerConfig> p) {
        return triggers.findAllByOrderBySortOrderAscCodeAsc().stream().filter(TriggerConfig::isEnabled).filter(p).findFirst();
    }

    private static boolean contains(String list, String value) {
        return value != null && split(list).contains(value.trim().toUpperCase());
    }

    static List<String> split(String list) {
        if (list == null || list.isBlank()) return List.of();
        return Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).toList();
    }
}
