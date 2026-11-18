import { lazy } from 'react';
import { GraduationCap, School } from 'lucide-react';
import type { ModuleDef } from '../types';

const ProgrammesPage = lazy(() => import('./ProgrammesPage'));
const ProgrammeDetailPage = lazy(() => import('./ProgrammeDetailPage'));
const MyTrainingPage = lazy(() => import('./MyTrainingPage'));

const mod: ModuleDef = {
  key: 'training',
  nav: [
    { path: '/training/my', label: 'trn.my', fallback: 'My training', icon: GraduationCap, section: 'self', order: 50, anyOf: ['SELF_SERVICE'] },
    { path: '/training/programmes', label: 'trn.programmes', fallback: 'Training', icon: School, section: 'talent', order: 30, anyOf: ['TRN_VIEW'] },
  ],
  routes: [
    { path: '/training/my', element: <MyTrainingPage />, anyOf: ['SELF_SERVICE'] },
    { path: '/training/programmes', element: <ProgrammesPage />, anyOf: ['TRN_VIEW'] },
    { path: '/training/programmes/:id', element: <ProgrammeDetailPage />, anyOf: ['TRN_VIEW'] },
  ],
  i18n: {
    si: { 'trn.section': 'පුහුණුව', 'trn.my': 'මගේ පුහුණුව', 'trn.programmes': 'පුහුණු වැඩසටහන්' },
    ta: { 'trn.section': 'பயிற்சி', 'trn.my': 'எனது பயிற்சி', 'trn.programmes': 'பயிற்சி நிகழ்ச்சிகள்' },
  },
};
export default mod;
