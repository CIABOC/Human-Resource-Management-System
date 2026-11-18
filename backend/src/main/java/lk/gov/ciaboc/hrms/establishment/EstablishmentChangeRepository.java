package lk.gov.ciaboc.hrms.establishment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EstablishmentChangeRepository extends JpaRepository<EstablishmentChange, Long> {
    List<EstablishmentChange> findAllByOrderByIdDesc();

    List<EstablishmentChange> findByStatusOrderByIdDesc(EstablishmentChange.Status status);

    List<EstablishmentChange> findByChangeTypeAndStatusIn(EstablishmentChange.ChangeType type,
                                                          Collection<EstablishmentChange.Status> statuses);
}
