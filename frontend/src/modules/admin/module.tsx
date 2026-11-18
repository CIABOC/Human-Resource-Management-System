import { lazy } from 'react';
import { BellRing, Database, GitPullRequestArrow, KeySquare, Settings2, UsersRound } from 'lucide-react';
import type { ModuleDef } from '../types';

const UsersPage = lazy(() => import('./UsersPage'));
const RolesPage = lazy(() => import('./RolesPage'));
const WorkflowsPage = lazy(() => import('./WorkflowsPage'));
const MasterDataPage = lazy(() => import('./MasterDataPage'));
const SettingsPage = lazy(() => import('./SettingsPage'));
const TemplatesPage = lazy(() => import('./TemplatesPage'));

const mod: ModuleDef = {
  key: 'admin',
  nav: [
    { path: '/admin/users', label: 'admin.users', fallback: 'Users', icon: UsersRound, section: 'administration', order: 1, anyOf: ['UAM_MANAGE'] },
    { path: '/admin/roles', label: 'admin.roles', fallback: 'Roles & permissions', icon: KeySquare, section: 'administration', order: 2, anyOf: ['UAM_MANAGE'] },
    { path: '/admin/workflows', label: 'admin.workflows', fallback: 'Approval workflows', icon: GitPullRequestArrow, section: 'administration', order: 3, anyOf: ['WFL_CONFIG'] },
    { path: '/admin/master-data', label: 'admin.masterData', fallback: 'Master data', icon: Database, section: 'administration', order: 4, anyOf: ['MDM_MANAGE'] },
    { path: '/admin/notifications', label: 'admin.templates', fallback: 'Notifications setup', icon: BellRing, section: 'administration', order: 5, anyOf: ['NOTIF_CONFIG'] },
    { path: '/admin/settings', label: 'admin.settings', fallback: 'Settings & integrations', icon: Settings2, section: 'administration', order: 6, anyOf: ['SYS_CONFIG'] },
  ],
  routes: [
    { path: '/admin/users', element: <UsersPage />, anyOf: ['UAM_MANAGE'] },
    { path: '/admin/roles', element: <RolesPage />, anyOf: ['UAM_MANAGE'] },
    { path: '/admin/workflows', element: <WorkflowsPage />, anyOf: ['WFL_CONFIG'] },
    { path: '/admin/master-data', element: <MasterDataPage />, anyOf: ['MDM_MANAGE'] },
    { path: '/admin/notifications', element: <TemplatesPage />, anyOf: ['NOTIF_CONFIG'] },
    { path: '/admin/settings', element: <SettingsPage />, anyOf: ['SYS_CONFIG'] },
  ],
  i18n: {
    si: { 'admin.users': 'පරිශීලකයින්', 'admin.roles': 'භූමිකා සහ අවසර', 'admin.workflows': 'අනුමැති ක්‍රියාවලි', 'admin.masterData': 'මූලික දත්ත', 'admin.templates': 'දැනුම්දීම් සැකසුම්', 'admin.settings': 'සැකසුම් සහ ඒකාබද්ධතා' },
    ta: { 'admin.users': 'பயனர்கள்', 'admin.roles': 'பாத்திரங்கள் மற்றும் அனுமதிகள்', 'admin.workflows': 'அங்கீகார பணிப்பாய்வுகள்', 'admin.masterData': 'முதன்மை தரவு', 'admin.templates': 'அறிவிப்பு அமைப்பு', 'admin.settings': 'அமைப்புகள் மற்றும் ஒருங்கிணைப்புகள்' },
  },
};
export default mod;
