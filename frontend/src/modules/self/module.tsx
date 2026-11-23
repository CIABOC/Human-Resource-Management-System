import { lazy } from 'react';
import { UserRound } from 'lucide-react';
import type { ModuleDef } from '../types';

const MyProfilePage = lazy(() => import('./MyProfilePage'));
const ChangePasswordPage = lazy(() => import('./ChangePasswordPage'));
const AccountSettingsPage = lazy(() => import('./AccountSettingsPage'));

const mod: ModuleDef = {
  key: 'self',
  nav: [{ path: '/me', label: 'profile.title', fallback: 'My profile', icon: UserRound, section: 'self', order: 1, anyOf: ['SELF_SERVICE'] }],
  routes: [
    { path: '/me', element: <MyProfilePage />, anyOf: ['SELF_SERVICE'] },
    { path: '/account/password', element: <ChangePasswordPage /> },
    { path: '/account/settings', element: <AccountSettingsPage /> },
  ],
};
export default mod;
