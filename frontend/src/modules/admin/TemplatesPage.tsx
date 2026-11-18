import { useState } from 'react';
import { Pencil } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import type { Page } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Card, Checkbox, Modal, PageHeader, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDateTime } from '@/components/DataTable';

interface Template {
  id: number; code: string; description?: string; titleEn: string; titleSi?: string; titleTa?: string; bodyEn: string; bodySi?: string; bodyTa?: string;
  sendEmail: boolean; sendSms: boolean; active: boolean;
}
interface Outbound { id: number; channel: string; recipient: string; subject?: string; status: string; attempts: number; lastError?: string; createdAt: string; sentAt?: string }

/** Trilingual notification templates (FR-NOT-003) and delivery status log (FR-NOT-002). */
export default function TemplatesPage() {
  const toast = useToast();
  const [tab, setTab] = useState<'templates' | 'delivery'>('templates');
  const templates = useApi<Template[]>('/admin/notification-templates');
  const [page, setPage] = useState(0);
  const outbound = useApi<Page<Outbound>>(tab === 'delivery' ? '/admin/outbound-messages' : null, { page });
  const [editing, setEditing] = useState<Template | null>(null);

  const save = async () => {
    if (!editing) return;
    try {
      await api.put(`/admin/notification-templates/${editing.id}`, editing);
      toast('Template saved', 'success');
      setEditing(null);
      void templates.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const set = (k: keyof Template) => (v: string | boolean) => setEditing((t) => (t ? { ...t, [k]: v } : t));

  return (
    <>
      <PageHeader eyebrow="Administration" title="Notifications setup" subtitle="Templates use {{placeholders}}. Users receive messages in their preferred language; notifications never include confidential content." />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'templates', label: 'Templates' }, { key: 'delivery', label: 'E-mail / SMS delivery log' }]} />
      {tab === 'templates' && (
        <Card pad={false}>
          <DataTable<Template> rows={templates.data} loading={templates.loading} columns={[
            { key: 'code', header: 'Code', render: (t) => <span className="mono">{t.code}</span> },
            { key: 'titleEn', header: 'Title', render: (t) => <div><strong>{t.titleEn}</strong><div className="faint" style={{ fontSize: 12 }}>{t.description}</div></div> },
            { key: 'ch', header: 'Channels', render: (t) => ['In-app', t.sendEmail && 'E-mail', t.sendSms && 'SMS'].filter(Boolean).join(', ') },
            { key: 'active', header: 'Status', render: (t) => <StatusBadge status={t.active ? 'ACTIVE' : 'INACTIVE'} /> },
            { key: 'a', header: '', render: (t) => <button className="btn btn-ghost btn-sm" aria-label="Edit" onClick={() => setEditing({ ...t })}><Pencil /></button> },
          ]} />
        </Card>
      )}
      {tab === 'delivery' && (
        <Card pad={false}>
          <DataTable<Outbound> rows={outbound.data?.content} loading={outbound.loading} page={outbound.data?.page} totalPages={outbound.data?.totalPages} onPage={setPage} columns={[
            { key: 'createdAt', header: 'Queued', render: (o) => fmtDateTime(o.createdAt) },
            { key: 'channel', header: 'Channel' },
            { key: 'recipient', header: 'To' },
            { key: 'subject', header: 'Subject' },
            { key: 'status', header: 'Status', render: (o) => <StatusBadge status={o.status} /> },
            { key: 'attempts', header: 'Attempts', align: 'right' },
            { key: 'lastError', header: 'Last error' },
          ]} />
        </Card>
      )}
      {editing && (
        <Modal title={editing.code} onClose={() => setEditing(null)} wide footer={<><button className="btn btn-outline" onClick={() => setEditing(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>}>
          <div className="form-grid">
            <TextField className="span-2" label="Title (English)" required value={editing.titleEn} onChange={set('titleEn')} />
            <TextArea className="span-2" label="Body (English)" required value={editing.bodyEn} onChange={set('bodyEn')} />
            <TextField label="Title (Sinhala)" lang="si" value={editing.titleSi} onChange={set('titleSi')} />
            <TextField label="Title (Tamil)" lang="ta" value={editing.titleTa} onChange={set('titleTa')} />
            <TextArea label="Body (Sinhala)" value={editing.bodySi} onChange={set('bodySi')} />
            <TextArea label="Body (Tamil)" value={editing.bodyTa} onChange={set('bodyTa')} />
            <div className="row span-2">
              <Checkbox label="Also send e-mail" checked={editing.sendEmail} onChange={set('sendEmail')} />
              <Checkbox label="Also send SMS" checked={editing.sendSms} onChange={set('sendSms')} />
              <Checkbox label="Active" checked={editing.active} onChange={set('active')} />
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}
