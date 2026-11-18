package lk.gov.ciaboc.hrms.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import lk.gov.ciaboc.hrms.common.Json;
import lk.gov.ciaboc.hrms.security.CurrentUser;
import lk.gov.ciaboc.hrms.security.Hashing;
import lk.gov.ciaboc.hrms.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuditService {
    private static final long CHAIN_LOCK_KEY = 7_311_2026L;

    private final AuditLogRepository repo;
    private final EntityManager em;

    /** Records an action by the current user. Runs in its own transaction so failed business operations that were attempted are still visible. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AuditAction action, String entityType, Object entityId, String summary, Object oldValue, Object newValue) {
        Optional<UserPrincipal> p = CurrentUser.find();
        write(action, entityType, entityId, summary, oldValue, newValue, p.map(UserPrincipal::getId).orElse(null),
                p.map(UserPrincipal::getUsername).orElse("system"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AuditAction action, String entityType, Object entityId, String summary) {
        Optional<UserPrincipal> p = CurrentUser.find();
        write(action, entityType, entityId, summary, null, null, p.map(UserPrincipal::getId).orElse(null),
                p.map(UserPrincipal::getUsername).orElse("system"));
    }

    /** Variant for flows where there is no authenticated principal yet (login). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAs(Long userId, String username, AuditAction action, String entityType, Object entityId, String summary) {
        write(action, entityType, entityId, summary, null, null, userId, username);
    }

    private void write(AuditAction action, String entityType, Object entityId, String summary, Object oldValue,
                       Object newValue, Long userId, String username) {
        // Serialize chain appends so every row links to its true predecessor.
        em.createNativeQuery("select pg_advisory_xact_lock(:k)").setParameter("k", CHAIN_LOCK_KEY).getSingleResult();
        AuditLog row = new AuditLog();
        row.setOccurredAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        row.setUserId(userId);
        row.setUsername(username);
        row.setAction(action.name());
        row.setEntityType(entityType);
        row.setEntityId(entityId == null ? null : String.valueOf(entityId));
        row.setSummary(truncate(summary, 1000));
        row.setOldValue(Json.write(oldValue));
        row.setNewValue(Json.write(newValue));
        row.setIpAddress(clientIp());
        row.setPrevHash(repo.findTopByOrderByIdDesc().map(AuditLog::getHash).orElse("GENESIS"));
        row.setHash(computeHash(row));
        repo.save(row);
    }

    static String computeHash(AuditLog r) {
        String material = String.join("|", r.getPrevHash(), String.valueOf(r.getOccurredAt()),
                String.valueOf(r.getUserId()), r.getUsername(), r.getAction(), String.valueOf(r.getEntityType()),
                String.valueOf(r.getEntityId()), String.valueOf(r.getSummary()), String.valueOf(r.getOldValue()),
                String.valueOf(r.getNewValue()), String.valueOf(r.getIpAddress()));
        return Hashing.sha256(material);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> search(String username, String action, String entityType, String entityId, Instant from,
                                 Instant to, Pageable pageable) {
        return repo.findAll(spec(username, action, entityType, entityId, from, to), pageable);
    }

    public static Specification<AuditLog> spec(String username, String action, String entityType, String entityId,
                                               Instant from, Instant to) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (username != null && !username.isBlank())
                ps.add(cb.like(cb.lower(root.get("username")), "%" + username.toLowerCase() + "%"));
            if (action != null && !action.isBlank()) ps.add(cb.equal(root.get("action"), action));
            if (entityType != null && !entityType.isBlank()) ps.add(cb.equal(root.get("entityType"), entityType));
            if (entityId != null && !entityId.isBlank()) ps.add(cb.equal(root.get("entityId"), entityId));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) ps.add(cb.lessThan(root.get("occurredAt"), to));
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    /** Walks the whole chain and reports the first broken link, if any. */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain() {
        String prev = "GENESIS";
        long checked = 0;
        int page = 0;
        Page<AuditLog> batch;
        do {
            batch = repo.findAll(PageRequest.of(page++, 1000, Sort.by("id")));
            for (AuditLog r : batch) {
                if (!prev.equals(r.getPrevHash()) || !computeHash(r).equals(r.getHash())) {
                    return new ChainVerification(false, checked, r.getId(),
                            "Audit chain broken at entry " + r.getId());
                }
                prev = r.getHash();
                checked++;
            }
        } while (batch.hasNext());
        return new ChainVerification(true, checked, null, "Audit chain intact");
    }

    public record ChainVerification(boolean valid, long entriesChecked, Long brokenAtId, String message) {
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            String fwd = req.getHeader("X-Forwarded-For");
            return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
