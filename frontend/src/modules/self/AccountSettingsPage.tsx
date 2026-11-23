import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { KeyRound, LogOut, ShieldCheck } from 'lucide-react';
import { api, errorMessage, tokens } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, PageHeader, SelectField } from '@/components/ui';
import { DataTable, fmtDateTime } from '@/components/DataTable';
import { applyTheme } from '@/theme';

interface Preferences {
  preferredTheme: 'LIGHT' | 'DARK';
  notifySystem: boolean;
  notifyEmail: boolean;
  notifySms: boolean;
}

interface Session { id: number; createdAt: string; expiresAt: string }
interface LoginRecord { occurredAt: string; action: string; ipAddress?: string; summary?: string }

const DEFAULTS: Preferences = {
  preferredTheme: 'LIGHT',
  notifySystem: true,
  notifyEmail: true,
  notifySms: true,
};

export default function AccountSettingsPage() {
  const { logout } = useAuth();
  const toast = useToast();
  const [preferences, setPreferences] = useState<Preferences>(DEFAULTS);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [history, setHistory] = useState<LoginRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = async () => {
    setError(null);
    try {
      const [pref, sess, logins] = await Promise.all([
        api.get<Preferences>('/account/preferences'),
        api.get<Session[]>('/account/sessions'),
        api.get<LoginRecord[]>('/account/login-history'),
      ]);
      const preferredTheme = applyTheme(pref.data.preferredTheme);
      setPreferences({ ...pref.data, preferredTheme });
      setSessions(sess.data);
      setHistory(logins.data);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void reload(); }, []);

  const updatePreference = async (updates: Partial<Preferences>) => {
    const next = { ...preferences, ...updates };
    setPreferences(next);
    if (updates.preferredTheme) applyTheme(updates.preferredTheme);
    setBusy(true);
    try {
      const { data } = await api.put<Preferences>('/account/preferences', updates);
      const preferredTheme = applyTheme(data.preferredTheme);
      setPreferences({ ...data, preferredTheme });
      toast('Account preferences saved', 'success');
    } catch (e) {
      setPreferences(preferences);
      applyTheme(preferences.preferredTheme);
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const logoutEverywhere = async () => {
    setBusy(true);
    try {
      await api.post('/account/sessions/logout-all');
      tokens.clear();
      await logout('You have signed out from all devices.');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Account settings" subtitle="Manage your preferences and account security." />
      {error && <Alert kind="danger">{error}</Alert>}
      <div className="grid grid-2">
        <Card title="Preferences" accent>
          <div className="stack">
            <SelectField label="Theme" value={preferences.preferredTheme}
              onChange={(v) => void updatePreference({ preferredTheme: v as Preferences['preferredTheme'] })}
              placeholder={null} options={[
                { value: 'LIGHT', label: 'Light' }, { value: 'DARK', label: 'Dark' },
              ]} />
            <h3>Notifications</h3>
            <label className="row"><input type="checkbox" checked={preferences.notifySystem} disabled={busy || loading}
              onChange={(e) => void updatePreference({ notifySystem: e.target.checked })} /> In-app notifications</label>
            <label className="row"><input type="checkbox" checked={preferences.notifyEmail} disabled={busy || loading}
              onChange={(e) => void updatePreference({ notifyEmail: e.target.checked })} /> E-mail notifications</label>
            <label className="row"><input type="checkbox" checked={preferences.notifySms} disabled={busy || loading}
              onChange={(e) => void updatePreference({ notifySms: e.target.checked })} /> SMS notifications</label>
          </div>
        </Card>

        <Card title="Account security" accent>
          <div className="stack">
            <p className="muted">Use a strong password and review your recent sign-ins and sessions regularly.</p>
            <Link className="btn btn-outline" to="/account/password"><KeyRound /> Change password</Link>
            <Alert kind="info"><ShieldCheck /> Multi-factor authentication is unavailable for now. Centralized CIABOC SSO/IAM integration is a future integration point.</Alert>
            <button className="btn btn-danger" disabled={busy || loading || sessions.length === 0} onClick={() => void logoutEverywhere()}>
              <LogOut /> Sign out from all devices
            </button>
          </div>
        </Card>

        <Card title="Active sessions" pad={false}>
          <DataTable<Session> rows={sessions} loading={loading} empty="No active sessions." columns={[
            { key: 'id', header: 'Session', render: (s) => <span className="mono">#{s.id}</span> },
            { key: 'createdAt', header: 'Signed in', render: (s) => fmtDateTime(s.createdAt) },
            { key: 'expiresAt', header: 'Expires', render: (s) => fmtDateTime(s.expiresAt) },
          ]} />
        </Card>

        <Card title="Login history" pad={false}>
          <DataTable<LoginRecord> rows={history} loading={loading} empty="No sign-ins recorded." columns={[
            { key: 'occurredAt', header: 'Date', render: (r) => fmtDateTime(r.occurredAt) },
            { key: 'action', header: 'Result', render: (r) => r.action === 'LOGIN' ? 'Successful' : 'Failed' },
            { key: 'ipAddress', header: 'IP address', render: (r) => r.ipAddress || '—' },
          ]} />
        </Card>
      </div>
    </>
  );
}
