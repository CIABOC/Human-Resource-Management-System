package lk.gov.ciaboc.hrms.extended.separation;

import lk.gov.ciaboc.hrms.extended.separation.SeparationEntities.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// Spring Data repositories of the offboarding sub-module (top-level so repository scanning picks them up).

interface SeparationTypeRepository extends JpaRepository<SeparationType, Long> {
    List<SeparationType> findAllByOrderBySortOrderAscNameEnAsc();

    Optional<SeparationType> findByCode(String code);

    long countByDefaultSeparationReason(String code);
}

interface ClearanceTemplateRepository extends JpaRepository<ClearanceItemTemplate, Long> {
    List<ClearanceItemTemplate> findAllByOrderBySortOrderAscCodeAsc();

    List<ClearanceItemTemplate> findByActiveTrueOrderBySortOrderAscCodeAsc();

    boolean existsByCode(String code);
}

interface SeparationCaseRepository extends JpaRepository<SeparationCase, Long> {
    List<SeparationCase> findAllByOrderByIdDesc();

    List<SeparationCase> findByStatus(SeparationCase.Status status);

    Optional<SeparationCase> findFirstByEmployeeIdAndStatus(Long employeeId, SeparationCase.Status status);

    long countBySeparationReason(String code);
}

interface ClearanceRepository extends JpaRepository<Clearance, Long> {
    List<Clearance> findByCaseIdOrderBySortOrderAscIdAsc(Long caseId);

    List<Clearance> findByCaseIdIn(Collection<Long> caseIds);

    List<Clearance> findByStatusAndResponsibleRoleIn(Clearance.Status status, Collection<String> roles);
}
