import { useState } from 'react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Empty, SelectField, TextArea, TextField } from '@/components/ui';
import { AppraisalShell, Chip, Confidential, fmtDayMonth, Note } from './shared';

interface Delegation { id: number; delegatorName: string; delegateName: string; dateFrom: string; dateTo: string; scope: string; status: string; mine: boolean }
interface Reopen { id: number; reference: string; employeeName: string; periodName: string; reason: string; justification: string; status: string; requestedBy: string; approvedBy?: string; canDecide: boolean }

const SCOPES = [{ value: 'VERIFICATION_AND_REVIEW', label: 'Verification and review' }, { value: 'APPROVAL', label: 'Reviewing officer approval' }, { value: 'ALL', label: 'All appraisal responsibilities' }];
const REASONS = ['Correction of data', 'Appeal decision', 'Late evidence', 'Administrative error'].map((r) => ({ value: r, label: r }));
const RO_STATUS: Record<string, string> = { PENDING: 'Pending approval', REOPENED: 'Reopened', REJECTED: 'Rejected', FINALIZED_AGAIN: 'Finalized again' };

/** Screen 15: delegate responsibilities for a period and scope; request controlled reopening of a finalized appraisal. */
export default function DelegationPage() {
  const toast = useToast();
  const delegations = useApi<Delegation[]>('/appraisal/delegations');
  const officers = useApi<{ userId: number; name: string }[]>('/appraisal/officers');
  const reopens = useApi<Reopen[]>('/appraisal/reopens');
  const candidates = useApi<{ appraisalId: number; label: string }[]>('/appraisal/reopens/candidates');
  const [d, setD] = useState({ to: '', from: '', until: '', scope: 'VERIFICATION_AND_REVIEW' });
  const [r, setR] = useState({ appraisalId: '', reason: 'Correction of data', justification: '' });
  const [busy, setBusy] = useState(false);

  const run = async (fn: () => Promise<unknown>, ok: string, after?: () => void) => {
    setBusy(true);
    try {
      await fn();
      toast(ok, 'success');
      after?.();
      void delegations.reload();
      void reopens.reload();
      void candidates.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AppraisalShell title="Delegation and controlled reopening">
      <div className="apr-chips"><Confidential /></div>
      {(delegations.error || reopens.error) && <Alert kind="danger">{delegations.error ?? reopens.error}</Alert>}
      <div className="grid grid-2">
        <Card title="Delegate review responsibilities" accent>
          <div className="form-grid">
            <SelectField className="span-all" label="Delegate to" value={d.to} onChange={(v) => setD({ ...d, to: v })}
              options={(officers.data ?? []).map((o) => ({ value: String(o.userId), label: o.name }))} placeholder="Authorized officer" />
            <TextField label="From" type="date" value={d.from} onChange={(v) => setD({ ...d, from: v })} />
            <TextField label="To" type="date" value={d.until} onChange={(v) => setD({ ...d, until: v })} />
            <SelectField className="span-all" label="Scope" value={d.scope} onChange={(v) => setD({ ...d, scope: v })} placeholder="" options={SCOPES} />
            <div className="span-all"><button className="btn btn-navy" disabled={busy || !d.to || !d.from || !d.until} onClick={() => void run(
              () => api.post('/appraisal/delegations', { delegateUserId: Number(d.to), dateFrom: d.from, dateTo: d.until, scope: d.scope }),
              'Delegation saved', () => setD({ to: '', from: '', until: '', scope: 'VERIFICATION_AND_REVIEW' }))}>Save delegation</button></div>
          </div>
        </Card>
        <Card title="Request reopening of a finalized appraisal" accent>
          {!(candidates.data ?? []).length ? <Empty text="No finalized appraisals you can ask to reopen." /> : (
            <div className="stack" style={{ gap: 10 }}>
              <SelectField label="Appraisal" value={r.appraisalId} onChange={(v) => setR({ ...r, appraisalId: v })}
                options={(candidates.data ?? []).map((c) => ({ value: String(c.appraisalId), label: c.label }))} />
              <SelectField label="Reason" value={r.reason} onChange={(v) => setR({ ...r, reason: v })} placeholder="" options={REASONS} />
              <TextArea label="Justification (required)" required value={r.justification} onChange={(v) => setR({ ...r, justification: v })} />
              <div><button className="btn btn-navy" disabled={busy || !r.appraisalId || !r.justification.trim()} onClick={() => void run(
                () => api.post('/appraisal/reopens', { appraisalId: Number(r.appraisalId), reason: r.reason, justification: r.justification }),
                'Reopening requested', () => setR({ appraisalId: '', reason: 'Correction of data', justification: '' }))}>Request reopening</button></div>
            </div>
          )}
        </Card>
      </div>
      <div className="grid grid-2">
        <Card title="Active delegations" accent>
          {!delegations.data?.length ? <Empty text="No delegations." /> : (
            <table className="apr-table">
              <thead><tr><th>Delegate</th><th>Period</th><th>Scope</th><th>Status</th><th aria-label="Action" /></tr></thead>
              <tbody>{delegations.data.map((x) => (
                <tr key={x.id}>
                  <td>{x.mine ? x.delegateName : <>{x.delegatorName} <span className="muted" style={{ fontSize: 11 }}>(to you)</span></>}</td>
                  <td>{fmtDayMonth(x.dateFrom)} to {fmtDayMonth(x.dateTo)}</td>
                  <td style={{ fontSize: 12 }}>{SCOPES.find((s) => s.value === x.scope)?.label}</td>
                  <td><Chip tone={x.status === 'ACTIVE' ? 'success' : 'muted'}>{x.status.charAt(0) + x.status.slice(1).toLowerCase()}</Chip></td>
                  <td>{x.mine && x.status === 'ACTIVE' && <button className="btn btn-outline btn-sm" disabled={busy}
                    onClick={() => void run(() => api.post(`/appraisal/delegations/${x.id}/revoke`), 'Delegation revoked')}>Revoke</button>}</td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </Card>
        <Card title="Reopened appraisals" accent>
          {!reopens.data?.length ? <Empty text="No reopening requests." /> : (
            <table className="apr-table">
              <thead><tr><th>Reference</th><th>Appraisal</th><th>Reason</th><th>Approved by</th><th>Status</th></tr></thead>
              <tbody>{reopens.data.map((x) => (
                <tr key={x.id}>
                  <td>{x.reference}</td><td style={{ fontSize: 12 }}>{x.employeeName}<div className="muted">{x.periodName}</div></td>
                  <td style={{ fontSize: 12 }}>{x.reason}</td><td>{x.approvedBy ?? '—'}</td>
                  <td>{x.canDecide ? (
                    <div className="row" style={{ gap: 4 }}>
                      <button className="btn btn-navy btn-sm" disabled={busy} onClick={() => void run(() => api.post(`/appraisal/reopens/${x.id}/decision`, { approve: true }), 'Appraisal reopened')}>Approve</button>
                      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => void run(() => api.post(`/appraisal/reopens/${x.id}/decision`, { approve: false }), 'Request rejected')}>Reject</button>
                    </div>
                  ) : <Chip tone={x.status === 'FINALIZED_AGAIN' ? 'success' : x.status === 'REJECTED' ? 'danger' : undefined}>{RO_STATUS[x.status]}</Chip>}</td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </Card>
      </div>
      <Note>Every action taken under a delegation is recorded against the delegate and the delegator. A reopened appraisal must be finalized again, and the earlier version is kept.</Note>
    </AppraisalShell>
  );
}
