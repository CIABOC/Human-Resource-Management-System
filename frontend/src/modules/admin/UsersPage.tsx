import { useEffect, useState } from 'react';
import { Activity, KeyRound, LockOpen, Pencil, Search, UserPlus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import type { Page } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Modal, PageHeader, SelectField, StatusBadge, TextField } from '@/components/ui';
import { DataTable, fmtDateTime } from '@/components/DataTable';

interface UserView {
  id: number; username: string; fullName: string; email?: string; mobile?: string; employeeId?: number | null; active: boolean; dataScope: string;
  preferredLanguage: string; otpChannel: string; roles: string[]; lastLoginAt?: string; locked: boolean;
}
interface RoleView { id: number; code: string; name: string; active: boolean }
interface EmployeeOption { id: number; employeeNo: string; name: string; hasAccount: boolean }
interface AuditRow { id: number; occurredAt: string; action: string; entityType?: string; summary?: string; ipAddress?: string }

/** User and access management (FR-UAM-002..007). */
export default function UsersPage() {
  const toast = useToast();
  const { me } = useAuth();
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  const [page, setPage] = useState(0);
  useEffect(() => { const id = setTimeout(() => { setDebounced(q); setPage(0); }, 300); return () => clearTimeout(id); }, [q]);
  const { data, loading, error, reload } = useApi<Page<UserView>>('/admin/users', { q: debounced, page });
  const [editing, setEditing] = useState<Partial<UserView> | null>(null);
  const [reset, setReset] = useState<UserView | null>(null);
  const [activity, setActivity] = useState<UserView | null>(null);

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    try { await fn(); toast(ok, 'success'); void reload(); } catch (e) { toast(errorMessage(e), 'error'); }
  };

  return (
    <>
      <PageHeader eyebrow="Administration" title="Users" subtitle="Accounts, roles and data scope. Identity will be federated with the CIABOC SSO/IAM platform when it is connected (INT-01)."
        actions={<button className="btn btn-gold" onClick={() => setEditing({ dataScope: 'SELF', preferredLanguage: 'en', otpChannel: 'EMAIL', roles: ['EMPLOYEE'] })}><UserPlus /> New user</button>} />
      <div className="filters">
        <div className="field"><label htmlFor="uq">Search</label><div className="search"><Search /><input id="uq" className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Username or name" /></div></div>
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<UserView> rows={data?.content} loading={loading} page={data?.page} totalPages={data?.totalPages} totalElements={data?.totalElements} onPage={setPage} columns={[
          { key: 'username', header: 'Username', render: (u) => <span className="mono">{u.username}</span> },
          { key: 'fullName', header: 'Name', render: (u) => <div><strong>{u.fullName}</strong><div className="faint" style={{ fontSize: 12 }}>{u.email}</div></div> },
          { key: 'roles', header: 'Roles', render: (u) => <div className="row" style={{ gap: 4 }}>{u.roles.map((r) => <Badge key={r} tone={r === 'EMPLOYEE' ? 'neutral' : 'gold'}>{r.replace(/_/g, ' ').toLowerCase()}</Badge>)}</div> },
          { key: 'dataScope', header: 'Scope', render: (u) => u.dataScope.toLowerCase() },
          { key: 'lastLoginAt', header: 'Last sign-in', render: (u) => fmtDateTime(u.lastLoginAt) },
          { key: 'active', header: 'Status', render: (u) => <span className="row" style={{ gap: 4 }}><StatusBadge status={u.active ? 'ACTIVE' : 'INACTIVE'} />{u.locked && <Badge tone="danger">Locked</Badge>}</span> },
          { key: 'a', header: '', render: (u) => (
            <div className="row" style={{ gap: 2 }}>
              <button className="btn btn-ghost btn-sm" title="Edit" aria-label="Edit" onClick={() => setEditing(u)}><Pencil /></button>
              <button className="btn btn-ghost btn-sm" title="Reset password" aria-label="Reset password" onClick={() => setReset(u)}><KeyRound /></button>
              <button className="btn btn-ghost btn-sm" title="Activity" aria-label="Activity" onClick={() => setActivity(u)}><Activity /></button>
              {u.locked && <button className="btn btn-ghost btn-sm" title="Unlock" aria-label="Unlock" onClick={() => void act(() => api.post(`/admin/users/${u.id}/unlock`), 'Unlocked')}><LockOpen /></button>}
              {u.id !== me?.id && (
                <button className="btn btn-outline btn-sm" onClick={() => void act(() => api.post(`/admin/users/${u.id}/status?active=${!u.active}`), u.active ? 'Deactivated' : 'Activated')}>
                  {u.active ? 'Deactivate' : 'Activate'}
                </button>
              )}
            </div>
          ) },
        ]} />
      </Card>
      {editing && <UserModal user={editing} onClose={() => setEditing(null)} onSaved={() => void reload()} />}
      {reset && <ResetModal user={reset} onClose={() => setReset(null)} />}
      {activity && <ActivityModal user={activity} onClose={() => setActivity(null)} />}
    </>
  );
}

