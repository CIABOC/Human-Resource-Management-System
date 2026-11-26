import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Loading, Modal, TextArea } from '@/components/ui';
import type { Appraisal } from './types';
import { methodLabel } from './types';
import { APPRAISAL_STEPS, AppraisalShell, CalcPanel, Chip, Confidential, fmtPct, Note, StatusTrail } from './shared';

interface Line { decision: string; value: string; comment: string }

/** Screen 11: the supervisor verifies each KPI against its evidence; the overall result is calculated, read-only. */
export default function VerificationPage() {
  const { id } = useParams();
  const toast = useToast();
  const { data: a, loading, error, setData } = useApi<Appraisal>(`/appraisal/appraisals/${id}`);
  const [lines, setLines] = useState<Record<number, Line>>({});
  const [busy, setBusy] = useState(false);
  const [returning, setReturning] = useState(false);
  const [comment, setComment] = useState('');

  useEffect(() => {
    if (!a) return;
    setLines(Object.fromEntries(a.kpis.map((k) => [k.id, {
      decision: k.decision ?? '', value: k.verifiedValue != null ? String(k.verifiedValue) : '', comment: k.verifierComment ?? '',
    }])));
  }, [a]);

  const active = a?.kpis.filter((k) => !k.excepted) ?? [];
  /** Live preview of the calculation from the current entries (the server recalculates on save). */
  const preview = useMemo(() => {
    let w = 0, s = 0;
    for (const k of active) {
      w += Number(k.weight);
      const l = lines[k.id];
      if (l && (l.decision === 'VERIFIED' || l.decision === 'ADJUSTED')) {
        const v = l.value === '' ? (l.decision === 'VERIFIED' ? Number(k.selfProgress ?? 0) : 0) : Number(l.value);
        s += v * Number(k.weight);
      }
    }
    return w > 0 ? Math.round((s / w) * 100) / 100 : null;
  }, [active, lines]);
  const allDecided = active.length > 0 && active.every((k) => ['VERIFIED', 'ADJUSTED'].includes(lines[k.id]?.decision ?? ''));
  const commentsOk = active.every((k) => !['ADJUSTED', 'RETURNED'].includes(lines[k.id]?.decision ?? '') || (lines[k.id]?.comment ?? '').trim());
  const anyReturned = active.some((k) => lines[k.id]?.decision === 'RETURNED');
  const editable = !!a?.flags.canVerify;

  const save = async (complete: boolean) => {
    setBusy(true);
    try {
      const { data } = await api.put<Appraisal>(`/appraisal/appraisals/${id}/verification`, {
        complete, lines: active.map((k) => ({ kpiId: k.id, decision: lines[k.id]?.decision || null, verifiedValue: lines[k.id]?.value === '' ? null : Number(lines[k.id]?.value), comment: lines[k.id]?.comment })),
      });
      setData(data);
      toast(complete ? 'Verification completed' : 'Saved', 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const doReturn = async () => {
    setBusy(true);
    try {
      await api.put(`/appraisal/appraisals/${id}/verification`, {
        complete: false, lines: active.map((k) => ({ kpiId: k.id, decision: lines[k.id]?.decision || null, verifiedValue: lines[k.id]?.value === '' ? null : Number(lines[k.id]?.value), comment: lines[k.id]?.comment })),
      });
      const { data } = await api.post<Appraisal>(`/appraisal/appraisals/${id}/verification/return`, { comment });
      setData(data);
      setReturning(false);
      toast('Returned to the employee', 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AppraisalShell title="KPI verification">
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !a ? <Loading /> : a && (
        <>
          <div className="stack" style={{ gap: 10 }}>
            <StatusTrail steps={APPRAISAL_STEPS} current={a.status} />
            <div className="apr-chips"><Confidential /><Chip>{a.employee.name}</Chip><Chip>{a.period.name}</Chip>
              <Link className="apr-chip" to={`/appraisal/assess/${a.id}`}>Behavioural assessment: {a.behaviourStatus === 'SUBMITTED' ? 'submitted' : 'pending'}</Link></div>
          </div>
          {a.flags.actingFor && <Note>You are acting on behalf of {a.flags.actingFor} under a delegation.</Note>}
          {a.status === 'DRAFT' && <Alert kind="info">The employee has not submitted the self-assessment yet. You can record decisions on submitted KPI progress; verification can be completed after the self-assessment is submitted.</Alert>}
          {!editable && a.verificationCompletedAt && <Alert kind="success">Verification completed.</Alert>}
          <Card title="Verify each KPI against its evidence" accent>
            <div className="apr-table-wrap">
              <table className="apr-table">
                <thead><tr><th>KPI</th><th>Method</th><th className="num">Self-reported</th><th>Evidence</th><th>Decision</th><th>Verified value</th><th>Comment</th></tr></thead>
                <tbody>{a.kpis.map((k) => (
                  <tr key={k.id}>
                    <td><b>{k.title}</b><div className="muted" style={{ fontSize: 11.5 }}>Weight {fmtPct(k.weight, 0)}{k.delayExplanation ? ` · Delay: ${k.delayExplanation}` : ''}</div></td>
                    <td>{methodLabel(k.method)}</td>
                    <td className="num">{fmtPct(k.selfProgress)}</td>
                    <td>{k.evidence.length === 0 ? <span className="muted">None</span> : k.evidence.map((e) => (
                      <div key={e.id}><button className="btn btn-ghost btn-sm" onClick={() => void download(`/appraisal/evidence/${e.id}/download`, { filename: e.fileName })}>View</button>
                        <span className="muted" style={{ fontSize: 11 }}>{e.fileName}</span></div>
                    ))}</td>
                    {k.excepted ? <td colSpan={3}><Chip tone="muted">Exception approved – left out of the result</Chip></td> : <>
                      <td>
                        <select className="select" aria-label={`Decision for ${k.title}`} disabled={!editable} value={lines[k.id]?.decision ?? ''}
                          onChange={(e) => setLines({ ...lines, [k.id]: { ...lines[k.id], decision: e.target.value, value: e.target.value === 'VERIFIED' && !lines[k.id]?.value ? String(k.selfProgress ?? '') : lines[k.id]?.value } })}>
                          <option value="">—</option><option value="VERIFIED">Verified</option><option value="ADJUSTED">Adjusted</option><option value="RETURNED">Returned</option>
                        </select>
                      </td>
                      <td><input className="input" type="number" min={0} max={100} placeholder="00%" aria-label={`Verified value for ${k.title}`}
                        disabled={!editable || lines[k.id]?.decision === 'RETURNED'} value={lines[k.id]?.value ?? ''}
                        onChange={(e) => setLines({ ...lines, [k.id]: { ...lines[k.id], value: e.target.value } })} /></td>
                      <td><input className="input" placeholder="Comment" aria-label={`Comment for ${k.title}`} disabled={!editable} value={lines[k.id]?.comment ?? ''}
                        onChange={(e) => setLines({ ...lines, [k.id]: { ...lines[k.id], comment: e.target.value } })} /></td>
                    </>}
                  </tr>
                ))}</tbody>
              </table>
            </div>
          </Card>
          <section className="apr-panel">
            <h4>Calculated result (read-only)</h4>
            <p>Overall KPI achievement: <b>{fmtPct(editable ? preview : a.kpiScore ?? a.verifiedScore)}</b>, calculated from the verified KPIs and their weights.</p>
          </section>
          {editable && (
            <div className="row">
              <button className="btn btn-navy" disabled={busy} onClick={() => void save(false)}>Save</button>
              <button className="btn btn-primary" disabled={busy || !allDecided || !commentsOk || a.status !== 'SUBMITTED'} onClick={() => void save(true)}>Complete verification</button>
              <button className="btn btn-outline" disabled={busy || !anyReturned || !commentsOk} onClick={() => setReturning(true)}>Return to employee</button>
            </div>
          )}
          <CalcPanel kpiWeight={a.kpiWeight} behaviourWeight={a.behaviourWeight} />
          <Note>Complete verification is enabled after every KPI has a decision. Adjusted and Returned decisions need a comment. Mark a KPI Returned and use Return to employee to send it back.</Note>
          {returning && (
            <Modal title="Return to the employee" onClose={() => setReturning(false)} footer={
              <><button className="btn btn-ghost" onClick={() => setReturning(false)}>Cancel</button>
                <button className="btn btn-navy" disabled={busy || !comment.trim()} onClick={() => void doReturn()}>Return</button></>
            }>
              <p className="muted">KPIs marked Returned go back to the employee with your comments. The employee must resubmit the self-assessment.</p>
              <TextArea label="Message to the employee" required value={comment} onChange={setComment} />
            </Modal>
          )}
        </>
      )}
    </AppraisalShell>
  );
}
