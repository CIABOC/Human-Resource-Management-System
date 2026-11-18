import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Modal, PageHeader, SelectField, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import type { Announcement, Vacancy } from './types';

/** Recruitment announcements; only REC_MANAGE holders can publish (FR-REC-003). */
export default function AnnouncementsPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const { can } = useAuth();
  const manage = can('REC_MANAGE');
  const { data, loading, reload } = useApi<Announcement[]>('/recruitment/announcements');
  const vacancies = useApi<Vacancy[]>('/recruitment/vacancies');
  const [edit, setEdit] = useState<Partial<Announcement> | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const save = async () => {
    if (!edit) return;
    setErr(null);
    try {
      const body = { ...edit, vacancyId: edit.vacancyId || null, closingDate: edit.closingDate || null };
      if (edit.id) await api.put(`/recruitment/announcements/${edit.id}`, body);
      else await api.post('/recruitment/announcements', body);
      setEdit(null);
      toast('Announcement saved', 'success');
      void reload();
    } catch (e) {
      setErr(errorMessage(e));
    }
  };

  const publish = async (a: Announcement, on: boolean) => {
    try {
      await api.post(`/recruitment/announcements/${a.id}/${on ? 'publish' : 'unpublish'}`);
      toast(on ? 'Published' : 'Unpublished', 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('rec.section', 'Recruitment')} title={t('rec.announcements', 'Announcements')}
        actions={manage && <button className="btn btn-gold" onClick={() => setEdit({ publishDate: new Date().toISOString().slice(0, 10) })}><Plus /> New announcement</button>} />
      <Card pad={false}>
        <DataTable<Announcement> rows={data} loading={loading}
          columns={[
            { key: 'title', header: 'Title' },
            { key: 'vacancyId', header: 'Vacancy', render: (a) => vacancies.data?.find((v) => v.id === a.vacancyId)?.referenceNo ?? '—' },
            { key: 'publishDate', header: 'Publish', render: (a) => fmtDate(a.publishDate) },
            { key: 'closingDate', header: 'Closes', render: (a) => fmtDate(a.closingDate) },
            { key: 'published', header: 'Status', render: (a) => (a.published ? <Badge tone="success">Published {fmtDateTime(a.publishedAt)}</Badge> : <Badge>Draft</Badge>) },
            { key: 'act', header: '', render: (a) => manage && (
              <div className="row">
                {!a.published && <button className="btn btn-ghost btn-sm" onClick={() => setEdit(a)}>Edit</button>}
                <button className="btn btn-outline btn-sm" onClick={() => void publish(a, !a.published)}>{a.published ? 'Unpublish' : 'Publish'}</button>
              </div>
            ) },
          ]} />
      </Card>
      {edit && (
        <Modal title={edit.id ? 'Edit announcement' : 'New announcement'} onClose={() => setEdit(null)} wide footer={
          <><button className="btn btn-outline" onClick={() => setEdit(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>
        }>
          {err && <Alert kind="danger">{err}</Alert>}
          <div className="form-grid">
            <TextField label="Title" value={edit.title} onChange={(v) => setEdit({ ...edit, title: v })} required className="span-all" />
            <SelectField label="Vacancy" value={edit.vacancyId} onChange={(v) => setEdit({ ...edit, vacancyId: v ? Number(v) : null })} placeholder="General"
              options={(vacancies.data ?? []).filter((v) => v.status !== 'CANCELLED').map((v) => ({ value: String(v.id), label: `${v.referenceNo} — ${v.title}` }))} />
            <div />
            <TextField label="Publish date" type="date" value={edit.publishDate} onChange={(v) => setEdit({ ...edit, publishDate: v })} required />
            <TextField label="Closing date" type="date" value={edit.closingDate} onChange={(v) => setEdit({ ...edit, closingDate: v })} />
            <TextArea label="Content" value={edit.content} onChange={(v) => setEdit({ ...edit, content: v })} required rows={8} className="span-all" />
          </div>
        </Modal>
      )}
    </>
  );
}
