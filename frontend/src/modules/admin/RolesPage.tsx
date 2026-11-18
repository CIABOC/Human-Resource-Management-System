import { useMemo, useState } from 'react';
import { Pencil, Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Checkbox, Modal, PageHeader, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable } from '@/components/DataTable';

interface RoleView { id: number; code: string; name: string; description?: string; systemRole: boolean; active: boolean; permissions: string[] }
interface PermissionView { code: string; description: string }

const GROUPS: [string, string][] = [
  ['DASHBOARD', 'General'], ['SELF', 'General'], ['EST', 'Establishment'], ['LEGAL', 'Establishment'], ['EMP', 'Employees'], ['REC', 'Recruitment'],
  ['LEAVE', 'Leave'], ['ATT', 'Attendance'], ['PAY', 'Payroll'], ['BEN', 'Benefits'], ['PER', 'Performance'], ['TRN', 'Training'], ['EXT', 'Extended HR'],
  ['WFL', 'Workflow'], ['MDM', 'Administration'], ['RPT', 'Reports'], ['NOTIF', 'Administration'], ['UAM', 'Administration'], ['AUDIT', 'Audit'], ['SYS', 'Administration'],
];
const groupOf = (p: string) => GROUPS.find(([pre]) => p.startsWith(pre))?.[1] ?? 'Other';

/** Proposed roles (SRS §9) are configurable, not hard-coded (FR-UAM-008). */
export default function RolesPage() {
  const toast = useToast();
  const { data, loading, error, reload } = useApi<RoleView[]>('/admin/roles');
  const perms = useApi<PermissionView[]>('/admin/permissions');
  const [editing, setEditing] = useState<Partial<RoleView> | null>(null);
  const grouped = useMemo(() => {
    const m = new Map<string, PermissionView[]>();
    (perms.data ?? []).forEach((p) => m.set(groupOf(p.code), [...(m.get(groupOf(p.code)) ?? []), p]));
    return [...m.entries()];
  }, [perms.data]);

  const save = async () => {
    if (!editing) return;
    const body = { code: editing.code, name: editing.name, description: editing.description, active: editing.active ?? true, permissions: editing.permissions ?? [] };
    try {
      if (editing.id) await api.put(`/admin/roles/${editing.id}`, body);
      else await api.post('/admin/roles', body);
      toast('Role saved', 'success');
      setEditing(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const toggle = (p: string) => setEditing((r) => r && ({ ...r, permissions: r.permissions?.includes(p) ? r.permissions.filter((x) => x !== p) : [...(r.permissions ?? []), p] }));

  return (
    <>
      <PageHeader eyebrow="Administration" title="Roles & permissions" subtitle="The role list is proposed and must be confirmed by CIABOC. Changes take effect on the user's next request and are audit-logged."
        actions={<button className="btn btn-gold" onClick={() => setEditing({ active: true, permissions: [] })}><Plus /> New role</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<RoleView> rows={data} loading={loading} columns={[
          { key: 'code', header: 'Code', render: (r) => <span className="mono">{r.code}</span> },
          { key: 'name', header: 'Role', render: (r) => <div><strong>{r.name}</strong><div className="faint" style={{ fontSize: 12 }}>{r.description}</div></div> },
          { key: 'permissions', header: 'Permissions', render: (r) => <Badge tone="gold">{r.permissions.length}</Badge> },
          { key: 'systemRole', header: '', render: (r) => (r.systemRole ? <Badge>seeded</Badge> : null) },
          { key: 'active', header: 'Status', render: (r) => <StatusBadge status={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
          { key: 'a', header: '', render: (r) => <button className="btn btn-ghost btn-sm" aria-label="Edit" onClick={() => setEditing(r)}><Pencil /></button> },
        ]} />
      </Card>
      {editing && (
        <Modal title={editing.id ? `Edit role ${editing.code}` : 'New role'} onClose={() => setEditing(null)} wide footer={<><button className="btn btn-outline" onClick={() => setEditing(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>}>
          <div className="stack">
            <div className="form-grid">
              <TextField label="Code" required value={editing.code} onChange={(v) => setEditing({ ...editing, code: v.toUpperCase() })} disabled={!!editing.id} />
              <TextField label="Name" required value={editing.name} onChange={(v) => setEditing({ ...editing, name: v })} />
              <TextArea className="span-2" label="Description" value={editing.description} onChange={(v) => setEditing({ ...editing, description: v })} rows={2} />
              <Checkbox label="Active" checked={editing.active ?? true} onChange={(v) => setEditing({ ...editing, active: v })} />
            </div>
            {grouped.map(([g, ps]) => (
              <div key={g}>
                <div className="section-title"><h3>{g}</h3></div>
                <div className="grid grid-2" style={{ gap: 8 }}>
                  {ps.map((p) => (
                    <label key={p.code} className="checkbox" style={{ alignItems: 'flex-start' }}>
                      <input type="checkbox" checked={editing.permissions?.includes(p.code) ?? false} onChange={() => toggle(p.code)} />
                      <span><span className="mono">{p.code}</span><br /><span className="muted" style={{ fontSize: 12 }}>{p.description}</span></span>
                    </label>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </Modal>
      )}
    </>
  );
}
