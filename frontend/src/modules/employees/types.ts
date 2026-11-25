export interface Employee {
  id: number; employeeNo: string; title?: string; fullName: string; nameWithInitials: string; nameSi?: string; nameTa?: string; nic: string;
  dateOfBirth: string; gender?: string; civilStatus?: string; email?: string; phone?: string; mobile?: string; permanentAddress?: string;
  residentialAddress?: string; status: string; employmentType: string; employeeCategory: string; serviceCategory?: string;
  appointmentDate: string; confirmationDate?: string; retirementDate?: string; incrementMonth?: number; separationDate?: string;
  separationReason?: string; separationRemarks?: string; secondedFrom?: string; secondmentEndDate?: string; orgUnitCode?: string;
  designationId?: number; positionId?: number; bankCode?: string; bankBranch?: string; bankAccountNo?: string; restricted: boolean;
  version?: number;
}

export interface EmployeeView {
  employee: Employee; designationName?: string; orgUnitName?: string; positionTitle?: string; hasUserAccount: boolean;
}

export interface EmployeeListItem {
  id: number; employeeNo: string; fullName: string; nameWithInitials: string; nic: string; designation?: string; orgUnitCode?: string;
  orgUnitName?: string; status: string; employeeCategory: string; employmentType: string; appointmentDate: string;
}

export interface AssignmentView {
  id: number; orgUnitCode: string; orgUnitName: string; designation?: string; position?: string; effectiveFrom: string; effectiveTo?: string;
  reason: string; reference?: string; remarks?: string;
}

export interface ServiceEvent { id: number; eventType: string; eventDate: string; description: string; reference?: string }

export interface Qualification { id?: number; qualificationType: string; title: string; institution?: string; yearObtained?: number | string; result?: string; remarks?: string }
export interface PreviousEmployment { id?: number; organization: string; designation?: string; fromDate?: string; toDate?: string; remarks?: string }
export interface EmergencyContact { id?: number; name: string; relationship?: string; phone: string; address?: string }

export interface EmployeeDocument {
  id: number; documentType: string; title: string; documentDate?: string; classification: string; fileName?: string; remarks?: string; createdAt: string; createdBy: string;
}

export interface ProfileChangeRequest { id: number; changes: string; reason?: string; source: string; status: string; workflowInstanceId?: number; createdAt: string }
