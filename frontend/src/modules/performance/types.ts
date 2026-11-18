import type { EmployeeBrief } from '@/api/types';

export interface Period {
  id: number; code: string; name: string; startDate: string; endDate: string; dueDate?: string | null; status: 'OPEN' | 'CLOSED';
}

export interface Model {
  id: number; code: string; name: string; description?: string | null; ratingMin: number; ratingMax: number; sample: boolean; active: boolean;
}

export interface Criterion {
  id?: number; modelId?: number; criterionType: 'CRITERION' | 'KPI' | 'OBJECTIVE'; title: string; description?: string | null; weight: number; sortOrder?: number;
}

export interface ModelView { model: Model; criteria: Criterion[]; totalWeight: number; categories: string[]; evaluations: number; locked: boolean }

export interface CategoryAssignment {
  employeeCategory: string; categoryName: string; modelId?: number | null; modelCode?: string | null; modelName?: string | null; sample: boolean;
}

export interface EvaluationListItem {
  id: number; periodId: number; periodName: string; employeeId: number; employeeNo?: string | null; employeeName?: string | null;
  designation?: string | null; orgUnitName?: string | null; employeeCategory?: string | null; modelCode: string;
  evaluatorUserId?: number | null; evaluatorName?: string | null; status: string; overallScore?: number | null; overallPercent?: number | null;
  submittedAt?: string | null; finalizedAt?: string | null; acknowledgedAt?: string | null;
}

export interface CriterionLine {
  criterionId: number; criterionType: string; title: string; description?: string | null; weight: number; rating?: number | null; comment?: string | null;
}

export interface EvaluationDetail {
  id: number; period: Period; employee: EmployeeBrief; model: Model; evaluatorUserId?: number | null; evaluatorName?: string | null;
  status: string; overallScore?: number | null; overallPercent?: number | null; evaluatorComments?: string | null;
  employeeComments?: string | null; submittedAt?: string | null; finalizedAt?: string | null; acknowledgedAt?: string | null;
  workflowInstanceId?: number | null; lines: CriterionLine[]; canEdit: boolean; canAcknowledge: boolean; canReassign: boolean;
}

export interface EvaluatorOption { userId: number; name: string; employeeId?: number | null }

export const EVALUATION_STATUSES = ['DRAFT', 'SUBMITTED', 'RETURNED', 'REJECTED', 'FINALIZED'];

/** Client-side preview of the weighted score; the server value is authoritative. */
export function weightedPreview(lines: { weight: number; rating?: number | null }[], min: number, max: number) {
  let sum = 0;
  let w = 0;
  for (const l of lines) {
    if (l.rating === null || l.rating === undefined || Number.isNaN(l.rating)) continue;
    sum += l.rating * l.weight;
    w += l.weight;
  }
  if (!w) return null;
  const score = sum / w;
  return { score: score.toFixed(2), percent: max > min ? (((score - min) / (max - min)) * 100).toFixed(2) : null };
}
