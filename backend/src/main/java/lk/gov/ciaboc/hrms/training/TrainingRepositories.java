package lk.gov.ciaboc.hrms.training;

import lk.gov.ciaboc.hrms.training.TrainingEntities.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface TrainingProgrammeRepository extends JpaRepository<TrainingProgramme, Long> {
    List<TrainingProgramme> findAllByOrderByStartDateDesc();

    List<TrainingProgramme> findByStatusOrderByStartDateAsc(ProgrammeStatus status);

    boolean existsByCode(String code);
}

interface TrainingNominationRepository extends JpaRepository<TrainingNomination, Long> {
    List<TrainingNomination> findByProgrammeIdOrderByIdAsc(Long programmeId);

    List<TrainingNomination> findByEmployeeIdOrderByIdDesc(Long employeeId);

    long countByProgrammeIdAndStatusIn(Long programmeId, Collection<NominationStatus> statuses);

    boolean existsByProgrammeIdAndEmployeeIdAndStatusIn(Long programmeId, Long employeeId, Collection<NominationStatus> statuses);
}
