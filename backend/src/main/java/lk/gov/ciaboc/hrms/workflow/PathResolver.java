package lk.gov.ciaboc.hrms.workflow;

import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.security.Role;
import lk.gov.ciaboc.hrms.security.RoleRepository;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Determines the approval path of a request from the version's routing rules, the initiator's post, division and the
 * reports-to hierarchy (REQ 10.4-10.6). Pure resolution: used both when a request is submitted and by the simulator.
 */
@Component
class PathResolver {
    private final OrgDirectory org;
    private final RoleRepository roles;
    private final AppUserRepository users;
    private final ApproverResolver approvers;

    PathResolver(@Lazy OrgDirectory org, RoleRepository roles, AppUserRepository users, ApproverResolver approvers) {
        this.org = org;
        this.roles = roles;
        this.users = users;
        this.approvers = approvers;
    }

    /** Who initiates, about whom, and the flattened routing attributes. */
    record Context(Long initiatorUserId, Long initiatorEmployeeId, Long subjectEmployeeId, String subjectOrgUnitCode,
                   Map<String, Object> attributes) {
    }

    record ResolvedSlot(ApproverType type, String ref, String label) {
    }

    record ResolvedStep(String name, String stageName, ActionType actionType, Mode mode, Integer slaHours,
                        String escalationRoleCode, Integer walkLevel, List<ResolvedSlot> slots) {
    }

    record RuleTrace(String name, String path, Boolean matched, List<String> trace) {
    }

    record ResolvedPath(String pathCode, String pathName, String matchedRule, List<RuleTrace> rules,
                        List<ResolvedStep> steps, List<String> notes) {
    }

    /** Builds the routing attributes: request attributes as supplied, plus {@code initiator.*} and {@code subject.*}. */
    Map<String, Object> attributes(Map<String, Object> request, Long initiatorUserId, Long initiatorEmployeeId, Long subjectEmployeeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (request != null) request.forEach((k, v) -> {
            if (k != null && !k.startsWith("initiator.") && !k.startsWith("subject.")) m.put(k, v);
        });
        AppUser u = initiatorUserId == null ? null : users.findById(initiatorUserId).orElse(null);
        if (u != null) {
            m.put("initiator.userId", u.getId());
            m.put("initiator.roles", u.getRoles().stream().map(Role::getCode).sorted().toList());
        }
        org.employeeAttributes(initiatorEmployeeId).forEach((k, v) -> m.put("initiator." + k, v));
        Long subject = subjectEmployeeId != null ? subjectEmployeeId : initiatorEmployeeId;
        org.employeeAttributes(subject).forEach((k, v) -> m.put("subject." + k, v));
        m.put("subject.isInitiator", subject != null && subject.equals(initiatorEmployeeId));
        return m;
    }

    /** Current org unit of an employee (the subject's unit used for DIVISION_HEAD routing). */
    String attributesUnit(Long employeeId) {
        return employeeId == null ? null : org.orgUnitOfEmployee(employeeId);
    }

    ResolvedPath resolve(WorkflowModel model, Context ctx) {
        List<String> notes = new ArrayList<>();
        List<RuleTrace> traces = new ArrayList<>();
        PathDef chosen = null;
        String matchedRule = null;
        for (RuleDef r : model.rules()) {
            if (chosen != null) {
                traces.add(new RuleTrace(r.name(), r.path(), null, List.of("not evaluated — an earlier rule matched")));
                continue;
            }
            ConditionEvaluator.Result res = ConditionEvaluator.evaluate(r.when(), ctx.attributes());
            traces.add(new RuleTrace(r.name(), r.path(), res.matched(), res.trace()));
            if (res.matched()) {
                PathDef p = model.path(r.path());
                if (p == null) {
                    notes.add("Rule '" + r.name() + "' points to unknown path '" + r.path() + "' and was ignored");
                } else {
                    chosen = p;
                    matchedRule = r.name();
                }
            }
        }
        if (chosen == null) chosen = model.defaultPathDef();
        if (chosen == null) {
            notes.add("The version has no paths");
            return new ResolvedPath(null, null, null, traces, fallbackOnly(notes), notes);
        }
        List<ResolvedStep> steps = new ArrayList<>();
        for (StageDef st : chosen.stages()) {
            if (st.when() != null && !st.when().isEmpty()) {
                ConditionEvaluator.Result res = ConditionEvaluator.evaluate(st.when(), ctx.attributes());
                if (!res.matched()) {
                    notes.add("Stage '" + st.name() + "' skipped: " + String.join("; ", res.trace()));
                    continue;
                }
            }
            if (st.walkUp() != null) steps.addAll(walkUp(st, ctx, notes));
            else steps.add(fixed(st, ctx, notes));
        }
        if (steps.isEmpty()) {
            notes.add("No approval step resolved; the request goes to the fallback approver");
            steps = fallbackOnly(notes);
        }
        return new ResolvedPath(chosen.code(), chosen.name(), matchedRule, traces, steps, notes);
    }

