import { ReactNode, useEffect, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useToast } from '@/components/Toast';
import { Alert, Card, Empty, Loading, Modal, TextArea } from '@/components/ui';
import { fmtDateTime } from '@/components/DataTable';
import type { Appraisal, Milestone } from './types';
import { METHODS, methodLabel } from './types';
import { AGREEMENT_STEPS, AppraisalShell, Chip, fmtDayMonth, fmtPct, NoAppraisal, Note, StatusTrail, useTargetAppraisal } from './shared';

interface Row { id?: number; objective: string; title: string; method: string; target: string; weight: string; reviewPeriod: string; milestones: Milestone[] }

const toRows = (a: Appraisal): Row[] => a.kpis.map((k) => ({
  id: k.id, objective: k.objective, title: k.title, method: k.method, target: k.target ?? '', weight: String(k.weight ?? ''),
  reviewPeriod: k.reviewPeriod ?? '', milestones: k.milestones ?? [],
}));

/** Screen 9: objectives linked to KPIs, then sign-off by employee, supervisor and reviewing officer in turn. */
export default function AgreementPage() {
  const toast = useToast();
  const { appraisal: a, loading, error, setAppraisal } = useTargetAppraisal();
  const [rows, setRows] = useState<Row[]>([]);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [returning, setReturning] = useState(false);
  const [comment, setComment] = useState('');

  useEffect(() => {
    if (a) setRows(toRows(a));
  }, [a]);

  const total = rows.reduce((s, r) => s + (Number(r.weight) || 0), 0);
  const call = async (fn: () => Promise<{ data: Appraisal }>, ok: string) => {
    setBusy(true);
    try {
      const { data } = await fn();
      setAppraisal(data);
      toast(ok, 'success');
      return true;
    } catch (e) {
      toast(errorMessage(e), 'error');
      return false;
    } finally {
      setBusy(false);
    }
  };
  const save = async () => {
    const body = rows.map((r) => ({ ...r, weight: Number(r.weight) || 0, milestones: r.method === 'MILESTONE' ? r.milestones.map((m) => ({ ...m, weight: Number(m.weight) || 0 })) : null }));
    if (await call(() => api.put(`/appraisal/appraisals/${a!.id}/agreement`, body), 'Objectives and KPIs saved')) setEditing(false);
  };
  const set = (i: number, patch: Partial<Row>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  return (
    <AppraisalShell title="Performance agreement and sign-off">
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !a ? <Loading /> : !a ? <NoAppraisal /> : (
        <>
          <div className="stack" style={{ gap: 10 }}>
            <StatusTrail steps={AGREEMENT_STEPS} current={a.agreementStatus === 'SUPERVISOR_SIGNED' ? 'SUPERVISOR_SIGNED' : a.agreementStatus} />
            <div className="apr-chips">
              <Chip>Version {a.agreementVersion}</Chip>
              <Chip>{a.agreementStatus === 'FINAL' ? 'Signed off' : a.agreementStatus === 'DRAFT' ? 'Draft' : 'Awaiting sign-off'}</Chip>
              <Chip tone={Number(a.totalKpiWeight) === 100 ? undefined : 'danger'}>Weightage total {fmtPct(a.totalKpiWeight, 0)}</Chip>
              {!a.flags.employee && <Chip>{a.employee.name}</Chip>}
              <Chip>{a.period.name}</Chip>
            </div>
          </div>
          {a.flags.actingFor && <Note>You are acting on behalf of {a.flags.actingFor} under a delegation.</Note>}

          <Card title="Objectives and KPIs" accent actions={a.flags.canEditAgreement && !editing
            ? <button className="btn btn-outline btn-sm" onClick={() => setEditing(true)}>Edit objectives and KPIs</button> : undefined}>
            {editing ? (
              <>
                <div className="apr-table-wrap">
                  <table className="apr-table">
                    <thead><tr><th>Objective</th><th>Linked KPI</th><th>Method</th><th>Target</th><th className="num">Weight %</th><th>Review period</th><th aria-label="Remove" /></tr></thead>
                    <tbody>
                      {rows.map((r, i) => (
                        <tr key={i}>
                          <td><input className="input" aria-label="Objective" value={r.objective} onChange={(e) => set(i, { objective: e.target.value })} /></td>
                          <td>
                            <input className="input" aria-label="KPI" value={r.title} onChange={(e) => set(i, { title: e.target.value })} />
                            {r.method === 'MILESTONE' && <MilestoneEditor value={r.milestones} onChange={(m) => set(i, { milestones: m })} />}
                          </td>
                          <td>
                            <select className="select" aria-label="Measurement method" value={r.method} onChange={(e) => set(i, { method: e.target.value })}>
                              {METHODS.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
                            </select>
                          </td>
                          <td><input className="input" aria-label="Target" value={r.target} onChange={(e) => set(i, { target: e.target.value })} /></td>
                          <td className="num"><input className="input" style={{ width: 80 }} type="number" min={0} max={100} aria-label="Weight" value={r.weight} onChange={(e) => set(i, { weight: e.target.value })} /></td>
                          <td><input className="input" aria-label="Review period" value={r.reviewPeriod} onChange={(e) => set(i, { reviewPeriod: e.target.value })} /></td>
                          <td><button className="btn btn-ghost btn-sm" aria-label="Remove KPI" onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))}><Trash2 /></button></td>
                        </tr>
                      ))}
                    </tbody>
                    <tfoot><tr><td colSpan={4}>Total</td><td className="num" style={{ color: total === 100 ? undefined : 'var(--danger)' }}>{total}%</td><td colSpan={2} /></tr></tfoot>
                  </table>
                </div>
                <div className="row between" style={{ marginTop: 12 }}>
                  <button className="btn btn-outline btn-sm" onClick={() => setRows((rs) => [...rs, { objective: '', title: '', method: 'QUALITATIVE', target: '', weight: '', reviewPeriod: 'Annual', milestones: [] }])}>
                    <Plus /> Add objective / KPI
                  </button>
                  <div className="row">
                    <button className="btn btn-ghost" onClick={() => { setRows(toRows(a)); setEditing(false); }}>Cancel</button>
                    <button className="btn btn-navy" disabled={busy} onClick={() => void save()}>Save</button>
                  </div>
                </div>
              </>
            ) : a.kpis.length === 0 ? <Empty text="No objectives yet. The employee or the supervisor adds them while the agreement is a draft." /> : (
              <div className="apr-table-wrap">
                <table className="apr-table">
                  <thead><tr><th>Objective</th><th>Linked KPI</th><th>Method</th><th>Target</th><th className="num">Weight</th><th>Review period</th></tr></thead>
                  <tbody>{a.kpis.map((k) => (
                    <tr key={k.id}>
                      <td>{k.objective}</td>
                      <td>{k.title}{k.excepted && <> <Chip tone="muted">Exception</Chip></>}
                        {k.milestones.length > 0 && <div className="muted" style={{ fontSize: 11.5 }}>{k.milestones.map((m) => `${m.name} (${m.weight}%)`).join(' · ')}</div>}</td>
                      <td>{methodLabel(k.method)}</td>
                      <td>{k.target ?? '—'}</td>
                      <td className="num">{fmtPct(k.weight, 0)}</td>
                      <td>{k.reviewPeriod ?? '—'}</td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            )}
          </Card>

          <div className="grid grid-2">
            <Card title="Sign-off" accent>
              <table className="apr-table">
                <thead><tr><th>Role</th><th>Status</th><th aria-label="Action" /></tr></thead>
                <tbody>
                  <SignRow role="Employee" who={a.employee.name} at={a.employeeSignedAt}
                    action={a.flags.canSignEmployee ? <button className="btn btn-navy btn-sm" disabled={busy || editing} onClick={() => void call(() => api.post(`/appraisal/appraisals/${a.id}/agreement/sign`), 'Agreement signed off')}>Sign off</button> : null} />
                  <SignRow role="Supervisor" who={a.supervisorSignedBy ?? a.supervisorName} at={a.supervisorSignedAt}
                    action={a.flags.canSignSupervisor ? <button className="btn btn-outline btn-sm" disabled={busy} onClick={() => void call(() => api.post(`/appraisal/appraisals/${a.id}/agreement/sign`), 'Agreement signed off')}>Sign off</button> : null} />
                  <SignRow role="Reviewing officer" who={a.reviewerSignedBy ?? a.reviewerName ?? 'Appraisal administrator'} at={a.agreementStatus === 'FINAL' ? a.reviewerSignedAt : null}
                    action={a.flags.canSignReviewer ? <button className="btn btn-outline btn-sm" disabled={busy} onClick={() => void call(() => api.post(`/appraisal/appraisals/${a.id}/agreement/sign`), 'Agreement approved')}>Approve</button> : null} />
                </tbody>
              </table>
              {a.flags.canReturnAgreement && <button className="btn btn-ghost btn-sm" style={{ marginTop: 10 }} onClick={() => setReturning(true)}>Return for revision</button>}
            </Card>
            <Card title="Version history" accent>
              {a.agreementHistory.length === 0 ? <Empty /> : (
                <table className="apr-table">
                  <thead><tr><th>Version</th><th>Date</th><th>Changed by</th><th>Note</th></tr></thead>
                  <tbody>{a.agreementHistory.map((h, i) => (
                    <tr key={i}><td>v{h.versionNo}</td><td>{fmtDayMonth(h.changedAt)}</td><td>{h.changedBy}</td><td>{h.note}</td></tr>
                  ))}</tbody>
                </table>
              )}
            </Card>
          </div>
          <Note>The agreement is final only after the employee, supervisor and reviewing officer have signed. Weights must total 100% before it can be submitted.</Note>
          {returning && (
            <Modal title="Return the agreement for revision" onClose={() => setReturning(false)} footer={
              <><button className="btn btn-ghost" onClick={() => setReturning(false)}>Cancel</button>
                <button className="btn btn-navy" disabled={busy || !comment.trim()} onClick={async () => {
                  if (await call(() => api.post(`/appraisal/appraisals/${a.id}/agreement/return`, { comment }), 'Agreement returned')) setReturning(false);
                }}>Return</button></>
            }>
              <TextArea label="Reason" required value={comment} onChange={setComment} />
            </Modal>
          )}
        </>
      )}
    </AppraisalShell>
  );
}

function SignRow({ role, who, at, action }: { role: string; who?: string | null; at?: string | null; action: ReactNode }) {
  return (
    <tr>
      <td><b>{role}</b>{who && <div className="muted" style={{ fontSize: 11.5 }}>{who}</div>}</td>
      <td>{at ? <><Chip tone="success">Signed</Chip> <span className="muted" style={{ fontSize: 11.5 }}>{fmtDateTime(at)}</span></> : <Chip>Pending</Chip>}</td>
      <td>{action ?? (!at && <span className="muted" style={{ fontSize: 12 }}>Not yet</span>)}</td>
    </tr>
  );
}

function MilestoneEditor({ value, onChange }: { value: Milestone[]; onChange: (m: Milestone[]) => void }) {
  const sum = value.reduce((s, m) => s + (Number(m.weight) || 0), 0);
  return (
    <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 4 }}>
      {value.map((m, i) => (
        <div key={i} className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
          <input className="input" style={{ height: 30 }} aria-label="Milestone" placeholder="Milestone" value={m.name}
            onChange={(e) => onChange(value.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
          <input className="input" style={{ height: 30, width: 70 }} type="number" aria-label="Milestone weight" placeholder="%" value={m.weight ?? ''}
            onChange={(e) => onChange(value.map((x, j) => (j === i ? { ...x, weight: Number(e.target.value) } : x)))} />
          <button className="btn btn-ghost btn-sm" aria-label="Remove milestone" onClick={() => onChange(value.filter((_, j) => j !== i))}><Trash2 /></button>
        </div>
      ))}
      <div className="row" style={{ gap: 8 }}>
        <button className="btn btn-ghost btn-sm" onClick={() => onChange([...value, { name: '', weight: 0 }])}><Plus /> Milestone</button>
        <span className="muted" style={{ fontSize: 11.5, color: sum === 100 ? undefined : 'var(--danger)' }}>Milestones total {sum}%</span>
      </div>
    </div>
  );
}
