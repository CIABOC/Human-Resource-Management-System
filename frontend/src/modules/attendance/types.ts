import type { EmployeeBrief } from '@/api/types';

export interface DailyRow {
  id: number;
  employeeId: number;
  employee?: EmployeeBrief | null;
  workDate: string;
  status: string;
  checkIn?: string | null;
  checkOut?: string | null;
  workedMinutes: number;
  lateMinutes: number;
  earlyMinutes: number;
  leaveTypeCode?: string | null;
  leaveHalfDay: boolean;
  unpaidLeave: boolean;
  corrected: boolean;
  exceptionNote?: string | null;
  processedAt: string;
}

export interface AttEvent {
  id: number;
  employeeId?: number | null;
  employeeNo: string;
  employeeName?: string | null;
  orgUnitCode?: string | null;
  eventTime: string;
  workDate: string;
  direction: string;
  source: string;
  deviceId?: string | null;
  batchRef?: string | null;
  flagged: boolean;
  flagReason?: string | null;
  reviewStatus?: string | null;
  reviewComment?: string | null;
  reviewedBy?: string | null;
  reviewedAt?: string | null;
}

export interface IngestResult {
  batchRef: string;
  received: number;
  accepted: number;
  flagged: number;
  skippedDuplicates: number;
  rejected: number;
  reprocessedEmployeeDays: number;
  errors: string[];
}

export interface Correction {
  id: number;
  employeeId: number;
  employee?: EmployeeBrief | null;
  workDate: string;
  checkIn?: string | null;
  checkOut?: string | null;
  reason: string;
  status: string;
  workflowInstanceId?: number | null;
  createdAt: string;
  createdBy: string;
}

export interface Schedule {
  id: number;
  code: string;
  name: string;
  startTime: string;
  endTime: string;
  lateGraceMinutes: number;
  earlyGraceMinutes: number;
  workDays: string;
  orgUnitCode?: string | null;
  employeeCategory?: string | null;
  effectiveFrom: string;
  effectiveTo?: string | null;
  remarks?: string | null;
}

export interface Holiday {
  id: number;
  holidayDate: string;
  name: string;
  nameSi?: string | null;
  nameTa?: string | null;
  holidayType: string;
  remarks?: string | null;
}

export interface NoPayRun {
  id: number;
  period: string;
  status: string;
  calculatedAt: string;
  calculatedBy?: string | null;
  rulesSnapshot?: string | null;
  unprocessedDays: number;
  totalDays: number;
  employeeCount: number;
  workflowInstanceId?: number | null;
  approvedAt?: string | null;
}

export interface NoPayLine {
  id: number;
  employeeId: number;
  employee?: EmployeeBrief | null;
  absenceDays: number;
  unpaidLeaveDays: number;
  lateCount: number;
  lateDays: number;
  calculatedDays: number;
  adjustedDays?: number | null;
  adjustmentReason?: string | null;
  effectiveDays: number;
}

export const STATUSES = ['PRESENT', 'LATE', 'EARLY', 'ABSENT', 'ON_LEAVE', 'HOLIDAY', 'WEEKEND', 'NO_SCHEDULE'];

export function isoDate(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function hm(v?: string | null) {
  if (!v) return '—';
  return new Date(v).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
}

export function mins(n?: number | null) {
  if (!n) return '—';
  const h = Math.floor(n / 60);
  return h ? `${h}h ${n % 60}m` : `${n}m`;
}

export const empLabel = (e?: EmployeeBrief | null, fallback?: string | number) => (e ? `${e.employeeNo} — ${e.nameWithInitials}` : String(fallback ?? '—'));
