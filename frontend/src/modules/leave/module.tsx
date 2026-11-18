import { lazy } from 'react';
import { CalendarDays, CalendarRange, ClipboardList } from 'lucide-react';
import type { ModuleDef } from '../types';

const MyLeavePage = lazy(() => import('./MyLeavePage'));
const LeaveApplicationPage = lazy(() => import('./LeaveApplicationPage'));
const LeaveAdminPage = lazy(() => import('./LeaveAdminPage'));
const TeamCalendarPage = lazy(() => import('./TeamCalendarPage'));

/** Leave Management (SRS 4.4). */
const mod: ModuleDef = {
  key: 'leave',
  nav: [
    { path: '/leave/my', label: 'leave.my', fallback: 'My leave', icon: CalendarDays, section: 'self', order: 20, anyOf: ['SELF_SERVICE'] },
    { path: '/leave/team-calendar', label: 'leave.teamCalendar', fallback: 'Team leave calendar', icon: CalendarRange, section: 'time', order: 10, anyOf: ['LEAVE_VIEW'] },
    { path: '/leave/admin', label: 'leave.admin', fallback: 'Leave administration', icon: ClipboardList, section: 'time', order: 11, anyOf: ['LEAVE_VIEW', 'LEAVE_MANAGE', 'LEAVE_CONFIG'] },
  ],
  routes: [
    { path: '/leave/my', element: <MyLeavePage />, anyOf: ['SELF_SERVICE'] },
    { path: '/leave/applications/:id', element: <LeaveApplicationPage /> },
    { path: '/leave/team-calendar', element: <TeamCalendarPage />, anyOf: ['LEAVE_VIEW'] },
    { path: '/leave/admin', element: <LeaveAdminPage />, anyOf: ['LEAVE_VIEW', 'LEAVE_MANAGE', 'LEAVE_CONFIG'] },
  ],
  i18n: {
    si: {
      'leave.my': 'මගේ නිවාඩු',
      'leave.apply': 'නිවාඩු සඳහා අයදුම් කරන්න',
      'leave.revise': 'නිවාඩු අයදුම්පත සංශෝධනය කරන්න',
      'leave.application': 'නිවාඩු අයදුම්පත',
      'leave.teamCalendar': 'කණ්ඩායම් නිවාඩු දින දර්ශනය',
      'leave.admin': 'නිවාඩු පරිපාලනය',
    },
    ta: {
      'leave.my': 'எனது விடுமுறை',
      'leave.apply': 'விடுமுறைக்கு விண்ணப்பிக்கவும்',
      'leave.revise': 'விடுமுறை விண்ணப்பத்தைத் திருத்தவும்',
      'leave.application': 'விடுமுறை விண்ணப்பம்',
      'leave.teamCalendar': 'குழு விடுமுறை நாட்காட்டி',
      'leave.admin': 'விடுமுறை நிர்வாகம்',
    },
  },
};
export default mod;
