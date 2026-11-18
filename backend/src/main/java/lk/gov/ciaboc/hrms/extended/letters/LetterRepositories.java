package lk.gov.ciaboc.hrms.extended.letters;

import lk.gov.ciaboc.hrms.extended.letters.LetterEntities.IssuedDocument;
import lk.gov.ciaboc.hrms.extended.letters.LetterEntities.LetterRequest;
import lk.gov.ciaboc.hrms.extended.letters.LetterEntities.LetterTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// Spring Data repositories of the correspondence sub-module (top-level so repository scanning picks them up).

interface ExtendedLetterTemplateRepository extends JpaRepository<LetterTemplate, Long> {
    List<LetterTemplate> findAllByOrderByCodeAscLanguageAsc();

    Optional<LetterTemplate> findByCodeAndLanguage(String code, String language);

    List<LetterTemplate> findByCode(String code);
}

interface ExtendedLetterRequestRepository extends JpaRepository<LetterRequest, Long> {
    List<LetterRequest> findAllByOrderByIdDesc();

    List<LetterRequest> findByEmployeeIdOrderByIdDesc(Long employeeId);

    boolean existsByEmployeeIdAndTemplateCodeAndStatusIn(Long employeeId, String templateCode, Collection<LetterRequest.Status> statuses);
}

interface ExtendedIssuedDocumentRepository extends JpaRepository<IssuedDocument, Long> {
    List<IssuedDocument> findAllByOrderByIdDesc();

    List<IssuedDocument> findByEmployeeIdOrderByIdDesc(Long employeeId);

    Optional<IssuedDocument> findByVerificationCode(String code);

    boolean existsByVerificationCode(String code);

    @Query(value = "SELECT nextval('ext_document_no_seq')", nativeQuery = true)
    long nextNumber();
}
