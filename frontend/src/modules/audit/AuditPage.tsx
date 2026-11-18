import { useState } from 'react';
import { Download, ShieldCheck, ShieldX } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import type { Page } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Modal, PageHeader, SelectField, TextField } from '@/components/ui';
import { DataTable, fmtDateTime } from '@/components/DataTable';

interface AuditRow {
  id: number; occurredAt: string; username: string; action: string; entityType?: string; entityId?: string; summary?: string;
  oldValue?: string; newValue?: string; ipAddress?: string; hash: string; prevHash?: string;
}

/** Tamper-evident audit history; read-only (FR-AUD-001..006). */
export default function AuditPage() {
  const toast = useToast();
  const actions = useApi<string[]>('/audit/actions');
  const [f, setF] = useState({ username: '', action: '', entityType: '', entityId: '', from: '', to: '' });
  const [page, setPage] = useState(0);
  const { data, loading, error } = useApi<Page<AuditRow>>('/audit', { ...f, page, size: 25 });
  const [open, setOpen] = useState<AuditRow | null>(null);
  const [verify, setVerify] = useState<{ valid: boolean; entriesChecked: number; message: string } | null>(null);
  const set = (k: keyof typeof f) => (v: string) => { setF({ ...f, [k]: v }); setPage(0); };

  const check = async () => {
    try {
      const { data: r } = await api.get('/audit/verify');
      setVerify(r);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader eyebrow="Audit" title="Audit history" subtitle="Every create, change, approval, view of sensitive data, export and sign-in is recorded. Entries are hash-chained and cannot be edited or deleted."
        actions={
          <>
            <button className="btn btn-outline" onClick={() => void download('/reports/AUD_AUDIT_LOG/export?format=csv', { method: 'post', body: { from: f.from || null, to: f.to || null, status: f.action || null } })}><Download /> Export</button>
            <button className="btn btn-gold" onClick={() => void check()}><ShieldCheck /> Verify integrity</button>
          </>
        } />
      {verify && (
        <div style={{ marginBottom: 14 }}>
          <Alert kind={verify.valid ? 'success' : 'danger'}>{verify.valid ? <ShieldCheck size={14} /> : <ShieldX size={14} />} {verify.message} — {verify.entriesChecked} entries checked.</Alert>
        </div>
      )}
      <div className="filters">
        <TextField label="User" value={f.username} onChange={set('username')} />
        <SelectField label="Action" value={f.action} onChange={set('action')} placeholder="— All —" options={(actions.data ?? []).map((a) => ({ value: a, label: a }))} />
        <TextField label="Record type" value={f.entityType} onChange={set('entityType')} placeholder="e.g. Employee" />
        <TextField label="Record id" value={f.entityId} onChange={set('entityId')} />
        <TextField label="From" type="date" value={f.from} onChange={set('from')} />
        <TextField label="To" type="date" value={f.to} onChange={set('to')} />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<AuditRow> rows={data?.content} loading={loading} onRowClick={setOpen} page={data?.page} totalPages={data?.totalPages} totalElements={data?.totalElements} onPage={setPage} columns={[
          { key: 'id', header: '#', render: (r) => <span className="mono">{r.id}</span> },
          { key: 'occurredAt', header: 'When', render: (r) => <span className="nowrap">{fmtDateTime(r.occurredAt)}</span> },
          { key: 'username', header: 'User' },
          { key: 'action', header: 'Action', render: (r) => <Badge tone={r.action.includes('FAIL') || r.action === 'REJECT' ? 'danger' : r.action === 'EXPORT' || r.action === 'VIEW' ? 'info' : 'neutral'}>{r.action}</Badge> },
          { key: 'entityType', header: 'Record', render: (r) => (r.entityType ? `${r.entityType}${r.entityId ? ` #${r.entityId}` : ''}` : '—') },
          { key: 'summary', header: 'Summary' },
          { key: 'ipAddress', header: 'IP' },
        ]} />
      </Card>
      {open && (
        <Modal title={`Audit entry #${open.id}`} onClose={() => setOpen(null)} wide>
          <div className="stack">
            <div><strong>{open.action}</strong> by {open.username} at {fmtDateTime(open.occurredAt)} from {open.ipAddress ?? 'n/a'}</div>
            <div>{open.summary}</div>
            {(open.oldValue || open.newValue) && (
              <div className="grid grid-2">
                <div><h3>Previous value</h3><pre className="mono" style={{ whiteSpace: 'pre-wrap', background: 'var(--surface-2)', padding: 10, borderRadius: 8 }}>{pretty(open.oldValue)}</pre></div>
                <div><h3>New value</h3><pre className="mono" style={{ whiteSpace: 'pre-wrap', background: 'var(--surface-2)', padding: 10, borderRadius: 8 }}>{pretty(open.newValue)}</pre></div>
              </div>
            )}
            <div className="faint mono" style={{ fontSize: 11 }}>hash {open.hash}<br />prev {open.prevHash}</div>
          </div>
        </Modal>
      )}
    </>
  );
}

function pretty(v?: string) {
  if (!v) return '—';
  try {
    return JSON.stringify(JSON.parse(v), null, 2);
  } catch {
    return v;
  }
}
