import { lazy } from 'react';
import { Scale } from 'lucide-react';
import type { ModuleDef } from '../types';

const LegalListPage = lazy(() => import('./LegalListPage'));
const LegalDetailPage = lazy(() => import('./LegalDetailPage'));

const mod: ModuleDef = {
  key: 'legal',
  nav: [{ path: '/legal', label: 'legal.title', fallback: 'Legal documents', icon: Scale, section: 'organization', order: 5, anyOf: ['LEGAL_VIEW'] }],
  routes: [
    { path: '/legal', element: <LegalListPage />, anyOf: ['LEGAL_VIEW'] },
    { path: '/legal/:id', element: <LegalDetailPage />, anyOf: ['LEGAL_VIEW'] },
  ],
  i18n: {
    si: { 'legal.title': 'නෛතික ලේඛන' },
    ta: { 'legal.title': 'சட்ட ஆவணங்கள்' },
  },
};
export default mod;
