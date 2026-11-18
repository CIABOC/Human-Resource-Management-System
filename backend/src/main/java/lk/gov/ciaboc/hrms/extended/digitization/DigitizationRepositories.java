package lk.gov.ciaboc.hrms.extended.digitization;

import lk.gov.ciaboc.hrms.extended.digitization.DigitizationEntities.Batch;
import lk.gov.ciaboc.hrms.extended.digitization.DigitizationEntities.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

// Spring Data repositories of the digitization sub-module (top-level so repository scanning picks them up).

interface BatchRepository extends JpaRepository<Batch, Long> {
    List<Batch> findAllByOrderByIdDesc();

    @Query(value = "SELECT nextval('ext_batch_no_seq')", nativeQuery = true)
    long nextNumber();
}

interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findByBatchIdOrderByIdAsc(Long batchId);

    long countByDocumentType(String documentType);
}
