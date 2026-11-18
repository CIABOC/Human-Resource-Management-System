import { lazy } from 'react';
import { Building2, GitBranchPlus, Network, Users2 } from 'lucide-react';
import type { ModuleDef } from '../types';

const OrgChartPage = lazy(() => import('./OrgChartPage'));
const StructurePage = lazy(() => import('./StructurePage'));
const CadrePage = lazy(() => import('./CadrePage'));
const ChangesPage = lazy(() => import('./ChangesPage'));
const ChangeDetailPage = lazy(() => import('./ChangeDetailPage'));

const VIEW = ['EST_VIEW', 'EMP_VIEW'];

const mod: ModuleDef = {
  key: 'establishment',
  nav: [
    { path: '/establishment/org-chart', label: 'est.orgChart', fallback: 'Org Chart & Position Categorization', icon: Network, section: 'organization', order: 1, anyOf: VIEW },
    { path: '/establishment/structure', label: 'est.structure', fallback: 'Structure & posts', icon: Building2, section: 'organization', order: 2, anyOf: ['EST_VIEW'] },
    { path: '/establishment/cadre', label: 'est.cadre', fallback: 'Cadre', icon: Users2, section: 'organization', order: 3, anyOf: ['EST_VIEW'] },
    { path: '/establishment/changes', label: 'est.changes', fallback: 'Establishment changes', icon: GitBranchPlus, section: 'organization', order: 4, anyOf: ['EST_VIEW'] },
  ],
  routes: [
    { path: '/establishment/org-chart', element: <OrgChartPage />, anyOf: VIEW },
    { path: '/establishment/structure', element: <StructurePage />, anyOf: ['EST_VIEW'] },
    { path: '/establishment/cadre', element: <CadrePage />, anyOf: ['EST_VIEW'] },
    { path: '/establishment/changes', element: <ChangesPage />, anyOf: ['EST_VIEW'] },
    { path: '/establishment/changes/:id', element: <ChangeDetailPage />, anyOf: ['EST_VIEW'] },
  ],
  i18n: {
    si: { 'est.orgChart': 'සංවිධාන සටහන', 'est.structure': 'ව්‍යුහය සහ තනතුරු', 'est.cadre': 'අනුමත කාර්ය මණ්ඩලය', 'est.changes': 'ස්ථාපන වෙනස්කම්' },
    ta: { 'est.orgChart': 'அமைப்பு வரைபடம்', 'est.structure': 'கட்டமைப்பு மற்றும் பதவிகள்', 'est.cadre': 'ஆளணி', 'est.changes': 'தாபன மாற்றங்கள்' },
  },
};
export default mod;
