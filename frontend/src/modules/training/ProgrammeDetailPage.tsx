import { useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Plus, Upload } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, DL, Loading, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtMoney } from '@/components/DataTable';
import { EmployeeSelect } from '@/components/pickers';
import { ProgrammeForm } from './ProgrammesPage';
import type { Nomination, ProgrammeView } from './types';

const NEXT: Record<string, { to: string; label: string }[]> = {
  PLANNED: [{ to: 'OPEN', label: 'Open nominations' }, { to: 'CANCELLED', label: 'Cancel' }],
  OPEN: [{ to: 'CLOSED', label: 'Close nominations' }, { to: 'CANCELLED', label: 'Cancel' }],
  CLOSED: [{ to: 'COMPLETED', label: 'Mark completed' }, { to: 'OPEN', label: 'Re-open' }, { to: 'CANCELLED', label: 'Cancel' }],
};

/** Programme detail: nominations (approved via TRAINING_NOMINATION), participation and certificates (FR-TRN-002, -003). */
export default function ProgrammeDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const { can } = useAuth();
  const manage = can('TRN_MANAGE');
  const { data, error, reload, setData } = useApi<ProgrammeView>(`/training/programmes/${id}`);
  const noms = useApi<Nomination[]>(`/training/programmes/${id}/nominations`);
  const [editing, setEditing] = useState(false);
  const [nominate, setNominate] = useState<{ employeeId: string; reason: string } | null>(null);
  const [completion, setCompletion] = useState<{ n: Nomination; completionStatus: string; result: string; remarks: string } | null>(null);
  const [cert, setCert] = useState<Nomination | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return <Loading />;
  const p = data.programme;
  const refresh = () => { void reload(); void noms.reload(); };

  const act = async (fn: () => Promise<unknown>, msg: string, close?: () => void) => {
    setErr(null);
    try {
      await fn();
      close?.();
      toast(msg, 'success');
      refresh();
    } catch (e) {
      if (close) setErr(errorMessage(e));
      else toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader eyebrow={`Training · ${p.code}`} title={p.title} subtitle={<><StatusBadge status={p.status} /> {fmtDate(p.startDate)} – {fmtDate(p.endDate)} · {p.venue ?? ''}</>}
        actions={manage && (
          <div className="row">
            {(NEXT[p.status] ?? []).map((n) => (
              <button key={n.to} className={`btn ${n.to === 'OPEN' ? 'btn-gold' : 'btn-outline'}`}
                onClick={() => void act(() => api.post<ProgrammeView>(`/training/programmes/${p.id}/status`, { status: n.to }).then((r) => setData(r.data)), 'Status updated')}>{n.label}</button>
            ))}
            {(p.status === 'PLANNED' || p.status === 'OPEN' || p.status === 'CLOSED') && <button className="btn btn-outline" onClick={() => setEditing(true)}>Edit</button>}
          </div>
        )} />
      <div className="grid grid-2">
        <Card title="Programme">
          <DL items={[['Provider', p.provider], ['Venue', p.venue], ['Cost', fmtMoney(p.cost)], ['Nominations close', fmtDate(p.nominationClosingDate)],
            ['Description', p.description && <span style={{ whiteSpace: 'pre-wrap' }}>{p.description}</span>]]} />
        </Card>
        <Card title="Seats">
          <DL items={[['Capacity', p.capacity], ['Approved', data.approved], ['Pending approval', data.pending], ['Available', data.seatsAvailable], ['Completed', data.completed]]} />
        </Card>
      </div>
      <Card pad={false} title="Nominations" className="mt" actions={manage && (p.status === 'OPEN' || p.status === 'PLANNED') && (
        <button className="btn btn-primary" disabled={data.seatsAvailable <= 0} onClick={() => { setErr(null); setNominate({ employeeId: '', reason: '' }); }}><Plus /> Nominate</button>
      )}>
        <DataTable<Nomination> rows={noms.data} loading={noms.loading}
          columns={[
            { key: 'employeeNo', header: 'Emp. No' },
            { key: 'employeeName', header: 'Employee' },
            { key: 'orgUnitName', header: 'Unit' },
            { key: 'source', header: 'By', render: (n) => (n.source === 'SELF' ? 'Self' : 'HR') },
            { key: 'status', header: 'Nomination', render: (n) => n.workflowInstanceId
              ? <Link to={`/approvals/${n.workflowInstanceId}`}><StatusBadge status={n.status} /></Link> : <StatusBadge status={n.status} /> },
            { key: 'completionStatus', header: 'Completion', render: (n) => (n.completionStatus === 'NOT_RECORDED' ? '—' : <StatusBadge status={n.completionStatus} />) },
            { key: 'result', header: 'Result' },
            { key: 'act', header: '', render: (n) => (
              <div className="row">
                {n.hasCertificate && <button className="btn btn-ghost btn-sm" onClick={() => void download(`/training/nominations/${n.id}/certificate`)}>Certificate</button>}
                {manage && n.status === 'APPROVED' && (
                  <button className="btn btn-outline btn-sm" onClick={() => { setErr(null); setCompletion({ n, completionStatus: n.completionStatus === 'NOT_RECORDED' ? 'COMPLETED' : n.completionStatus, result: n.result ?? '', remarks: n.completionRemarks ?? '' }); }}>Record</button>
                )}
                {manage && n.completionStatus === 'COMPLETED' && <button className="btn btn-ghost btn-sm" onClick={() => setCert(n)}><Upload /></button>}
              </div>
            ) },
          ]} />
      </Card>

      {editing && <ProgrammeForm programme={p} onClose={() => setEditing(false)} onSaved={(v) => { setEditing(false); setData(v); toast('Saved', 'success'); }} />}
      {nominate && (
        <Modal title="Nominate employee" onClose={() => setNominate(null)} footer={
          <><button className="btn btn-outline" onClick={() => setNominate(null)}>Cancel</button>
            <button className="btn btn-primary" onClick={() => void act(() => api.post('/training/nominations', { programmeId: p.id, employeeId: Number(nominate.employeeId), reason: nominate.reason }),
              'Nomination submitted for approval', () => setNominate(null))}>Submit for approval</button></>
        }>
          {err && <Alert kind="danger">{err}</Alert>}
          <div className="stack">
            <EmployeeSelect value={nominate.employeeId} onChange={(v) => setNominate({ ...nominate, employeeId: v })} required />
            <TextArea label="Reason / training need" value={nominate.reason} onChange={(v) => setNominate({ ...nominate, reason: v })} />
            <div className="faint">Approval: division head, then HR.</div>
          </div>
        </Modal>
      )}
      {completion && (
        <Modal title={`Participation — ${completion.n.employeeName}`} onClose={() => setCompletion(null)} footer={
          <><button className="btn btn-outline" onClick={() => setCompletion(null)}>Cancel</button>
            <button className="btn btn-primary" onClick={() => void act(() => api.put(`/training/nominations/${completion.n.id}/completion`, {
              completionStatus: completion.completionStatus, result: completion.result, remarks: completion.remarks }), 'Participation recorded', () => setCompletion(null))}>Save</button></>
        }>
          {err && <Alert kind="danger">{err}</Alert>}
          <div className="form-grid">
            <SelectField label="Outcome" value={completion.completionStatus} onChange={(v) => setCompletion({ ...completion, completionStatus: v })} placeholder={null}
              options={[{ value: 'COMPLETED', label: 'Attended and completed' }, { value: 'NOT_COMPLETED', label: 'Attended, not completed' }, { value: 'ABSENT', label: 'Did not attend' }]} />
            <TextField label="Result / grade" value={completion.result} onChange={(v) => setCompletion({ ...completion, result: v })} />
            <TextArea label="Remarks" value={completion.remarks} onChange={(v) => setCompletion({ ...completion, remarks: v })} className="span-all" />
          </div>
        </Modal>
      )}
      {cert && (
        <Modal title={`Certificate — ${cert.employeeName}`} onClose={() => setCert(null)} footer={
          <><button className="btn btn-outline" onClick={() => setCert(null)}>Cancel</button>
            <button className="btn btn-primary" onClick={() => {
              const file = fileRef.current?.files?.[0];
              if (!file) return;
              const fd = new FormData();
              fd.append('file', file);
              void act(() => api.post(`/training/nominations/${cert.id}/certificate`, fd), 'Certificate uploaded', () => setCert(null));
            }}>Upload</button></>
        }>
          <input type="file" ref={fileRef} aria-label="Certificate file" accept=".pdf,.png,.jpg,.jpeg" />
        </Modal>
      )}
    </>
  );
}
