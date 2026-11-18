import type { EmployeeBrief } from '@/api/types';

export interface LeaveType {
  id: number;
  code: string;
  name: string;
  nameSi?: string | null;
  nameTa?: string | null;
  description?: string | null;
  paid: boolean;
  balanceTracked: boolean;
  requiresReason: boolean;
  requiresDocument: boolean;
  documentAfterDays?: number | null;
  minNoticeDays: number;
  maxConsecutiveDays?: number | null;
  allowBackdated: boolean;
  halfDayAllowed: boolean;
  sortOrder: number;
  active: boolean;
}

export interface Entitlement {
  id: number;
  leaveTypeId: number;
  employeeCategory: string;
  daysPerYear: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
  remarks?: string | null;
}

export interface Balance {
  id?: number | null;
  employeeId: number;
  leaveTypeId: number;
  leaveTypeCode: string;
  leaveTypeName: string;
  year: number;
  entitled: number;
  adjusted: number;
  used: number;
  pending: number;
  available: number;
  employee?: EmployeeBrief | null;
}

export interface LeaveApplication {
  id: number;
  employeeId: number;
  employee?: EmployeeBrief | null;
  leaveTypeId: number;
  leaveTypeCode: string;
  leaveTypeName: string;
  paid: boolean;
  leaveYear: number;
  startDate: string;
  endDate: string;
  halfDay: boolean;
  halfDaySession?: string | null;
  days: number;
  reason?: string | null;
  contactDuringLeave?: string | null;
  hasDocument: boolean;
  status: string;
  workflowInstanceId?: number | null;
  cancellationStatus?: string | null;
  cancellationReason?: string | null;
  cancelWorkflowInstanceId?: number | null;
  createdAt: string;
  decidedAt?: string | null;
}

export interface Preview {
  days: number;
  balanceTracked: boolean;
  available?: number | null;
  documentRequired: boolean;
  valid: boolean;
  problem?: string | null;
}

export interface CalendarEntry {
  applicationId: number;
  employeeId: number;
  employeeNo: string;
  employeeName: string;
  orgUnitCode?: string | null;
  orgUnitName?: string | null;
  leaveTypeCode: string;
  leaveTypeName: string;
  startDate: string;
  endDate: string;
  halfDay: boolean;
  days: number;
  status: string;
}

export interface TeamCalendar {
  from: string;
  to: string;
  entries: CalendarEntry[];
  holidays: string[];
}

export interface Adjustment {
  id: number;
  leaveTypeId: number;
  leaveYear: number;
  kind: string;
  days: number;
  reason: string;
  createdAt: string;
  createdBy: string;
}

export const fmtDays = (n?: number | null) => (n === null || n === undefined ? '—' : String(Number(n)));

/** Local ISO date (yyyy-mm-dd) without UTC shifting. */
export function isoDate(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
