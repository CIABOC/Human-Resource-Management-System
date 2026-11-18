import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import type { WorkflowInstance } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { Alert, Badge, Card, PageHeader, StatusBadge, Tabs } from '@/components/ui';
import { DataTable, fmtDateTime } from '@/components/DataTable';

type Tab = 'inbox' | 'mine' | 'acted';

const PROCESS_LABELS: Record<string, string> = {
  ESTABLISHMENT_CHANGE: 'Establishment change', PROFILE_CHANGE: 'Profile change', LEAVE: 'Leave', LEAVE_CANCEL: 'Leave cancellation',
  ATTENDANCE_CORRECTION: 'Attendance correction', NOPAY_APPROVAL: 'No-pay approval', PAYROLL_RUN: 'Payroll run', LOAN_ADJUSTMENT: 'Loan adjustment',
  PERFORMANCE_EVALUATION: 'Performance evaluation', TRAINING_NOMINATION: 'Training nomination', RECRUITMENT_SELECTION: 'Recruitment selection',
  TRANSFER: 'Transfer', PROMOTION: 'Promotion', LETTER_REQUEST: 'Letter request',
};
export const processLabel = (c: string) => PROCESS_LABELS[c] ?? c.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (x) => x.toUpperCase());

export default function ApprovalsPage() {
  const { t } = useTranslation();
  const { can } = useAuth();
  const nav = useNavigate();
  const [params, setParams] = useSearchParams();
  const approver = can('WFL_APPROVE');
  const [tab, setTab] = useState<Tab>((params.get('tab') as Tab) ?? (approver ? 'inbox' : 'mine'));
  const url = tab === 'inbox' ? '/workflow/inbox' : tab === 'acted' ? '/workflow/acted' : '/workflow/my-requests';
  const { data, loading, error } = useApi<WorkflowInstance[]>(url);

  const tabs: { key: Tab; label: string }[] = [
    ...(approver ? [{ key: 'inbox' as Tab, label: t('approvals.inbox', 'Awaiting my action') }] : []),
    { key: 'mine', label: t('approvals.mine', 'My requests') },
    ...(approver ? [{ key: 'acted' as Tab, label: t('approvals.acted', 'Decided by me') }] : []),
  ];

  return (
    <>
      <PageHeader title={t('approvals.title', 'Approvals & requests')} subtitle="Every request follows the approval chain configured for its process. You can never approve your own request." />
      <Tabs tabs={tabs} value={tab} onChange={(k) => { setTab(k); setParams({ tab: k }); }} />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<WorkflowInstance>
          rows={data}
          loading={loading}
          onRowClick={(r) => nav(`/approvals/${r.id}`)}
          empty={tab === 'inbox' ? 'Nothing is waiting for your approval.' : undefined}
          columns={[
            { key: 'id', header: 'Ref', render: (r) => <span className="mono">#{r.id}</span>, width: 70 },
            { key: 'title', header: 'Request', render: (r) => (
              <div>
                <div style={{ fontWeight: 600, color: 'var(--navy-800)' }}>{r.title}</div>
                <div className="muted" style={{ fontSize: 12 }}>{processLabel(r.processCode)}</div>
              </div>
            ) },
            { key: 'requesterName', header: 'Requested by' },
            { key: 'stage', header: 'Stage', render: (r) => (
              <span>{r.status === 'PENDING' ? `${r.currentStage}/${r.totalStages} · ${r.currentStageName ?? ''}` : '—'} {r.escalated && <Badge tone="warning">Overdue</Badge>}</span>
            ) },
            { key: 'submittedAt', header: 'Submitted', render: (r) => <span className="nowrap">{fmtDateTime(r.submittedAt)}</span> },
            { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
          ]}
        />
      </Card>
    </>
  );
}
