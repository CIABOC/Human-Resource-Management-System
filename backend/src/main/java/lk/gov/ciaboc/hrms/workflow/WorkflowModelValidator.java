package lk.gov.ciaboc.hrms.workflow;

import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lk.gov.ciaboc.hrms.security.RoleRepository;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/** Validates a workflow version before it is saved or published, with a clear reason per problem (REQ 10.7). */
@Component
class WorkflowModelValidator {
    static final int MAX_PATHS = 20;
    static final int MAX_STAGES = 30;
    static final int MAX_SLOTS = 10;
    static final int MAX_RULES = 50;
    static final int MAX_CLAUSES = 50;
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");
    private static final Pattern ATTRIBUTE = Pattern.compile("[A-Za-z][A-Za-z0-9_.]{0,79}");

    private final RoleRepository roles;
    private final AppUserRepository users;
    private final OrgDirectory directory;

    WorkflowModelValidator(RoleRepository roles, AppUserRepository users, @Lazy OrgDirectory directory) {
        this.roles = roles;
        this.users = users;
        this.directory = directory;
    }

    /** Throws 422 INVALID_WORKFLOW listing every problem. */
    void validateOrThrow(WorkflowModel m) {
        List<String> errors = validate(m);
        if (!errors.isEmpty())
            throw ApiException.rule("INVALID_WORKFLOW", "The workflow cannot be saved: " + String.join("; ", errors));
    }

    List<String> validate(WorkflowModel m) {
        List<String> e = new ArrayList<>();
        if (m == null) return List.of("no content");
        if (m.paths().isEmpty()) e.add("at least one path is required");
        if (m.paths().size() > MAX_PATHS) e.add("at most " + MAX_PATHS + " paths");
        Set<String> codes = new HashSet<>();
        for (PathDef p : m.paths()) {
            String where = "path '" + (p.name() == null ? p.code() : p.name()) + "'";
            if (p.code() == null || !CODE.matcher(p.code()).matches())
                e.add(where + ": code must be upper-case letters, digits or _ (max 40)");
            else if (!codes.add(p.code())) e.add(where + ": duplicate path code " + p.code());
            if (p.name() == null || p.name().isBlank() || p.name().length() > 150) e.add(where + ": name is required (max 150)");
            if (p.stages().isEmpty()) e.add(where + ": add at least one stage");
            if (p.stages().size() > MAX_STAGES) e.add(where + ": at most " + MAX_STAGES + " stages");
            for (int i = 0; i < p.stages().size(); i++) stage(p.stages().get(i), where + " stage " + (i + 1), e);
        }
        if (m.defaultPath() == null || m.path(m.defaultPath()) == null) e.add("choose a default path (used when no rule matches)");
        if (m.rules().size() > MAX_RULES) e.add("at most " + MAX_RULES + " rules");
        for (int i = 0; i < m.rules().size(); i++) {
            RuleDef r = m.rules().get(i);
            String where = "rule " + (i + 1) + (r.name() == null ? "" : " '" + r.name() + "'");
            if (r.name() == null || r.name().isBlank() || r.name().length() > 150) e.add(where + ": name is required (max 150)");
            if (r.path() == null || m.path(r.path()) == null) e.add(where + ": choose an existing path");
            if (r.when() == null || r.when().isEmpty()) e.add(where + ": add at least one condition (the default path covers everything else)");
            else condition(r.when(), where, e, 0, new int[]{0});
        }
        return e;
    }

