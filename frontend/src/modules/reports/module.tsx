import { lazy } from 'react';
import { BarChart3 } from 'lucide-react';
import type { ModuleDef } from '../types';

const ReportsPage = lazy(() => import('./ReportsPage'));

const mod: ModuleDef = {
  key: 'reports',
  nav: [{ path: '/reports', label: 'reports.title', fallback: 'Reports & analytics', icon: BarChart3, section: 'insight', order: 1, anyOf: ['RPT_VIEW'] }],
  routes: [
    { path: '/reports', element: <ReportsPage />, anyOf: ['RPT_VIEW'] },
    { path: '/reports/:code', element: <ReportsPage />, anyOf: ['RPT_VIEW'] },
  ],
  i18n: {
    si: { 'reports.title': 'වාර්තා සහ විශ්ලේෂණ' },
    ta: { 'reports.title': 'அறிக்கைகள் மற்றும் பகுப்பாய்வு' },
  },
};
export default mod;
