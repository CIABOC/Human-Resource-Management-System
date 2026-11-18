package lk.gov.ciaboc.hrms.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only, hash-chained audit record (FR-AUD-001..006, NFR-SEC-04). Each row's hash covers its content and the
 * previous row's hash, so any modification or deletion breaks the chain. A database trigger also rejects
 * UPDATE/DELETE on this table.
 */
@Getter
@Setter
@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id", length = 60)
    private String entityId;

    @Column(length = 1000)
    private String summary;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(nullable = false, length = 64)
    private String hash;
}
