package lk.gov.ciaboc.hrms.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "notification")
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Column(name = "template_code", length = 60)
    private String templateCode;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 2000)
    private String body;

    /** Front-end route the user is taken to, e.g. /approvals/12. */
    @Column(length = 300)
    private String link;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
