import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, ConfirmDialog, DL, Loading, Modal, PageHeader, SelectField, StatusBadge, TextArea } from '@/components/ui';
import { fmtDate, fmtDateTime } from '@/components/DataTable';
import { WorkflowTrail } from '@/components/WorkflowTrail';
import { EvaluationDetail, EvaluatorOption, weightedPreview } from './types';

/** Evaluation form (evaluator), read-only review (HR / workflow participants) and acknowledgement (employee). */
export default function EvaluationPage() {
  const { id } = useParams();
  const toast = useToast();
  const { data, error, setData } = useApi<EvaluationDetail>(`/performance/evaluations/${id}`);
  const [lines, setLines] = useState<{ criterionId: number; rating: string; comment: string }[]>([]);
  const [comments, setComments] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [ack, setAck] = useState<string | null>(null);
  const [reassign, setReassign] = useState<string | null>(null);
  const evaluators = useApi<EvaluatorOption[]>(reassign !== null ? '/performance/evaluators' : null);

  useEffect(() => {
    if (!data) return;
    setLines(data.lines.map((l) => ({ criterionId: l.criterionId, rating: l.rating == null ? '' : String(l.rating), comment: l.comment ?? '' })));
    setComments(data.evaluatorComments ?? '');
  }, [data]);

  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return <Loading />;
  const d = data;
  const preview = weightedPreview(d.lines.map((l, i) => ({ weight: l.weight, rating: lines[i]?.rating === '' ? null : Number(lines[i]?.rating) })),
    d.model.ratingMin, d.model.ratingMax);
  const scale: number[] = [];
  for (let v = d.model.ratingMin; v <= d.model.ratingMax; v++) scale.push(v);

  const run = async (fn: () => Promise<{ data: EvaluationDetail }>, msg: string) => {
    setBusy(true);
    try {
      const r = await fn();
      setData(r.data);
      toast(msg, 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const save = () => api.put<EvaluationDetail>(`/performance/evaluations/${d.id}`, {
    evaluatorComments: comments,
    ratings: lines.map((l) => ({ criterionId: l.criterionId, rating: l.rating === '' ? null : Number(l.rating), comment: l.comment })),
  });

  return (
    <>
      <PageHeader eyebrow={`Performance evaluation · ${d.period.name}`} title={`${d.employee.employeeNo} — ${d.employee.nameWithInitials}`}
        subtitle={<><StatusBadge status={d.status} /> {d.employee.designation} · {d.employee.orgUnitName}</>}
        actions={
          <div className="row">
            {d.canReassign && <button className="btn btn-outline" onClick={() => setReassign(String(d.evaluatorUserId ?? ''))}>Change evaluator</button>}
            {d.canEdit && <button className="btn btn-outline" disabled={busy} onClick={() => void run(save, 'Saved')}>Save draft</button>}
            {d.canEdit && <button className="btn btn-gold" disabled={busy} onClick={() => setConfirm(true)}>Submit for HR review</button>}
            {d.canAcknowledge && <button className="btn btn-gold" onClick={() => setAck('')}>Acknowledge</button>}
          </div>
        } />
      <Alert kind="info">Confidential. The score is computed from the model's weights; the assessment is the evaluator's, reviewed by HR.</Alert>
      <div className="grid grid-3" style={{ margin: '16px 0' }}>
        <Card title="Model">
          <div><strong>{d.model.name}</strong> {d.model.sample && <Badge tone="warning">Sample</Badge>}</div>
          <div className="faint">Rating scale {d.model.ratingMin} – {d.model.ratingMax}</div>
        </Card>
        <Card title="Evaluator">
          <div>{d.evaluatorName ?? <span className="badge badge-warning">Not assigned</span>}</div>
          <div className="faint">Due {fmtDate(d.period.dueDate)}</div>
        </Card>
        <Card title="Weighted score">
          <div className="kpi" style={{ padding: 0 }}>
            <div className="value">{d.canEdit ? preview?.score ?? '—' : d.overallScore ?? '—'}</div>
            <div className="hint">{(d.canEdit ? preview?.percent : d.overallPercent) != null ? `${d.canEdit ? preview?.percent : d.overallPercent}% of scale` : ''}</div>
          </div>
        </Card>
      </div>
      <Card pad={false} title="Criteria, KPIs and objectives">
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Criterion</th><th className="num">Weight</th><th>Rating</th><th>Comment</th></tr></thead>
            <tbody>
              {d.lines.map((l, i) => (
                <tr key={l.criterionId}>
                  <td><div><strong>{l.title}</strong> <span className="badge badge-neutral">{l.criterionType}</span></div>{l.description && <div className="faint">{l.description}</div>}</td>
                  <td className="num">{l.weight}%</td>
                  <td>{d.canEdit ? (
                    <select className="select" value={lines[i]?.rating ?? ''} aria-label={`Rating for ${l.title}`}
                      onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, rating: e.target.value } : x)))}>
                      <option value="">—</option>
                      {scale.map((s) => <option key={s} value={s}>{s}</option>)}
                    </select>
                  ) : l.rating ?? '—'}</td>
                  <td>{d.canEdit ? (
                    <input className="input" value={lines[i]?.comment ?? ''} aria-label={`Comment for ${l.title}`}
                      onChange={(e) => setLines((ls) => ls.map((x, j) => (j === i ? { ...x, comment: e.target.value } : x)))} />
                  ) : l.comment}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card-body">
          {d.canEdit
            ? <TextArea label="Overall evaluator comments" value={comments} onChange={setComments} required rows={4} />
            : <DL items={[['Evaluator comments', d.evaluatorComments], ['Employee comments', d.employeeComments],
              ['Submitted', d.submittedAt && fmtDateTime(d.submittedAt)], ['Finalized', d.finalizedAt && fmtDateTime(d.finalizedAt)],
              ['Acknowledged', d.acknowledgedAt && fmtDateTime(d.acknowledgedAt)]]} />}
        </div>
      </Card>
      {d.workflowInstanceId && <Card title="HR review trail" className="mt"><WorkflowTrail entityType="PerformanceEvaluation" entityId={d.id} /></Card>}

      {confirm && (
        <ConfirmDialog title="Submit evaluation" message="Submit this evaluation for HR review? You will not be able to change it unless it is returned."
          confirmLabel="Submit" busy={busy} onCancel={() => setConfirm(false)} onConfirm={() => {
            setConfirm(false);
            void run(async () => { await save(); return api.post<EvaluationDetail>(`/performance/evaluations/${d.id}/submit`); }, 'Submitted for HR review');
          }} />
      )}
      {ack !== null && (
        <Modal title="Acknowledge evaluation" onClose={() => setAck(null)} footer={
          <><button className="btn btn-outline" onClick={() => setAck(null)}>Cancel</button>
            <button className="btn btn-gold" onClick={() => { const c = ack; setAck(null); void run(() => api.post<EvaluationDetail>(`/performance/evaluations/${d.id}/acknowledge`, { comments: c }), 'Acknowledged'); }}>Acknowledge</button></>
        }>
          <TextArea label="Your comments (optional)" value={ack} onChange={setAck} rows={5} help="Acknowledging confirms you have seen the evaluation; it does not mean you agree." />
        </Modal>
      )}
      {reassign !== null && (
        <Modal title="Change evaluator" onClose={() => setReassign(null)} footer={
          <><button className="btn btn-outline" onClick={() => setReassign(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={!reassign} onClick={() => { const u = reassign; setReassign(null); void run(() => api.put<EvaluationDetail>(`/performance/evaluations/${d.id}/evaluator`, { evaluatorUserId: Number(u) }), 'Evaluator changed'); }}>Save</button></>
        }>
          <SelectField label="Evaluator" value={reassign} onChange={setReassign} options={(evaluators.data ?? []).map((u) => ({ value: String(u.userId), label: u.name }))} />
        </Modal>
      )}
    </>
  );
}
