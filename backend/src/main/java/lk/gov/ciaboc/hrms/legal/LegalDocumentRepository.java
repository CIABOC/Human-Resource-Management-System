package lk.gov.ciaboc.hrms.legal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, Long> {
    List<LegalDocument> findAllByOrderByDocTypeAscPublicationDateDesc();

    List<LegalDocument> findBySeriesIdOrderByVersionNoDesc(Long seriesId);
}
