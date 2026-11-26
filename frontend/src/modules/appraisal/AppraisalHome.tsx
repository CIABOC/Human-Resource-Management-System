import { Navigate } from 'react-router-dom';
import { useApi } from '@/hooks/useApi';
import { Alert, Loading } from '@/components/ui';
import type { AprContext } from './types';

/** Opens the screen that fits the user's role: management first, then reviewing officer, supervisor, employee, admin. */
export default function AppraisalHome() {
  const { data, error } = useApi<AprContext>('/appraisal/context');
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return <Loading />;
  const to = data.overview ? '/appraisal/management'
    : data.pendingApprovals > 0 ? '/appraisal/approvals'
      : data.supervisor ? '/appraisal/team'
        : data.employeeId ? '/appraisal/my'
          : data.admin ? '/appraisal/sync'
            : data.manage || data.policyApprove ? '/appraisal/config' : '/appraisal/my';
  return <Navigate to={to} replace />;
}
