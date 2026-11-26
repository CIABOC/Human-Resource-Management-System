package lk.gov.ciaboc.hrms.appraisal;

import lk.gov.ciaboc.hrms.appraisal.AppraisalEntities.Appraisal;
import lk.gov.ciaboc.hrms.appraisal.AppraisalService.*;
import lk.gov.ciaboc.hrms.config.AppProperties;
import lk.gov.ciaboc.hrms.employee.EmployeeLookup;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * Development only (app.bootstrap.demo-users): walks sample appraisals through the real service layer, signed in as
 * the people who would act on them, so every screen - in particular the Director General's management dashboard -
 * has data. Runs once, when no appraisal exists yet. Creates two earlier demo periods for the performance trend.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AppraisalDemoSeeder {
    private final AppProperties props;
    private final JdbcTemplate jdbc;
    private final AprAppraisalRepository appraisals;
    private final AppraisalService service;
    private final AppraisalWorkService work;
    private final AppUserRepository users;
    private final EmployeeLookup lookup;
    private final TransactionTemplate tx;

    private enum Stage {AGREEMENT, IN_PROGRESS, SELF_SUBMITTED, VERIFIED, FINALIZED}

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (props.bootstrap() == null || !props.bootstrap().demoUsers()) return;
        if (appraisals.count() > 0) return;
        Long hr = userId("hr");
        if (hr == null) return;
        try {
            LocalDate today = LocalDate.now();
            int year = today.getYear();
            for (int back = 2; back >= 1; back--) {
                int y = year - back;
                Long pid = ensurePeriod("DEMO-APR-" + y, "Demo appraisal " + y, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31));
                as(hr, () -> service.start(pid));
                jdbc.update("UPDATE per_period SET due_date = ? WHERE id = ?", today.plusDays(30), pid);
                for (Appraisal a : appraisals.findByPeriodId(pid)) run(a, Stage.FINALIZED, back);
                jdbc.update("UPDATE per_period SET status = 'CLOSED', due_date = ? WHERE id = ?", LocalDate.of(y + 1, 1, 31), pid);
            }
            Long current = jdbc.query("SELECT id FROM per_period WHERE code = 'DEMO-ANNUAL'", rs -> rs.next() ? rs.getLong(1) : null);
            if (current == null) return;
            as(hr, () -> service.start(current));
            List<Appraisal> list = new ArrayList<>(appraisals.findByPeriodId(current));
            list.sort(Comparator.comparing(Appraisal::getEmployeeId));
            Long dg = userId("dg");
            Long employee = users.findByUsernameIgnoreCase("employee").map(AppUser::getEmployeeId).orElse(null);
            boolean dgPending = false;
            int i = 0;
            for (Appraisal a : list) {
                Stage stage;
                if (a.getEmployeeId().equals(employee)) stage = Stage.IN_PROGRESS;
                else if (dg != null && dg.equals(a.getReviewerUserId()) && !dgPending) {
                    stage = Stage.VERIFIED;
                    dgPending = true;
                } else stage = switch (i++ % 6) {
                    case 0 -> Stage.AGREEMENT;
                    case 1 -> Stage.IN_PROGRESS;
                    case 2 -> Stage.SELF_SUBMITTED;
                    case 3 -> Stage.VERIFIED;
                    default -> Stage.FINALIZED;
                };
                run(a, stage, 0);
            }
            extras(current, employee);
            log.info("Demo appraisals created: {} in the current period", list.size());
        } catch (Exception e) {
            log.warn("Demo appraisal data could not be created: {}", e.getMessage(), e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void run(Appraisal a0, Stage stage, int seedOffset) {
        Long id = a0.getId();
        Long emp = a0.getEmployeeId();
        Long sup = a0.getSupervisorUserId();
        Long rev = a0.getReviewerUserId();
        if (sup == null) return;
        int seed = (int) ((emp * 37 + seedOffset * 11) % 100);
        try {
            asEmployee(emp, () -> service.saveAgreement(id, kpis()));
            if (stage == Stage.AGREEMENT) return;
            asEmployee(emp, () -> service.signAgreement(id));
            as(sup, () -> service.signAgreement(id));
            if (rev != null) as(rev, () -> service.signAgreement(id));
            AppraisalView v = asEmployee(emp, () -> service.view(id));
            List<KpiView> ks = v.kpis();
            int tasksDone = 6 + seed % 7;
            asEmployee(emp, () -> service.saveProgress(ks.get(0).id(), new ProgressRequest(null, tasksDone, 12, null, null, null, null,
                    tasksDone < 9 ? "Two files were delayed awaiting external records." : null, null)));
            asEmployee(emp, () -> service.saveProgress(ks.get(1).id(), new ProgressRequest(List.of(true, seed > 30, seed > 75), null, null,
                    null, null, null, null, seed <= 30 ? "The mid-year review was rescheduled by the unit." : null, null)));
            int quality = 60 + seed % 40;
            asEmployee(emp, () -> service.saveProgress(ks.get(2).id(), new ProgressRequest(null, null, null, null,
                    "Reports accepted with minor corrections", null, BigDecimal.valueOf(quality), null, null)));
            if (stage == Stage.IN_PROGRESS) return;
            AppraisalView v2 = asEmployee(emp, () -> service.view(id));
            List<SelfLine> lines = v2.kpis().stream().map(k -> new SelfLine(k.id(), k.selfProgress(), "Self-assessed against the agreed target")).toList();
            asEmployee(emp, () -> service.saveSelfAssessment(id, new SelfAssessmentRequest(lines,
                    "Completed the assigned files and supported two joint operations.", "Delays in receiving external records.",
                    "Would benefit from advanced training on digital evidence.", true)));
            if (stage == Stage.SELF_SUBMITTED) return;
            List<VerifyLine> ver = new ArrayList<>();
            for (KpiView k : v2.kpis()) {
                boolean adjust = (k.id() + seed) % 4 == 0;
                BigDecimal val = adjust ? k.selfProgress().subtract(BigDecimal.TEN).max(BigDecimal.ZERO) : k.selfProgress();
                ver.add(new VerifyLine(k.id(), adjust ? "ADJUSTED" : "VERIFIED", val, adjust ? "Evidence supports a lower achievement." : null));
            }
            as(sup, () -> service.saveVerification(id, new VerificationRequest(ver, true)));
            List<RatingLine> rl = new ArrayList<>();
            for (CriterionRating c : as(sup, () -> service.view(id)).behaviour()) {
                boolean na = c.code().equals("LEADERSHIP") && seed % 3 == 0;
                int r = 2 + (int) ((c.criterionId() * 7 + seed) % 4);
                rl.add(new RatingLine(c.criterionId(), na ? null : r, na, na ? null : "Observed consistently during the period."));
            }
            as(sup, () -> service.saveBehaviour(id, new BehaviourRequest(rl, "Reliable and thorough.", "Time management on long files.", true)));
            if (stage == Stage.VERIFIED) return;
            Long approver = rev != null ? rev : firstHrOtherThan(emp, sup);
            if (approver == null) return;
            BigDecimal adj = seed % 9 == 0 ? BigDecimal.valueOf(2) : null;
            as(approver, () -> service.approve(id, new ApprovalRequest(adj, adj == null ? null : "Moderated for consistency across the division.", "Approved.")));
            asEmployee(emp, () -> service.acknowledge(id, "Acknowledged."));
        } catch (Exception e) {
            log.debug("Demo appraisal {} (employee {}, supervisor {}, reviewer {}) stopped at {}: {}", id, emp, sup, rev, stage, e.getMessage());
        }
    }

    private void extras(Long periodId, Long employeeId) {
        if (employeeId == null) return;
        Appraisal a = appraisals.findByPeriodIdAndEmployeeId(periodId, employeeId).orElse(null);
        if (a == null || a.getSupervisorUserId() == null) return;
        Long sup = a.getSupervisorUserId();
        try {
            as(sup, () -> work.scheduleCheckin(new AppraisalWorkService.CheckinRequest(employeeId, LocalDate.now().minusDays(40),
                    "Progress on assigned investigation files", "Prioritize the two delayed files")));
            Long done = as(sup, () -> work.checkins()).stream().filter(c -> c.employeeId().equals(employeeId)).findFirst().map(AppraisalWorkService.CheckinView::id).orElse(null);
            if (done != null) as(sup, () -> work.completeCheckin(done, new AppraisalWorkService.CheckinRequest(employeeId, null,
                    "Reviewed progress on assigned files and milestones", "Prioritize the two delayed files")));
            as(sup, () -> work.scheduleCheckin(new AppraisalWorkService.CheckinRequest(employeeId, LocalDate.now().plusDays(10), null, null)));
            as(sup, () -> work.sendFeedback(new AppraisalWorkService.FeedbackRequest(employeeId, "RECOGNITION",
                    "Excellent coordination on the joint operation last month.", null)));
        } catch (Exception e) {
            log.debug("Demo appraisal extras skipped: {}", e.getMessage());
        }
    }

    private static List<KpiDef> kpis() {
        return List.of(
                new KpiDef(null, "Objective 1: Timely completion of assigned work", "Assigned files / tasks completed on time", "TASK_COUNT",
                        "12 files", BigDecimal.valueOf(40), "Annual", null),
                new KpiDef(null, "Objective 2: Deliver the unit work plan", "Work plan milestones delivered", "MILESTONE",
                        "3 milestones", BigDecimal.valueOf(35), "Annual",
                        List.of(new Milestone("Plan approved", BigDecimal.valueOf(30)), new Milestone("Mid-year review", BigDecimal.valueOf(30)),
                                new Milestone("Final report", BigDecimal.valueOf(40)))),
                new KpiDef(null, "Objective 3: Quality of work", "Quality of reports submitted", "QUALITY",
                        "No major corrections", BigDecimal.valueOf(25), "Quarterly", null));
    }

    // ------------------------------------------------------------------ helpers

    private Long ensurePeriod(String code, String name, LocalDate start, LocalDate end) {
        jdbc.update("INSERT INTO per_period (version, created_at, created_by, code, name, start_date, end_date, due_date, status) " +
                "VALUES (0, now(), 'demo', ?, ?, ?, ?, ?, 'OPEN') ON CONFLICT (code) DO NOTHING", code, name, start, end, end.plusDays(31));
        return jdbc.queryForObject("SELECT id FROM per_period WHERE code = ?", Long.class, code);
    }

    private Long userId(String username) {
        return users.findByUsernameIgnoreCase(username).map(AppUser::getId).orElse(null);
    }

    private Long firstHrOtherThan(Long employeeId, Long supervisorUserId) {
        for (String u : List.of("hr", "hr2", "headhr")) {
            Optional<AppUser> x = users.findByUsernameIgnoreCase(u);
            if (x.isPresent() && !Objects.equals(x.get().getEmployeeId(), employeeId) && !x.get().getId().equals(supervisorUserId)) return x.get().getId();
        }
        return null;
    }

    private <T> T as(Long userId, Supplier<T> action) {
        UserPrincipal p = tx.execute(s -> new UserPrincipal(users.findById(userId).orElseThrow()));
        return withPrincipal(p, action);
    }

    /** Employees without a user account act through a transient principal carrying only their employee id. */
    private <T> T asEmployee(Long employeeId, Supplier<T> action) {
        Optional<AppUser> u = users.findByEmployeeId(employeeId);
        if (u.isPresent()) return as(u.get().getId(), action);
        AppUser t = new AppUser();
        t.setUsername("demo-employee-" + employeeId);
        EmployeeLookup.EmployeeBrief b = lookup.brief(employeeId);
        t.setFullName(b == null ? "Employee " + employeeId : b.name());
        t.setEmployeeId(employeeId);
        return withPrincipal(new UserPrincipal(t), action);
    }

    private static <T> T withPrincipal(UserPrincipal p, Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
