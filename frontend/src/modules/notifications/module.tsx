import { lazy } from 'react';
import { Bell } from 'lucide-react';
import type { ModuleDef } from '../types';

const NotificationsPage = lazy(() => import('./NotificationsPage'));

const mod: ModuleDef = {
  key: 'notifications',
  nav: [{ path: '/notifications', label: 'notifications.title', fallback: 'Notifications', icon: Bell, section: 'self', order: 90 }],
  routes: [{ path: '/notifications', element: <NotificationsPage /> }],
};
export default mod;