    private List<ResolvedStep> fallbackOnly(List<String> notes) {
        String role = approvers.fallbackRole(null);
        return List.of(new ResolvedStep("Approval", "Approval", ActionType.APPROVE, Mode.ANY, null, null, null,
                List.of(new ResolvedSlot(ApproverType.ROLE, role, roleLabel(role)))));
    }

    private ResolvedStep fixed(StageDef st, Context ctx, List<String> notes) {
        List<ResolvedSlot> slots = new ArrayList<>();
        for (ApproverDef a : st.approvers()) slots.add(slot(a, ctx, notes, st.name()));
        Mode mode = slots.size() > 1 ? st.mode() : Mode.ANY;
        return new ResolvedStep(st.name(), st.name(), st.actionType(), mode, st.slaHours(), blankToNull(st.escalationRoleCode()), null, slots);
    }

    private ResolvedSlot slot(ApproverDef a, Context ctx, List<String> notes, String stage) {
        String ref = blankToNull(a.ref());
        return switch (a.type()) {
            case ROLE -> new ResolvedSlot(ApproverType.ROLE, ref, roleLabel(ref));
            case USER -> new ResolvedSlot(ApproverType.USER, ref, "Officer: " + userLabel(ref));
            case POSITION -> new ResolvedSlot(ApproverType.POSITION, ref, postLabel(ref));
            case DIVISION_HEAD -> divisionHead(ctx);
            case UNIT_HEAD -> {
                String unit = ref == null ? ctx.subjectOrgUnitCode() : ref;
                String head = org.headPostOfUnit(unit);
                if (head == null) {
                    notes.add("Stage '" + stage + "': unit " + unit + " has no head post; using the division head");
                    yield divisionHead(ctx);
                }
                yield new ResolvedSlot(ApproverType.POSITION, head, "Head of " + unit + ": " + postLabel(head));
            }
            case SUPERVISOR -> {
                boolean subject = "SUBJECT".equalsIgnoreCase(ref);
                Long emp = subject ? (ctx.subjectEmployeeId() != null ? ctx.subjectEmployeeId() : ctx.initiatorEmployeeId()) : ctx.initiatorEmployeeId();
                String sup = supervisorPost(emp, ctx, notes, stage);
                if (sup == null) {
                    notes.add("Stage '" + stage + "': no supervising post found; using the division head");
                    yield divisionHead(ctx);
                }
                yield new ResolvedSlot(ApproverType.POSITION, sup, "Supervisor: " + postLabel(sup));
            }
        };
    }

    private ResolvedSlot divisionHead(Context ctx) {
        return new ResolvedSlot(ApproverType.DIVISION_HEAD, null,
                "Division head" + (ctx.subjectOrgUnitCode() == null ? "" : " of " + ctx.subjectOrgUnitCode()));
    }

