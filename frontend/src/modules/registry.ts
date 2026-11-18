import type { ModuleDef } from './types';
import dashboard from './dashboard/module';
import self from './self/module';
import establishment from './establishment/module';
import legal from './legal/module';
import employees from './employees/module';
import approvals from './approvals/module';
import reports from './reports/module';
import notifications from './notifications/module';
import admin from './admin/module';
import audit from './audit/module';
// Release 2-5 modules
import leave from './leave/module';
import attendance from './attendance/module';
import payroll from './payroll/module';
import benefits from './benefits/module';
import recruitment from './recruitment/module';
import performance from './performance/module';
import training from './training/module';
import extended from './extended/module';

export const modules: ModuleDef[] = [
  dashboard, self, establishment, legal, employees, approvals, reports, notifications, admin, audit,
  leave, attendance, payroll, benefits, recruitment, performance, training, extended,
];
