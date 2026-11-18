import { lazy } from 'react';
import { ArrowRightLeft, FileCheck2, FileSignature, FileStack, ListChecks, LogOut, ScanLine } from 'lucide-react';
import type { ModuleDef } from '../types';

const MyLettersPage = lazy(() => import('./MyLettersPage'));
const MovementsPage = lazy(() => import('./MovementsPage'));
const MovementDetailPage = lazy(() => import('./MovementDetailPage'));
const RetirementsPage = lazy(() => import('./RetirementsPage'));
const SeparationDetailPage = lazy(() => import('./SeparationDetailPage'));
const ClearanceTasksPage = lazy(() => import('./ClearanceTasksPage'));
const LettersPage = lazy(() => import('./LettersPage'));
const DocumentViewPage = lazy(() => import('./DocumentViewPage'));
const LetterTemplatesPage = lazy(() => import('./LetterTemplatesPage'));
const DigitizationPage = lazy(() => import('./DigitizationPage'));
const DigitizationBatchPage = lazy(() => import('./DigitizationBatchPage'));

const EXT = ['EXT_VIEW', 'EXT_MANAGE'];
/** Clearance responsibilities sit with many staff roles (HR, Finance, ICT, admin, division heads). */
const STAFF = ['EXT_VIEW', 'EXT_MANAGE', 'EMP_VIEW', 'WFL_APPROVE', 'DASHBOARD_VIEW'];

const mod: ModuleDef = {
  key: 'extended',
  nav: [
    { path: '/self/letters', label: 'extended.myLetters', fallback: 'My letters', icon: FileSignature, section: 'self', order: 40, anyOf: ['SELF_SERVICE'] },
    { path: '/extended/movements', label: 'extended.movements', fallback: 'Transfers & promotions', icon: ArrowRightLeft, section: 'people', order: 40, anyOf: EXT },
    { path: '/extended/retirements', label: 'extended.retirements', fallback: 'Retirement & offboarding', icon: LogOut, section: 'people', order: 41, anyOf: EXT },
    { path: '/extended/clearance', label: 'extended.clearance', fallback: 'Clearance tasks', icon: ListChecks, section: 'people', order: 42, anyOf: STAFF },
    { path: '/extended/letters', label: 'extended.letters', fallback: 'Letters & certificates', icon: FileCheck2, section: 'people', order: 43, anyOf: EXT },
    { path: '/extended/digitization', label: 'extended.digitization', fallback: 'Digitization', icon: ScanLine, section: 'people', order: 44, anyOf: EXT },
    { path: '/extended/letter-templates', label: 'extended.letterTemplates', fallback: 'Letter templates', icon: FileStack, section: 'administration', order: 40, anyOf: ['EXT_MANAGE'] },
  ],
  routes: [
    { path: '/self/letters', element: <MyLettersPage />, anyOf: ['SELF_SERVICE'] },
    { path: '/extended/movements', element: <MovementsPage />, anyOf: EXT },
    { path: '/extended/movements/:id', element: <MovementDetailPage />, anyOf: EXT },
    { path: '/extended/retirements', element: <RetirementsPage />, anyOf: EXT },
    { path: '/extended/separations/:id', element: <SeparationDetailPage />, anyOf: STAFF },
    { path: '/extended/clearance', element: <ClearanceTasksPage />, anyOf: STAFF },
    { path: '/extended/letters', element: <LettersPage />, anyOf: EXT },
    { path: '/extended/documents/:id', element: <DocumentViewPage />, anyOf: ['SELF_SERVICE', ...EXT] },
    { path: '/extended/letter-templates', element: <LetterTemplatesPage />, anyOf: ['EXT_MANAGE'] },
    { path: '/extended/digitization', element: <DigitizationPage />, anyOf: EXT },
    { path: '/extended/digitization/:id', element: <DigitizationBatchPage />, anyOf: EXT },
  ],
  i18n: {
    si: {
      'extended.myLetters': 'මගේ ලිපි',
      'extended.movements': 'ස්ථාන මාරු සහ උසස්වීම්',
      'extended.retirements': 'විශ්‍රාම සහ සේවයෙන් ඉවත්වීම',
      'extended.clearance': 'නිෂ්කාශන කාර්යයන්',
      'extended.letters': 'ලිපි සහ සහතික',
      'extended.digitization': 'ඩිජිටල්කරණය',
      'extended.letterTemplates': 'ලිපි ආකෘති',
      'extended.document': 'නිකුත් කළ ලේඛනය',
      'extended.movementDetail': 'ස්ථාන මාරු / උසස්වීම් ඉල්ලීම',
      'extended.separationCase': 'සේවයෙන් ඉවත්වීමේ නඩුව',
      'extended.batch': 'ඩිජිටල්කරණ කාණ්ඩය',
    },
    ta: {
      'extended.myLetters': 'எனது கடிதங்கள்',
      'extended.movements': 'இடமாற்றங்கள் மற்றும் பதவி உயர்வுகள்',
      'extended.retirements': 'ஓய்வு மற்றும் விலகல்',
      'extended.clearance': 'அனுமதிப் பணிகள்',
      'extended.letters': 'கடிதங்கள் மற்றும் சான்றிதழ்கள்',
      'extended.digitization': 'இலக்கமயமாக்கல்',
      'extended.letterTemplates': 'கடித வார்ப்புருக்கள்',
      'extended.document': 'வழங்கப்பட்ட ஆவணம்',
      'extended.movementDetail': 'இடமாற்றம் / பதவி உயர்வு கோரிக்கை',
      'extended.separationCase': 'விலகல் வழக்கு',
      'extended.batch': 'இலக்கமயமாக்கல் தொகுதி',
    },
  },
};
export default mod;
