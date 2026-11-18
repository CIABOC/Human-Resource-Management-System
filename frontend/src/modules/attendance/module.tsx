import { lazy } from 'react';
import { Clock, Fingerprint, Settings2, WalletCards } from 'lucide-react';
import { useAuth } from '@/auth/AuthContext';
import type { ModuleDef } from '../types';

const AttendancePage = lazy(() => import('./AttendancePage'));
const MyAttendancePage = lazy(() => import('./MyAttendancePage'));
const AttendanceConfigPage = lazy(() => import('./AttendanceConfigPage'));
const NoPayPage = lazy(() => import('./NoPayPage'));

/** Correction notifications link here: managers see all corrections, employees their own. */
function CorrectionsRoute() {
  const { can } = useAuth();
  return can('ATT_VIEW') ? <AttendancePage initialTab="corrections" /> : <MyAttendancePage />;
}

/** Attendance Management (SRS 4.5). */
const mod: ModuleDef = {
  key: 'attendance',
  nav: [
    { path: '/attendance/my', label: 'attendance.my', fallback: 'My attendance', icon: Clock, section: 'self', order: 21, anyOf: ['SELF_SERVICE'] },
    { path: '/attendance', label: 'attendance.title', fallback: 'Attendance', icon: Fingerprint, section: 'time', order: 20, anyOf: ['ATT_VIEW'] },
    { path: '/attendance/nopay', label: 'attendance.nopay', fallback: 'No-pay', icon: WalletCards, section: 'time', order: 22, anyOf: ['ATT_VIEW'] },
    { path: '/attendance/config', label: 'attendance.config', fallback: 'Working schedules & holidays', icon: Settings2, section: 'time', order: 23, anyOf: ['ATT_CONFIG', 'ATT_VIEW'] },
  ],
  routes: [
    { path: '/attendance', element: <AttendancePage />, anyOf: ['ATT_VIEW'] },
    { path: '/attendance/events', element: <AttendancePage initialTab="events" />, anyOf: ['ATT_VIEW'] },
    { path: '/attendance/corrections', element: <CorrectionsRoute />, anyOf: ['ATT_VIEW', 'SELF_SERVICE'] },
    { path: '/attendance/my', element: <MyAttendancePage />, anyOf: ['SELF_SERVICE'] },
    { path: '/attendance/nopay', element: <NoPayPage />, anyOf: ['ATT_VIEW'] },
    { path: '/attendance/config', element: <AttendanceConfigPage />, anyOf: ['ATT_CONFIG', 'ATT_VIEW'] },
  ],
  i18n: {
    si: {
      'attendance.title': 'පැමිණීම',
      'attendance.my': 'මගේ පැමිණීම',
      'attendance.nopay': 'වැටුප් රහිත දින',
      'attendance.config': 'වැඩ කාලසටහන් සහ නිවාඩු දින',
    },
    ta: {
      'attendance.title': 'வருகை',
      'attendance.my': 'எனது வருகை',
      'attendance.nopay': 'சம்பளமற்ற நாட்கள்',
      'attendance.config': 'வேலை அட்டவணைகள் மற்றும் விடுமுறை நாட்கள்',
    },
  },
};
export default mod;
