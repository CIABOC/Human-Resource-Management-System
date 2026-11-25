package lk.gov.ciaboc.hrms.attendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface PublicHolidayRepository extends JpaRepository<PublicHoliday, Long> {
    List<PublicHoliday> findByHolidayDateBetweenOrderByHolidayDateAsc(LocalDate from, LocalDate to);

    Optional<PublicHoliday> findByHolidayDate(LocalDate date);

    boolean existsByHolidayDate(LocalDate date);
}

interface DutyPolicyRepository extends JpaRepository<DutyPolicy, Long> {
    @Query("select p from DutyPolicy p where p.status = 'APPROVED' and p.effectiveFrom <= :d and (p.effectiveTo is null or p.effectiveTo > :d) order by p.code")
    List<DutyPolicy> approvedOn(@Param("d") LocalDate date);

    @Query("select p from DutyPolicy p where p.code = :code and p.status = 'APPROVED' and p.effectiveFrom <= :d and (p.effectiveTo is null or p.effectiveTo > :d)")
    Optional<DutyPolicy> approvedOn(@Param("code") String code, @Param("d") LocalDate date);

    @Query("select p from DutyPolicy p where p.status = 'APPROVED' order by p.code, p.effectiveFrom desc")
    List<DutyPolicy> allApproved();

    @Query("select p from DutyPolicy p where p.code = :code and p.status = 'APPROVED' order by p.effectiveFrom desc")
    List<DutyPolicy> approvedHistory(@Param("code") String code);

    boolean existsByCode(String code);

    @Query("select coalesce(max(p.versionNo), 0) from DutyPolicy p where p.code = :code")
    int maxVersionNo(@Param("code") String code);

    @Query("select count(p) from DutyPolicy p where p.code = :code and p.status = 'APPROVED' and p.effectiveFrom > :d")
    long countStartingAfter(@Param("code") String code, @Param("d") LocalDate date);
}

interface DutyPolicyAssignmentRepository extends JpaRepository<DutyPolicyAssignment, Long> {
    @Query("select a from DutyPolicyAssignment a where a.effectiveFrom <= :d and (a.effectiveTo is null or a.effectiveTo > :d)")
    List<DutyPolicyAssignment> effectiveOn(@Param("d") LocalDate date);

    /** Group-level (non-employee) assignments of a code, newest first. */
    @Query("select a from DutyPolicyAssignment a where a.policyCode = :code and a.scopeType <> 'EMPLOYEE' order by a.effectiveFrom desc")
    List<DutyPolicyAssignment> groupAssignments(@Param("code") String code);

    @Query("select count(a) from DutyPolicyAssignment a where a.employeeCategory = :cat and (a.effectiveTo is null or a.effectiveTo > CURRENT_DATE)")
    long countCurrentByCategory(@Param("cat") String category);
}

interface AttendanceEventRepository extends JpaRepository<AttendanceEvent, Long>, JpaSpecificationExecutor<AttendanceEvent> {
    List<AttendanceEvent> findByWorkDateOrderByEventTimeAsc(LocalDate date);

    List<AttendanceEvent> findByEmployeeIdAndWorkDateOrderByEventTimeAsc(Long employeeId, LocalDate date);

    boolean existsByEmployeeNoAndEventTimeAndDirection(String employeeNo, Instant eventTime, AttendanceEvent.Direction direction);

    @Query("select count(e) from AttendanceEvent e where e.employeeNo = :no and e.direction = :dir " +
            "and e.eventTime > :from and e.eventTime < :to and (e.reviewStatus is null or e.reviewStatus = lk.gov.ciaboc.hrms.attendance.AttendanceEvent.ReviewStatus.ACCEPTED)")
    long countNear(@Param("no") String employeeNo, @Param("dir") AttendanceEvent.Direction direction,
                   @Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(e) from AttendanceEvent e where e.flagged = true and e.reviewStatus is null")
    long countAwaitingReview();
}

interface AttendanceDailyRepository extends JpaRepository<AttendanceDaily, Long> {
    List<AttendanceDaily> findByWorkDate(LocalDate date);

    Optional<AttendanceDaily> findByEmployeeIdAndWorkDate(Long employeeId, LocalDate date);

    List<AttendanceDaily> findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(Long employeeId, LocalDate from, LocalDate to);

    List<AttendanceDaily> findByWorkDateBetween(LocalDate from, LocalDate to);

    boolean existsByWorkDate(LocalDate date);

    @Query("select distinct d.workDate from AttendanceDaily d where d.workDate between :from and :to")
    List<LocalDate> processedDates(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select d.employeeId, d.workDate from AttendanceDaily d where d.employeeId in :emps and d.workDate between :from and :to")
    List<Object[]> processedFor(@Param("emps") Collection<Long> employeeIds, @Param("from") LocalDate from, @Param("to") LocalDate to);
}

interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrection, Long> {
    List<AttendanceCorrection> findByEmployeeIdOrderByWorkDateDesc(Long employeeId);

    List<AttendanceCorrection> findAllByOrderByIdDesc();

    List<AttendanceCorrection> findByStatusOrderByIdDesc(AttendanceCorrection.Status status);

    boolean existsByEmployeeIdAndWorkDateAndStatusIn(Long employeeId, LocalDate date, Collection<AttendanceCorrection.Status> statuses);
}

interface NoPayRunRepository extends JpaRepository<NoPayRun, Long> {
    Optional<NoPayRun> findByPeriod(String period);

    List<NoPayRun> findAllByOrderByPeriodDesc();
}

interface NoPayLineRepository extends JpaRepository<NoPayLine, Long> {
    List<NoPayLine> findByRunIdOrderByIdAsc(Long runId);

    void deleteByRunId(Long runId);
}
