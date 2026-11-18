import { lazy } from 'react';
import { FileUp, Users } from 'lucide-react';
import type { ModuleDef } from '../types';

const EmployeeListPage = lazy(() => import('./EmployeeListPage'));
const EmployeeDetailPage = lazy(() => import('./EmployeeDetailPage'));
const EmployeeFormPage = lazy(() => import('./EmployeeFormPage'));
const ImportPage = lazy(() => import('./ImportPage'));

const mod: ModuleDef = {
  key: 'employees',
  nav: [
    { path: '/employees', label: 'emp.title', fallback: 'Employees', icon: Users, section: 'people', order: 1, anyOf: ['EMP_VIEW'] },
    { path: '/employees/import', label: 'emp.import', fallback: 'Bulk import', icon: FileUp, section: 'people', order: 50, anyOf: ['EMP_IMPORT'] },
  ],
  routes: [
    { path: '/employees', element: <EmployeeListPage />, anyOf: ['EMP_VIEW'] },
    { path: '/employees/new', element: <EmployeeFormPage />, anyOf: ['EMP_MANAGE'] },
    { path: '/employees/import', element: <ImportPage />, anyOf: ['EMP_IMPORT'] },
    { path: '/employees/:id', element: <EmployeeDetailPage /> },
    { path: '/employees/:id/edit', element: <EmployeeFormPage />, anyOf: ['EMP_MANAGE'] },
  ],
  i18n: {
    si: { 'emp.title': 'සේවකයින්', 'emp.import': 'තොග ආයාත කිරීම' },
    ta: { 'emp.title': 'ஊழியர்கள்', 'emp.import': 'மொத்த இறக்குமதி' },
  },
};
export default mod;
