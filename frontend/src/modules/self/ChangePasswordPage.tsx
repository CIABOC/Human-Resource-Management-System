import { FormEvent, useState } from 'react';
import { KeyRound } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { Alert, Card, PageHeader, TextField } from '@/components/ui';

export default function ChangePasswordPage() {
  const { logout } = useAuth();
  const [f, setF] = useState({ currentPassword: '', newPassword: '', confirm: '' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const mismatch = f.confirm.length > 0 && f.confirm !== f.newPassword;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post('/auth/change-password', { currentPassword: f.currentPassword, newPassword: f.newPassword });
      await logout('Your password was changed. Please sign in with the new password.');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Change password" subtitle="All your sessions are signed out after the change." />
      <Card accent className="" >
        <form onSubmit={submit} className="stack" style={{ maxWidth: 440 }}>
          {error && <Alert kind="danger">{error}</Alert>}
          <TextField label="Current password" type="password" autoComplete="current-password" required value={f.currentPassword} onChange={(v) => setF({ ...f, currentPassword: v })} />
          <TextField label="New password" type="password" autoComplete="new-password" required value={f.newPassword} onChange={(v) => setF({ ...f, newPassword: v })}
            help="At least 10 characters with upper and lower case letters, a digit and a symbol." />
          <TextField label="Confirm new password" type="password" autoComplete="new-password" required value={f.confirm} onChange={(v) => setF({ ...f, confirm: v })} error={mismatch ? 'Passwords do not match' : undefined} />
          <div><button className="btn btn-primary" disabled={busy || mismatch || !f.newPassword}><KeyRound /> Change password</button></div>
        </form>
      </Card>
    </>
  );
}
