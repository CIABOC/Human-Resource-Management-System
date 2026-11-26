import { useRef, useState } from 'react';
import { Paperclip } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Empty, Loading, Modal, SelectField, TextArea, TextField } from '@/components/ui';
import { fmtDate } from '@/components/DataTable';
import type { Appraisal, Member } from './types';
import { AppraisalShell, Chip, Confidential, fmtPct, Note, useAprContext } from './shared';

interface Case {
  id: number; caseType: string; employeeName?: string; kpiTitle?: string; effectiveFrom?: string; effectiveTo?: string; newPosition?: string;
  previousSupervisor?: string; targetAdjustment?: string; reason?: string; justification: string; originalValue?: number; adjustedValue?: number;
  fileId?: number; fileName?: string; status: string; requestedBy?: string; requestedAt: string; decidedBy?: string; decidedAt?: string;
  decisionComment?: string; canDecide: boolean;
}

const CASES = [
  { value: 'PARTIAL_PERIOD', label: 'Partial period' }, { value: 'TRANSFER', label: 'Transfer' }, { value: 'PROBATION', label: 'Probation' },
  { value: 'EXTENDED_LEAVE', label: 'Extended leave' }, { value: 'ACTING_APPOINTMENT', label: 'Acting appointment' }, { value: 'REPORTING_LINE_CHANGE', label: 'Change of reporting line' },
];
const ADJUST = [{ value: 'PRO_RATA', label: 'Pro-rata for period served' }, { value: 'REVISED_TARGETS', label: 'Revised targets agreed' }, { value: 'NO_CHANGE', label: 'No change to targets' }];
const REASONS = ['Change in priorities', 'Resources not provided', 'External dependency', 'Policy or legal change', 'Force majeure'].map((r) => ({ value: r, label: r }));
const typeLabel = (t: string) => (t === 'KPI_EXCEPTION' ? 'KPI exception' : CASES.find((c) => c.value === t)?.label ?? t);

/** Screen 14: period adjustments and KPI exceptions; adjustments take effect only after approval. */
export default function SpecialCasesPage() {
  return <AppraisalShell title="Special appraisal cases and KPI exceptions"><Body /></AppraisalShell>;
}

