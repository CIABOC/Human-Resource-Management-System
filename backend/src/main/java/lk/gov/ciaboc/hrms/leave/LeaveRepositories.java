package lk.gov.ciaboc.hrms.leave;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface LeaveTypeRepository extends JpaRepository<LeaveType, Long> {
    Optional<LeaveType> findByCode(String code);

    boolean existsByCode(String code);

    List<LeaveType> findAllByOrderBySortOrderAscNameAsc();

    List<LeaveType> findByActiveTrueOrderBySortOrderAscNameAsc();
}

interface LeaveEntitlementRepository extends JpaRepository<LeaveEntitlement, Long> {
    List<LeaveEntitlement> findByLeaveTypeIdOrderByEmployeeCategoryAscEffectiveFromDesc(Long leaveTypeId);

    @Query("select x from LeaveEntitlement x where x.effectiveFrom <= :d and (x.effectiveTo is null or x.effectiveTo > :d) " +
            "order by x.leaveTypeId, x.employeeCategory")
    List<LeaveEntitlement> effectiveOn(@Param("d") LocalDate date);

    @Query("select x from LeaveEntitlement x where x.leaveTypeId = :type and x.employeeCategory = :cat and x.effectiveFrom <= :d " +
            "and (x.effectiveTo is null or x.effectiveTo > :d)")
    Optional<LeaveEntitlement> effectiveOn(@Param("type") Long leaveTypeId, @Param("cat") String category, @Param("d") LocalDate date);

    /** Versions in force at any time during [from, to], latest first. */
    @Query("select x from LeaveEntitlement x where x.leaveTypeId = :type and x.employeeCategory = :cat and x.effectiveFrom <= :to " +
            "and (x.effectiveTo is null or x.effectiveTo > :from) order by x.effectiveFrom desc")
    List<LeaveEntitlement> inForceDuring(@Param("type") Long leaveTypeId, @Param("cat") String category,
                                         @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(x) from LeaveEntitlement x where x.leaveTypeId = :type and x.employeeCategory = :cat and x.effectiveFrom > :d")
    long countStartingAfter(@Param("type") Long leaveTypeId, @Param("cat") String category, @Param("d") LocalDate date);

    long countByEmployeeCategory(String category);
}

interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {
    Optional<LeaveBalance> findByEmployeeIdAndLeaveTypeIdAndLeaveYear(Long employeeId, Long leaveTypeId, int year);

    List<LeaveBalance> findByEmployeeIdAndLeaveYear(Long employeeId, int year);

    List<LeaveBalance> findByLeaveYear(int year);

    List<LeaveBalance> findByLeaveYearAndEmployeeIdIn(int year, Collection<Long> employeeIds);
}

interface LeaveBalanceAdjustmentRepository extends JpaRepository<LeaveBalanceAdjustment, Long> {
    List<LeaveBalanceAdjustment> findByEmployeeIdAndLeaveYearOrderByIdDesc(Long employeeId, int year);

    List<LeaveBalanceAdjustment> findByEmployeeIdOrderByIdDesc(Long employeeId);
}

interface LeaveApplicationRepository extends JpaRepository<LeaveApplication, Long>, JpaSpecificationExecutor<LeaveApplication> {
    List<LeaveApplication> findByEmployeeIdOrderByStartDateDesc(Long employeeId);

    @Query("select a from LeaveApplication a where a.employeeId = :emp and a.status in :statuses " +
            "and a.startDate <= :to and a.endDate >= :from and a.id <> :exclude")
    List<LeaveApplication> overlapping(@Param("emp") Long employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                                       @Param("statuses") Collection<LeaveApplication.Status> statuses,
                                       @Param("exclude") Long excludeId);

    @Query("select a from LeaveApplication a where a.status in :statuses and a.startDate <= :to and a.endDate >= :from " +
            "order by a.startDate")
    List<LeaveApplication> inRange(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                   @Param("statuses") Collection<LeaveApplication.Status> statuses);

    long countByLeaveTypeId(Long leaveTypeId);
}
