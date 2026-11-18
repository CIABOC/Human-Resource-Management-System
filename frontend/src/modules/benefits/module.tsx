import { lazy } from 'react';
import { AlarmClock, FileHeart, HeartPulse, ShieldPlus, Umbrella } from 'lucide-react';
import type { ModuleDef } from '../types';

const SchemesPage = lazy(() => import('./SchemesPage'));
const CoveragePage = lazy(() => import('./CoveragePage'));
const ExpiringPage = lazy(() => import('./ExpiringPage'));
const ClaimsPage = lazy(() => import('./ClaimsPage'));
const MyBenefitsPage = lazy(() => import('./MyBenefitsPage'));

const mod: ModuleDef = {
  key: 'benefits',
  nav: [
    { path: '/self/benefits', label: 'ben.my', fallback: 'My benefits', icon: HeartPulse, section: 'self', order: 31, anyOf: ['SELF_SERVICE'] },
    { path: '/benefits/schemes', label: 'ben.schemes', fallback: 'Insurance schemes', icon: Umbrella, section: 'compensation', order: 20, anyOf: ['BEN_VIEW'] },
    { path: '/benefits/coverage', label: 'ben.coverage', fallback: 'Coverage and policies', icon: ShieldPlus, section: 'compensation', order: 21, anyOf: ['BEN_VIEW'] },
    { path: '/benefits/claims', label: 'ben.claims', fallback: 'Insurance claims', icon: FileHeart, section: 'compensation', order: 22, anyOf: ['BEN_VIEW'] },
    { path: '/benefits/expiring', label: 'ben.expiring', fallback: 'Expiring policies', icon: AlarmClock, section: 'compensation', order: 23, anyOf: ['BEN_VIEW'] },
  ],
  routes: [
    { path: '/self/benefits', element: <MyBenefitsPage />, anyOf: ['SELF_SERVICE'] },
    { path: '/benefits/schemes', element: <SchemesPage />, anyOf: ['BEN_VIEW'] },
    { path: '/benefits/coverage', element: <CoveragePage />, anyOf: ['BEN_VIEW'] },
    { path: '/benefits/claims', element: <ClaimsPage />, anyOf: ['BEN_VIEW'] },
    { path: '/benefits/expiring', element: <ExpiringPage />, anyOf: ['BEN_VIEW'] },
  ],
  i18n: {
    si: {
      'ben.my': 'මගේ ප්‍රතිලාභ', 'ben.schemes': 'රක්ෂණ යෝජනා ක්‍රම', 'ben.coverage': 'රක්ෂණ ආවරණ සහ ඔප්පු',
      'ben.claims': 'රක්ෂණ හිමිකම්', 'ben.expiring': 'කල් ඉකුත් වන ඔප්පු',
    },
    ta: {
      'ben.my': 'எனது நலன்கள்', 'ben.schemes': 'காப்புறுதித் திட்டங்கள்', 'ben.coverage': 'காப்பீடு மற்றும் கொள்கைகள்',
      'ben.claims': 'காப்புறுதிக் கோரிக்கைகள்', 'ben.expiring': 'காலாவதியாகும் கொள்கைகள்',
    },
  },
};
export default mod;
