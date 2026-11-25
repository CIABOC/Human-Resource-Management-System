package lk.gov.ciaboc.hrms.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, Long> {
    Optional<WorkflowDefinition> findByProcessCode(String processCode);
}

interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, Long> {
    List<WorkflowInstance> findByStatusOrderBySubmittedAtAsc(WorkflowInstance.Status status);

    List<WorkflowInstance> findByRequesterUserIdOrderByIdDesc(Long userId);

    List<WorkflowInstance> findByEntityTypeAndEntityIdOrderByIdDesc(String entityType, Long entityId);

    long countByStatus(WorkflowInstance.Status status);

    @Query("select distinct a.instanceId from ApprovalAction a where a.actorUserId = :userId")
    List<Long> instanceIdsActedOnBy(@Param("userId") Long userId);

    List<WorkflowInstance> findByIdInOrderByIdDesc(Collection<Long> ids);

    List<WorkflowInstance> findByProcessCodeAndStatusInOrderByIdAsc(String processCode, Collection<WorkflowInstance.Status> statuses);

    @Query("select i.versionId, count(i) from WorkflowInstance i where i.processCode = :p and i.status in :st group by i.versionId")
    List<Object[]> openCountsByVersion(@Param("p") String processCode, @Param("st") Collection<WorkflowInstance.Status> statuses);
}

interface ApprovalActionRepository extends JpaRepository<ApprovalAction, Long> {
    List<ApprovalAction> findByInstanceIdOrderByIdAsc(Long instanceId);

    boolean existsByInstanceIdAndActorUserId(Long instanceId, Long actorUserId);
}

interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, Long> {
    List<WorkflowVersion> findByDefinitionIdOrderByVersionNoDesc(Long definitionId);

    List<WorkflowVersion> findByDefinitionIdAndStatusOrderByVersionNoDesc(Long definitionId, WorkflowVersion.Status status);

    @Query("select coalesce(max(v.versionNo), 0) from WorkflowVersion v where v.definitionId = :def")
    int maxVersionNo(@Param("def") Long definitionId);

    long countByDefinitionId(Long definitionId);
}

interface WorkflowInstanceStepRepository extends JpaRepository<WorkflowInstanceStep, Long> {
    List<WorkflowInstanceStep> findByInstanceIdAndRoundOrderByStepOrderAsc(Long instanceId, int round);

    List<WorkflowInstanceStep> findByInstanceIdOrderByRoundAscStepOrderAsc(Long instanceId);

    long countByInstanceId(Long instanceId);
}

interface DelegationRepository extends JpaRepository<Delegation, Long> {
    @Query("select d from Delegation d where d.active = true and d.startDate <= :day and d.endDate >= :day")
    List<Delegation> activeOn(@Param("day") LocalDate day);

    List<Delegation> findByFromUserIdOrToUserIdOrderByStartDateDesc(Long fromUserId, Long toUserId);

    List<Delegation> findAllByOrderByStartDateDesc();
}
