import { lazy } from 'react';
import { ShieldCheck } from 'lucide-react';
import type { ModuleDef } from '../types';

const AuditPage = lazy(() => import('./AuditPage'));

const mod: ModuleDef = {
  key: 'audit',
  nav: [{ path: '/audit', label: 'audit.title', fallback: 'Audit history', icon: ShieldCheck, section: 'insight', order: 2, anyOf: ['AUDIT_VIEW'] }],
  routes: [{ path: '/audit', element: <AuditPage />, anyOf: ['AUDIT_VIEW'] }],
  i18n: { si: { 'audit.title': 'විගණන ඉතිහාසය' }, ta: { 'audit.title': 'தணிக்கை வரலாறு' } },
};
export default mod;
