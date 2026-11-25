import { FormEvent, useEffect, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Lock, ShieldCheck, User } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { Alert } from '@/components/ui';

interface Challenge {
  challengeId: string;
  deliveredTo: string;
  expiresInSeconds: number;
  devOtp?: string | null;
}

/** Two-step sign-in: password, then one-time verification code. */
export default function LoginPage() {
  const { t, i18n } = useTranslation();
  const { me, completeLogin, notice } = useAuth();
  const nav = useNavigate();
  const loc = useLocation() as { state?: { from?: string } };
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [challenge, setChallenge] = useState<Challenge | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [remaining, setRemaining] = useState(0);

  useEffect(() => {
    if (!challenge) return;
    setRemaining(challenge.expiresInSeconds);
    const id = window.setInterval(() => setRemaining((r) => Math.max(0, r - 1)), 1000);
    return () => window.clearInterval(id);
  }, [challenge]);

  if (me) return <Navigate to={loc.state?.from ?? '/'} replace />;

  const submitPassword = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.post<Challenge>('/auth/login', { username, password });
      setChallenge(data);
      setPassword('');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const submitCode = async (e: FormEvent) => {
    e.preventDefault();
    if (!challenge) return;
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.post('/auth/verify-otp', { challengeId: challenge.challengeId, code });
      await completeLogin(data.accessToken, data.refreshToken);
      nav(loc.state?.from ?? '/', { replace: true });
    } catch (err) {
      setError(errorMessage(err));
      setCode('');
    } finally {
      setBusy(false);
    }
  };

  const resend = async () => {
    if (!challenge) return;
    setError(null);
    try {
      const { data } = await api.post<Challenge>('/auth/resend-otp', { challengeId: challenge.challengeId });
      setChallenge(data);
    } catch (err) {
      setError(errorMessage(err));
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

          {!challenge ? (
            <form onSubmit={submitPassword} className="stack" noValidate>
              <div>
                <h2>{t('auth.signIn', 'Sign in')}</h2>
                <p className="muted" style={{ marginTop: 6 }}>Use your CIABOC account. A verification code will be sent to you.</p>
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
          ) : (
            <form onSubmit={submitCode} className="stack" noValidate>
              <button type="button" className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => { setChallenge(null); setError(null); setCode(''); }}>
                <ArrowLeft /> Back
              </button>
              <div>
                <h2>{t('auth.verify', 'Verify it’s you')}</h2>
                <p className="muted" style={{ marginTop: 6 }}>
                  Enter the 6-digit code sent by {challenge.deliveredTo}.
                </p>
              </div>
              {challenge.devOtp && (
                <Alert kind="info">
                  Development mode — your code is <strong className="mono">{challenge.devOtp}</strong>
                </Alert>
              )}
              {error && <Alert kind="danger">{error}</Alert>}
              <div className="field">
                <label htmlFor="otp">{t('auth.otp', 'Verification code')}</label>
                <input id="otp" className="input otp-input" inputMode="numeric" autoComplete="one-time-code" maxLength={6}
                  value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} autoFocus />
                <span className="help">
                  {remaining > 0 ? `Code expires in ${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}` : 'Code expired — request a new one.'}
                </span>
              </div>
              <button className="btn btn-gold btn-block" style={{ height: 44 }} disabled={busy || code.length < 6 || remaining === 0}>
                <ShieldCheck /> {busy ? '…' : t('auth.verify', 'Verify and sign in')}
              </button>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => void resend()}>Send a new code</button>
            </form>
          )}
        </div>
      </section>
    </div>
  );
}
