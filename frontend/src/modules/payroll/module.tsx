import { lazy } from 'react';
import { Calculator, FileText, HandCoins, Landmark, ListChecks, Wallet } from 'lucide-react';
import type { ModuleDef } from '../types';

const PayrollRunsPage = lazy(() => import('./PayrollRunsPage'));
const PayrollRunDetailPage = lazy(() => import('./PayrollRunDetailPage'));
const SalaryAdminPage = lazy(() => import('./SalaryAdminPage'));
const DeductionsPage = lazy(() => import('./DeductionsPage'));
const LoansPage = lazy(() => import('./LoansPage'));
const LoanDetailRoute = lazy(() => import('./LoanDetailRoute'));
const AdjustmentsPage = lazy(() => import('./AdjustmentsPage'));
const MyPayslipsPage = lazy(() => import('./MyPayslipsPage'));

const mod: ModuleDef = {
  key: 'payroll',
  nav: [
    { path: '/self/payslips', label: 'pay.myPayslips', fallback: 'My payslips', icon: FileText, section: 'self', order: 30, anyOf: ['SELF_SERVICE'] },
    { path: '/payroll/runs', label: 'pay.runs', fallback: 'Payroll runs', icon: Calculator, section: 'compensation', order: 1, anyOf: ['PAY_VIEW'] },
    { path: '/payroll/salary', label: 'pay.salary', fallback: 'Salary administration', icon: Wallet, section: 'compensation', order: 2, anyOf: ['PAY_VIEW'] },
    { path: '/payroll/deductions', label: 'pay.deductions', fallback: 'Deductions and schemes', icon: Landmark, section: 'compensation', order: 3, anyOf: ['PAY_VIEW'] },
    { path: '/payroll/loans', label: 'pay.loans', fallback: 'Employee loans', icon: HandCoins, section: 'compensation', order: 4, anyOf: ['PAY_VIEW'] },
    { path: '/payroll/adjustments', label: 'pay.adjustments', fallback: 'Payroll adjustments', icon: ListChecks, section: 'compensation', order: 5, anyOf: ['PAY_VIEW'] },
  ],
  routes: [
    { path: '/self/payslips', element: <MyPayslipsPage />, anyOf: ['SELF_SERVICE'] },
    { path: '/payroll/runs', element: <PayrollRunsPage />, anyOf: ['PAY_VIEW'] },
    { path: '/payroll/runs/:id', element: <PayrollRunDetailPage />, anyOf: ['PAY_VIEW'] },
    { path: '/payroll/salary', element: <SalaryAdminPage />, anyOf: ['PAY_VIEW'] },
    { path: '/payroll/deductions', element: <DeductionsPage />, anyOf: ['PAY_VIEW'] },
    { path: '/payroll/loans', element: <LoansPage />, anyOf: ['PAY_VIEW'] },
    { path: '/payroll/loans/:id', element: <LoanDetailRoute />, anyOf: ['PAY_VIEW'] },
    { path: '/payroll/adjustments', element: <AdjustmentsPage />, anyOf: ['PAY_VIEW'] },
  ],
  i18n: {
    si: {
      'pay.myPayslips': 'මගේ වැටුප් පත්‍රිකා', 'pay.runs': 'වැටුප් ගෙවීම් ධාවන', 'pay.run': 'වැටුප්', 'pay.salary': 'වැටුප් පරිපාලනය',
      'pay.deductions': 'අඩු කිරීම් සහ යෝජනා ක්‍රම', 'pay.loans': 'සේවක ණය', 'pay.adjustments': 'වැටුප් ගැලපීම්',
    },
    ta: {
      'pay.myPayslips': 'எனது சம்பளச் சீட்டுகள்', 'pay.runs': 'சம்பளக் கணிப்புகள்', 'pay.run': 'சம்பளம்', 'pay.salary': 'சம்பள நிர்வாகம்',
      'pay.deductions': 'கழிவுகள் மற்றும் திட்டங்கள்', 'pay.loans': 'ஊழியர் கடன்கள்', 'pay.adjustments': 'சம்பளச் சீராக்கங்கள்',
    },
  },
};
export default mod;
