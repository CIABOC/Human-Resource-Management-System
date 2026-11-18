import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Check, CornerUpLeft, ExternalLink, RotateCcw, X } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import type { ApprovalAction, WorkflowInstance } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, DL, Loading, Modal, PageHeader, StatusBadge, TextArea } from '@/components/ui';
import { fmtDateTime } from '@/components/DataTable';
import { ActionTimeline } from '@/components/WorkflowTrail';
import { processLabel } from './ApprovalsPage';

interface Stage { id: number; stageOrder: number; name: string; approverType: string; approverRef?: string | null }
interface Detail {
  instance: WorkflowInstance;
  actions: ApprovalAction[];
  summary: Record<string, unknown>;
  stages: Stage[];
  canAct: boolean;
  isRequester: boolean;
}

type Decision = 'APPROVE' | 'REJECT' | 'RETURN';

export default function ApprovalDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const { data, loading, error, reload } = useApi<Detail>(`/workflow/instances/${id}`);
  const [decision, setDecision] = useState<Decision | 'RESUBMIT' | 'CANCEL' | null>(null);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);

  if (loading && !data) return <Loading />;
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return null;
  const wi = data.instance;

  const submit = async () => {
    if (!decision) return;
    setBusy(true);
    try {
      if (decision === 'RESUBMIT') await api.post(`/workflow/instances/${wi.id}/resubmit`, { comment });
      else if (decision === 'CANCEL') await api.post(`/workflow/instances/${wi.id}/cancel`, { comment });
      else await api.post(`/workflow/instances/${wi.id}/act`, { decision, comment });
      toast('Your decision has been recorded', 'success');
      setDecision(null);
      setComment('');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const needsComment = decision === 'REJECT' || decision === 'RETURN';
  const titles: Record<string, string> = { APPROVE: 'Approve request', REJECT: 'Reject request', RETURN: 'Return for correction', RESUBMIT: 'Resubmit request', CANCEL: 'Cancel request' };

  return (
    <>
      <PageHeader eyebrow={`${processLabel(wi.processCode)} · Request #${wi.id}`} title={wi.title}
        subtitle={<span>Submitted by <strong>{wi.requesterName}</strong> on {fmtDateTime(wi.submittedAt)} &nbsp; <StatusBadge status={wi.status} /></span>}
        actions={
          <>
            {wi.link && <Link className="btn btn-outline" to={wi.link}><ExternalLink /> Open record</Link>}
            {data.isRequester && wi.status === 'RETURNED' && <button className="btn btn-gold" onClick={() => setDecision('RESUBMIT')}><RotateCcw /> Resubmit</button>}
            {data.isRequester && (wi.status === 'PENDING' || wi.status === 'RETURNED') && <button className="btn btn-outline" onClick={() => setDecision('CANCEL')}>Cancel request</button>}
          </>
        } />

      {data.canAct && (
        <div className="card card-pad accent" style={{ marginBottom: 18 }}>
          <div className="row between">
            <div>
              <h3>Your decision is required</h3>
              <div className="muted">Stage {wi.currentStage} of {wi.totalStages}: {wi.currentStageName}</div>
            </div>
            <div className="row">
              <button className="btn btn-outline" onClick={() => setDecision('RETURN')}><CornerUpLeft /> Return</button>
              <button className="btn btn-danger" onClick={() => setDecision('REJECT')}><X /> Reject</button>
              <button className="btn btn-gold" onClick={() => setDecision('APPROVE')}><Check /> Approve</button>
            </div>
          </div>
        </div>
      )}
      {data.isRequester && wi.status === 'RETURNED' && (
        <div style={{ marginBottom: 18 }}>
          <Alert kind="warning">This request was returned for correction. Update the underlying record if needed, then resubmit.</Alert>
        </div>
      )}

      <div className="grid grid-2">
        <Card title="Request details" accent>
          {Object.keys(data.summary).length === 0 ? <div className="faint">No additional details.</div> : (
            <DL items={Object.entries(data.summary).map(([k, v]) => [k, v === null || v === undefined ? '' : String(v)])} />
          )}
        </Card>
        <div className="stack">
          <Card title="Approval chain">
            <ol style={{ margin: 0, paddingLeft: 18 }}>
              {data.stages.map((s) => {
                const done = wi.status === 'APPROVED' || s.stageOrder < wi.currentStage;
                const current = wi.status === 'PENDING' && s.stageOrder === wi.currentStage;
                return (
                  <li key={s.id} style={{ marginBottom: 6, fontWeight: current ? 700 : 400, color: done ? 'var(--success)' : current ? 'var(--gold-700)' : undefined }}>
                    {s.name} <span className="faint" style={{ fontWeight: 400 }}>({s.approverType === 'DIVISION_HEAD' ? 'division head' : `${s.approverType.toLowerCase()} ${s.approverRef ?? ''}`})</span>
                    {done && ' ✓'}{current && ' — awaiting'}
                  </li>
                );
              })}
            </ol>
          </Card>
          <Card title="History">
            <ActionTimeline actions={data.actions} />
          </Card>
        </div>
      </div>

      {decision && (
        <Modal title={titles[decision]} onClose={() => setDecision(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setDecision(null)}>Cancel</button>
            <button className={`btn ${decision === 'REJECT' ? 'btn-danger' : decision === 'APPROVE' ? 'btn-gold' : 'btn-primary'}`} disabled={busy || (needsComment && !comment.trim())} onClick={() => void submit()}>
              Confirm
            </button>
          </>
        }>
          <TextArea label={needsComment ? 'Reason (required)' : 'Comment (optional)'} value={comment} onChange={setComment} required={needsComment} rows={4} />
        </Modal>
      )}
    </>
  );
}
