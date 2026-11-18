import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable } from '@/components/DataTable';
import type { LetterTemplate } from './types';
import { LANG_LABEL, titleCase } from './types';

const PLACEHOLDERS = ['employee.title', 'employee.fullName', 'employee.nameWithInitials', 'employee.employeeNo', 'employee.nic',
  'employee.designation', 'employee.orgUnit', 'employee.appointmentDate', 'employee.confirmationDate', 'employee.retirementDate',
  'employee.separationDate', 'employee.employmentType', 'org.name', 'document.no', 'document.date', 'request.purpose',
  'request.addressee', 'signatory', 'field.<name>'];

/** Configurable trilingual letter and certificate templates (FR-EXT-005). */
export default function LetterTemplatesPage() {
  const { t } = useTranslation();
  const { data, loading, error, reload } = useApi<LetterTemplate[]>('/extended/letter-templates');
  const [edit, setEdit] = useState<Partial<LetterTemplate> | null>(null);
  return (
    <>
      <PageHeader title={t('extended.letterTemplates', 'Letter templates')}
        subtitle="Service letters, salary confirmations and certificates in English, Sinhala and Tamil"
        actions={<button className="btn btn-gold" onClick={() => setEdit({ language: 'en', documentKind: 'LETTER', selfService: true, active: true })}><Plus /> New template</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<LetterTemplate>
          rows={data}
          loading={loading}
          onRowClick={(r) => setEdit(r)}
          columns={[
            { key: 'code', header: 'Code', render: (r) => <span className="mono">{r.code}</span> },
            { key: 'language', header: 'Language', render: (r) => LANG_LABEL[r.language] ?? r.language },
            { key: 'name', header: 'Name' },
            { key: 'documentKind', header: 'Kind', render: (r) => titleCase(r.documentKind) },
            { key: 'selfService', header: 'Self-service', render: (r) => (r.selfService ? 'Yes' : 'No') },
            { key: 'active', header: 'Status', render: (r) => <StatusBadge status={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
          ]}
        />
      </Card>
      {edit && <TemplateEditor value={edit} onClose={() => setEdit(null)} onSaved={() => { setEdit(null); void reload(); }} />}
    </>
  );
}

function TemplateEditor({ value, onClose, onSaved }: { value: Partial<LetterTemplate>; onClose: () => void; onSaved: () => void }) {
  const toast = useToast();
  const [f, setF] = useState<Partial<LetterTemplate>>(value);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      if (f.id) await api.put(`/extended/letter-templates/${f.id}`, f);
      else await api.post('/extended/letter-templates', f);
      toast('Template saved', 'success');
      onSaved();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal wide title={f.id ? `Edit ${f.code} (${LANG_LABEL[f.language ?? 'en']})` : 'New letter template'} onClose={onClose} footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={busy || !f.code || !f.name || !f.title || !f.bodyHtml} onClick={() => void save()}>Save</button>
      </>
    }>
      <div className="form-grid">
        <TextField label="Code" required disabled={!!f.id} value={f.code} onChange={(v) => setF({ ...f, code: v.toUpperCase() })} help="Same code for each language version" />
        <SelectField label="Language" required disabled={!!f.id} value={f.language} placeholder={null} onChange={(v) => setF({ ...f, language: v })}
          options={Object.entries(LANG_LABEL).map(([value, label]) => ({ value, label }))} />
        <TextField label="Name" required value={f.name} onChange={(v) => setF({ ...f, name: v })} />
        <SelectField label="Kind" required value={f.documentKind} placeholder={null} onChange={(v) => setF({ ...f, documentKind: v as LetterTemplate['documentKind'] })}
          options={[{ value: 'LETTER', label: 'Letter' }, { value: 'CERTIFICATE', label: 'Certificate' }]} />
        <TextField label="Document title" required value={f.title} onChange={(v) => setF({ ...f, title: v })} />
        <div className="row">
          <Checkbox label="Employees may request (self-service)" checked={f.selfService !== false} onChange={(v) => setF({ ...f, selfService: v })} />
          <Checkbox label="Active" checked={f.active !== false} onChange={(v) => setF({ ...f, active: v })} />
        </div>
      </div>
      <TextArea label="Body (HTML formatting only)" required rows={12} value={f.bodyHtml} onChange={(v) => setF({ ...f, bodyHtml: v })}
        help="Scripts, frames, forms and event handlers are rejected. Values are escaped automatically." />
      <div className="muted" style={{ fontSize: 12.5 }}>
        Placeholders: {PLACEHOLDERS.map((p) => <code key={p} style={{ marginRight: 8 }}>{`{{${p}}}`}</code>)}
        <div>{'{{field.<name>}}'} values are entered by HR when issuing (e.g. salary figures) and are mandatory.</div>
      </div>
    </Modal>
  );
}
