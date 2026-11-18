import { Link, useParams } from 'react-router-dom';
import { useApi } from '@/hooks/useApi';
import { Alert, Card, DL, Loading, PageHeader, StatusBadge } from '@/components/ui';
import { fmtDate, fmtDateTime } from '@/components/DataTable';
import { WorkflowTrail } from '@/components/WorkflowTrail';
import type { EstablishmentChange } from './ChangesPage';

export default function ChangeDetailPage() {
  const { id } = useParams();
  const { data: c, loading, error } = useApi<EstablishmentChange>(`/establishment/changes/${id}`);
  if (loading && !c) return <Loading />;
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!c) return null;
  let payload: Record<string, unknown> = {};
  try {
    payload = JSON.parse(c.payload);
  } catch {
    /* ignore */
  }
  return (
    <>
      <PageHeader eyebrow={`Establishment change #${c.id}`} title={c.summary} subtitle={<StatusBadge status={c.status} />}
        actions={c.workflowInstanceId ? <Link className="btn btn-outline" to={`/approvals/${c.workflowInstanceId}`}>Open approval request</Link> : undefined} />
      {c.status === 'RETURNED' && <div style={{ marginBottom: 16 }}><Alert kind="warning">Returned for correction. Resubmit from the approval request after reviewing the approver's comments.</Alert></div>}
      <div className="grid grid-2">
        <Card title="Proposal" accent>
          <DL items={[
            ['Type', c.changeType.replace(/_/g, ' ').toLowerCase()],
            ['Target', c.targetRef ?? '—'],
            ['Effective date', fmtDate(c.effectiveDate)],
            ['Reason', c.reason],
            ['Proposed by', c.createdBy],
            ['Submitted', fmtDateTime(c.createdAt)],
            ['Applied', c.appliedAt ? fmtDateTime(c.appliedAt) : 'Not yet'],
          ]} />
          <h3 style={{ marginTop: 18, marginBottom: 8 }}>Proposed values</h3>
          <DL items={Object.entries(payload).map(([k, v]) => [k, v === null ? '' : String(v)])} />
        </Card>
        <Card title="Approval trail">
          <WorkflowTrail entityType="EstablishmentChange" entityId={c.id} />
        </Card>
      </div>
    </>
  );
}
