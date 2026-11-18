import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { CheckCircle2, Send, Upload } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, DL, Loading, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import { MasterDataSelect, useMasterDataLabels } from '@/components/pickers';
import type { BatchDetail, BatchItem } from './types';
import { titleCase } from './types';

interface QueueRow { file: File; employeeNo: string; documentType: string; title: string; documentDate: string; classification: string }

const CLASSIFICATIONS = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'];

export default function DigitizationBatchPage() {
  const { id } = useParams();
  const { t } = useTranslation();
  const { me, can } = useAuth();
  const toast = useToast();
  const docType = useMasterDataLabels('DOCUMENT_TYPE');
  const { data, loading, error, reload } = useApi<BatchDetail>(`/extended/digitization/batches/${id}`);
  const [queue, setQueue] = useState<QueueRow[]>([]);
  const [busy, setBusy] = useState(false);
  const [qa, setQa] = useState<{ item: BatchItem; decision: 'VERIFY' | 'REJECT' } | null>(null);
  const [remarks, setRemarks] = useState('');
  const [correct, setCorrect] = useState<BatchItem | null>(null);

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert kind="danger">{error ?? 'Not found'}</Alert>;
  const b = data.batch;
  const manage = can('EXT_MANAGE');

  const addFiles = (files: FileList | null) => {
    if (!files) return;
    setQueue([...queue, ...Array.from(files).map((file) => ({
      file, employeeNo: '', documentType: '', title: file.name.replace(/\.[^.]+$/, ''), documentDate: '', classification: 'CONFIDENTIAL',
    }))]);
  };
  const setRow = (i: number, patch: Partial<QueueRow>) => setQueue(queue.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  const uploadAll = async () => {
    setBusy(true);
    const failed: QueueRow[] = [];
    for (const r of queue) {
      const fd = new FormData();
      fd.append('file', r.file);
      fd.append('employeeNo', r.employeeNo);
      fd.append('documentType', r.documentType);
      fd.append('title', r.title);
      if (r.documentDate) fd.append('documentDate', r.documentDate);
      fd.append('classification', r.classification);
      try {
        await api.post(`/extended/digitization/batches/${b.id}/items`, fd);
      } catch (e) {
        failed.push(r);
        toast(`${r.file.name}: ${errorMessage(e)}`, 'error');
      }
    }
    setQueue(failed);
    setBusy(false);
    if (failed.length === 0) toast('Scans added to the batch', 'success');
    void reload();
  };

  const act = async (url: string, body: unknown, ok: string) => {
    setBusy(true);
    try {
      await api.post(url, body);
      toast(ok, 'success');
      setQa(null);
      setRemarks('');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const verifiedCount = data.items.filter((i) => i.status === 'VERIFIED').length;

  return (
    <>
      <PageHeader eyebrow={`${t('extended.batch', 'Digitization batch')} ${b.batchNo}`} title={b.title}
        subtitle={<StatusBadge status={b.status} label={b.status === 'IN_QA' ? 'In QA' : undefined} />}
        actions={manage && (
          <>
            {b.status === 'OPEN' && <button className="btn btn-primary" disabled={busy || !(b.counts.UPLOADED > 0)}
              onClick={() => void act(`/extended/digitization/batches/${b.id}/submit`, {}, 'Batch submitted for QA')}><Send /> Submit for QA</button>}
            {b.status === 'IN_QA' && can('EMP_MANAGE') && <button className="btn btn-gold" disabled={busy || verifiedCount === 0}
              onClick={() => void act(`/extended/digitization/batches/${b.id}/file`, {}, 'Verified scans filed into personal files')}><CheckCircle2 /> File {verifiedCount} verified</button>}
          </>
        )} />
      {data.hiddenItems > 0 && <Alert kind="info">{data.hiddenItems} scan(s) belong to employees outside your data scope and are not shown.</Alert>}
      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 3fr) minmax(240px, 1fr)', alignItems: 'start' }}>
        <div className="stack">
          {manage && b.status === 'OPEN' && (
            <Card title="Add scanned documents" actions={
              <label className="btn btn-outline btn-sm"><Upload /> Choose files
                <input type="file" multiple accept=".pdf,.png,.jpg,.jpeg" style={{ display: 'none' }} onChange={(e) => { addFiles(e.target.files); e.target.value = ''; }} />
              </label>
            }>
              {queue.length === 0 ? <div className="faint">Select one or more scanned files, then enter the index data for each.</div> : (
                <div className="stack">
                  {queue.map((r, i) => (
                    <div key={i} className="form-grid" style={{ borderBottom: '1px solid var(--line, #e5e7eb)', paddingBottom: 8 }}>
                      <TextField label={`File: ${r.file.name}`} required value={r.title} onChange={(v) => setRow(i, { title: v })} placeholder="Title" />
                      <TextField label="Employee no." required value={r.employeeNo} onChange={(v) => setRow(i, { employeeNo: v.toUpperCase() })} />
                      <MasterDataSelect category="DOCUMENT_TYPE" label="Document type" required value={r.documentType} onChange={(v) => setRow(i, { documentType: v })} />
                      <TextField label="Document date" type="date" value={r.documentDate} onChange={(v) => setRow(i, { documentDate: v })} />
                      <SelectField label="Classification" value={r.classification} placeholder={null} onChange={(v) => setRow(i, { classification: v })}
                        options={CLASSIFICATIONS.map((c) => ({ value: c, label: titleCase(c) }))} />
                      <div className="row" style={{ alignItems: 'end' }}>
                        <button className="btn btn-ghost btn-sm" onClick={() => setQueue(queue.filter((_, j) => j !== i))}>Remove</button>
                      </div>
                    </div>
                  ))}
                  <div className="row right">
                    <button className="btn btn-primary" disabled={busy || queue.some((r) => !r.employeeNo || !r.documentType || !r.title)} onClick={() => void uploadAll()}>
                      Upload {queue.length} scan(s)
                    </button>
                  </div>
                </div>
              )}
            </Card>
          )}
          <Card title="Scanned documents" pad={false}>
            <DataTable<BatchItem>
              rows={data.items}
              columns={[
                { key: 'employee', header: 'Employee', render: (i) => `${i.employeeNo}${i.employeeName ? ` — ${i.employeeName}` : ''}` },
                { key: 'title', header: 'Document', render: (i) => <div><div>{i.title}</div><div className="muted" style={{ fontSize: 12 }}>{docType(i.documentType)} · {fmtDate(i.documentDate)} · {titleCase(i.classification)}</div></div> },
                { key: 'uploadedBy', header: 'Uploaded by' },
                { key: 'qa', header: 'QA', render: (i) => i.qaBy ? <div><div>{i.qaBy} · {fmtDateTime(i.qaAt)}</div>{i.qaRemarks && <div className="muted">{i.qaRemarks}</div>}</div> : <span className="faint">—</span> },
                { key: 'status', header: 'Status', render: (i) => <StatusBadge status={i.status === 'UPLOADED' ? 'PENDING' : i.status === 'FILED' ? 'COMPLETED' : i.status} label={titleCase(i.status)} /> },
                { key: 'actions', header: '', render: (i) => (
                  <div className="row">
                    <button className="btn btn-ghost btn-sm" onClick={() => void download(`/extended/digitization/items/${i.id}/file`).catch((e) => toast(errorMessage(e), 'error'))}>View</button>
                    {manage && b.status === 'IN_QA' && i.status === 'UPLOADED' && i.uploadedBy !== me?.username && (
                      <>
                        <button className="btn btn-primary btn-sm" onClick={() => setQa({ item: i, decision: 'VERIFY' })}>Verify</button>
                        <button className="btn btn-outline btn-sm" onClick={() => setQa({ item: i, decision: 'REJECT' })}>Reject</button>
                      </>
                    )}
                    {manage && b.status !== 'COMPLETED' && (i.status === 'REJECTED' || i.status === 'UPLOADED') && i.uploadedBy === me?.username && (
                      <button className="btn btn-ghost btn-sm" onClick={() => setCorrect(i)}>Correct</button>
                    )}
                    {manage && i.status === 'REJECTED' && (
                      <button className="btn btn-ghost btn-sm" onClick={() => void act(`/extended/digitization/items/${i.id}/withdraw`, {}, 'Scan withdrawn')}>Withdraw</button>
                    )}
                  </div>
                ) },
              ]}
            />
          </Card>
        </div>
        <Card title="Batch">
          <DL items={[
            ['Batch no.', <span key="b" className="mono">{b.batchNo}</span>],
            ['Source', b.sourceLocation],
            ['Created', `${b.createdBy} · ${fmtDateTime(b.createdAt)}`],
            ['Submitted', b.submittedAt ? fmtDateTime(b.submittedAt) : null],
            ['Completed', b.completedAt ? fmtDateTime(b.completedAt) : null],
            ['Scans', `${b.total} (filed ${b.counts.FILED ?? 0}, verified ${b.counts.VERIFIED ?? 0}, awaiting QA ${b.counts.UPLOADED ?? 0}, rejected ${b.counts.REJECTED ?? 0})`],
            ['Remarks', data.remarks],
          ]} />
          <p className="muted" style={{ fontSize: 12.5, marginTop: 12 }}>QA must be done by an officer other than the one who uploaded each scan.</p>
        </Card>
      </div>

      {qa && (
        <Modal title={`${qa.decision === 'VERIFY' ? 'Verify' : 'Reject'}: ${qa.item.title}`} onClose={() => setQa(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setQa(null)}>Cancel</button>
            <button className={`btn ${qa.decision === 'VERIFY' ? 'btn-primary' : 'btn-danger'}`} disabled={busy || (qa.decision === 'REJECT' && !remarks.trim())}
              onClick={() => void act(`/extended/digitization/items/${qa.item.id}/qa`, { decision: qa.decision, remarks }, qa.decision === 'VERIFY' ? 'Scan verified' : 'Scan rejected')}>
              Confirm
            </button>
          </>
        }>
          <Alert kind="info">Check legibility, completeness and that the index data (employee {qa.item.employeeNo}, {docType(qa.item.documentType)}) matches the scan.</Alert>
          <TextArea label={qa.decision === 'REJECT' ? 'Reason' : 'Remarks (optional)'} required={qa.decision === 'REJECT'} value={remarks} onChange={setRemarks} />
        </Modal>
      )}
      {correct && <CorrectModal item={correct} onClose={() => setCorrect(null)} onSaved={() => { setCorrect(null); void reload(); }} />}
    </>
  );
}

function CorrectModal({ item, onClose, onSaved }: { item: BatchItem; onClose: () => void; onSaved: () => void }) {
  const toast = useToast();
  const [f, setF] = useState({ employeeNo: item.employeeNo, documentType: item.documentType, title: item.title, documentDate: item.documentDate ?? '', classification: item.classification });
  const [file, setFile] = useState<File | null>(null);
  const save = async () => {
    const fd = new FormData();
    Object.entries(f).forEach(([k, v]) => v && fd.append(k, v));
    if (file) fd.append('file', file);
    try {
      await api.post(`/extended/digitization/items/${item.id}/correct`, fd);
      toast('Scan corrected; it needs QA again', 'success');
      onSaved();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  return (
    <Modal title={`Correct: ${item.title}`} onClose={onClose} footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={!f.employeeNo || !f.documentType || !f.title} onClick={() => void save()}>Save</button>
      </>
    }>
      {item.qaRemarks && <Alert kind="warning">QA remarks: {item.qaRemarks}</Alert>}
      <div className="form-grid">
        <TextField label="Title" required value={f.title} onChange={(v) => setF({ ...f, title: v })} />
        <TextField label="Employee no." required value={f.employeeNo} onChange={(v) => setF({ ...f, employeeNo: v.toUpperCase() })} />
        <MasterDataSelect category="DOCUMENT_TYPE" label="Document type" required value={f.documentType} onChange={(v) => setF({ ...f, documentType: v })} />
        <TextField label="Document date" type="date" value={f.documentDate} onChange={(v) => setF({ ...f, documentDate: v })} />
        <SelectField label="Classification" value={f.classification} placeholder={null} onChange={(v) => setF({ ...f, classification: v })}
          options={CLASSIFICATIONS.map((c) => ({ value: c, label: titleCase(c) }))} />
        <div className="field">
          <label>Replacement scan (optional)</label>
          <input type="file" accept=".pdf,.png,.jpg,.jpeg" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </div>
      </div>
    </Modal>
  );
}
