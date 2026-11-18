import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Download } from 'lucide-react';
import { download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, DL, Loading, PageHeader, StatusBadge } from '@/components/ui';
import { fmtDate, fmtDateTime } from '@/components/DataTable';
import { WorkflowTrail } from '@/components/WorkflowTrail';
import { fmtDays, type LeaveApplication } from './types';

export default function LeaveApplicationPage() {
  const { id } = useParams();
  const { t } = useTranslation();
  const toast = useToast();
  const { data: a, error } = useApi<LeaveApplication>(`/leave/applications/${id}`);
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!a) return <Loading />;
  const who = a.employee ? `${a.employee.employeeNo} — ${a.employee.nameWithInitials}` : '';
  return (
    <>
      <PageHeader eyebrow={t('leave.application', 'Leave application')} title={`${a.leaveTypeName} · ${fmtDays(a.days)} day(s)`} subtitle={who}
        actions={a.hasDocument ? (
          <button className="btn btn-outline" onClick={() => download(`/leave/applications/${a.id}/document`).catch((e) => toast(errorMessage(e), 'error'))}>
            <Download /> Supporting document
          </button>
        ) : undefined} />
      <div className="grid grid-2">
        <Card title="Details">
          <DL items={[
            ['Employee', who],
            ['Unit', a.employee?.orgUnitName],
            ['Leave type', `${a.leaveTypeName}${a.paid ? '' : ' (unpaid)'}`],
            ['From', fmtDate(a.startDate)],
            ['To', fmtDate(a.endDate)],
            ['Half day', a.halfDay ? a.halfDaySession : 'No'],
            ['Working days', fmtDays(a.days)],
            ['Reason', a.reason],
            ['Contact during leave', a.contactDuringLeave],
            ['Status', <StatusBadge key="s" status={a.status} />],
            ['Cancellation', a.cancellationStatus ? <span key="c"><StatusBadge status={a.cancellationStatus} /> {a.cancellationReason}</span> : null],
            ['Applied', fmtDateTime(a.createdAt)],
            ['Decided', a.decidedAt ? fmtDateTime(a.decidedAt) : null],
          ]} />
        </Card>
        <Card title="Approval trail">
          <WorkflowTrail entityType="LeaveApplication" entityId={a.id} />
          {a.cancelWorkflowInstanceId && (
            <div style={{ marginTop: 16 }}>
              <h4>Cancellation request</h4>
              <WorkflowTrail entityType="LeaveCancellation" entityId={a.id} />
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
