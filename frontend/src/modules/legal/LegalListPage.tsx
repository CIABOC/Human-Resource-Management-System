import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { DOC_TYPES, LegalDocument, multipart } from './types';

export default function LegalListPage() {
  const { can } = useAuth();
  const nav = useNavigate();
  const [all, setAll] = useState(false);
  const { data, loading, error, reload } = useApi<LegalDocument[]>('/legal/documents');
  const [open, setOpen] = useState(false);
  const rows = (data ?? []).filter((d) => all || d.status !== 'SUPERSEDED');
  return (
    <>
      <PageHeader eyebrow="Establishment" title="Legal documents"
        subtitle="Acts, Gazettes, regulations and circulars, with structured provisions and amendment history. The initial reference is the Anti-Corruption Act, No. 9 of 2023."
        actions={can('LEGAL_MANAGE') ? <button className="btn btn-gold" onClick={() => setOpen(true)}><Plus /> Add document</button> : undefined} />
      <div className="filters"><Checkbox label="Show superseded versions" checked={all} onChange={setAll} /></div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<LegalDocument> rows={rows} loading={loading} onRowClick={(d) => nav(`/legal/${d.id}`)} columns={[
          { key: 'docType', header: 'Type', render: (d) => <span className="badge badge-neutral">{d.docType}</span> },
          { key: 'title', header: 'Title', render: (d) => <div><strong>{d.title}</strong><div className="faint" style={{ fontSize: 12 }}>{d.docNumber} · v{d.versionNo}</div></div> },
          { key: 'issuingAuthority', header: 'Issued by' },
          { key: 'publicationDate', header: 'Published', render: (d) => fmtDate(d.publicationDate) },
          { key: 'effectiveDate', header: 'Effective', render: (d) => fmtDate(d.effectiveDate) },
          { key: 'verificationStatus', header: 'Verification', render: (d) => <StatusBadge status={d.verificationStatus} /> },
          { key: 'status', header: 'Status', render: (d) => <StatusBadge status={d.status} /> },
        ]} />
      </Card>
      {open && <DocumentModal onClose={() => setOpen(false)} onSaved={(d) => { void reload(); nav(`/legal/${d.id}`); }} />}
    </>
  );
}

export function DocumentModal({ onClose, onSaved, existing, mode = 'create' }: {
  onClose: () => void; onSaved: (d: LegalDocument) => void; existing?: LegalDocument; mode?: 'create' | 'edit' | 'version';
}) {
  const toast = useToast();
  const [f, setF] = useState({
    docType: existing?.docType ?? 'ACT', docNumber: existing?.docNumber ?? '', title: existing?.title ?? '', issuingAuthority: existing?.issuingAuthority ?? '',
    publicationDate: existing?.publicationDate ?? '', effectiveDate: existing?.effectiveDate ?? '', description: existing?.description ?? '',
  });
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (v: string) => setF({ ...f, [k]: v });
  const body = { ...f, publicationDate: f.publicationDate || null, effectiveDate: f.effectiveDate || null };

  const save = async () => {
    setBusy(true);
    try {
      let res;
      if (mode === 'edit' && existing) res = await api.put(`/legal/documents/${existing.id}`, body);
      else if (mode === 'version' && existing) res = await api.post(`/legal/documents/${existing.id}/versions`, multipart(body, file));
      else res = await api.post('/legal/documents', multipart(body, file));
      toast('Saved', 'success');
      onSaved(res.data);
      onClose();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={mode === 'version' ? 'Upload new version' : mode === 'edit' ? 'Edit document details' : 'Add legal document'} onClose={onClose} wide footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={busy || !f.docNumber || !f.title} onClick={() => void save()}>Save</button>
      </>
    }>
      <div className="form-grid">
        <SelectField label="Type" required value={f.docType} onChange={set('docType')} placeholder={null} options={DOC_TYPES.map((d) => ({ value: d, label: d.toLowerCase() }))} />
        <TextField label="Document number" required value={f.docNumber} onChange={set('docNumber')} help="e.g. No. 9 of 2023, Gazette 2345/12" />
        <TextField className="span-2" label="Title" required value={f.title} onChange={set('title')} />
        <TextField className="span-2" label="Issuing authority" value={f.issuingAuthority} onChange={set('issuingAuthority')} />
        <TextField label="Publication date" type="date" value={f.publicationDate} onChange={set('publicationDate')} />
        <TextField label="Effective date" type="date" value={f.effectiveDate} onChange={set('effectiveDate')} />
        <TextArea className="span-2" label="Description" value={f.description} onChange={set('description')} />
        {mode !== 'edit' && (
          <div className="field span-2">
            <label htmlFor="legal-file">Document file (PDF preferred)</label>
            <input id="legal-file" type="file" className="input" style={{ paddingTop: 7 }} accept=".pdf,.doc,.docx" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </div>
        )}
      </div>
      {mode === 'edit' && existing?.verificationStatus === 'VERIFIED' && (
        <div style={{ marginTop: 12 }}><Alert kind="warning">Editing will reset the verification status to Draft until re-verified.</Alert></div>
      )}
    </Modal>
  );
}
