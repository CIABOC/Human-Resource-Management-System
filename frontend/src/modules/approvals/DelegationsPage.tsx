import { useState } from 'react';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';

interface Delegation { id: number; fromUserId: number; fromName: string; toName: string; processCode?: string | null; startDate: string; endDate: string; reason?: string; active: boolean }
interface UserOption { id: number; fullName: string; username: string }

/** Acting arrangements: approvers delegate their approvals during absence (FR-WFL-007). */
export default function DelegationsPage() {
  const { me, can } = useAuth();
  const toast = useToast();
  const [all, setAll] = useState(false);
  const { data, loading, error, reload } = useApi<Delegation[]>('/workflow/delegations', { all });
  const users = useApi<UserOption[]>('/workflow/user-options');
  const [open, setOpen] = useState(false);
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState({ fromUserId: '', toUserId: '', processCode: '', startDate: today, endDate: today, reason: '' });

  const save = async () => {
    try {
      await api.post('/workflow/delegations', { ...form, fromUserId: form.fromUserId ? Number(form.fromUserId) : null, toUserId: Number(form.toUserId), processCode: form.processCode || null });
      toast('Acting arrangement saved', 'success');
      setOpen(false);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const revoke = async (d: Delegation) => {
    try {
      await api.post(`/workflow/delegations/${d.id}/revoke`);
      toast('Revoked', 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const userOpts = (users.data ?? []).filter((u) => u.id !== me?.id).map((u) => ({ value: String(u.id), label: `${u.fullName} (${u.username})` }));

  return (
    <>
      <PageHeader title="Acting arrangements" subtitle="Let a colleague approve on your behalf while you are away. Every decision records who acted for whom."
        actions={<button className="btn btn-gold" onClick={() => setOpen(true)}><Plus /> New arrangement</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false} title={can('WFL_CONFIG') ? <Checkbox label="Show all arrangements" checked={all} onChange={setAll} /> : 'My arrangements'}>
        <DataTable<Delegation>
          rows={data}
          loading={loading}
          columns={[
            { key: 'fromName', header: 'Approver' },
            { key: 'toName', header: 'Acting officer' },
            { key: 'processCode', header: 'Processes', render: (d) => d.processCode ?? 'All' },
            { key: 'period', header: 'Period', render: (d) => `${fmtDate(d.startDate)} – ${fmtDate(d.endDate)}` },
            { key: 'reason', header: 'Reason' },
            { key: 'active', header: 'Status', render: (d) => <StatusBadge status={d.active ? (d.endDate < today ? 'CLOSED' : 'ACTIVE') : 'CANCELLED'} /> },
            { key: 'x', header: '', render: (d) => d.active && (d.fromUserId === me?.id || can('WFL_CONFIG')) ? <button className="btn btn-outline btn-sm" onClick={() => void revoke(d)}>Revoke</button> : null },
          ]}
        />
      </Card>
      {open && (
        <Modal title="New acting arrangement" onClose={() => setOpen(false)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setOpen(false)}>Cancel</button>
            <button className="btn btn-primary" disabled={!form.toUserId} onClick={() => void save()}>Save</button>
          </>
        }>
          <div className="form-grid">
            {can('WFL_CONFIG') && (
              <SelectField className="span-2" label="Approver (leave empty for yourself)" value={form.fromUserId} onChange={(v) => setForm({ ...form, fromUserId: v })}
                options={(users.data ?? []).map((u) => ({ value: String(u.id), label: u.fullName }))} placeholder="— Myself —" />
            )}
            <SelectField className="span-2" label="Acting officer" required value={form.toUserId} onChange={(v) => setForm({ ...form, toUserId: v })} options={userOpts} />
            <TextField label="From" type="date" required value={form.startDate} onChange={(v) => setForm({ ...form, startDate: v })} />
            <TextField label="To" type="date" required value={form.endDate} onChange={(v) => setForm({ ...form, endDate: v })} />
            <TextField className="span-2" label="Process code (optional)" help="Leave blank to delegate all processes, e.g. LEAVE" value={form.processCode} onChange={(v) => setForm({ ...form, processCode: v.toUpperCase() })} />
            <TextArea className="span-2" label="Reason" value={form.reason} onChange={(v) => setForm({ ...form, reason: v })} />
          </div>
        </Modal>
      )}
    </>
  );
}
