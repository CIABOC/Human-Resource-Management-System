import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable } from '@/components/DataTable';
import { LETTER_CATEGORIES, LetterTemplate, PLACEHOLDERS } from './types';

/** Configurable correspondence templates (FR-REC-009). */
export default function LetterTemplatesPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const { can } = useAuth();
  const manage = can('REC_MANAGE');
  const { data, loading, reload } = useApi<LetterTemplate[]>('/recruitment/letter-templates');
  const [edit, setEdit] = useState<Partial<LetterTemplate> | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const save = async () => {
    if (!edit) return;
    setErr(null);
    try {
      if (edit.id) await api.put(`/recruitment/letter-templates/${edit.id}`, edit);
      else await api.post('/recruitment/letter-templates', edit);
      setEdit(null);
      toast('Template saved', 'success');
      void reload();
    } catch (e) {
      setErr(errorMessage(e));
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('rec.section', 'Recruitment')} title={t('rec.templates', 'Letter templates')}
        subtitle="Appointment, examination, interview and regret letters in Sinhala, Tamil and English"
        actions={manage && <button className="btn btn-gold" onClick={() => setEdit({ language: 'en', category: 'OTHER', active: true })}><Plus /> New template</button>} />
      <Card pad={false}>
        <DataTable<LetterTemplate> rows={data} loading={loading} onRowClick={manage ? setEdit : undefined}
          columns={[
            { key: 'code', header: 'Code' },
            { key: 'language', header: 'Lang.' },
            { key: 'name', header: 'Name' },
            { key: 'category', header: 'Category' },
            { key: 'subject', header: 'Subject' },
            { key: 'active', header: 'Status', render: (x) => <StatusBadge status={x.active ? 'ACTIVE' : 'INACTIVE'} /> },
          ]} />
      </Card>
      {edit && (
        <Modal title={edit.id ? `Edit ${edit.code} (${edit.language})` : 'New template'} onClose={() => setEdit(null)} wide footer={
          <><button className="btn btn-outline" onClick={() => setEdit(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>
        }>
          <div className="stack">
            {err && <Alert kind="danger">{err}</Alert>}
            <div className="form-grid cols-3">
              <TextField label="Code" value={edit.code} onChange={(v) => setEdit({ ...edit, code: v.toUpperCase() })} required />
              <SelectField label="Language" value={edit.language} onChange={(v) => setEdit({ ...edit, language: v as LetterTemplate['language'] })} placeholder={null}
                options={[{ value: 'en', label: 'English' }, { value: 'si', label: 'සිංහල' }, { value: 'ta', label: 'தமிழ்' }]} />
              <SelectField label="Category" value={edit.category} onChange={(v) => setEdit({ ...edit, category: v })} placeholder={null}
                options={LETTER_CATEGORIES.map((c) => ({ value: c, label: c.replace(/_/g, ' ') }))} />
              <TextField label="Name" value={edit.name} onChange={(v) => setEdit({ ...edit, name: v })} required className="span-2" />
              <Checkbox label="Active" checked={edit.active ?? true} onChange={(v) => setEdit({ ...edit, active: v })} />
              <TextField label="Subject" value={edit.subject} onChange={(v) => setEdit({ ...edit, subject: v })} required className="span-all" />
              <TextArea label="Body" value={edit.body} onChange={(v) => setEdit({ ...edit, body: v })} rows={12} className="span-all"
                help="Plain text; blank lines separate paragraphs." />
            </div>
            <div className="faint">Placeholders: {PLACEHOLDERS.map((p) => `{{${p}}}`).join(' ')}</div>
          </div>
        </Modal>
      )}
    </>
  );
}
