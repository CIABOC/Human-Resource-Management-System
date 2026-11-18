import type { EmployeeBrief } from '@/api/types';

export type MovementType = 'TRANSFER' | 'PROMOTION' | 'CONFIRMATION';

export interface Movement {
  id: number;
  requestType: MovementType;
  employee: EmployeeBrief;
  fromOrgUnitCode?: string | null;
  fromOrgUnitName?: string | null;
  fromDesignation?: string | null;
  fromPosition?: string | null;
  targetOrgUnitCode?: string | null;
  targetOrgUnitName?: string | null;
  targetDesignationId?: number | null;
  targetDesignation?: string | null;
  targetPositionId?: number | null;
  targetPosition?: string | null;
  effectiveDate: string;
  reason: string;
  reference?: string | null;
  cadreWarning?: string | null;
  status: string;
  workflowInstanceId?: number | null;
  appliedAt?: string | null;
  createdBy: string;
  createdAt: string;
}

export interface CadreCheck {
  applicable: boolean;
  vacancyAvailable: boolean;
  blocking: boolean;
  approved: number;
  occupied: number;
  otherPending: number;
  available: number;
  message: string;
}

export interface ForecastRow {
  employee: EmployeeBrief;
  dateOfBirth: string;
  retirementDate: string;
  monthsRemaining: number;
  daysRemaining: number;
  openCaseId?: number | null;
}

export interface SeparationType {
  id: number;
  code: string;
  nameEn: string;
  nameSi?: string | null;
  nameTa?: string | null;
  employeeStatus: string;
  defaultSeparationReason: string;
  active: boolean;
}

export interface ClearanceTemplate {
  id: number;
  code: string;
  nameEn: string;
  nameSi?: string | null;
  nameTa?: string | null;
  description?: string | null;
  responsibleRole: string;
  appliesTo?: string | null;
  sortOrder: number;
  active: boolean;
}

export interface CaseSummary {
  id: number;
  employee: EmployeeBrief;
  separationType: string;
  separationReason: string;
  targetStatus: string;
  separationDate: string;
  status: string;
  itemsTotal: number;
  itemsDone: number;
  settlementRecorded: boolean;
  createdAt: string;
  createdBy: string;
  completedAt?: string | null;
}

export interface ClearanceItem {
  id: number;
  itemCode: string;
  itemName: string;
  responsibleRole: string;
  sortOrder: number;
  status: 'PENDING' | 'CLEARED' | 'WAIVED';
  remarks?: string | null;
  clearedBy?: string | null;
  clearedAt?: string | null;
}

export interface CaseDetail {
  summary: CaseSummary;
  remarks?: string | null;
  items: ClearanceItem[];
  settlementAmount?: number | null;
  settlementNotes?: string | null;
  settlementBy?: string | null;
  settlementAt?: string | null;
  settlementRequired: boolean;
  settlementRole: string;
  completedBy?: string | null;
  cancelReason?: string | null;
  canComplete: boolean;
  blockers: string[];
}

export interface ClearanceTask {
  itemId: number;
  caseId: number;
  itemCode: string;
  itemName: string;
  responsibleRole: string;
  employee: EmployeeBrief;
  separationDate: string;
  separationType: string;
}

export interface TemplateOption {
  code: string;
  name: string;
  documentKind: string;
  languages: string[];
}

export interface LetterRequest {
  id: number;
  employee: EmployeeBrief;
  templateCode: string;
  documentName: string;
  language: string;
  purpose: string;
  addressee?: string | null;
  status: string;
  workflowInstanceId?: number | null;
  issuedDocumentId?: number | null;
  createdAt: string;
  requiredFields: string[];
}

export interface IssuedDoc {
  id: number;
  documentNo: string;
  employee: EmployeeBrief;
  templateCode: string;
  documentTypeName: string;
  documentKind: string;
  language: string;
  title: string;
  issuedAt: string;
  issuedBy: string;
  status: 'VALID' | 'REVOKED';
  revokedAt?: string | null;
  revokedBy?: string | null;
  revokeReason?: string | null;
  verificationCode: string;
  requestId?: number | null;
}

export interface MyLetters {
  requests: LetterRequest[];
  documents: IssuedDoc[];
  available: TemplateOption[];
  approvalRequired: boolean;
}

export interface LetterTemplate {
  id: number;
  code: string;
  language: string;
  name: string;
  documentKind: 'LETTER' | 'CERTIFICATE';
  title: string;
  bodyHtml: string;
  selfService: boolean;
  active: boolean;
}

export interface BatchSummary {
  id: number;
  batchNo: string;
  title: string;
  sourceLocation?: string | null;
  status: 'OPEN' | 'IN_QA' | 'COMPLETED';
  createdBy: string;
  createdAt: string;
  submittedAt?: string | null;
  completedAt?: string | null;
  counts: Record<string, number>;
  total: number;
}

export interface BatchItem {
  id: number;
  employeeId: number;
  employeeNo: string;
  employeeName?: string | null;
  documentType: string;
  title: string;
  documentDate?: string | null;
  classification: string;
  fileName?: string | null;
  status: 'UPLOADED' | 'VERIFIED' | 'REJECTED' | 'FILED' | 'WITHDRAWN';
  uploadedBy: string;
  uploadedAt: string;
  qaBy?: string | null;
  qaAt?: string | null;
  qaRemarks?: string | null;
  employeeDocumentId?: number | null;
  filedBy?: string | null;
  filedAt?: string | null;
}

export interface BatchDetail {
  batch: BatchSummary;
  remarks?: string | null;
  items: BatchItem[];
  hiddenItems: number;
}

export const LANG_LABEL: Record<string, string> = { en: 'English', si: 'සිංහල', ta: 'தமிழ்' };

export function today() {
  return new Date().toISOString().slice(0, 10);
}

export function titleCase(s?: string | null) {
  return s ? s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) : '—';
}
