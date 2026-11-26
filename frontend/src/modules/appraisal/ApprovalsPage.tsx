import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Empty, Loading, Modal, TextArea, TextField } from '@/components/ui';
import { fmtDate } from '@/components/DataTable';
import type { Appraisal, QueueRow } from './types';
import { APPRAISAL_STEPS, AppraisalShell, CalcPanel, Chip, fmtNum, fmtPct, Note, pretty, StatusTrail, SummaryCard } from './shared';

/** Reviewing officer queue: verified appraisals waiting for approval, then recent decisions. */
export function ApprovalsListPage() {
  const { data, loading, error } = useApi<QueueRow[]>('/appraisal/approvals');
  const pending = (data ?? []).filter((r) => r.status === 'VERIFIED');
  const decided = (data ?? []).filter((r) => r.status !== 'VERIFIED');
  const table = (rows: QueueRow[], action: boolean) => (
    <div className="apr-table-wrap">
      <table className="apr-table">
        <thead><tr><th>Employee</th><th>Unit</th><th className="num">KPI score</th><th className="num">Behavioural</th><th className="num">Final</th><th>Status</th><th aria-label="Action" /></tr></thead>
        <tbody>{rows.map((r) => (
          <tr key={r.appraisalId}>
            <td><b>{r.employeeName}</b><div className="muted" style={{ fontSize: 11.5 }}>{r.employeeNo} · {r.designation}{r.delegatedFrom ? ` · for ${r.delegatedFrom}` : ''}</div></td>
            <td>{r.orgUnitName}</td>
            <td className="num">{fmtNum(r.kpiScore)}</td>
            <td className="num">{fmtNum(r.behaviourScore)}</td>
            <td className="num">{fmtNum(r.finalScore)}</td>
            <td><Chip tone={r.status === 'FINALIZED' ? 'success' : undefined}>{r.queueStatus}</Chip></td>
            <td><Link className={`btn btn-sm ${action ? 'btn-navy' : 'btn-outline'}`} to={`/appraisal/approvals/${r.appraisalId}`}>{action ? 'Review' : 'Open'}</Link></td>
          </tr>
        ))}</tbody>
      </table>
    </div>
  );
  return (
    <AppraisalShell title="Reviewing officer approvals">
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !data ? <Loading /> : <>
        <Card title={`Waiting for your approval (${pending.length})`} accent>{pending.length === 0 ? <Empty text="Nothing is waiting for your approval." /> : table(pending, true)}</Card>
        {decided.length > 0 && <Card title="Approved and finalized">{table(decided, false)}</Card>}
      </>}
    </AppraisalShell>
  );
}

