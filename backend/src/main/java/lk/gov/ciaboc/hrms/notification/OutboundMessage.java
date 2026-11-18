package lk.gov.ciaboc.hrms.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Queued e-mail / SMS. Delivery status is recorded (FR-NOT-002) and failures are retried with back-off so an
 * unavailable external service never blocks the core system (NFR-REL-04).
 */
@Getter
@Setter
@Entity
@Table(name = "outbound_message")
public class OutboundMessage {
    public enum Status {PENDING, SENT, SIMULATED, FAILED, SKIPPED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String channel;

    @Column(nullable = false, length = 200)
    private String recipient;

    @Column(length = 300)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** OTP and similar secrets are masked when listed for administrators. */
    @Column(nullable = false)
    private boolean sensitive;
}
