package lk.gov.ciaboc.hrms.legal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface LegalSectionRepository extends JpaRepository<LegalSection, Long> {
    @Query("select s from LegalSection s where s.documentId = :doc and s.effectiveFrom <= :d " +
            "and (s.effectiveTo is null or s.effectiveTo > :d) order by s.sortOrder, s.number")
    List<LegalSection> effectiveOn(@Param("doc") Long documentId, @Param("d") LocalDate date);

    List<LegalSection> findBySectionKeyOrderByVersionNoDesc(String sectionKey);

    @Query("select s from LegalSection s join s.designationIds d where d = :des and (s.effectiveTo is null or s.effectiveTo > :d2)")
    List<LegalSection> currentForDesignation(@Param("des") Long designationId, @Param("d2") LocalDate today);

    @Query("select s from LegalSection s join s.tags t where t = :tag and (s.effectiveTo is null or s.effectiveTo > :d2)")
    List<LegalSection> currentForTag(@Param("tag") String tag, @Param("d2") LocalDate today);
}
