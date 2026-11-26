export interface Period { id: number; code: string; name: string; startDate: string; endDate: string; dueDate?: string | null; status: string }

export interface AprContext {
  employeeId: number | null; currentPeriodId: number | null; periods: Period[]; hasAppraisal: boolean;
  supervisor: boolean; reviewer: boolean; manage: boolean; overview: boolean; report: boolean;
  policyApprove: boolean; admin: boolean; pendingVerifications: number; pendingApprovals: number;
}

export interface EmployeeBrief { id: number; employeeNo: string; name: string; nameWithInitials?: string; designation?: string; orgUnitName?: string }
export interface Milestone { name: string; weight: number }
export interface Evidence { id: number; fileName: string; status: string; uploadedAt: string }
export interface Submission { kpiId: number; versionNo: number; submittedAt: string; status: string; selfProgress?: number | null }

export interface Kpi {
  id: number; objective: string; title: string; method: string; target?: string | null; weight: number; reviewPeriod?: string | null;
  sortOrder: number; milestones: Milestone[]; progressData: Record<string, unknown>; selfProgress?: number | null; selfNote?: string | null;
  delayExplanation?: string | null; progressStatus: string; decision?: string | null; verifiedValue?: number | null;
  verifierComment?: string | null; excepted: boolean; behindTarget: boolean; evidenceStatus: string; chip: string;
  evidence: Evidence[]; submissions: Submission[];
}

export interface CriterionRating { criterionId: number; code: string; name: string; descriptors: (string | null)[]; rating?: number | null; notApplicable: boolean; justification?: string | null }
export interface TrailEntry { level: string; officerName?: string; onBehalfOf?: string | null; decision: string; comment?: string | null; actedAt: string }
export interface HistoryEntry { versionNo: number; changedBy?: string; changedAt: string; note?: string }

export interface Flags {
  employee: boolean; supervisor: boolean; reviewer: boolean; hr: boolean; actingFor?: string | null;
  canEditAgreement: boolean; canSignEmployee: boolean; canSignSupervisor: boolean; canSignReviewer: boolean; canReturnAgreement: boolean;
  canUpdateProgress: boolean; canSelfAssess: boolean; canVerify: boolean; canAssess: boolean; canApprove: boolean;
  canAcknowledge: boolean; canAppeal: boolean; canRequestReopen: boolean; canSeeBehaviour: boolean; canAssignOfficers: boolean;
}

export interface Appraisal {
  id: number; period: Period; employee: EmployeeBrief; supervisorUserId?: number | null; supervisorName?: string | null;
  reviewerUserId?: number | null; reviewerName?: string | null; status: string; agreementStatus: string; agreementVersion: number;
  employeeSignedAt?: string | null; supervisorSignedAt?: string | null; supervisorSignedBy?: string | null;
  reviewerSignedAt?: string | null; reviewerSignedBy?: string | null;
  selfStatus: string; selfAchievements?: string | null; selfChallenges?: string | null; selfComments?: string | null; selfSubmittedAt?: string | null;
  behaviourStatus: string; strengths?: string | null; improvements?: string | null; behaviourSubmittedAt?: string | null; verificationCompletedAt?: string | null;
  kpiWeight: number; behaviourWeight: number; totalKpiWeight: number; selfScore?: number | null; verifiedScore?: number | null;
  kpiScore?: number | null; behaviourScore?: number | null; overallScore?: number | null; adjustment?: number | null;
  adjustmentJustification?: string | null; finalScore?: number | null; category?: string | null; reviewerComments?: string | null;
  approvedAt?: string | null; acknowledgedAt?: string | null; employeeAckComments?: string | null; finalizedAt?: string | null;
  selfAssessmentCloses?: string | null; appealWindowCloses?: string | null;
  kpis: Kpi[]; behaviour: CriterionRating[]; trail: TrailEntry[]; agreementHistory: HistoryEntry[]; flags: Flags;
}

export interface MyDashboard { appraisal: Appraisal | null; actions: { action: string; due?: string | null; link: string }[]; trend: { label: string; value: number }[] }

export interface QueueRow {
  appraisalId: number; employeeId: number; employeeNo: string; employeeName: string; designation?: string; orgUnitName?: string;
  kpisVerified: number; kpisTotal: number; selfStatus: string; behaviourStatus: string; status: string; agreementStatus: string;
  queueStatus: string; delegatedFrom?: string | null; overdue: boolean; kpiScore?: number | null; behaviourScore?: number | null;
  finalScore?: number | null; category?: string | null;
}

export interface TeamDashboard { employees: number; pendingVerifications: number; overdueActions: number; assessmentsCompleted: number; assessmentsTotal: number; queue: QueueRow[] }
export interface Member { employeeId: number; employeeNo: string; name: string; designation?: string; appraisalId: number }

export interface Spread { category: string; count: number }
export interface CompletionRow { unitCode: string; unitName: string; total: number; completed: number; pending: number; overdue: number }
export interface ResultRow { unitCode: string; unitName: string; finalized: number; averageScore?: number | null; spread: Spread[] }
export interface Overview {
  period: Period | null; dataAsOf: string; totalAppraisals: number; completed: number; pending: number; overdue: number;
  averageScore?: number | null; categories: string[]; categorySpread: Spread[]; completion: CompletionRow[]; results: ResultRow[];
  stages: Record<string, number>; pendingApprovals: number; canDrillDown: boolean; canExport: boolean;
  kpiWeight?: number | null; behaviourWeight?: number | null;
}
export interface DrillRow { appraisalId: number; employeeNo: string; employeeName: string; designation?: string; unitName?: string; status: string; kpiScore?: number | null; behaviourScore?: number | null; finalScore?: number | null; category?: string | null }

export const METHODS: { value: string; label: string; help: string }[] = [
  { value: 'MILESTONE', label: 'Milestones', help: 'Progress = weight of the milestones completed' },
  { value: 'TASK_COUNT', label: 'Task count', help: 'Progress = completed tasks / total tasks' },
  { value: 'TIMELINESS', label: 'Timeliness', help: 'Actual completion date against the target date' },
  { value: 'QUALITY', label: 'Quality measure', help: 'A quality measure, with self-reported achievement' },
  { value: 'QUALITATIVE', label: 'Qualitative', help: 'A description of the achievement' },
];
export const methodLabel = (m: string) => METHODS.find((x) => x.value === m)?.label ?? m;
