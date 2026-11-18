import { useState } from 'react';
import { Pencil } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Checkbox, Modal, PageHeader, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDateTime } from '@/components/DataTable';

interface Setting { key: string; value: string; category?: string; valueType: string; description?: string; updatedAt?: string; updatedBy?: string }
interface Integration { id: number; code: string; refId?: string; name: string; purpose?: string; priority?: string; enabled: boolean; endpointUrl?: string; notes?: string }

export default function SettingsPage() {
  const toast = useToast();
  const [tab, setTab] = useState<'settings' | 'integrations'>('settings');
  const settings = useApi<Setting[]>('/admin/settings');
  const integrations = useApi<Integration[]>('/admin/integrations');
  const [editS, setEditS] = useState<Setting | null>(null);
  const [editI, setEditI] = useState<Integration | null>(null);

  const saveS = async () => {
    if (!editS) return;
    try {
      await api.put(`/admin/settings/${encodeURIComponent(editS.key)}`, { value: editS.value });
      toast('Setting saved', 'success');
      setEditS(null);
      void settings.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const saveI = async () => {
    if (!editI) return;
    try {
      await api.put(`/admin/integrations/${editI.id}`, { enabled: editI.enabled, endpointUrl: editI.endpointUrl || null, notes: editI.notes || null });
      toast('Integration saved', 'success');
      setEditI(null);
      void integrations.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader eyebrow="Administration" title="Settings & integrations" subtitle="Business rules and switches are data — no source-code change is needed (SYS-01, INT-12)." />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'settings', label: 'System settings' }, { key: 'integrations', label: 'Integrations' }]} />
      {tab === 'settings' && (
        <Card pad={false}>
          {settings.error && <Alert kind="danger">{settings.error}</Alert>}
          <DataTable<Setting> rows={settings.data} loading={settings.loading} rowKey={(s) => s.key} columns={[
            { key: 'category', header: 'Area', render: (s) => <Badge>{s.category}</Badge> },
            { key: 'key', header: 'Setting', render: (s) => <div><span className="mono">{s.key}</span><div className="faint" style={{ fontSize: 12 }}>{s.description}</div></div> },
            { key: 'value', header: 'Value', render: (s) => <strong>{s.value}</strong> },
            { key: 'updatedAt', header: 'Last changed', render: (s) => (s.updatedAt ? `${fmtDateTime(s.updatedAt)} · ${s.updatedBy}` : '—') },
            { key: 'a', header: '', render: (s) => <button className="btn btn-ghost btn-sm" aria-label="Edit" onClick={() => setEditS({ ...s })}><Pencil /></button> },
          ]} />
        </Card>
      )}
      {tab === 'integrations' && (
        <>
          <Alert kind="warning">Every integration is subject to legal authority, data-sharing agreements and security approval before being enabled (C-03). Failures never stop the core system; messages are queued and retried.</Alert>
          <div style={{ height: 14 }} />
          <Card pad={false}>
            <DataTable<Integration> rows={integrations.data} loading={integrations.loading} columns={[
              { key: 'refId', header: 'Ref', render: (i) => <span className="mono">{i.refId}</span> },
              { key: 'name', header: 'Interface', render: (i) => <div><strong>{i.name}</strong><div className="faint" style={{ fontSize: 12 }}>{i.purpose}</div></div> },
              { key: 'priority', header: 'Priority', render: (i) => <Badge tone={i.priority === 'M' ? 'gold' : 'neutral'}>{i.priority === 'M' ? 'Must' : i.priority === 'S' ? 'Should' : 'Could'}</Badge> },
              { key: 'enabled', header: 'State', render: (i) => <Badge tone={i.enabled ? 'success' : 'neutral'}>{i.enabled ? 'Enabled' : 'Disabled'}</Badge> },
              { key: 'notes', header: 'Notes' },
              { key: 'a', header: '', render: (i) => <button className="btn btn-ghost btn-sm" aria-label="Edit" onClick={() => setEditI({ ...i })}><Pencil /></button> },
            ]} />
          </Card>
        </>
      )}
      {editS && (
        <Modal title={editS.key} onClose={() => setEditS(null)} footer={<><button className="btn btn-outline" onClick={() => setEditS(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void saveS()}>Save</button></>}>
          <div className="stack">
            <div className="muted">{editS.description}</div>
            {editS.valueType === 'BOOLEAN'
              ? <Checkbox label="Enabled" checked={editS.value === 'true'} onChange={(v) => setEditS({ ...editS, value: String(v) })} />
              : <TextField label={`Value (${editS.valueType.toLowerCase()})`} value={editS.value} onChange={(v) => setEditS({ ...editS, value: v })} type={editS.valueType === 'INT' || editS.valueType === 'DECIMAL' ? 'number' : 'text'} />}
          </div>
        </Modal>
      )}
      {editI && (
        <Modal title={`${editI.refId} — ${editI.name}`} onClose={() => setEditI(null)} footer={<><button className="btn btn-outline" onClick={() => setEditI(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void saveI()}>Save</button></>}>
          <div className="stack">
            <Checkbox label="Enabled" checked={editI.enabled} onChange={(v) => setEditI({ ...editI, enabled: v })} />
            <TextField label="Endpoint URL" value={editI.endpointUrl} onChange={(v) => setEditI({ ...editI, endpointUrl: v })} />
            <TextArea label="Notes (authority, agreement reference)" value={editI.notes} onChange={(v) => setEditI({ ...editI, notes: v })} />
          </div>
        </Modal>
      )}
    </>
  );
}
