package lk.gov.ciaboc.hrms.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
    Optional<AuditLog> findTopByOrderByIdDesc();

    List<AuditLog> findByUserIdAndActionInOrderByOccurredAtDesc(Long userId, List<String> actions, Pageable pageable);
}
