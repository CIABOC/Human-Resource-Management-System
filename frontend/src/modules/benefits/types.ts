export interface InsuranceScheme {
  id: number; code: string; name: string; nameSi?: string | null; nameTa?: string | null; schemeType: 'AGRAHARA' | 'OTHER';
  category?: string | null; provider?: string | null; employeePremium: number; employerPremium: number; coverageAmount?: number | null;
  payrollDeduction: boolean; claimsEnabled: boolean; eligibleEmploymentTypes?: string | null; minServiceMonths: number;
  eligibilityNotes?: string | null; active: boolean;
}

export interface CoverageView {
  id: number; employeeId: number; employeeNo: string; employeeName: string; schemeId: number; schemeCode: string; schemeName: string;
  schemeType: string; policyNo?: string | null; startDate: string; expiryDate?: string | null; employeePremium: number;
  employerPremium: number; coverageAmount?: number | null; status: 'ACTIVE' | 'EXPIRED' | 'CANCELLED'; expiryNotifiedOn?: string | null;
  remarks?: string | null; daysToExpiry?: number | null;
}

export interface ClaimView {
  id: number; coverageId: number; employeeId: number; employeeNo: string; employeeName: string; schemeCode: string; schemeName: string;
  policyNo?: string | null; claimDate: string; incidentDate?: string | null; claimType: string; amountClaimed: number;
  amountApproved?: number | null; status: string; providerRef?: string | null; description?: string | null; remarks?: string | null;
}

export interface Eligibility { schemeId: number; schemeCode: string; schemeName: string; schemeType: string; eligible: boolean; covered: boolean; reasons: string[] }

export interface MyBenefits { coverages: CoverageView[]; claims: ClaimView[]; eligibility: Eligibility[] }

export const today = () => new Date().toISOString().slice(0, 10);
export const num = (v: string) => (v === '' ? null : Number(v));
