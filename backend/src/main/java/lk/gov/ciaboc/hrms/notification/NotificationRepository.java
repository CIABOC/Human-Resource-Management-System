package lk.gov.ciaboc.hrms.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Page<Notification> findByRecipientUserIdOrderByIdDesc(Long userId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndReadFalseOrderByIdDesc(Long userId, Pageable pageable);

    long countByRecipientUserIdAndReadFalse(Long userId);

    @Modifying
    @Query("update Notification n set n.read = true, n.readAt = :now where n.recipientUserId = :userId and n.read = false")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
