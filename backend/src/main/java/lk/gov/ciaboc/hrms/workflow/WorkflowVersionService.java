package lk.gov.ciaboc.hrms.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.workflow.WorkflowModel.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Versioned workflow configuration (REQ 10.7, 10.9; G-2, G-3): drafts are edited in the designer, validated and
 * published with an effective date; published versions are never modified. Seeded single-chain stages
 * ({@code workflow_stage}) are imported as a new version whenever a module migration changes them.
 */
@Slf4j
@Service
public class WorkflowVersionService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowVersionRepository versions;
    private final WorkflowModelValidator validator;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Map<Long, WorkflowModel> publishedCache = new ConcurrentHashMap<>();

    public WorkflowVersionService(WorkflowDefinitionRepository definitions, WorkflowVersionRepository versions,
                                  WorkflowModelValidator validator, ObjectMapper json, AuditService audit,
                                  PlatformTransactionManager txm) {
        this.definitions = definitions;
        this.versions = versions;
        this.validator = validator;
        this.json = json;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    // ------------------------------------------------------------------ lookup

    /** The published version in force at {@code at}: latest effective date reached, then highest number. */
    @Transactional(readOnly = true)
    public Optional<WorkflowVersion> effectiveVersion(Long definitionId, Instant at) {
        return versions.findByDefinitionIdAndStatusOrderByVersionNoDesc(definitionId, WorkflowVersion.Status.PUBLISHED).stream()
                .filter(v -> v.getEffectiveFrom() != null && !v.getEffectiveFrom().isAfter(at))
                .max(Comparator.comparing(WorkflowVersion::getEffectiveFrom).thenComparing(WorkflowVersion::getVersionNo));
    }

    WorkflowVersion version(Long id) {
        return versions.findById(id).orElseThrow(() -> ApiException.notFound("Workflow version", id));
    }

    WorkflowDefinition definition(Long id) {
        return definitions.findById(id).orElseThrow(() -> ApiException.notFound("Workflow", id));
    }

    public WorkflowModel model(WorkflowVersion v) {
        if (v.getStatus() == WorkflowVersion.Status.PUBLISHED && v.getId() != null) {
            WorkflowModel cached = publishedCache.get(v.getId());
            if (cached != null) return cached;
            WorkflowModel m = parse(v.getContent());
            publishedCache.put(v.getId(), m);
            return m;
        }
        return parse(v.getContent());
    }

    WorkflowModel parse(String content) {
        try {
            return json.readValue(content, WorkflowModel.class);
        } catch (JsonProcessingException e) {
            throw ApiException.rule("INVALID_WORKFLOW", "Stored workflow version is not readable: " + e.getOriginalMessage());
        }
    }

    String write(WorkflowModel m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ legacy stage import

    @EventListener(ApplicationReadyEvent.class)
    public void syncAllOnStartup() {
        try {
            tx.executeWithoutResult(s -> definitions.findAll().forEach(this::syncLegacyStages));
        } catch (RuntimeException e) {
            log.error("Could not synchronise seeded workflow stages with workflow versions", e);
        }
    }

    /**
     * Imports the seeded {@code workflow_stage} rows as a new published version when they changed since the last
     * import (or when the process has no version yet). Must run inside a transaction.
     */
    void syncLegacyStages(WorkflowDefinition def) {
        String fp = fingerprint(def.getStages());
        if (fp.equals(def.getLegacyFingerprint())) return;
        boolean hasVersions = versions.countByDefinitionId(def.getId()) > 0;
        boolean firstSync = def.getLegacyFingerprint() == null;
        def.setLegacyFingerprint(fp);
        if (def.getStages().isEmpty() || (firstSync && hasVersions)) return;
        List<StageDef> stages = def.getStages().stream().map(s -> new StageDef(s.getName(), ActionType.APPROVE, Mode.ANY,
                new ArrayList<>(List.of(new ApproverDef(ApproverType.valueOf(s.getApproverType().name()), s.getApproverRef()))),
                null, s.getSlaHours(), s.getEscalationRoleCode(), null)).collect(Collectors.toCollection(ArrayList::new));
        WorkflowVersion v = new WorkflowVersion();
        v.setDefinitionId(def.getId());
        v.setVersionNo(versions.maxVersionNo(def.getId()) + 1);
        v.setStatus(WorkflowVersion.Status.PUBLISHED);
        v.setEffectiveFrom(Instant.now());
        v.setContent(write(WorkflowModel.singlePath(stages)));
        v.setSource("LEGACY_IMPORT");
        v.setChangeNote("Imported from the seeded approval chain");
        v.setPublishedAt(Instant.now());
        v.setPublishedBy("system");
        versions.save(v);
        log.info("Workflow {}: seeded stages imported as version {}", def.getProcessCode(), v.getVersionNo());
    }

    static String fingerprint(List<WorkflowStage> stages) {
        String s = stages.stream().sorted(Comparator.comparingInt(WorkflowStage::getStageOrder).thenComparing(x -> x.getId() == null ? 0 : x.getId()))
                .map(x -> x.getStageOrder() + "|" + x.getName() + "|" + x.getApproverType() + "|" + Objects.toString(x.getApproverRef(), "")
                        + "|" + Objects.toString(x.getSlaHours(), "") + "|" + Objects.toString(x.getEscalationRoleCode(), ""))
                .collect(Collectors.joining(";"));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ designer

    @Transactional
    public WorkflowDefinition createDefinition(String processCode, String name, String description) {
        String code = processCode == null ? "" : processCode.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{1,49}")) throw ApiException.badRequest("Process code must be upper-case letters, digits or _");
        if (definitions.findByProcessCode(code).isPresent()) throw ApiException.conflict("A workflow for " + code + " already exists");
        WorkflowDefinition d = new WorkflowDefinition();
        d.setProcessCode(code);
        d.setName(name.trim());
        d.setDescription(description);
        d.setActive(true);
        d.setLegacyFingerprint(fingerprint(List.of()));
        definitions.save(d);
        audit.log(AuditAction.CREATE, "WorkflowDefinition", d.getId(), "Workflow created for process " + code);
        return d;
    }

    /** New draft copied from {@code baseVersionId} (default: the version in force, else the latest). One draft at a time. */
    @Transactional
    public WorkflowVersion createDraft(Long definitionId, Long baseVersionId, String changeNote) {
        WorkflowDefinition def = definition(definitionId);
        versions.findByDefinitionIdAndStatusOrderByVersionNoDesc(def.getId(), WorkflowVersion.Status.DRAFT).stream().findFirst()
                .ifPresent(d -> {
                    throw ApiException.conflict("Draft version " + d.getVersionNo() + " already exists; edit, publish or discard it first");
                });
        WorkflowVersion base = baseVersionId != null ? version(baseVersionId)
                : effectiveVersion(def.getId(), Instant.now()).orElseGet(() ->
                versions.findByDefinitionIdOrderByVersionNoDesc(def.getId()).stream().findFirst().orElse(null));
        if (base != null && !base.getDefinitionId().equals(def.getId())) throw ApiException.badRequest("Base version belongs to another workflow");
        WorkflowVersion v = new WorkflowVersion();
        v.setDefinitionId(def.getId());
        v.setVersionNo(versions.maxVersionNo(def.getId()) + 1);
        v.setStatus(WorkflowVersion.Status.DRAFT);
        v.setContent(base != null ? base.getContent() : write(new WorkflowModel("STANDARD",
                new ArrayList<>(List.of(new PathDef("STANDARD", "Standard path", new ArrayList<>()))), new ArrayList<>())));
        v.setBasedOnVersionNo(base == null ? null : base.getVersionNo());
        v.setChangeNote(changeNote);
        v.setSource("DESIGNER");
        versions.save(v);
        audit.log(AuditAction.CREATE, "WorkflowVersion", v.getId(), "Draft version " + v.getVersionNo() + " of " + def.getProcessCode()
                + (base == null ? "" : " created from version " + base.getVersionNo()));
        return v;
    }

    @Transactional
    public WorkflowVersion updateDraft(Long versionId, WorkflowModel model, String changeNote) {
        WorkflowVersion v = version(versionId);
        if (v.getStatus() != WorkflowVersion.Status.DRAFT)
            throw ApiException.rule("NOT_DRAFT", "Version " + v.getVersionNo() + " is " + v.getStatus().name().toLowerCase()
                    + " and can no longer be changed; create a new draft");
        WorkflowModel normalized = normalize(model);
        validator.validateOrThrow(normalized);
        String before = v.getContent();
        v.setContent(write(normalized));
        if (changeNote != null) v.setChangeNote(changeNote);
        audit.log(AuditAction.CONFIG_CHANGE, "WorkflowVersion", v.getId(), "Draft version " + v.getVersionNo() + " edited",
                Map.of("content", before), Map.of("content", v.getContent()));
        return v;
    }

    /**
     * Publishes a draft. {@code effectiveDate} null or today = immediately; a future date = from the start of that
     * day (Sri Lanka time). Past dates are refused (G-3: earlier requests are not re-routed).
     */
    @Transactional
    public WorkflowVersion publish(Long versionId, LocalDate effectiveDate, String changeNote) {
        WorkflowVersion v = version(versionId);
        if (v.getStatus() != WorkflowVersion.Status.DRAFT) throw ApiException.rule("NOT_DRAFT", "Only a draft can be published");
        WorkflowModel m = parse(v.getContent());
        validator.validateOrThrow(m);
        LocalDate today = LocalDate.now(ZONE);
        if (effectiveDate != null && effectiveDate.isBefore(today))
            throw ApiException.rule("PAST_EFFECTIVE_DATE", "The effective date cannot be in the past");
        Instant eff = effectiveDate == null || effectiveDate.equals(today) ? Instant.now() : effectiveDate.atStartOfDay(ZONE).toInstant();
        if (changeNote != null && !changeNote.isBlank()) v.setChangeNote(changeNote);
        if (v.getChangeNote() == null || v.getChangeNote().isBlank())
            throw ApiException.badRequest("Describe the change (change note) before publishing");
        v.setStatus(WorkflowVersion.Status.PUBLISHED);
        v.setEffectiveFrom(eff);
        v.setPublishedAt(Instant.now());
        v.setPublishedBy(CurrentUser.username());
        WorkflowDefinition def = definition(v.getDefinitionId());
        audit.log(AuditAction.APPROVE, "WorkflowVersion", v.getId(), "Workflow " + def.getProcessCode() + " version " + v.getVersionNo()
                + " published, effective " + eff.atZone(ZONE).toLocalDate() + ": " + v.getChangeNote());
        return v;
    }

    /** Discards a draft, or withdraws a published version whose effective date has not been reached yet. */
    @Transactional
    public WorkflowVersion withdraw(Long versionId, String reason) {
        WorkflowVersion v = version(versionId);
        boolean scheduled = v.getStatus() == WorkflowVersion.Status.PUBLISHED && v.getEffectiveFrom() != null
                && v.getEffectiveFrom().isAfter(Instant.now());
        if (v.getStatus() != WorkflowVersion.Status.DRAFT && !scheduled)
            throw ApiException.rule("IN_FORCE", "A version that has taken effect cannot be withdrawn; publish a new version instead");
        v.setStatus(WorkflowVersion.Status.WITHDRAWN);
        publishedCache.remove(v.getId());
        audit.log(AuditAction.CANCEL, "WorkflowVersion", v.getId(), "Version " + v.getVersionNo() + " withdrawn"
                + (reason == null || reason.isBlank() ? "" : ": " + reason));
        return v;
    }

    /** Legacy single-chain edit (pre-designer API): publishes the chain as a new version in force immediately. */
    @Transactional
    public WorkflowVersion publishSingleChain(WorkflowDefinition def, List<StageDef> stages, String note) {
        WorkflowModel m = normalize(WorkflowModel.singlePath(new ArrayList<>(stages)));
        validator.validateOrThrow(m);
        versions.findByDefinitionIdAndStatusOrderByVersionNoDesc(def.getId(), WorkflowVersion.Status.DRAFT)
                .forEach(d -> d.setStatus(WorkflowVersion.Status.WITHDRAWN));
        WorkflowVersion v = new WorkflowVersion();
        v.setDefinitionId(def.getId());
        v.setVersionNo(versions.maxVersionNo(def.getId()) + 1);
        v.setStatus(WorkflowVersion.Status.PUBLISHED);
        v.setEffectiveFrom(Instant.now());
        v.setContent(write(m));
        v.setSource("DESIGNER");
        v.setChangeNote(note);
        v.setPublishedAt(Instant.now());
        v.setPublishedBy(CurrentUser.username());
        versions.save(v);
        return v;
    }

    /** Trims text, upper-cases codes and drops empty approvers so stored versions are canonical. */
    WorkflowModel normalize(WorkflowModel m) {
        if (m == null) throw ApiException.badRequest("Workflow content is required");
        List<PathDef> paths = new ArrayList<>();
        for (PathDef p : m.paths()) {
            List<StageDef> stages = new ArrayList<>();
            for (StageDef s : p.stages()) {
                List<ApproverDef> appr = s.approvers().stream().filter(Objects::nonNull)
                        .map(a -> new ApproverDef(a.type(), a.ref() == null || a.ref().isBlank() ? null : a.ref().trim()))
                        .collect(Collectors.toCollection(ArrayList::new));
                WalkUpDef w = s.walkUp() == null ? null : new WalkUpDef(s.walkUp().from() == null ? "INITIATOR" : s.walkUp().from().trim().toUpperCase(Locale.ROOT),
                        s.walkUp().until(), s.walkUp().value() == null ? null : s.walkUp().value().trim(), s.walkUp().maxLevels(), s.walkUp().skipVacant());
                stages.add(new StageDef(trim(s.name()), s.actionType(), s.mode(), w != null ? new ArrayList<>() : appr, w, s.slaHours(),
                        s.escalationRoleCode() == null || s.escalationRoleCode().isBlank() ? null : s.escalationRoleCode().trim(),
                        s.when() == null || s.when().isEmpty() ? null : s.when()));
            }
            paths.add(new PathDef(p.code() == null ? null : p.code().trim().toUpperCase(Locale.ROOT), trim(p.name()), stages));
        }
        List<RuleDef> rules = m.rules().stream().map(r -> new RuleDef(trim(r.name()),
                r.path() == null ? null : r.path().trim().toUpperCase(Locale.ROOT), r.when())).collect(Collectors.toCollection(ArrayList::new));
        String def = m.defaultPath() == null ? (paths.isEmpty() ? null : paths.get(0).code()) : m.defaultPath().trim().toUpperCase(Locale.ROOT);
        return new WorkflowModel(def, paths, rules);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    // ------------------------------------------------------------------ compare (REQ 10.9)

    public record Change(String area, String kind, String before, String after) {
    }

    public record Comparison(Integer fromVersionNo, Integer toVersionNo, List<Change> changes) {
    }

    @Transactional(readOnly = true)
    public Comparison compare(Long fromVersionId, Long toVersionId) {
        WorkflowVersion a = fromVersionId == null ? null : version(fromVersionId);
        WorkflowVersion b = version(toVersionId);
        if (a != null && !a.getDefinitionId().equals(b.getDefinitionId()))
            throw ApiException.badRequest("Both versions must belong to the same workflow");
        return new Comparison(a == null ? null : a.getVersionNo(), b.getVersionNo(), diff(a == null ? null : model(a), model(b)));
    }

    static List<Change> diff(WorkflowModel a, WorkflowModel b) {
        List<Change> out = new ArrayList<>();
        WorkflowModel empty = new WorkflowModel(null, null, null);
        if (a == null) a = empty;
        if (!Objects.equals(a.defaultPath(), b.defaultPath()))
            out.add(new Change("Default path", "CHANGED", a.defaultPath(), b.defaultPath()));
        Set<String> codes = new LinkedHashSet<>();
        a.paths().forEach(p -> codes.add(p.code()));
        b.paths().forEach(p -> codes.add(p.code()));
        for (String code : codes) {
            PathDef pa = a.path(code), pb = b.path(code);
            String area = "Path " + code;
            if (pa == null) {
                out.add(new Change(area, "ADDED", null, pb.name() + ": " + pb.stages().stream().map(WorkflowVersionService::describeStage).collect(Collectors.joining(" → "))));
                continue;
            }
            if (pb == null) {
                out.add(new Change(area, "REMOVED", pa.name(), null));
                continue;
            }
            if (!Objects.equals(pa.name(), pb.name())) out.add(new Change(area + " name", "CHANGED", pa.name(), pb.name()));
            int n = Math.max(pa.stages().size(), pb.stages().size());
            for (int i = 0; i < n; i++) {
                StageDef sa = i < pa.stages().size() ? pa.stages().get(i) : null;
                StageDef sb = i < pb.stages().size() ? pb.stages().get(i) : null;
                String sArea = area + " · stage " + (i + 1);
                if (sa == null) out.add(new Change(sArea, "ADDED", null, describeStage(sb)));
                else if (sb == null) out.add(new Change(sArea, "REMOVED", describeStage(sa), null));
                else if (!describeStage(sa).equals(describeStage(sb))) out.add(new Change(sArea, "CHANGED", describeStage(sa), describeStage(sb)));
            }
        }
        int n = Math.max(a.rules().size(), b.rules().size());
        for (int i = 0; i < n; i++) {
            RuleDef ra = i < a.rules().size() ? a.rules().get(i) : null;
            RuleDef rb = i < b.rules().size() ? b.rules().get(i) : null;
            String area = "Rule " + (i + 1);
            if (ra == null) out.add(new Change(area, "ADDED", null, describeRule(rb)));
            else if (rb == null) out.add(new Change(area, "REMOVED", describeRule(ra), null));
            else if (!describeRule(ra).equals(describeRule(rb))) out.add(new Change(area, "CHANGED", describeRule(ra), describeRule(rb)));
        }
        return out;
    }

    static String describeRule(RuleDef r) {
        return r.name() + ": if " + describeCondition(r.when()) + " → path " + r.path();
    }

    static String describeStage(StageDef s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.name()).append(" [").append(s.actionType()).append("] ");
        if (s.walkUp() != null) {
            WalkUpDef w = s.walkUp();
            sb.append("walk up from ").append(w.fromSubject() ? "subject" : "initiator").append(" until ").append(w.until())
                    .append(w.value() == null ? "" : " " + w.value()).append(" (max ").append(w.levels()).append(" levels")
                    .append(w.skipsVacant() ? ", skip vacant" : "").append(")");
        } else {
            sb.append(s.approvers().stream().map(a -> a.type() + (a.ref() == null ? "" : " " + a.ref())).collect(Collectors.joining(s.mode() == Mode.ALL ? " AND " : " OR ")));
            if (s.approvers().size() > 1) sb.append(" (").append(s.mode() == Mode.ALL ? "all must act" : "any one").append(")");
        }
        if (s.slaHours() != null) sb.append(" · SLA ").append(s.slaHours()).append("h");
        if (s.escalationRoleCode() != null) sb.append(" · escalate to ").append(s.escalationRoleCode());
        if (s.when() != null && !s.when().isEmpty()) sb.append(" · only if ").append(describeCondition(s.when()));
        return sb.toString();
    }

    static String describeCondition(Condition c) {
        if (c == null || c.isEmpty()) return "always";
        List<String> parts = new ArrayList<>();
        for (Clause cl : c.clauses()) {
            if (cl == null || cl.operator() == null) continue;
            parts.add(cl.attribute() + " " + ConditionEvaluator.symbol(cl.operator()) + (ConditionEvaluator.needsValue(cl.operator()) ? " " + cl.value() : ""));
        }
        for (Condition g : c.groups()) parts.add("(" + describeCondition(g) + ")");
        return String.join(c.join() == Join.OR ? " OR " : " AND ", parts);
    }
}
