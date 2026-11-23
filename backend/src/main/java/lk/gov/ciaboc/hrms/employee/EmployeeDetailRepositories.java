package lk.gov.ciaboc.hrms.employee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface QualificationRepository extends JpaRepository<EmployeeDetails.Qualification, Long> {
    List<EmployeeDetails.Qualification> findByEmployeeIdOrderByYearObtainedDesc(Long employeeId);
}

interface PreviousEmploymentRepository extends JpaRepository<EmployeeDetails.PreviousEmployment, Long> {
    List<EmployeeDetails.PreviousEmployment> findByEmployeeIdOrderByFromDateDesc(Long employeeId);
}

interface EmergencyContactRepository extends JpaRepository<EmployeeDetails.EmergencyContact, Long> {
    List<EmployeeDetails.EmergencyContact> findByEmployeeIdOrderByIdAsc(Long employeeId);
}

interface EmployeeDocumentRepository extends JpaRepository<EmployeeDetails.Document, Long> {
    List<EmployeeDetails.Document> findByEmployeeIdOrderByIdDesc(Long employeeId);

    long countByDocumentType(String documentType);
}

interface ServiceEventRepository extends JpaRepository<EmployeeDetails.ServiceEvent, Long> {
    List<EmployeeDetails.ServiceEvent> findByEmployeeIdOrderByEventDateDescIdDesc(Long employeeId);
}

interface ProfileChangeRequestRepository extends JpaRepository<EmployeeDetails.ProfileChangeRequest, Long> {
    List<EmployeeDetails.ProfileChangeRequest> findByEmployeeIdOrderByIdDesc(Long employeeId);
    boolean existsByEmployeeIdAndStatusIn(Long employeeId, List<String> statuses);
    boolean existsByEmployeeIdAndRequestTypeAndStatusIn(Long employeeId, String requestType, List<String> statuses);
}
