package lk.gov.ciaboc.hrms.establishment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CategorizationBatchRepository extends JpaRepository<Categorization.Batch, Long> {
    List<Categorization.Batch> findByStatusIn(Collection<Categorization.Batch.Status> statuses);
}
