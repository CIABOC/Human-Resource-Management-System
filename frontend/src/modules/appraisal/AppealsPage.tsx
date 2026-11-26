import { useRef, useState } from 'react';
import { Paperclip, X } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Empty, Loading, Modal, SelectField, TextArea } from '@/components/ui';
import { fmtDate } from '@/components/DataTable';
import type { MyDashboard } from './types';
import { APPRAISAL_STEPS, AppraisalShell, Chip, fmtDayMonth, NoAppraisal, Note, StatusTrail } from './shared';

interface Appeal { id: number; appraisalId: number; reference: string; periodName: string; employeeName: string; grounds: string; fileIds: number[]; status: string; decision?: string; decidedBy?: string; decidedAt?: string; submittedAt: string }

const STATUS: Record<string, string> = { SUBMITTED: 'Submitted', UNDER_REVIEW: 'Under review', DECIDED: 'Decided' };
const APPEAL_STEPS: [string, string][] = [['SUBMITTED', 'Submitted'], ['UNDER_REVIEW', 'Under review'], ['DECIDED', 'Decision']];

/** Screen 7: appeals open only after the employee acknowledges the finalized appraisal, within the appeal window. */
export default function AppealsPage() {
  const toast = useToast();
  const my = useApi<MyDashboard>('/appraisal/my');
  const appeals = useApi<Appeal[]>('/appraisal/my/appeals');
  const [grounds, setGrounds] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const ref = useRef<HTMLInputElement>(null);
  const a = my.data?.appraisal ?? null;
  const open = (appeals.data ?? []).find((x) => x.status !== 'DECIDED' && x.appraisalId === a?.id);
  const canAppeal = !!a?.flags.canAppeal && !open;

  const submit = async () => {
    const fd = new FormData();
    fd.append('grounds', grounds);
    files.forEach((f) => fd.append('files', f));
    setBusy(true);
    try {
      await api.post(`/appraisal/appraisals/${a!.id}/appeals`, fd, { headers: { 'Content-Type': 'multipart/form-data' } });
      toast('Appeal submitted', 'success');
      setGrounds('');
      setFiles([]);
      void appeals.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AppraisalShell title="Appeals">
      {(my.error || appeals.error) && <Alert kind="danger">{my.error ?? appeals.error}</Alert>}
      <Note>Appeals can be submitted after you acknowledge the finalized appraisal, within the appeal window.</Note>
      {my.loading && !my.data ? <Loading /> : !a ? <NoAppraisal /> : (
        <>
          <StatusTrail steps={APPRAISAL_STEPS} current={a.status} />
          <div className="apr-split">
            <Card title="Submit an appeal" accent>
              <div className="stack" style={{ gap: 10 }}>
                <StatusTrail label="Appeal" steps={APPEAL_STEPS} current={open?.status ?? 'NONE'} />
                <TextArea label="Grounds for the appeal" rows={5} value={grounds} onChange={setGrounds} />
                {files.length > 0 && (
                  <div className="apr-chips">{files.map((f, i) => (
                    <Chip key={i}>{f.name} <button className="btn btn-ghost btn-sm" style={{ height: 18, padding: 0 }} aria-label={`Remove ${f.name}`}
                      onClick={() => setFiles(files.filter((_, j) => j !== i))}><X size={12} /></button></Chip>
                  ))}</div>
                )}
                <div className="row">
                  <input ref={ref} type="file" hidden multiple onChange={(e) => { setFiles([...files, ...Array.from(e.target.files ?? [])]); e.target.value = ''; }} />
                  <button className="btn btn-outline" disabled={!canAppeal} onClick={() => ref.current?.click()}><Paperclip /> Attach document</button>
                  <button className="btn btn-navy" disabled={!canAppeal || busy || !grounds.trim()} onClick={() => void submit()}>Submit appeal</button>
                </div>
                <span className="muted" style={{ fontSize: 12.5 }}>
                  {a.appealWindowCloses ? `Appeal window closes on ${fmtDate(a.appealWindowCloses)}.` : 'The appeal window opens when you acknowledge the finalized appraisal.'}
                  {open && ` Your appeal ${open.reference} is ${STATUS[open.status].toLowerCase()}.`}
                </span>
              </div>
            </Card>
            <Card title="My appeals" accent>
              {!appeals.data?.length ? <Empty text="You have not submitted any appeals." /> : (
                <table className="apr-table">
                  <thead><tr><th>Reference</th><th>Submitted</th><th>Status</th></tr></thead>
                  <tbody>{appeals.data.map((x) => (
                    <tr key={x.id}><td>{x.reference}<div className="muted" style={{ fontSize: 11 }}>{x.periodName}</div></td><td>{fmtDayMonth(x.submittedAt)}</td>
                      <td><Chip tone={x.status === 'DECIDED' ? 'success' : undefined}>{STATUS[x.status]}</Chip>
                        {x.decision && <div className="muted" style={{ fontSize: 11.5, marginTop: 3 }}>{x.decision}</div>}</td></tr>
                  ))}</tbody>
                </table>
              )}
            </Card>
          </div>
        </>
      )}
    </AppraisalShell>
  );
}

/** HR: review appeals and record decisions. */
export function AppealsReviewPage() {
  const toast = useToast();
  const { data, loading, error, reload } = useApi<Appeal[]>('/appraisal/appeals');
  const [sel, setSel] = useState<Appeal | null>(null);
  const [status, setStatus] = useState('UNDER_REVIEW');
  const [decision, setDecision] = useState('');
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      await api.post(`/appraisal/appeals/${sel!.id}/decision`, { status, decision });
      toast('Appeal updated', 'success');
      setSel(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <AppraisalShell title="Appeals review">
      {error && <Alert kind="danger">{error}</Alert>}
      <Card title="Appeals" accent>
        {loading && !data ? <Loading /> : !data?.length ? <Empty text="No appeals." /> : (
          <div className="apr-table-wrap">
            <table className="apr-table">
              <thead><tr><th>Reference</th><th>Employee</th><th>Period</th><th>Submitted</th><th>Status</th><th aria-label="Action" /></tr></thead>
              <tbody>{data.map((x) => (
                <tr key={x.id}><td>{x.reference}</td><td>{x.employeeName}</td><td>{x.periodName}</td><td>{fmtDayMonth(x.submittedAt)}</td>
                  <td><Chip tone={x.status === 'DECIDED' ? 'success' : undefined}>{STATUS[x.status]}</Chip></td>
                  <td><button className="btn btn-outline btn-sm" onClick={() => { setSel(x); setStatus(x.status === 'SUBMITTED' ? 'UNDER_REVIEW' : 'DECIDED'); setDecision(x.decision ?? ''); }}>Open</button></td></tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </Card>
      {sel && (
        <Modal title={`Appeal ${sel.reference}`} onClose={() => setSel(null)} footer={sel.status === 'DECIDED' ? undefined : <>
          <button className="btn btn-ghost" onClick={() => setSel(null)}>Cancel</button>
          <button className="btn btn-navy" disabled={busy || (status === 'DECIDED' && !decision.trim())} onClick={() => void save()}>Save</button></>}>
          <div className="stack" style={{ gap: 10 }}>
            <dl className="apr-kv"><dt>Employee</dt><dd>{sel.employeeName}</dd><dt>Period</dt><dd>{sel.periodName}</dd><dt>Grounds</dt><dd style={{ whiteSpace: 'pre-wrap' }}>{sel.grounds}</dd></dl>
            {sel.fileIds.length > 0 && <div className="row">{sel.fileIds.map((f, i) => (
              <button key={f} className="btn btn-outline btn-sm" onClick={() => void download(`/appraisal/appeals/${sel.id}/files/${f}`)}>Document {i + 1}</button>
            ))}</div>}
            {sel.status === 'DECIDED' ? <Alert kind="success">Decided by {sel.decidedBy} on {fmtDate(sel.decidedAt)}: {sel.decision}</Alert> : <>
              <SelectField label="Status" value={status} onChange={setStatus} placeholder="" options={[{ value: 'UNDER_REVIEW', label: 'Under review' }, { value: 'DECIDED', label: 'Decision' }]} />
              <TextArea label="Decision" required={status === 'DECIDED'} value={decision} onChange={setDecision} />
            </>}
          </div>
        </Modal>
      )}
    </AppraisalShell>
  );
}
