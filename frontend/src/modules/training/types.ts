export interface Programme {
  id: number; code: string; title: string; description?: string | null; provider?: string | null; startDate: string; endDate: string;
  venue?: string | null; capacity: number; cost?: number | null; nominationClosingDate?: string | null; status: string;
}

export interface ProgrammeView { programme: Programme; seatsTaken: number; approved: number; pending: number; completed: number; seatsAvailable: number }

export interface Nomination {
  id: number; programmeId: number; programmeCode: string; programmeTitle: string; startDate: string; endDate: string; venue?: string | null;
  provider?: string | null; employeeId: number; employeeNo?: string | null; employeeName?: string | null; designation?: string | null;
  orgUnitName?: string | null; source: 'HR' | 'SELF'; reason?: string | null; status: string; workflowInstanceId?: number | null;
  completionStatus: string; attended: boolean; result?: string | null; completionRemarks?: string | null; certificateFileName?: string | null;
  hasCertificate: boolean; createdAt?: string;
}

export const PROGRAMME_STATUSES = ['PLANNED', 'OPEN', 'CLOSED', 'COMPLETED', 'CANCELLED'];
