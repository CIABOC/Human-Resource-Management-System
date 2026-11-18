package lk.gov.ciaboc.hrms.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Long> {
    List<OutboundMessage> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(OutboundMessage.Status status, Instant now);

    Page<OutboundMessage> findAllByOrderByIdDesc(Pageable pageable);
}
