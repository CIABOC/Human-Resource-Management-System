package lk.gov.ciaboc.hrms.recruitment.sor;

import lk.gov.ciaboc.hrms.recruitment.sor.SorEntities.Sor;
import lk.gov.ciaboc.hrms.recruitment.sor.SorEntities.SorStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SorRepository extends JpaRepository<Sor, Long> {
    List<Sor> findAllByOrderBySorNoAscVersionNoDesc();

    List<Sor> findBySeriesIdOrderByVersionNoDesc(Long seriesId);

    List<Sor> findByDesignationIdAndStatusOrderByApprovedAtDesc(Long designationId, SorStatus status);

    boolean existsBySorNo(String sorNo);

    Optional<Sor> findFirstBySeriesIdOrderByVersionNoDesc(Long seriesId);
}