function Body() {
  const toast = useToast();
  const { ctx } = useAprContext();
  const cases = useApi<Case[]>('/appraisal/special-cases');
  const members = useApi<Member[]>('/appraisal/team/members');
  const [p, setP] = useState({ employeeId: '', caseType: 'PARTIAL_PERIOD', from: '', to: '', position: '', previous: '', adjustment: 'PRO_RATA', justification: '' });
  const [k, setK] = useState({ appraisalId: '', kpiId: '', reason: '', justification: '' });
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [decide, setDecide] = useState<Case | null>(null);
  const [comment, setComment] = useState('');
  const fileRef = useRef<HTMLInputElement>(null);
  const target = useApi<Appraisal>(k.appraisalId ? `/appraisal/appraisals/${k.appraisalId}` : null);
  const mine = useApi<{ appraisal: Appraisal | null }>(ctx?.employeeId ? '/appraisal/my' : null);

  const kpiSources = [
    ...(mine.data?.appraisal ? [{ value: String(mine.data.appraisal.id), label: 'My appraisal' }] : []),
    ...(members.data ?? []).map((m) => ({ value: String(m.appraisalId), label: `${m.name} (${m.employeeNo})` })),
  ];
  const send = async (request: object, f: File | null, reset: () => void) => {
    const fd = new FormData();
    fd.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    if (f) fd.append('file', f);
    setBusy(true);
    try {
      await api.post('/appraisal/special-cases', fd, { headers: { 'Content-Type': 'multipart/form-data' } });
      toast('Submitted for approval', 'success');
      reset();
      void cases.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const decideCase = async (approve: boolean) => {
    setBusy(true);
    try {
      await api.post(`/appraisal/special-cases/${decide!.id}/decision`, { approve, comment });
      toast(approve ? 'Approved' : 'Rejected', 'success');
      setDecide(null);
      void cases.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="apr-chips"><Confidential /><Chip>Needs HR approval</Chip></div>
      {cases.error && <Alert kind="danger">{cases.error}</Alert>}
      <div className="grid grid-2">
        <Card title="Period adjustment" accent>
          {!(members.data ?? []).length ? <Empty text="Period adjustments are submitted by supervisors and HR for their team members." /> : (
            <div className="form-grid">
              <SelectField label="Employee" value={p.employeeId} onChange={(v) => setP({ ...p, employeeId: v })} options={(members.data ?? []).map((m) => ({ value: String(m.employeeId), label: m.name }))} />
              <SelectField label="Case" value={p.caseType} onChange={(v) => setP({ ...p, caseType: v })} placeholder="" options={CASES} />
              <TextField label="Effective from" type="date" value={p.from} onChange={(v) => setP({ ...p, from: v })} />
              <TextField label="Effective to" type="date" value={p.to} onChange={(v) => setP({ ...p, to: v })} />
              <TextField label="New or acting position" value={p.position} onChange={(v) => setP({ ...p, position: v })} />
              <TextField label="Previous supervisor" value={p.previous} onChange={(v) => setP({ ...p, previous: v })} />
              <SelectField className="span-all" label="Target adjustment" value={p.adjustment} onChange={(v) => setP({ ...p, adjustment: v })} placeholder="" options={ADJUST} />
              <TextArea className="span-all" label="Justification (required)" required value={p.justification} onChange={(v) => setP({ ...p, justification: v })} />
              <div className="row span-all">
                <button className="btn btn-navy" disabled={busy || !p.employeeId || !p.from || !p.justification.trim()} onClick={() => void send({
                  employeeId: Number(p.employeeId), caseType: p.caseType, effectiveFrom: p.from, effectiveTo: p.to || null, newPosition: p.position,
                  previousSupervisor: p.previous, targetAdjustment: p.adjustment, justification: p.justification,
                }, null, () => setP({ ...p, from: '', to: '', position: '', previous: '', justification: '' }))}>Submit for approval</button>
                <button className="btn btn-outline" onClick={() => setP({ employeeId: '', caseType: 'PARTIAL_PERIOD', from: '', to: '', position: '', previous: '', adjustment: 'PRO_RATA', justification: '' })}>Cancel</button>
              </div>
            </div>
          )}
        </Card>
        <Card title="KPI exception request" accent>
          <div className="stack" style={{ gap: 10 }}>
            <SelectField label="Appraisal" value={k.appraisalId} onChange={(v) => setK({ ...k, appraisalId: v, kpiId: '' })} options={kpiSources} />
            <SelectField label="KPI" value={k.kpiId} onChange={(v) => setK({ ...k, kpiId: v })}
              options={(target.data?.kpis ?? []).filter((x) => !x.excepted).map((x) => ({ value: String(x.id), label: `${x.title} (${fmtPct(x.weight, 0)})` }))} />
            <SelectField label="Reason" value={k.reason} onChange={(v) => setK({ ...k, reason: v })} options={REASONS} />
            <TextArea label="Justification (required)" required value={k.justification} onChange={(v) => setK({ ...k, justification: v })} />
            <div className="row">
              <input ref={fileRef} type="file" hidden onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
              <button className="btn btn-outline" onClick={() => fileRef.current?.click()}><Paperclip /> {file ? file.name : 'Attach evidence'}</button>
              <button className="btn btn-navy" disabled={busy || !k.kpiId || !k.reason || !k.justification.trim() || !file}
                onClick={() => void send({ caseType: 'KPI_EXCEPTION', kpiId: Number(k.kpiId), reason: k.reason, justification: k.justification }, file,
                  () => { setK({ appraisalId: '', kpiId: '', reason: '', justification: '' }); setFile(null); })}>Submit request</button>
            </div>
            <span className="muted" style={{ fontSize: 12 }}>For a KPI that could not be achieved for reasons beyond the employee's control. Approved exceptions are left out of the KPI result.</span>
          </div>
        </Card>
      </div>
      <Card title="Requests and adjustments" accent>
        {cases.loading && !cases.data ? <Loading /> : !cases.data?.length ? <Empty text="No requests yet." /> : (
          <div className="apr-table-wrap">
            <table className="apr-table">
              <thead><tr><th>Type</th><th>Employee</th><th>Detail</th><th className="num">Original</th><th className="num">Adjusted</th><th>Status</th><th aria-label="Action" /></tr></thead>
              <tbody>{cases.data.map((c) => (
                <tr key={c.id}>
                  <td>{typeLabel(c.caseType)}</td><td>{c.employeeName}</td>
                  <td style={{ fontSize: 12 }}>{c.kpiTitle ?? `${fmtDate(c.effectiveFrom)}${c.effectiveTo ? ` – ${fmtDate(c.effectiveTo)}` : ''}`}<div className="muted">{c.reason ?? c.targetAdjustment?.replace(/_/g, ' ').toLowerCase()}</div></td>
                  <td className="num">{fmtPct(c.originalValue)}</td><td className="num">{fmtPct(c.adjustedValue)}</td>
                  <td><Chip tone={c.status === 'APPROVED' ? 'success' : c.status === 'REJECTED' ? 'danger' : undefined}>{c.status.charAt(0) + c.status.slice(1).toLowerCase()}</Chip></td>
                  <td><button className={`btn btn-sm ${c.canDecide ? 'btn-navy' : 'btn-ghost'}`} onClick={() => { setDecide(c); setComment(''); }}>{c.canDecide ? 'Decide' : 'View'}</button></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </Card>
      <Note>The original and the adjusted values are both kept for audit. Adjustments take effect only after approval by an HR officer other than the requester.</Note>
      {decide && (
        <Modal title={`${typeLabel(decide.caseType)}: ${decide.employeeName ?? ''}`} onClose={() => setDecide(null)} footer={decide.canDecide ? <>
          <button className="btn btn-ghost" onClick={() => setDecide(null)}>Cancel</button>
          <button className="btn btn-outline" disabled={busy || !comment.trim()} onClick={() => void decideCase(false)}>Reject</button>
          <button className="btn btn-navy" disabled={busy} onClick={() => void decideCase(true)}>Approve</button></> : undefined}>
          <div className="stack" style={{ gap: 10 }}>
            <dl className="apr-kv">
              {decide.kpiTitle && <><dt>KPI</dt><dd>{decide.kpiTitle}</dd></>}
              {decide.effectiveFrom && <><dt>Effective</dt><dd>{fmtDate(decide.effectiveFrom)}{decide.effectiveTo ? ` – ${fmtDate(decide.effectiveTo)}` : ''}</dd></>}
              {decide.newPosition && <><dt>New / acting position</dt><dd>{decide.newPosition}</dd></>}
              {decide.previousSupervisor && <><dt>Previous supervisor</dt><dd>{decide.previousSupervisor}</dd></>}
              {decide.reason && <><dt>Reason</dt><dd>{decide.reason}</dd></>}
              <dt>Justification</dt><dd style={{ whiteSpace: 'pre-wrap' }}>{decide.justification}</dd>
              <dt>Original → adjusted</dt><dd>{fmtPct(decide.originalValue)} → {fmtPct(decide.adjustedValue)}</dd>
              <dt>Requested by</dt><dd>{decide.requestedBy} on {fmtDate(decide.requestedAt)}</dd>
              {decide.decidedBy && <><dt>Decided by</dt><dd>{decide.decidedBy} on {fmtDate(decide.decidedAt)}{decide.decisionComment ? ` — ${decide.decisionComment}` : ''}</dd></>}
            </dl>
            {decide.fileId && <button className="btn btn-outline btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => void download(`/appraisal/special-cases/${decide.id}/file`, { filename: decide.fileName })}>Evidence: {decide.fileName}</button>}
            {decide.canDecide && <TextArea label="Comment (required to reject)" value={comment} onChange={setComment} />}
          </div>
        </Modal>
      )}
    </>
  );
}
