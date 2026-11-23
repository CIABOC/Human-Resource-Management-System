import { FormEvent, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Lock, User } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { Alert } from '@/components/ui';

/** Password-based sign-in. */
export default function LoginPage() {
  const { t, i18n } = useTranslation();
  const { me, completeLogin, notice } = useAuth();
  const nav = useNavigate();
  const loc = useLocation() as { state?: { from?: string } };
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (me) return <Navigate to={loc.state?.from ?? '/'} replace />;

  const submitPassword = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.post('/auth/login', { username, password });
      await completeLogin(data.accessToken, data.refreshToken);
      setPassword('');
      nav(loc.state?.from ?? '/', { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <section className="login-hero" aria-hidden>
        <div className="row" style={{ gap: 14 }}>
          <img src="/emblem.svg" alt="" width={54} height={54} />
          <div>
            <div style={{ fontFamily: 'var(--font-display)', fontSize: 22, color: '#fff', letterSpacing: 1 }}>CIABOC</div>
            <div style={{ fontSize: 11, letterSpacing: 2, color: 'var(--gold-300)', textTransform: 'uppercase' }}>Human Resource Management</div>
          </div>
        </div>
        <div style={{ position: 'relative', zIndex: 1 }}>
          <h1>
            Integrity in every <span className="gold">record</span>.
          </h1>
          <p>
            The authoritative platform for CIABOC's establishment, people and services — secure, accountable and available in
            English, සිංහල and தமிழ்.
          </p>
        </div>
        <div style={{ fontSize: 12, color: 'rgba(238,241,248,.55)', position: 'relative', zIndex: 1 }}>
          {t('app.org', 'Commission to Investigate Allegations of Bribery or Corruption')} · Official use only
        </div>
      </section>

      <section className="login-form-wrap">
        <div className="login-card">
          <div className="row between" style={{ marginBottom: 28 }}>
            <img src="/emblem.svg" alt="CIABOC" width={40} height={40} />
            <div className="lang-switch" role="group" aria-label="Language">
              {(['en', 'si', 'ta'] as const).map((l) => (
                <button key={l} type="button" className={i18n.language === l ? 'active' : ''} onClick={() => void i18n.changeLanguage(l)} lang={l}>
                  {l === 'en' ? 'English' : l === 'si' ? 'සිංහල' : 'தமிழ்'}
                </button>
              ))}
            </div>
          </div>

          <form onSubmit={submitPassword} className="stack" noValidate>
              <div>
                <h2>{t('auth.signIn', 'Sign in')}</h2>
                <p className="muted" style={{ marginTop: 6 }}>Use your CIABOC account.</p>
              </div>
              {notice && <Alert kind="warning">{notice}</Alert>}
              {error && <Alert kind="danger">{error}</Alert>}
              <div className="field">
                <label htmlFor="u">{t('auth.username', 'Username')}</label>
                <div className="search">
                  <User />
                  <input id="u" className="input" style={{ minWidth: 0 }} autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
                </div>
              </div>
              <div className="field">
                <label htmlFor="p">{t('auth.password', 'Password')}</label>
                <div className="search">
                  <Lock />
                  <input id="p" type="password" className="input" style={{ minWidth: 0 }} autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
                </div>
              </div>
              <button className="btn btn-primary btn-block" style={{ height: 44 }} disabled={busy || !username || !password}>
                {busy ? '…' : t('auth.continue', 'Continue')}
              </button>
          </form>
        </div>
      </section>
    </div>
  );
}