    /** The reports-to post of the employee's post; without a linked post, the head post of the employee's unit. */
    private String supervisorPost(Long employeeId, Context ctx, List<String> notes, String stage) {
        String own = org.postOfEmployee(employeeId);
        if (own != null) return org.post(own).map(OrgDirectory.PostInfo::reportsTo).orElse(null);
        String unit = employeeId != null ? org.orgUnitOfEmployee(employeeId) : ctx.subjectOrgUnitCode();
        String head = org.headPostOfUnit(unit);
        if (head != null) notes.add("Stage '" + stage + "': officer has no post linked; starting from the head post of " + unit);
        return head;
    }

    private List<ResolvedStep> walkUp(StageDef st, Context ctx, List<String> notes) {
        WalkUpDef w = st.walkUp();
        Long emp = w.fromSubject() ? (ctx.subjectEmployeeId() != null ? ctx.subjectEmployeeId() : ctx.initiatorEmployeeId()) : ctx.initiatorEmployeeId();
        String cur = supervisorPost(emp, ctx, notes, st.name());
        List<ResolvedStep> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String own = org.postOfEmployee(emp);
        if (own != null) visited.add(own);
        if (own != null && targetReached(org.post(own).orElse(null), w)) {
            notes.add("Stage '" + st.name() + "': the officer's own post already meets the target; no levels added");
            return out;
        }
        boolean reached = false;
        int guard = 0;
        while (cur != null && out.size() < w.levels() && guard++ < 60 && visited.add(cur)) {
            OrgDirectory.PostInfo p = org.post(cur).orElse(null);
            if (p == null) {
                notes.add("Stage '" + st.name() + "': post " + cur + " not found in the structure; walk stopped");
                break;
            }
            boolean target = targetReached(p, w);
            boolean vacant = p.holders() == null || p.holders().isEmpty();
            if (vacant && w.skipsVacant() && !target) {
                notes.add("Stage '" + st.name() + "': vacant post " + p.code() + " skipped");
            } else {
                int level = out.size() + 1;
                out.add(new ResolvedStep(st.name() + " — " + shortPost(p), st.name(), st.actionType(), Mode.ANY, st.slaHours(),
                        blankToNull(st.escalationRoleCode()), level,
                        List.of(new ResolvedSlot(ApproverType.POSITION, p.code(), postLabel(p)))));
            }
            if (target) {
                reached = true;
                break;
            }
            cur = p.reportsTo();
        }
        if (!reached && w.until() != WalkUntil.TOP) {
            notes.add("Stage '" + st.name() + "': target " + w.until() + (w.value() == null ? "" : " " + w.value())
                    + " not reached (" + (out.size() >= w.levels() ? "level limit " + w.levels() : "top of hierarchy") + ")");
        }
        return out;
    }

    static boolean targetReached(OrgDirectory.PostInfo p, WalkUpDef w) {
        if (p == null || w.until() == null) return false;
        String v = w.value() == null ? "" : w.value().trim();
        return switch (w.until()) {
            case POSITION -> p.code().equalsIgnoreCase(v);
            case DESIGNATION_CODE -> p.designationCode() != null && p.designationCode().equalsIgnoreCase(v);
            case DESIGNATION_LEVEL -> {
                Integer lvl = p.level();
                try {
                    yield lvl != null && lvl <= Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            case UNIT_HEAD -> p.unitHead();
            case TOP -> false;
        };
    }

    private String roleLabel(String code) {
        if (code == null) return "Role (not set)";
        return "Role: " + roles.findByCode(code).map(Role::getName).orElse(code);
    }

    private String userLabel(String ref) {
        try {
            return ref == null ? "(not set)" : users.findById(Long.parseLong(ref)).map(AppUser::getFullName).orElse("#" + ref);
        } catch (NumberFormatException e) {
            return ref;
        }
    }

    private String postLabel(String code) {
        if (code == null) return "Post (not set)";
        return org.post(code).map(this::postLabel).orElse("Post " + code);
    }

    private String postLabel(OrgDirectory.PostInfo p) {
        return "Post " + p.code() + " (" + (p.designationName() != null ? p.designationName() : p.title()) + ")";
    }

    private static String shortPost(OrgDirectory.PostInfo p) {
        return (p.designationName() != null ? p.designationName() : p.title()) + " " + p.code();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