/** Screen 12: scores, optional moderation with required justification, approve or return; approval trail. */
export function ApprovalDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const { data: a, loading, error, setData } = useApi<Appraisal>(`/appraisal/appraisals/${id}`);
  const [adjustment, setAdjustment] = useState('');
  const [justification, setJustification] = useState('');
  const [comments, setComments] = useState('');
  const [busy, setBusy] = useState(false);
  const [returning, setReturning] = useState(false);

  const adj = Number(adjustment) || 0;
  const act = async (url: string, body: object, ok: string) => {
    setBusy(true);
    try {
      const { data } = await api.post<Appraisal>(url, body);
      setData(data);
      setReturning(false);
      toast(ok, 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const levelName: Record<string, string> = { EMPLOYEE: 'Employee', SUPERVISOR: 'Supervisor', REVIEWING_OFFICER: 'Reviewing officer', HR: 'HR', SYSTEM: 'System' };

  return (
    <AppraisalShell title="Reviewing officer approval">
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !a ? <Loading /> : a && (
        <>
          <div className="stack" style={{ gap: 10 }}>
            <StatusTrail steps={APPRAISAL_STEPS} current={a.status} />
            <div className="apr-chips">
              <Chip tone={a.verificationCompletedAt ? undefined : 'danger'}>KPIs verified</Chip>
              <Chip tone={a.behaviourStatus === 'SUBMITTED' ? undefined : 'danger'}>Behavioural assessment submitted</Chip>
              <Chip tone={a.selfStatus === 'SUBMITTED' ? undefined : 'danger'}>Self-assessment received</Chip>
              <Chip>{a.employee.name}</Chip><Chip>{a.period.name}</Chip>
            </div>
          </div>
          {a.flags.actingFor && <Note>You are acting on behalf of {a.flags.actingFor} under a delegation.</Note>}
          <div className="apr-cards">
            <SummaryCard label="KPI score" value={fmtNum(a.kpiScore)} hint={`Weight ${fmtPct(a.kpiWeight, 0)}`} />
            <SummaryCard label="Behavioural score" value={fmtNum(a.behaviourScore)} hint={`Weight ${fmtPct(a.behaviourWeight, 0)}`} />
            <SummaryCard label="Overall score (calculated)" value={fmtNum(a.overallScore)} hint={a.finalScore != null && a.adjustment ? `Final ${a.finalScore} after moderation` : undefined} />
            <SummaryCard label="Category" value={a.category ?? '—'} />
          </div>
          {a.flags.canApprove ? (
            <div className="grid grid-2">
              <Card title="Moderation (optional)" accent>
                <div className="stack" style={{ gap: 10 }}>
                  <TextField label="Adjustment (points, + or −)" type="number" step="0.5" value={adjustment} onChange={setAdjustment}
                    help={adj ? `Final score would be ${Math.max(0, Math.min(100, Number(a.overallScore ?? 0) + adj)).toFixed(2)}` : 'Leave empty for no adjustment'} />
                  <TextArea label="Justification (required if adjusted)" required={adj !== 0} value={justification} onChange={setJustification}
                    error={adj !== 0 && !justification.trim() ? 'Required when the score is adjusted' : undefined} />
                </div>
              </Card>
              <Card title="Decision" accent>
                <TextArea label="Comments" rows={4} value={comments} onChange={setComments} />
                <div className="row" style={{ marginTop: 12 }}>
                  <button className="btn btn-navy" disabled={busy || (adj !== 0 && !justification.trim())}
                    onClick={() => void act(`/appraisal/appraisals/${id}/approve`, { adjustment: adj || null, justification, comments }, 'Appraisal approved')}>Approve</button>
                  <button className="btn btn-outline" disabled={busy} onClick={() => setReturning(true)}>Return for correction</button>
                </div>
              </Card>
            </div>
          ) : (a.status === 'APPROVED' || a.status === 'FINALIZED') && (
            <Card title="Decision">
              <dl className="apr-kv">
                <dt>Final score</dt><dd>{fmtNum(a.finalScore)} ({a.category})</dd>
                {a.adjustment != null && <><dt>Moderation</dt><dd>{a.adjustment} — {a.adjustmentJustification}</dd></>}
                <dt>Comments</dt><dd>{a.reviewerComments ?? '—'}</dd>
                <dt>Approved</dt><dd>{fmtDate(a.approvedAt)}</dd>
                {a.acknowledgedAt && <><dt>Acknowledged</dt><dd>{fmtDate(a.acknowledgedAt)}</dd></>}
              </dl>
            </Card>
          )}
          <div className="row">
            <Link className="btn btn-ghost btn-sm" to={`/appraisal/verify/${a.id}`}>View KPI verification</Link>
            <Link className="btn btn-ghost btn-sm" to={`/appraisal/assess/${a.id}`}>View behavioural assessment</Link>
            <Link className="btn btn-ghost btn-sm" to={`/appraisal/agreement?appraisal=${a.id}`}>View agreement</Link>
          </div>
          <Card title="Approval trail" accent>
            <div className="apr-table-wrap">
              <table className="apr-table">
                <thead><tr><th>Level</th><th>Officer</th><th>Decision</th><th>Date</th></tr></thead>
                <tbody>
                  {a.trail.map((t, i) => (
                    <tr key={i}>
                      <td>{levelName[t.level] ?? pretty(t.level)}</td>
                      <td>{t.officerName}{t.onBehalfOf && <div className="muted" style={{ fontSize: 11.5 }}>on behalf of {t.onBehalfOf}</div>}</td>
                      <td>{t.decision}{t.comment && <div className="muted" style={{ fontSize: 11.5 }}>{t.comment}</div>}</td>
                      <td>{fmtDate(t.actedAt)}</td>
                    </tr>
                  ))}
                  {a.status === 'VERIFIED' && <tr><td>Reviewing officer</td><td>{a.reviewerName ?? 'Appraisal administrator'}</td><td>Pending</td><td /></tr>}
                </tbody>
              </table>
            </div>
          </Card>
          <CalcPanel kpiWeight={a.kpiWeight} behaviourWeight={a.behaviourWeight} />
          {returning && (
            <Modal title="Return for correction" onClose={() => setReturning(false)} footer={
              <><button className="btn btn-ghost" onClick={() => setReturning(false)}>Cancel</button>
                <button className="btn btn-navy" disabled={busy || !comments.trim()} onClick={() => void act(`/appraisal/appraisals/${id}/return`, { comment: comments }, 'Returned to the supervisor')}>Return</button></>
            }>
              <p className="muted">The appraisal goes back to the supervisor, who must complete verification and the behavioural assessment again.</p>
              <TextArea label="Reason" required value={comments} onChange={setComments} />
            </Modal>
          )}
        </>
      )}
    </AppraisalShell>
  );
}
