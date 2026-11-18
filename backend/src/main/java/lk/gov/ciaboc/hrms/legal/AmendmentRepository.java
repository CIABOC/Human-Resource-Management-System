package lk.gov.ciaboc.hrms.legal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AmendmentRepository extends JpaRepository<Amendment, Long> {
    List<Amendment> findByOriginalDocumentIdOrderByEffectiveDateDesc(Long documentId);
}
