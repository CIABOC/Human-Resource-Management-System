package lk.gov.ciaboc.hrms.establishment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CategorizationLineRepository extends JpaRepository<Categorization.Line, Long> {
    List<Categorization.Line> findByBatchId(Long batchId);

    List<Categorization.Line> findByBatchIdIn(Collection<Long> batchIds);
}
