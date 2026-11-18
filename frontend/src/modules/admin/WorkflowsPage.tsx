import { useState } from 'react';
import { ArrowDown, ArrowUp, Pencil, Plus, Trash2 } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable } from '@/components/DataTable';

interface Stage { id?: number; stageOrder?: number; name: string; approverType: string; approverRef?: string | null; slaHours?: number | null; escalationRoleCode?: string | null }
interface Definition { id: number; processCode: string; name: string; description?: string; active: boolean; stages: Stage[] }
interface RoleView { code: string; name: string }
interface UserOption { id: number; fullName: string }

/** Approval chains are configured per process, never hard-coded (FR-WFL-001..003, -009). */
export default function WorkflowsPage() {
  const toast = useToast();
  const { data, loading, error, reload } = useApi<Definition[]>('/workflow/definitions');
  const roles = useApi<RoleView[]>('/admin/roles');
  const users = useApi<UserOption[]>('/workflow/user-options');
  const [editing, setEditing] = useState<Definition | null>(null);

  const save = async () => {
    if (!editing) return;
    try {
      await api.put(`/workflow/definitions/${editing.id}`, {
        name: editing.name, description: editing.description, active: editing.active,
        stages: editing.stages.map((s) => ({ ...s, slaHours: s.slaHours || null, escalationRoleCode: s.escalationRoleCode || null, approverRef: s.approverRef || null })),
      });
      toast('Workflow saved. New requests use the new chain.', 'success');
      setEditing(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const upd = (i: number, patch: Partial<Stage>) => setEditing((d) => d && ({ ...d, stages: d.stages.map((s, j) => (j === i ? { ...s, ...patch } : s)) }));
  const move = (i: number, dir: -1 | 1) => setEditing((d) => {
    if (!d) return d;
    const st = [...d.stages];
    const j = i + dir;
    if (j < 0 || j >= st.length) return d;
    [st[i], st[j]] = [st[j], st[i]];
    return { ...d, stages: st };
  });

  return (
    <>
      <PageHeader eyebrow="Administration" title="Approval workflows" subtitle="Each process has its own chain. Approvers may be a role, a position, a named officer, or the requester's division head." />
      <Alert kind="info">Actual approval authorities and sequences are to be confirmed by Administration and the Director General's office (SRS 11.1 #14). A requester can never approve their own request.</Alert>
      {error && <Alert kind="danger">{error}</Alert>}
      <div style={{ height: 14 }} />
      <Card pad={false}>
        <DataTable<Definition> rows={data} loading={loading} columns={[
          { key: 'processCode', header: 'Process', render: (d) => <span className="mono">{d.processCode}</span> },
          { key: 'name', header: 'Name', render: (d) => <strong>{d.name}</strong> },
          { key: 'stages', header: 'Approval chain', render: (d) => d.stages.map((s) => s.name).join(' → ') },
          { key: 'active', header: 'Status', render: (d) => <StatusBadge status={d.active ? 'ACTIVE' : 'INACTIVE'} /> },
          { key: 'a', header: '', render: (d) => <button className="btn btn-ghost btn-sm" aria-label="Edit" onClick={() => setEditing(JSON.parse(JSON.stringify(d)))}><Pencil /></button> },
        ]} />
      </Card>
      {editing && (
        <Modal title={`Configure ${editing.processCode}`} onClose={() => setEditing(null)} wide footer={<><button className="btn btn-outline" onClick={() => setEditing(null)}>Cancel</button><button className="btn btn-primary" disabled={editing.stages.length === 0} onClick={() => void save()}>Save</button></>}>
          <div className="stack">
            <div className="form-grid">
              <TextField label="Name" value={editing.name} onChange={(v) => setEditing({ ...editing, name: v })} />
              <Checkbox label="Active" checked={editing.active} onChange={(v) => setEditing({ ...editing, active: v })} />
              <TextArea className="span-2" label="Description" value={editing.description} onChange={(v) => setEditing({ ...editing, description: v })} rows={2} />
            </div>
            {editing.stages.map((s, i) => (
              <div key={i} className="card card-pad" style={{ borderLeft: '4px solid var(--gold-500)' }}>
                <div className="row between" style={{ marginBottom: 10 }}>
                  <strong>Stage {i + 1}</strong>
                  <div className="row" style={{ gap: 2 }}>
                    <button className="btn btn-ghost btn-sm" aria-label="Move up" onClick={() => move(i, -1)}><ArrowUp /></button>
                    <button className="btn btn-ghost btn-sm" aria-label="Move down" onClick={() => move(i, 1)}><ArrowDown /></button>
                    <button className="btn btn-ghost btn-sm" aria-label="Remove stage" onClick={() => setEditing({ ...editing, stages: editing.stages.filter((_, j) => j !== i) })}><Trash2 /></button>
                  </div>
                </div>
                <div className="form-grid cols-3">
                  <TextField label="Stage name" required value={s.name} onChange={(v) => upd(i, { name: v })} />
                  <SelectField label="Approver type" required value={s.approverType} placeholder={null} onChange={(v) => upd(i, { approverType: v, approverRef: '' })}
                    options={[{ value: 'ROLE', label: 'Role' }, { value: 'DIVISION_HEAD', label: "Requester's division head" }, { value: 'POSITION', label: 'Position' }, { value: 'USER', label: 'Named officer' }]} />
                  {s.approverType === 'ROLE' && <SelectField label="Role" required value={s.approverRef} onChange={(v) => upd(i, { approverRef: v })} options={(roles.data ?? []).map((r) => ({ value: r.code, label: r.name }))} />}
                  {s.approverType === 'USER' && <SelectField label="Officer" required value={s.approverRef} onChange={(v) => upd(i, { approverRef: v })} options={(users.data ?? []).map((u) => ({ value: String(u.id), label: u.fullName }))} />}
                  {s.approverType === 'POSITION' && <TextField label="Position code" required value={s.approverRef} onChange={(v) => upd(i, { approverRef: v.toUpperCase() })} />}
                  {s.approverType === 'DIVISION_HEAD' && <div />}
                  <TextField label="Time limit (hours)" type="number" value={s.slaHours ?? ''} onChange={(v) => upd(i, { slaHours: v ? Number(v) : null })} help="Overdue requests are escalated" />
                  <SelectField label="Escalate to role" value={s.escalationRoleCode} onChange={(v) => upd(i, { escalationRoleCode: v })} options={(roles.data ?? []).map((r) => ({ value: r.code, label: r.name }))} placeholder="— Approvers only —" />
                </div>
              </div>
            ))}
            <div><button className="btn btn-outline" onClick={() => setEditing({ ...editing, stages: [...editing.stages, { name: '', approverType: 'ROLE', approverRef: '' }] })}><Plus /> Add stage</button></div>
          </div>
        </Modal>
      )}
    </>
  );
}
