import { lazy } from 'react';
import { LayoutDashboard } from 'lucide-react';
import type { ModuleDef } from '../types';

const DashboardPage = lazy(() => import('./DashboardPage'));

const mod: ModuleDef = {
  key: 'dashboard',
  nav: [{ path: '/', label: 'dashboard.title', fallback: 'Dashboard', icon: LayoutDashboard, section: 'overview', order: 1 }],
  routes: [{ path: '/', element: <DashboardPage /> }],
};
export default mod;
