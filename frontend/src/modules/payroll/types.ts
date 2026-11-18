import type { EmployeeBrief } from '@/api/types';

export type Basis = 'NONE' | 'FIXED' | 'PERCENT_OF_BASIC' | 'PERCENT_OF_GROSS';

export interface SalaryScale {
  id: number; code: string; name: string; grade?: string | null; initialSalary: number; incrementAmount: number;
  maxSteps: number; maxSalary?: number; effectiveFrom: string; effectiveTo?: string | null; notes?: string | null;
}

export interface AllowanceType {
  id: number; code: string; name: string; nameSi?: string | null; nameTa?: string | null; appliesToAll: boolean; active: boolean; description?: string | null;
}
export interface AllowanceRate { id: number; allowanceTypeId: number; basis: Basis; rateValue: number; effectiveFrom: string; effectiveTo?: string | null; notes?: string | null }
export interface AllowanceTypeView { type: AllowanceType; currentRate?: AllowanceRate | null }

export interface DeductionType {
  id: number; code: string; name: string; nameSi?: string | null; nameTa?: string | null; category: 'STATUTORY' | 'SCHEME' | 'OTHER';
  membershipRequired: boolean; pensionRelated: boolean; active: boolean; description?: string | null;
}
export interface DeductionRate {
  id: number; deductionTypeId: number; employeeBasis: Basis; employeeValue: number; employerBasis: Basis; employerValue: number;
  effectiveFrom: string; effectiveTo?: string | null; toBeConfirmed: boolean; notes?: string | null;
}
export interface DeductionTypeView { type: DeductionType; currentRate?: DeductionRate | null }

export interface EmployeeSalary {
  id: number; employeeId: number; scaleCode: string; step: number; basicSalary: number; effectiveFrom: string; effectiveTo?: string | null;
  reason: string; reference?: string | null; remarks?: string | null; createdBy?: string;
}
export interface EmployeeAllowanceView {
  id: number; allowanceTypeId: number; code: string; name: string; amountOverride?: number | null; effectiveFrom: string;
  effectiveTo?: string | null; remarks?: string | null; current: boolean;
}
export interface MembershipView {
  id: number; employeeId: number; employeeNo: string; employeeName: string; deductionTypeId: number; code: string; name: string;
  category: string; memberNo?: string | null; employeeValueOverride?: number | null; employerValueOverride?: number | null;
  effectiveFrom: string; effectiveTo?: string | null; remarks?: string | null; current: boolean;
}
export interface SalaryOverview {
  employee: EmployeeBrief; current?: EmployeeSalary | null; scale?: SalaryScale | null; history: EmployeeSalary[];
  allowances: EmployeeAllowanceView[]; memberships: MembershipView[]; activeLoans: number; incrementMonth?: number | null;
}
export interface IncrementRow {
  employeeId: number; employeeNo: string; name: string; scaleCode?: string | null; currentStep?: number | null; currentBasic?: number | null;
  newStep?: number | null; newBasic?: number | null; status: string; note?: string | null;
}

export interface LoanView {
  id: number; employeeId: number; employeeNo: string; employeeName: string; loanType: string; lender: string; previousWorkplace?: string | null;
  referenceNo?: string | null; originalAmount: number; outstandingBalance: number; monthlyInstalment: number; startDate: string;
  status: string; closedOn?: string | null; remarks?: string | null;
}
export interface LoanTransaction { id: number; txnDate: string; txnType: string; amount: number; balanceAfter: number; period?: string | null; remarks?: string | null }
export interface LoanAdjustment {
  id: number; loanId: number; adjustmentType: string; newInstalment?: number | null; settlementAmount?: number | null; effectiveDate: string;
  reason: string; status: string; workflowInstanceId?: number | null; appliedAt?: string | null; createdBy?: string;
}
export interface LoanDetail { loan: LoanView; transactions: LoanTransaction[]; adjustments: LoanAdjustment[] }

export interface PayrollRun {
  id: number; period: string; payDate?: string | null; status: 'DRAFT' | 'SUBMITTED' | 'RETURNED' | 'FINALIZED'; locked: boolean;
  calculatedAt?: string | null; calculatedBy?: string | null; submittedAt?: string | null; submittedBy?: string | null; finalizedAt?: string | null;
  workflowInstanceId?: number | null; employeeCount: number; totalGross: number; totalDeductions: number; totalNet: number;
  totalEmployer: number; warningCount: number; notes?: string | null;
}
export interface ComponentTotal { componentType: string; code: string; description: string; employees: number; amount: number }
export interface RunDetail { run: PayrollRun; components: ComponentTotal[]; canCalculate: boolean; canSubmit: boolean; openAdjustmentsPending: boolean; openAdjustments: number }
export interface LineSummary {
  id: number; employeeId: number; employeeNo: string; employeeName: string; designation?: string | null; orgUnitCode?: string | null;
  scaleCode?: string | null; step?: number | null; basicSalary: number; grossPay: number; totalDeductions: number; netPay: number;
  employerContributions: number; noPayDays: number; warnings?: string | null;
}
export interface LineItem { id: number; componentType: 'EARNING' | 'DEDUCTION' | 'EMPLOYER'; sourceType: string; code: string; description: string; amount: number; sortOrder: number }
export interface LineDetail {
  line: LineSummary; period: string; runStatus: string; payDate?: string | null; bankCode?: string | null; bankBranch?: string | null;
  bankAccountNo?: string | null; items: LineItem[];
}
export interface PayslipItem { lineId: number; period: string; payDate?: string | null; grossPay: number; totalDeductions: number; netPay: number }

export interface PayrollAdjustmentView {
  id: number; employeeId: number; employeeNo: string; employeeName: string; adjustmentType: string; code: string; description: string;
  amount: number; targetPeriod: string; relatedPeriod?: string | null; reason: string; status: string; appliedPeriod?: string | null;
  createdBy?: string | null; createdAt?: string | null;
}
export interface DeductionHistoryRow { period: string; componentType: string; sourceType: string; code: string; description: string; amount: number }

export const BASIS_LABELS: Record<Basis, string> = {
  NONE: 'None', FIXED: 'Fixed amount', PERCENT_OF_BASIC: '% of basic', PERCENT_OF_GROSS: '% of gross',
};

export function rateText(basis?: Basis | null, value?: number | null) {
  if (!basis || basis === 'NONE') return '—';
  if (basis === 'FIXED') return `LKR ${Number(value ?? 0).toLocaleString('en-LK', { minimumFractionDigits: 2 })}`;
  return `${Number(value ?? 0)}% ${basis === 'PERCENT_OF_BASIC' ? 'of basic' : 'of gross'}`;
}

/** Current YYYY-MM. */
export function thisPeriod() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

export const today = () => new Date().toISOString().slice(0, 10);

export const num = (v: string) => (v === '' ? null : Number(v));
