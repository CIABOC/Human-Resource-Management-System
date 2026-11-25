package lk.gov.ciaboc.hrms.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Content of one workflow version (REQ 10.4-10.7): named approval paths, ordered routing rules that select a path
 * (first match wins, otherwise the default path), and per path an ordered list of stages. Stored as JSON on
 * {@link WorkflowVersion}; never executed as code — conditions use a closed set of operators.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowModel(String defaultPath, List<PathDef> paths, List<RuleDef> rules) {

    public WorkflowModel {
        paths = paths == null ? new ArrayList<>() : paths;
        rules = rules == null ? new ArrayList<>() : rules;
    }

    /** What the approver does at a stage (REQ 10.7). */
    public enum ActionType {
        /** approve / return / reject */
        APPROVE,
        /** verify / return */
        VERIFY,
        /** acknowledge and record only */
        RECORD
    }

    /** For a stage with several approver slots: ALL slots must act, or ANY one of them. */
    public enum Mode {ALL, ANY}

    public enum ApproverType {
        /** Active users holding the role (ref = role code). */
        ROLE,
        /** Holder(s) of a post (ref = position code). */
        POSITION,
        /** A named officer (ref = user id). */
        USER,
        /** Division head of the subject employee's unit (nearest level with a head). */
        DIVISION_HEAD,
        /** Holder of the reports-to post of the initiator's post (ref = INITIATOR, default) or the subject's (ref = SUBJECT). */
        SUPERVISOR,
        /** Holder of the head post of a unit (ref = unit code; blank = the subject's unit). */
        UNIT_HEAD
    }

    /** Where a walk-up stops (inclusive). */
    public enum WalkUntil {
        /** a specific post (value = position code) */
        POSITION,
        /** first post whose designation has this code (value = designation code, e.g. DG) */
        DESIGNATION_CODE,
        /** first post whose designation level is at or above (numerically at or below) the value; 0 = DG */
        DESIGNATION_LEVEL,
        /** the head post of the starting unit's division chain, i.e. the first unit head reached */
        UNIT_HEAD,
        /** walk to the top of the hierarchy (bounded by maxLevels) */
        TOP
    }

    public enum Join {AND, OR}

    public enum Operator {EQ, NE, GT, GE, LT, LE, IN, NOT_IN, CONTAINS, NOT_CONTAINS, IS_SET, IS_NOT_SET, IS_TRUE, IS_FALSE}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PathDef(String code, String name, List<StageDef> stages) {
        public PathDef {
            stages = stages == null ? new ArrayList<>() : stages;
        }
    }

    /** A routing rule selects a path when its condition matches (REQ 10.6). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleDef(String name, String path, Condition when) {
    }

    /** Clauses and nested groups combined with AND or OR. An empty condition always matches. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Condition(Join join, List<Clause> clauses, List<Condition> groups) {
        public Condition {
            join = join == null ? Join.AND : join;
            clauses = clauses == null ? new ArrayList<>() : clauses;
            groups = groups == null ? new ArrayList<>() : groups;
        }

        public boolean isEmpty() {
            return clauses.isEmpty() && groups.isEmpty();
        }
    }

    /** {@code attribute operator value}, e.g. {@code days GT 3} or {@code initiator.designationLevel LE 3}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Clause(String attribute, Operator operator, String value) {
    }

    /**
     * One stage. Either a fixed stage with one or more approver slots ({@code approvers}; several = parallel step
     * completed by ALL or ANY), or a walk-up stage ({@code walkUp}) that expands to one step per hierarchy level.
     * An optional {@code when} condition skips the stage when it does not match.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StageDef(String name, ActionType actionType, Mode mode, List<ApproverDef> approvers, WalkUpDef walkUp,
                           Integer slaHours, String escalationRoleCode, Condition when) {
        public StageDef {
            actionType = actionType == null ? ActionType.APPROVE : actionType;
            mode = mode == null ? Mode.ANY : mode;
            approvers = approvers == null ? new ArrayList<>() : approvers;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApproverDef(ApproverType type, String ref) {
    }

    /**
     * Walk up the reports-to chain from the initiator's (or subject's) post, one approval step per level, until the
     * target is reached (inclusive). Vacant posts are skipped when {@code skipVacant}. Bounded by {@code maxLevels}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WalkUpDef(String from, WalkUntil until, String value, Integer maxLevels, Boolean skipVacant) {
        public static final int HARD_LIMIT = 15;

        public boolean fromSubject() {
            return "SUBJECT".equalsIgnoreCase(from);
        }

        public int levels() {
            return Math.max(1, Math.min(maxLevels == null ? 10 : maxLevels, HARD_LIMIT));
        }

        public boolean skipsVacant() {
            return skipVacant == null || skipVacant;
        }
    }

    public PathDef path(String code) {
        return paths.stream().filter(p -> p.code() != null && p.code().equals(code)).findFirst().orElse(null);
    }

    public PathDef defaultPathDef() {
        PathDef p = path(defaultPath);
        return p != null ? p : (paths.isEmpty() ? null : paths.get(0));
    }

    /** A model with a single path built from legacy single-approver stages. */
    public static WorkflowModel singlePath(List<StageDef> stages) {
        return new WorkflowModel("STANDARD", new ArrayList<>(List.of(new PathDef("STANDARD", "Standard path", stages))), new ArrayList<>());
    }
}
