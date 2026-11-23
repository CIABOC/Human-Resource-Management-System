/** Shared API shapes. Module-specific types live in each module folder. */

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Me {
  id: number;
  username: string;
  fullName: string;
  employeeId: number | null;
  dataScope: 'ALL' | 'DIVISION' | 'SELF';
  language: 'en' | 'si' | 'ta';
  preferredTheme: 'LIGHT' | 'DARK';
  roles: string[];
  permissions: string[];
  sessionIdleMinutes: number;
}

export interface MasterDataItem {
  id: number;
  category: string;
  code: string;
  nameEn: string;
  nameSi?: string | null;
  nameTa?: string | null;
  description?: string | null;
  sortOrder: number;
  active: boolean;
  effectiveFrom: string;
  effectiveTo?: string | null;
}

export interface OrgUnit {
  id: number;
  code: string;
  name: string;
  nameSi?: string | null;
  nameTa?: string | null;
  unitType: 'DIVISION' | 'DEPARTMENT' | 'SECTION' | 'SUB_SECTION';
  parentCode?: string | null;
  sortOrder: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
}

export interface Designation {
  id: number;
  code: string;
  name: string;
  nameSi?: string | null;
  nameTa?: string | null;
  grade?: string | null;
  serviceCategory?: string | null;
  description?: string | null;
  active: boolean;
}

export interface Position {
  id: number;
  code: string;
  title: string;
  designationId: number;
  orgUnitCode: string;
  reportsToPositionCode?: string | null;
  active: boolean;
  activatedOn?: string | null;
  deactivatedOn?: string | null;
}

export type WorkflowStatus = 'PENDING' | 'RETURNED' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface WorkflowInstance {
  id: number;
  processCode: string;
  entityType: string;
  entityId: number;
  title: string;
  link?: string | null;
  requesterUserId: number;
  requesterName: string;
  currentStage: number;
  totalStages: number;
  currentStageName?: string | null;
  status: WorkflowStatus;
  submittedAt: string;
  stageEnteredAt: string;
  completedAt?: string | null;
  escalated: boolean;
}

export interface ApprovalAction {
  id: number;
  instanceId: number;
  stageOrder: number;
  stageName?: string | null;
  actorName: string;
  onBehalfOfName?: string | null;
  action: string;
  comment?: string | null;
  actedAt: string;
}

/** Compact employee summary returned by most module APIs (backend EmployeeLookup.EmployeeBrief). */
export interface EmployeeBrief {
  id: number;
  employeeNo: string;
  name: string;
  nameWithInitials: string;
  designation?: string | null;
  orgUnitCode?: string | null;
  orgUnitName?: string | null;
  status: string;
  employeeCategory: string;
  employmentType: string;
  restricted: boolean;
}
