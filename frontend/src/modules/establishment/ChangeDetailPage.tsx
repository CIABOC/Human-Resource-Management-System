import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Paperclip, Send, Trash2 } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, ConfirmDialog, DL, Loading, PageHeader, StatusBadge } from '@/components/ui';
import { fmtDate, fmtDateTime } from '@/components/DataTable';
import { WorkflowTrail } from '@/components/WorkflowTrail';
import type { EstablishmentChange } from './ChangesPage';

type ChangeX = EstablishmentChange & {
  approvingAuthority?: string | null; referenceNo?: string | null; attachmentFileId?: number | null; exceptionReason?: string | null; cadreOldQty?: number | null;
};

export default function ChangeDetailPage() {
  const { id } = useParams();
  const { can } = useAuth();
  const toast = useToast();
  const nav = useNavigate();
  const { data: c, loading, error, reload } = useApi<ChangeX>(`/establishment/changes/${id}`);
  const [busy, setBusy] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  if (loading && !c) return <Loading />;
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!c) return null;
  let payload: Record<string, unknown> = {};
  try {
    payload = JSON.parse(c.payload);
  } catch {
    /* ignore */
  }
  const isDraft = c.status === 'DRAFT';
  const act = async (path: 'submit' | 'discard') => {
    setBusy(true);
    try {
      await api.post(`/establishment/changes/${c.id}/${path}`);
      toast(path === 'submit' ? 'Submitted for approval' : 'Draft discarded', 'success');
      if (path === 'discard') nav('/establishment/changes');
      else void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
      setConfirmDiscard(false);
    }
  };
  return (
    <>
      <PageHeader eyebrow={`Establishment change #${c.id}`} title={c.summary} subtitle={<StatusBadge status={c.status} />}
        actions={
          <>
            {isDraft && can('EST_MANAGE') && <button className="btn btn-outline" disabled={busy} onClick={() => setConfirmDiscard(true)}><Trash2 /> Discard draft</button>}
            {isDraft && can('EST_MANAGE') && <button className="btn btn-gold" disabled={busy} onClick={() => void act('submit')}><Send /> Submit for approval</button>}
            {c.workflowInstanceId && <Link className="btn btn-outline" to={`/approvals/${c.workflowInstanceId}`}>Open approval request</Link>}
          </>
        } />
      {isDraft && <div style={{ marginBottom: 16 }}><Alert kind="info">This is a draft. It has not been sent for approval and changes nothing until it is submitted and approved.</Alert></div>}
      {c.status === 'RETURNED' && <div style={{ marginBottom: 16 }}><Alert kind="warning">Returned for correction. Resubmit from the approval request after reviewing the approver's comments.</Alert></div>}
      <div className="grid grid-2">
        <Card title="Proposal" accent>
          <DL items={[
            ['Type', c.changeType.replace(/_/g, ' ').toLowerCase()],
            ['Target', c.targetRef ?? '—'],
            ['Effective date', fmtDate(c.effectiveDate)],
            ['Reason', c.reason],
            ['Approving authority', c.approvingAuthority ?? '—'],
            ['Reference no.', c.referenceNo ?? '—'],
            ...(c.cadreOldQty != null ? [['Previous approved cadre', String(c.cadreOldQty)] as [string, string]] : []),
            ...(c.exceptionReason ? [['Exception reason', c.exceptionReason] as [string, string]] : []),
            ['Supporting document', c.attachmentFileId
              ? <button className="btn btn-ghost btn-sm" onClick={() => void download(`/establishment/changes/${c.id}/attachment`).catch((e) => toast(errorMessage(e), 'error'))}><Paperclip /> Download</button>
              : '—'],
            ['Proposed by', c.createdBy],
            ['Created', fmtDateTime(c.createdAt)],
            ['Applied', c.appliedAt ? fmtDateTime(c.appliedAt) : 'Not yet'],
          ]} />
          <h3 style={{ marginTop: 18, marginBottom: 8 }}>Proposed values</h3>
          <DL items={Object.entries(payload).filter(([, v]) => v !== null && v !== '').map(([k, v]) => [k, String(v)])} />
        </Card>
        <Card title="Approval trail">
          {isDraft ? <p className="muted">Not yet submitted.</p> : <WorkflowTrail entityType="EstablishmentChange" entityId={c.id} />}
        </Card>
      </div>
      {confirmDiscard && <ConfirmDialog title="Discard draft?" message="The draft will be closed and cannot be submitted." danger confirmLabel="Discard"
        busy={busy} onConfirm={() => void act('discard')} onCancel={() => setConfirmDiscard(false)} />}
    </>
  );
}
