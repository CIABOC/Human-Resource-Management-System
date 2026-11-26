import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Loading, SelectField } from '@/components/ui';
import { fmtDate } from '@/components/DataTable';
import { AppraisalShell, Chip, Note, StatusTrail, useAprContext } from './shared';

interface Band { label: string; ratingValue: number; category: string; minScore: number; maxScore: number }
interface Policy { id: number; versionNo: number; status: string; kpiWeight: number; behaviourWeight: number; changedBy?: string; submittedAt?: string; approvedBy?: string; approvedAt?: string; note?: string; updatedAt?: string; bands: Band[] }
interface Screen { approved: Policy | null; working: Policy | null; history: Policy[]; canEdit: boolean; canApprove: boolean; isSystemAdmin: boolean }

const STEPS: [string, string][] = [['DRAFT', 'Draft'], ['PENDING_APPROVAL', 'Pending approval'], ['APPROVED', 'Approved']];

/** Screen 6: HR policy configuration (left) and technical administration (right), with version history. */
export default function ConfigPage() {
  return <AppraisalShell title="Appraisal configuration"><Body /></AppraisalShell>;
}

function Body() {
  const toast = useToast();
  const { ctx } = useAprContext();
  const { data, loading, error, reload } = useApi<Screen>('/appraisal/policy');
  const [kpi, setKpi] = useState('');
  const [beh, setBeh] = useState('');
  const [bands, setBands] = useState<Band[]>([]);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [periodId, setPeriodId] = useState('');

  const shown = data?.working ?? data?.approved ?? null;
  useEffect(() => {
    if (!shown) return;
    setKpi(String(shown.kpiWeight));
    setBeh(String(shown.behaviourWeight));
    setBands(shown.bands);
  }, [shown]);

  const editable = !!data?.canEdit && shown?.status !== 'PENDING_APPROVAL';
  const total = (Number(kpi) || 0) + (Number(beh) || 0);
  const run = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    try {
      await fn();
      setMsg(ok);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const saveDraft = () => run(() => api.put('/appraisal/policy/draft', { kpiWeight: Number(kpi), behaviourWeight: Number(beh), bands }),
    'Draft saved. Changes take effect only after HR approval and apply to the next appraisal period.');

  return (
    <>
      {msg && <Note kind="success">{msg}</Note>}
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !data ? <Loading /> : data && shown && (
        <>
          <div className="apr-split">
            <Card title="HR policy configuration" accent>
              <div className="stack" style={{ gap: 12 }}>
                <div className="apr-chips"><Chip>Version {shown.versionNo}</Chip><StatusTrail label="" steps={STEPS} current={shown.status} /></div>
                <div className="row" style={{ alignItems: 'flex-end' }}>
                  <div className="field"><label htmlFor="kw">KPI achievement weight (%)</label>
                    <input id="kw" className="input" type="number" min={0} max={100} style={{ width: 120 }} disabled={!editable} value={kpi} onChange={(e) => setKpi(e.target.value)} /></div>
                  <div className="field"><label htmlFor="bw">Behavioural weight (%)</label>
                    <input id="bw" className="input" type="number" min={0} max={100} style={{ width: 120 }} disabled={!editable} value={beh} onChange={(e) => setBeh(e.target.value)} /></div>
                  <Chip tone={total === 100 ? 'success' : 'danger'}>Total {total}%: {total === 100 ? 'valid' : 'must be 100%'}</Chip>
                </div>
                <div className="apr-table-wrap">
                  <table className="apr-table">
                    <thead><tr><th>Rating</th><th className="num">Value</th><th>Category</th><th>Score range</th>{editable && <th aria-label="Remove" />}</tr></thead>
                    <tbody>{bands.map((b, i) => {
                      const set = (patch: Partial<Band>) => setBands(bands.map((x, j) => (j === i ? { ...x, ...patch } : x)));
                      return editable ? (
                        <tr key={i}>
                          <td><input className="input" aria-label="Rating" value={b.label} onChange={(e) => set({ label: e.target.value })} /></td>
                          <td className="num"><input className="input" style={{ width: 64 }} type="number" aria-label="Value" value={b.ratingValue} onChange={(e) => set({ ratingValue: Number(e.target.value) })} /></td>
                          <td><input className="input" aria-label="Category" value={b.category} onChange={(e) => set({ category: e.target.value })} /></td>
                          <td><div className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
                            <input className="input" style={{ width: 76 }} type="number" aria-label="Minimum score" value={b.minScore} onChange={(e) => set({ minScore: Number(e.target.value) })} /> to
                            <input className="input" style={{ width: 76 }} type="number" aria-label="Maximum score" value={b.maxScore} onChange={(e) => set({ maxScore: Number(e.target.value) })} /></div></td>
                          <td><button className="btn btn-ghost btn-sm" aria-label="Remove band" onClick={() => setBands(bands.filter((_, j) => j !== i))}><Trash2 /></button></td>
                        </tr>
                      ) : (
                        <tr key={i}><td>{b.label}</td><td className="num">{b.ratingValue}</td><td>{b.category}</td><td>{b.minScore} to {b.maxScore}</td></tr>
                      );
                    })}</tbody>
                  </table>
                </div>
                <div className="row">
                  {editable && <>
                    <button className="btn btn-ghost btn-sm" onClick={() => setBands([...bands, { label: '', ratingValue: 0, category: '', minScore: 0, maxScore: 0 }])}><Plus /> Band</button>
                    <button className="btn btn-outline" disabled={busy || total !== 100} onClick={() => void saveDraft()}>Save draft</button>
                    {data.working?.status === 'DRAFT' && <button className="btn btn-navy" disabled={busy}
                      onClick={() => void run(() => api.post(`/appraisal/policy/${data.working!.id}/submit`), 'Submitted for HR approval.')}>Submit for HR approval</button>}
                  </>}
                  {data.canApprove && data.working?.status === 'PENDING_APPROVAL' && <>
                    <button className="btn btn-navy" disabled={busy} onClick={() => void run(() => api.post(`/appraisal/policy/${data.working!.id}/approve`), `Version ${data.working!.versionNo} approved. It applies to appraisals started from now on.`)}>Approve policy</button>
                    <button className="btn btn-outline" disabled={busy} onClick={() => void run(() => api.post(`/appraisal/policy/${data.working!.id}/reject`, {}), 'Returned to draft.')}>Return</button>
                  </>}
                </div>
                {shown.status === 'PENDING_APPROVAL' && !data.canApprove && <span className="muted" style={{ fontSize: 12.5 }}>Waiting for the HR policy approver.</span>}
                {data.approved && data.working && <span className="muted" style={{ fontSize: 12 }}>In force: version {data.approved.versionNo} (KPI {data.approved.kpiWeight}%, behaviour {data.approved.behaviourWeight}%).</span>}
              </div>
            </Card>
            <Card title="Technical administration" accent>
              <table className="apr-table">
                <thead><tr><th>Setting</th><th>Action</th></tr></thead>
                <tbody>
                  <tr><td>User roles and permissions</td><td><Link className="btn btn-outline btn-sm" to="/admin/roles">Manage</Link></td></tr>
                  <tr><td>Appraisal periods</td><td><Link className="btn btn-outline btn-sm" to="/performance/config">Manage</Link></td></tr>
                  <tr><td>Notification service</td><td><Link className="btn btn-outline btn-sm" to="/admin/notifications">Manage</Link></td></tr>
                  <tr><td>Audit log</td><td><Link className="btn btn-outline btn-sm" to="/audit">View</Link></td></tr>
                </tbody>
              </table>
              <p className="muted" style={{ fontSize: 12, marginBottom: 0 }}>System administrators cannot approve policy changes.</p>
              {ctx?.manage && (
                <div className="stack" style={{ gap: 8, marginTop: 16 }}>
                  <SelectField label="Start appraisals for a period" value={periodId} onChange={setPeriodId}
                    options={(ctx.periods ?? []).filter((p) => p.status === 'OPEN').map((p) => ({ value: String(p.id), label: p.name }))} />
                  <div><button className="btn btn-outline btn-sm" disabled={busy || !periodId} onClick={() => void run(async () => {
                    const { data: r } = await api.post<{ created: number; skipped: number; withoutSupervisor: number }>(`/appraisal/periods/${periodId}/start`);
                    toast(`${r.created} appraisal(s) created, ${r.skipped} already existed, ${r.withoutSupervisor} without a supervisor`, 'success');
                  }, 'Appraisals started for the period.')}>Start appraisals</button></div>
                </div>
              )}
            </Card>
          </div>
          <Card title="Version history" accent>
            <table className="apr-table">
              <thead><tr><th>Version</th><th>Weighting</th><th>Changed by</th><th>Approved by</th><th>Status</th><th>Date</th></tr></thead>
              <tbody>{data.history.map((p) => (
                <tr key={p.id}><td>v{p.versionNo}</td><td>KPI {p.kpiWeight}% · behaviour {p.behaviourWeight}%</td><td>{p.changedBy ?? '—'}</td>
                  <td>{p.approvedBy ?? (p.status === 'PENDING_APPROVAL' ? 'Pending' : '—')}</td>
                  <td>{p.status.replace(/_/g, ' ').toLowerCase()}</td><td>{fmtDate(p.approvedAt ?? p.updatedAt)}</td></tr>
              ))}</tbody>
            </table>
          </Card>
        </>
      )}
    </>
  );
}