    private void stage(StageDef s, String where, List<String> e) {
        if (s.name() == null || s.name().isBlank() || s.name().length() > 150) e.add(where + ": name is required (max 150)");
        else where = where + " '" + s.name() + "'";
        if (s.slaHours() != null && (s.slaHours() < 1 || s.slaHours() > 8760)) e.add(where + ": SLA must be 1-8760 hours");
        if (s.escalationRoleCode() != null && !s.escalationRoleCode().isBlank() && roles.findByCode(s.escalationRoleCode()).isEmpty())
            e.add(where + ": unknown escalation role " + s.escalationRoleCode());
        if (s.when() != null && !s.when().isEmpty()) condition(s.when(), where + " condition", e, 0, new int[]{0});
        if (s.walkUp() != null) {
            WalkUpDef w = s.walkUp();
            if (!s.approvers().isEmpty()) e.add(where + ": a walk-up stage takes its approvers from the hierarchy; remove the fixed approvers");
            if (w.from() != null && !w.from().isBlank() && !w.from().equalsIgnoreCase("INITIATOR") && !w.from().equalsIgnoreCase("SUBJECT"))
                e.add(where + ": walk-up must start from INITIATOR or SUBJECT");
            if (w.until() == null) e.add(where + ": choose where the walk-up stops");
            else {
                String v = w.value() == null ? "" : w.value().trim();
                switch (w.until()) {
                    case POSITION -> {
                        if (v.isEmpty()) e.add(where + ": target position code is required");
                        else if (directory.post(v).isEmpty()) e.add(where + ": position " + v + " does not exist");
                    }
                    case DESIGNATION_CODE -> {
                        if (v.isEmpty()) e.add(where + ": target designation code is required");
                    }
                    case DESIGNATION_LEVEL -> {
                        try {
                            int lvl = Integer.parseInt(v);
                            if (lvl < 0 || lvl > 99) e.add(where + ": designation level must be 0-99");
                        } catch (NumberFormatException ex) {
                            e.add(where + ": designation level must be a whole number");
                        }
                    }
                    default -> {
                    }
                }
            }
            if (w.maxLevels() != null && (w.maxLevels() < 1 || w.maxLevels() > WalkUpDef.HARD_LIMIT))
                e.add(where + ": maximum levels must be 1-" + WalkUpDef.HARD_LIMIT);
            return;
        }
        if (s.approvers().isEmpty()) e.add(where + ": add at least one approver (or make it a walk-up stage)");
        if (s.approvers().size() > MAX_SLOTS) e.add(where + ": at most " + MAX_SLOTS + " parallel approvers");
        for (ApproverDef a : s.approvers()) {
            if (a == null || a.type() == null) {
                e.add(where + ": approver type is required");
                continue;
            }
            String ref = a.ref() == null ? "" : a.ref().trim();
            switch (a.type()) {
                case ROLE -> {
                    if (ref.isEmpty()) e.add(where + ": choose a role");
                    else if (roles.findByCode(ref).isEmpty()) e.add(where + ": unknown role " + ref);
                }
                case USER -> {
                    if (ref.isEmpty()) e.add(where + ": choose an officer");
                    else {
                        try {
                            if (users.findById(Long.parseLong(ref)).filter(AppUser::isActive).isEmpty())
                                e.add(where + ": officer #" + ref + " is not an active user");
                        } catch (NumberFormatException ex) {
                            e.add(where + ": officer reference must be a user id");
                        }
                    }
                }
                case POSITION -> {
                    if (ref.isEmpty()) e.add(where + ": choose a position");
                    else if (directory.post(ref).isEmpty()) e.add(where + ": position " + ref + " does not exist");
                }
                case SUPERVISOR -> {
                    if (!ref.isEmpty() && !ref.equalsIgnoreCase("INITIATOR") && !ref.equalsIgnoreCase("SUBJECT"))
                        e.add(where + ": supervisor must be of the INITIATOR or the SUBJECT");
                }
                case DIVISION_HEAD, UNIT_HEAD -> {
                }
            }
        }
    }

    private void condition(Condition c, String where, List<String> e, int depth, int[] count) {
        if (depth > ConditionEvaluator.MAX_DEPTH) {
            e.add(where + ": conditions nested too deeply (max " + ConditionEvaluator.MAX_DEPTH + ")");
            return;
        }
        for (Clause cl : c.clauses()) {
            if (++count[0] > MAX_CLAUSES) {
                e.add(where + ": too many conditions (max " + MAX_CLAUSES + ")");
                return;
            }
            if (cl == null || cl.attribute() == null || !ATTRIBUTE.matcher(cl.attribute()).matches()) {
                e.add(where + ": attribute name is required (letters, digits, . and _)");
                continue;
            }
            if (cl.operator() == null) {
                e.add(where + ": choose an operator for " + cl.attribute());
                continue;
            }
            if (ConditionEvaluator.needsValue(cl.operator())) {
                if (cl.value() == null || cl.value().isBlank()) e.add(where + ": a value is required for " + cl.attribute());
                else if (cl.value().length() > 500) e.add(where + ": value too long for " + cl.attribute());
                else if (EnumSet.of(Operator.GT, Operator.GE, Operator.LT, Operator.LE).contains(cl.operator())
                        && ConditionEvaluator.number(cl.value()) == null)
                    e.add(where + ": " + cl.attribute() + " " + ConditionEvaluator.symbol(cl.operator()) + " needs a number");
            }
        }
        for (Condition g : c.groups()) condition(g, where, e, depth + 1, count);
    }
}
