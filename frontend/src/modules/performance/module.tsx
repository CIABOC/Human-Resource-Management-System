import { lazy } from 'react';
import { ClipboardCheck, ClipboardList, Settings2, Star } from 'lucide-react';
import type { ModuleDef } from '../types';

const PerformanceConfigPage = lazy(() => import('./PerformanceConfigPage'));
const EvaluationsPage = lazy(() => import('./EvaluationLists').then((m) => ({ default: m.EvaluationsPage })));
const AssignedPage = lazy(() => import('./EvaluationLists').then((m) => ({ default: m.AssignedPage })));
const MyEvaluationsPage = lazy(() => import('./EvaluationLists').then((m) => ({ default: m.MyEvaluationsPage })));
const EvaluationPage = lazy(() => import('./EvaluationPage'));

const mod: ModuleDef = {
  key: 'performance',
  nav: [
    { path: '/performance/my', label: 'per.my', fallback: 'My evaluations', icon: Star, section: 'self', order: 40, anyOf: ['SELF_SERVICE'] },
    { path: '/performance/assigned', label: 'per.assigned', fallback: 'Evaluations to complete', icon: ClipboardCheck, section: 'talent', order: 20, anyOf: ['PER_EVALUATE', 'PER_MANAGE'] },
    { path: '/performance/evaluations', label: 'per.evaluations', fallback: 'Performance evaluations', icon: ClipboardList, section: 'talent', order: 21, anyOf: ['PER_VIEW'] },
    { path: '/performance/config', label: 'per.config', fallback: 'Evaluation setup', icon: Settings2, section: 'talent', order: 22, anyOf: ['PER_MANAGE'] },
  ],
  routes: [
    { path: '/performance/my', element: <MyEvaluationsPage />, anyOf: ['SELF_SERVICE'] },
    { path: '/performance/assigned', element: <AssignedPage /> },
    { path: '/performance/evaluations', element: <EvaluationsPage />, anyOf: ['PER_VIEW'] },
    { path: '/performance/evaluations/:id', element: <EvaluationPage /> },
    { path: '/performance/config', element: <PerformanceConfigPage />, anyOf: ['PER_MANAGE'] },
  ],
  i18n: {
    si: {
      'per.section': 'කාර්ය සාධනය', 'per.my': 'මගේ ඇගයීම්', 'per.assigned': 'සම්පූර්ණ කළ යුතු ඇගයීම්',
      'per.evaluations': 'කාර්ය සාධන ඇගයීම්', 'per.config': 'ඇගයීම් සැකසුම',
    },
    ta: {
      'per.section': 'செயல்திறன்', 'per.my': 'எனது மதிப்பீடுகள்', 'per.assigned': 'நிறைவு செய்ய வேண்டிய மதிப்பீடுகள்',
      'per.evaluations': 'செயல்திறன் மதிப்பீடுகள்', 'per.config': 'மதிப்பீட்டு அமைப்பு',
    },
  },
};
export default mod;
