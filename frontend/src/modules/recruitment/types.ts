export interface Vacancy {
  id: number;
  referenceNo: string;
  title: string;
  designationId: number;
  designationName?: string | null;
  orgUnitCode: string;
  orgUnitName?: string | null;
  numPosts: number;
  status: 'DRAFT' | 'OPEN' | 'CLOSED' | 'FILLED' | 'CANCELLED';
  openingDate?: string | null;
  closingDate?: string | null;
  description?: string | null;
  requirements?: string | null;
  examWeight: number;
  interviewWeight: number;
  applicantsByStatus: Record<string, number>;
  applicants: number;
  appointed: number;
  createdAt?: string;
  createdBy?: string;
}

export interface VacancySuggestion {
  designationId: number;
  designationName: string;
  grade?: string | null;
  orgUnitCode: string;
  orgUnitName: string;
  approved: number;
  occupied: number;
  vacant: number;
  underRecruitment: number;
  suggestedPosts: number;
}

export interface AvailablePosition {
  id: number;
  referenceNo: string;
  title: string;
  designationName?: string | null;
  orgUnitName?: string | null;
  numPosts: number;
  closingDate?: string | null;
  description?: string | null;
  requirements?: string | null;
}

export interface Announcement {
  id: number;
  vacancyId?: number | null;
  title: string;
  content: string;
  publishDate: string;
  closingDate?: string | null;
  published: boolean;
  publishedAt?: string | null;
  publishedBy?: string | null;
}

export interface ApplicantListItem {
  id: number;
  applicationNo: string;
  vacancyId: number;
  vacancyRef?: string | null;
  fullName: string;
  nameWithInitials: string;
  nic: string;
  email?: string | null;
  mobile?: string | null;
  receivedDate: string;
  status: string;
  employeeId?: number | null;
}

export interface Applicant {
  id: number;
  vacancyId: number;
  applicationNo: string;
  title?: string | null;
  fullName: string;
  nameWithInitials: string;
  nic: string;
  dateOfBirth: string;
  gender?: string | null;
  civilStatus?: string | null;
  email?: string | null;
  mobile?: string | null;
  address?: string | null;
  qualifications?: string | null;
  receivedDate: string;
  source: string;
  status: string;
  remarks?: string | null;
  employeeId?: number | null;
}

export interface StatusChange { id: number; fromStatus?: string | null; toStatus: string; changedAt: string; changedBy: string; remarks?: string | null }
export interface ApplicantDocument { id: number; documentType: string; title: string; fileName: string; createdAt?: string }
export interface ExamResultView {
  examId: number; examTitle: string; examType: string; examDate: string; maxMarks: number; passMarks?: number | null;
  marks?: number | null; absent: boolean; remarks?: string | null;
}
export interface Interview {
  id: number; applicantId: number; interviewDate: string; interviewTime?: string | null; venue?: string | null; panel: string;
  maxMarks: number; marks?: number | null; remarks?: string | null; status: string;
}
export interface Score { examPercent?: number | null; interviewPercent?: number | null; total: number }

export interface ApplicantDetail {
  applicant: Applicant;
  vacancy: Vacancy;
  history: StatusChange[];
  documents: ApplicantDocument[];
  examResults: ExamResultView[];
  interviews: Interview[];
  score: Score;
}

export interface Exam {
  id: number; vacancyId: number; title: string; examType: 'WRITTEN' | 'PRACTICAL'; examDate: string; startTime?: string | null;
  venue?: string | null; maxMarks: number; passMarks?: number | null; status: string;
}

export interface ExamResultRow {
  applicantId: number; applicationNo: string; name: string; applicantStatus: string; marks?: number | null; absent: boolean;
  remarks?: string | null; passed?: boolean | null;
}

export interface InterviewRow extends Interview { applicationNo?: string | null; applicantName?: string | null }

export interface SelectionItem {
  id: number; applicantId: number; applicationNo?: string | null; applicantName?: string | null; applicantStatus?: string | null;
  rankNo: number; examScore?: number | null; interviewScore?: number | null; totalScore: number; recommended: boolean; remarks?: string | null;
}

export interface Selection {
  id: number; vacancyId: number; vacancyRef: string; numPosts: number; alreadyAppointed: number; status: string;
  remarks?: string | null; workflowInstanceId?: number | null; submittedAt?: string | null; decidedAt?: string | null;
  items: SelectionItem[];
}

export interface LetterTemplate {
  id: number; code: string; language: 'en' | 'si' | 'ta'; name: string; category: string; subject: string; body: string; active: boolean;
}

export interface LetterLog {
  id: number; applicantId: number; applicationNo?: string | null; applicantName?: string | null; templateCode: string; category: string;
  language: string; subject: string; channel: string; recipient?: string | null; status: string; deliveryStatus?: string | null;
  generatedAt: string; generatedBy: string;
}

export const APPLICANT_STATUSES = ['RECEIVED', 'SHORTLISTED', 'REJECTED', 'WITHDRAWN', 'RECOMMENDED', 'SELECTED', 'NOT_SELECTED', 'APPOINTED'];
export const LETTER_CATEGORIES = ['APPOINTMENT', 'EXAM_CALL', 'INTERVIEW_CALL', 'REGRET', 'OTHER'];
export const PLACEHOLDERS = [
  'orgName', 'date', 'applicationNo', 'applicantTitle', 'applicantName', 'nameWithInitials', 'address', 'vacancyRef', 'vacancyTitle',
  'designation', 'orgUnit', 'examTitle', 'examType', 'examDate', 'examTime', 'examVenue', 'interviewDate', 'interviewTime',
  'interviewVenue', 'appointmentDate', 'employeeNo',
];

/** Opens the letter HTML (already escaped by the server) in a print window. */
export function printLetter(html: string, title: string) {
  const w = window.open('', '_blank', 'noopener=no,width=800,height=900');
  if (!w) return;
  w.document.title = title;
  const style = w.document.createElement('style');
  style.textContent = `body{font-family:'Times New Roman',serif;margin:40px;color:#111;line-height:1.55}
    .letter-head{font-weight:bold;font-size:18px;text-align:center;border-bottom:2px solid #0b2a5b;padding-bottom:8px;margin-bottom:18px}
    .letter-date{text-align:right;margin-bottom:16px}.letter-subject{text-decoration:underline;font-size:15px}`;
  w.document.head.appendChild(style);
  w.document.body.innerHTML = html;
  w.focus();
  w.print();
}
