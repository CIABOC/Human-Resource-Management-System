import { lazy } from 'react';
import { Target } from 'lucide-react';
import type { ModuleDef } from '../types';

const Home = lazy(() => import('./AppraisalHome'));
const MyDashboard = lazy(() => import('./MyDashboardPage'));
const Agreement = lazy(() => import('./AgreementPage'));
const Kpis = lazy(() => import('./KpiProgressPage'));
const SelfAssessment = lazy(() => import('./SelfAssessmentPage'));
const Feedback = lazy(() => import('./FeedbackPage'));
const Appeals = lazy(() => import('./AppealsPage'));
const AppealsReview = lazy(() => import('./AppealsPage').then((m) => ({ default: m.AppealsReviewPage })));
const Team = lazy(() => import('./TeamPage'));
const Verification = lazy(() => import('./VerificationPage'));
const Assessment = lazy(() => import('./AssessmentPage'));
const Approvals = lazy(() => import('./ApprovalsPage').then((m) => ({ default: m.ApprovalsListPage })));
const ApprovalDetail = lazy(() => import('./ApprovalsPage').then((m) => ({ default: m.ApprovalDetailPage })));
const SpecialCases = lazy(() => import('./SpecialCasesPage'));
const Delegation = lazy(() => import('./DelegationPage'));
const Management = lazy(() => import('./ManagementPage'));
const Config = lazy(() => import('./ConfigPage'));
const Sync = lazy(() => import('./SyncPage'));

/**
 * Performance appraisal (wireframes 1-15). One sidebar entry; inside the module a role-based sub-navigation shows the
 * employee, supervisor, reviewing officer, management and administration screens the user is entitled to. Authority
 * over each appraisal is checked by the API per record.
 */
const mod: ModuleDef = {
  key: 'appraisal',
  nav: [{ path: '/appraisal', label: 'apr.title', fallback: 'Performance appraisal', icon: Target, section: 'overview', order: 5 }],
  routes: [
    { path: '/appraisal', element: <Home /> },
    { path: '/appraisal/my', element: <MyDashboard /> },
    { path: '/appraisal/agreement', element: <Agreement /> },
    { path: '/appraisal/kpis', element: <Kpis /> },
    { path: '/appraisal/self-assessment', element: <SelfAssessment /> },
    { path: '/appraisal/feedback', element: <Feedback /> },
    { path: '/appraisal/appeals', element: <Appeals /> },
    { path: '/appraisal/appeals-review', element: <AppealsReview />, anyOf: ['APR_MANAGE'] },
    { path: '/appraisal/team', element: <Team /> },
    { path: '/appraisal/verify/:id', element: <Verification /> },
    { path: '/appraisal/assess/:id', element: <Assessment /> },
    { path: '/appraisal/approvals', element: <Approvals /> },
    { path: '/appraisal/approvals/:id', element: <ApprovalDetail /> },
    { path: '/appraisal/special-cases', element: <SpecialCases /> },
    { path: '/appraisal/delegation', element: <Delegation /> },
    { path: '/appraisal/management', element: <Management />, anyOf: ['APR_OVERVIEW'] },
    { path: '/appraisal/config', element: <Config />, anyOf: ['APR_MANAGE', 'APR_POLICY_APPROVE', 'APR_ADMIN'] },
    { path: '/appraisal/sync', element: <Sync />, anyOf: ['APR_ADMIN'] },
  ],
  i18n: {
    si: { 'apr.title': 'කාර්ය සාධන ඇගයීම', 'dashboard.appraisal': 'කාර්ය සාධන ඇගයීම' },
    ta: { 'apr.title': 'செயல்திறன் மதிப்பீடு', 'dashboard.appraisal': 'செயல்திறன் மதிப்பீடு' },
  },
};
export default mod;
