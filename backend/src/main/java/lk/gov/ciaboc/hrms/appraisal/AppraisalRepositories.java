package lk.gov.ciaboc.hrms.appraisal;

import lk.gov.ciaboc.hrms.appraisal.AppraisalEntities.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface AprPolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {
    List<PolicyVersion> findAllByOrderByVersionNoDesc();

    Optional<PolicyVersion> findFirstByStatusOrderByVersionNoDesc(PolicyStatus status);

    List<PolicyVersion> findByStatusIn(Collection<PolicyStatus> statuses);
}

interface AprRatingBandRepository extends JpaRepository<RatingBand, Long> {
    List<RatingBand> findByPolicyVersionIdOrderBySortOrderAsc(Long policyVersionId);

    void deleteByPolicyVersionId(Long policyVersionId);
}

interface AprBehaviourCriterionRepository extends JpaRepository<BehaviourCriterion, Long> {
    List<BehaviourCriterion> findByActiveTrueOrderBySortOrderAsc();
}

interface AprAppraisalRepository extends JpaRepository<Appraisal, Long> {
    List<Appraisal> findByPeriodId(Long periodId);

    Optional<Appraisal> findByPeriodIdAndEmployeeId(Long periodId, Long employeeId);

    List<Appraisal> findByEmployeeIdOrderByIdDesc(Long employeeId);

    List<Appraisal> findBySupervisorUserIdIn(Collection<Long> userIds);

    List<Appraisal> findByReviewerUserIdIn(Collection<Long> userIds);

    List<Appraisal> findByStatus(Status status);

    boolean existsByPeriodIdAndEmployeeId(Long periodId, Long employeeId);
}

interface AprKpiRepository extends JpaRepository<Kpi, Long> {
    List<Kpi> findByAppraisalIdOrderBySortOrderAscIdAsc(Long appraisalId);

    List<Kpi> findByAppraisalIdIn(Collection<Long> appraisalIds);
}

interface AprKpiSubmissionRepository extends JpaRepository<KpiSubmission, Long> {
    List<KpiSubmission> findByKpiIdInOrderByVersionNoDesc(Collection<Long> kpiIds);

    Optional<KpiSubmission> findFirstByKpiIdOrderByVersionNoDesc(Long kpiId);
}

interface AprEvidenceRepository extends JpaRepository<Evidence, Long> {
    List<Evidence> findByKpiIdInOrderByIdAsc(Collection<Long> kpiIds);

    List<Evidence> findByKpiId(Long kpiId);
}

interface AprBehaviourRatingRepository extends JpaRepository<BehaviourRating, Long> {
    List<BehaviourRating> findByAppraisalId(Long appraisalId);
}

interface AprTrailRepository extends JpaRepository<Trail, Long> {
    List<Trail> findByAppraisalIdOrderByActedAtAscIdAsc(Long appraisalId);
}

interface AprAgreementHistoryRepository extends JpaRepository<AgreementHistory, Long> {
    List<AgreementHistory> findByAppraisalIdOrderByVersionNoDesc(Long appraisalId);
}

interface AprAppealRepository extends JpaRepository<Appeal, Long> {
    List<Appeal> findByAppraisalIdInOrderBySubmittedAtDesc(Collection<Long> appraisalIds);

    List<Appeal> findAllByOrderBySubmittedAtDesc();

    long count();
}

interface AprCheckinRepository extends JpaRepository<Checkin, Long> {
    List<Checkin> findByEmployeeIdOrderByCheckinDateDesc(Long employeeId);

    List<Checkin> findBySupervisorUserIdOrderByCheckinDateDesc(Long userId);
}

interface AprFeedbackRepository extends JpaRepository<Feedback, Long> {
    List<Feedback> findByToUserIdOrderByCreatedAtDesc(Long userId);

    List<Feedback> findByToEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    List<Feedback> findByFromUserIdOrderByCreatedAtDesc(Long userId);
}

interface AprSpecialCaseRepository extends JpaRepository<SpecialCase, Long> {
    List<SpecialCase> findAllByOrderByCreatedAtDesc();

    List<SpecialCase> findByRequestedByUserIdOrderByCreatedAtDesc(Long userId);
}

interface AprDelegationRepository extends JpaRepository<Delegation, Long> {
    List<Delegation> findByDelegatorUserIdOrderByCreatedAtDesc(Long userId);

    List<Delegation> findByDelegateUserIdAndStatus(Long userId, String status);
}

interface AprReopenRepository extends JpaRepository<Reopen, Long> {
    List<Reopen> findAllByOrderByCreatedAtDesc();

    List<Reopen> findByAppraisalIdAndStatus(Long appraisalId, ReopenStatus status);

    List<Reopen> findByAppraisalIdAndStatusIn(Long appraisalId, Collection<ReopenStatus> statuses);
}

interface AprSyncModuleRepository extends JpaRepository<SyncModule, Long> {
    List<SyncModule> findAllByOrderBySortOrderAsc();

    Optional<SyncModule> findByModuleCode(String code);
}

interface AprSyncFailureRepository extends JpaRepository<SyncFailure, Long> {
    List<SyncFailure> findTop20ByOrderByFailedAtDesc();
}
