import { lazy } from 'react';
import { CalendarClock, ClipboardCheck } from 'lucide-react';
import type { ModuleDef } from '../types';

const ApprovalsPage = lazy(() => import('./ApprovalsPage'));
const ApprovalDetailPage = lazy(() => import('./ApprovalDetailPage'));
const DelegationsPage = lazy(() => import('./DelegationsPage'));

const mod: ModuleDef = {
  key: 'approvals',
  nav: [
    { path: '/approvals', label: 'approvals.title', fallback: 'Approvals & requests', icon: ClipboardCheck, section: 'overview', order: 2 },
    { path: '/approvals/delegations', label: 'approvals.delegations', fallback: 'Acting arrangements', icon: CalendarClock, section: 'overview', order: 3, anyOf: ['WFL_APPROVE', 'WFL_CONFIG'] },
  ],
  routes: [
    { path: '/approvals', element: <ApprovalsPage /> },
    { path: '/approvals/delegations', element: <DelegationsPage />, anyOf: ['WFL_APPROVE', 'WFL_CONFIG'] },
    { path: '/approvals/:id', element: <ApprovalDetailPage /> },
  ],
  i18n: {
    si: { 'approvals.delegations': 'වැඩබැලීමේ විධිවිධාන' },
    ta: { 'approvals.delegations': 'பதில் கடமை ஏற்பாடுகள்' },
  },
};
export default mod;
