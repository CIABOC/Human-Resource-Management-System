package lk.gov.ciaboc.hrms.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {
    Optional<Employee> findByEmployeeNo(String employeeNo);

    Optional<Employee> findByNic(String nic);

    boolean existsByEmployeeNo(String employeeNo);

    boolean existsByNic(String nic);

    List<Employee> findByStatusIn(Collection<String> statuses);

    long countByStatus(String status);

    long countByEmploymentType(String code);

    long countByEmployeeCategory(String code);

    long countByServiceCategory(String code);

    long countByStatusIn(Collection<String> statuses);

    @Query("select e.status, count(e) from Employee e group by e.status")
    List<Object[]> countByStatusGrouped();

    @Query("select e.id from Employee e where e.orgUnitCode in :units")
    List<Long> idsInUnits(@Param("units") Collection<String> units);
}
