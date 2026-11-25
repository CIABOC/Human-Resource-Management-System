package lk.gov.ciaboc.hrms.workflow;

import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lk.gov.ciaboc.hrms.security.AppUser;
import lk.gov.ciaboc.hrms.security.AppUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * Turns approver slots into the people who may act now: holders of the role / post, the named officer or the
 * division head; never the requester (segregation of duties); a slot nobody can take falls back to the stage's
 * escalation role, else the configured {@code workflow.fallback-role}; active acting arrangements add delegates.
 */
@Slf4j
@Component
class ApproverResolver {
    private final AppUserRepository users;
    private final OrgDirectory orgDirectory;
    private final DelegationRepository delegations;
    private final SettingsService settings;

    ApproverResolver(AppUserRepository users, @Lazy OrgDirectory org, DelegationRepository delegations, SettingsService settings) {
        this.users = users;
        this.orgDirectory = org;
        this.delegations = delegations;
        this.settings = settings;
    }

    /** A person who may act on a slot, possibly on behalf of the actual approver. */
    record Candidate(int slotIndex, Long actorUserId, Long onBehalfOfUserId, boolean fallback, boolean delegate) {
    }

    /** Slot as needed for resolution (instance slot or simulated slot). */
    record SlotRef(WorkflowModel.ApproverType type, String ref) {
    }

    String fallbackRole(String escalationRoleCode) {
        return escalationRoleCode != null && !escalationRoleCode.isBlank() ? escalationRoleCode
                : settings.get("workflow.fallback-role", "HR_ADMIN");
    }

    /** Users holding the slot (before SoD, fallback and delegation). */
    Set<Long> holders(SlotRef slot, String subjectOrgUnitCode, Long requesterUserId) {
        Set<Long> base = new LinkedHashSet<>();
        if (slot.type() == null) return base;
        switch (slot.type()) {
            case ROLE -> users.findActiveByRole(slot.ref()).forEach(u -> base.add(u.getId()));
            case USER -> {
                try {
                    users.findById(Long.parseLong(slot.ref())).filter(AppUser::isActive).ifPresent(u -> base.add(u.getId()));
                } catch (NumberFormatException | NullPointerException e) {
                    log.warn("Workflow slot has invalid user reference {}", slot.ref());
                }
            }
            case POSITION -> {
                if (slot.ref() != null) base.addAll(orgDirectory.userIdsHoldingPosition(slot.ref()));
            }
            case DIVISION_HEAD -> {
                if (subjectOrgUnitCode != null) base.addAll(orgDirectory.divisionHeadUserIdsFor(subjectOrgUnitCode, requesterUserId));
            }
            case UNIT_HEAD -> {
                String unit = slot.ref() == null || slot.ref().isBlank() ? subjectOrgUnitCode : slot.ref();
                String head = orgDirectory.headPostOfUnit(unit);
                if (head != null) base.addAll(orgDirectory.userIdsHoldingPosition(head));
            }
            case SUPERVISOR -> {
                // Resolved to a POSITION when the path is built; kept for robustness.
            }
        }
        return base;
    }

    /**
     * Candidates for the given pending slots. {@code excluded} are people who already acted on this step (a parallel
     * step needs different people) — plus always the requester.
     */
    Map<Long, Candidate> candidates(List<SlotRef> slots, List<Integer> pendingIndexes, String escalationRoleCode,
                                    String subjectOrgUnitCode, Long requesterUserId, String processCode, Set<Long> excluded) {
        Map<Long, Candidate> out = new LinkedHashMap<>();
        List<Delegation> active = delegations.activeOn(LocalDate.now());
        for (int idx : pendingIndexes) {
            SlotRef slot = slots.get(idx);
            Set<Long> base = holders(slot, subjectOrgUnitCode, requesterUserId);
            base.remove(requesterUserId);
            base.removeAll(excluded);
            boolean fallback = false;
            if (base.isEmpty()) {
                fallback = true;
                users.findActiveByRole(fallbackRole(escalationRoleCode)).forEach(u -> base.add(u.getId()));
                base.remove(requesterUserId);
                base.removeAll(excluded);
            }
            for (Long id : base) out.putIfAbsent(id, new Candidate(idx, id, null, fallback, false));
            for (Delegation d : active) {
                if (base.contains(d.getFromUserId()) && (d.getProcessCode() == null || d.getProcessCode().equals(processCode))
                        && !d.getToUserId().equals(requesterUserId) && !excluded.contains(d.getToUserId())) {
                    out.putIfAbsent(d.getToUserId(), new Candidate(idx, d.getToUserId(), d.getFromUserId(), fallback, true));
                }
            }
        }
        out.remove(requesterUserId);
        return out;
    }

    String userName(Long id) {
        return id == null ? null : users.findById(id).map(AppUser::getFullName).orElse("User #" + id);
    }
}