function UserModal({ user, onClose, onSaved }: { user: Partial<UserView>; onClose: () => void; onSaved: () => void }) {
  const toast = useToast();
  const roles = useApi<RoleView[]>('/admin/roles');
  const [empQ, setEmpQ] = useState('');
  const employees = useApi<EmployeeOption[]>('/admin/users/employee-options', { q: empQ });
  const [f, setF] = useState({
    username: user.username ?? '', fullName: user.fullName ?? '', email: user.email ?? '', mobile: user.mobile ?? '', employeeId: user.employeeId ? String(user.employeeId) : '',
    dataScope: user.dataScope ?? 'SELF', preferredLanguage: user.preferredLanguage ?? 'en', otpChannel: user.otpChannel ?? 'EMAIL', password: '',
  });
  const [sel, setSel] = useState<string[]>(user.roles ?? []);
  const save = async () => {
    const body = { ...f, employeeId: f.employeeId ? Number(f.employeeId) : null, roleCodes: sel, email: f.email || null, mobile: f.mobile || null, password: f.password || null };
    try {
      if (user.id) await api.put(`/admin/users/${user.id}`, body);
      else await api.post('/admin/users', body);
      toast('User saved', 'success');
      onSaved();
      onClose();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const set = (k: keyof typeof f) => (v: string) => setF({ ...f, [k]: v });
  return (
    <Modal title={user.id ? `Edit ${user.username}` : 'New user'} onClose={onClose} wide footer={<><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>}>
      <div className="form-grid">
        <TextField label="Username" required value={f.username} onChange={set('username')} disabled={!!user.id} />
        <TextField label="Full name" required value={f.fullName} onChange={set('fullName')} />
        <TextField label="E-mail (receives sign-in codes)" type="email" value={f.email} onChange={set('email')} />
        <TextField label="Mobile" value={f.mobile} onChange={set('mobile')} />
        <TextField label="Find employee" value={empQ} onChange={setEmpQ} placeholder="Employee no. or name" />
        <SelectField label="Linked employee record" value={f.employeeId} onChange={set('employeeId')} placeholder="— None (service account) —"
          options={(employees.data ?? []).map((e) => ({ value: String(e.id), label: `${e.employeeNo} — ${e.name}${e.hasAccount && String(e.id) !== f.employeeId ? ' (has account)' : ''}` }))} />
        <SelectField label="Data scope" required value={f.dataScope} onChange={set('dataScope')} placeholder={null}
          options={[{ value: 'SELF', label: 'Own records only' }, { value: 'DIVISION', label: 'Own division' }, { value: 'ALL', label: 'Whole institution' }]} />
        <SelectField label="Sign-in code via" value={f.otpChannel} onChange={set('otpChannel')} placeholder={null} options={[{ value: 'EMAIL', label: 'E-mail' }, { value: 'SMS', label: 'SMS' }]} />
        <SelectField label="Language" value={f.preferredLanguage} onChange={set('preferredLanguage')} placeholder={null} options={[{ value: 'en', label: 'English' }, { value: 'si', label: 'සිංහල' }, { value: 'ta', label: 'தமிழ்' }]} />
        {!user.id && <TextField label="Initial password" type="password" value={f.password} onChange={set('password')} help="Min. 10 chars with upper/lower case, digit and symbol" />}
        <div className="field span-2">
          <label>Roles</label>
          <div className="row">
            {(roles.data ?? []).filter((r) => r.active).map((r) => (
              <label key={r.code} className="checkbox"><input type="checkbox" checked={sel.includes(r.code)} onChange={() => setSel(sel.includes(r.code) ? sel.filter((x) => x !== r.code) : [...sel, r.code])} /> {r.name}</label>
            ))}
          </div>
        </div>
      </div>
    </Modal>
  );
}

function ResetModal({ user, onClose }: { user: UserView; onClose: () => void }) {
  const toast = useToast();
  const [pw, setPw] = useState('');
  const save = async () => {
    try {
      await api.post(`/admin/users/${user.id}/reset-password`, { newPassword: pw });
      toast('Password reset; the user’s sessions were revoked', 'success');
      onClose();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  return (
    <Modal title={`Reset password — ${user.username}`} onClose={onClose} footer={<><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Reset</button></>}>
      <TextField label="New temporary password" type="password" value={pw} onChange={setPw} help="Share it securely; ask the user to change it after signing in." />
    </Modal>
  );
}

function ActivityModal({ user, onClose }: { user: UserView; onClose: () => void }) {
  const { data, loading } = useApi<Page<AuditRow>>(`/admin/users/${user.id}/activity`, { size: 50 });
  return (
    <Modal title={`Activity — ${user.fullName}`} onClose={onClose} wide>
      <DataTable<AuditRow> rows={data?.content} loading={loading} columns={[
        { key: 'occurredAt', header: 'When', render: (r) => <span className="nowrap">{fmtDateTime(r.occurredAt)}</span> },
        { key: 'action', header: 'Action', render: (r) => <Badge>{r.action}</Badge> },
        { key: 'summary', header: 'Summary' },
        { key: 'ipAddress', header: 'IP' },
      ]} />
    </Modal>
  );
}
