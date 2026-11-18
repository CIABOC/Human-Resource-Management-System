package lk.gov.ciaboc.hrms.extended.movement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MovementRequestRepository extends JpaRepository<MovementRequest, Long> {
    List<MovementRequest> findAllByOrderByIdDesc();

    List<MovementRequest> findByEmployeeIdOrderByIdDesc(Long employeeId);

    boolean existsByEmployeeIdAndStatusIn(Long employeeId, Collection<MovementRequest.Status> statuses);

    /** Other open requests that would consume a vacancy in the same designation and unit. */
    @Query("select count(m) from MovementRequest m where m.status in :open and m.targetDesignationId = :des " +
            "and m.targetOrgUnitCode = :unit and m.id <> :exclude and m.requestType <> :excludedType")
    long countOpenTargeting(@Param("open") Collection<MovementRequest.Status> open, @Param("des") Long designationId,
                            @Param("unit") String unit, @Param("exclude") Long excludeId,
                            @Param("excludedType") MovementRequest.Type excludedType);
}
