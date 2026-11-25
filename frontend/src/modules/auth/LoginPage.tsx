import { FormEvent, useEffect, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Eye, EyeOff, ShieldCheck } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { Alert, Wordmark } from '@/components/ui';

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
  const [showPw, setShowPw] = useState(false);
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

  const org = t('app.org', 'Commission to Investigate Allegations of Bribery or Corruption');

  return (
    <div className="login-page">
      <span className="login-deco ring r1" aria-hidden />
      <span className="login-deco ring r2" aria-hidden />
      <span className="login-deco diamond d1" aria-hidden />
      <span className="login-deco diamond d2" aria-hidden />

      <section className="login-hero">
        <div className="login-org">
          <img src="/emblem.png" alt="" />
          <span>{org}</span>
        </div>
        <Wordmark />
        <div className="rule" aria-hidden />
        <h1>{t('auth.heroTitle', 'CIABOC HRMS')}</h1>
        <p>{t('auth.heroSub', 'Welcome to your secure human resource management portal.')}</p>
        <div className="copy">© {new Date().getFullYear()} {org}</div>
      </section>

      <div className="login-mobile-brand" aria-hidden>
        <img src="/emblem.png" alt="" />
        <Wordmark />
      </div>

      <div className="login-top">
        <div className="lang-switch" role="group" aria-label="Language">
          {(['si', 'ta', 'en'] as const).map((l) => (
            <button key={l} type="button" className={i18n.language === l ? 'active' : ''} onClick={() => void i18n.changeLanguage(l)} lang={l}>
              {l === 'en' ? 'English' : l === 'si' ? 'සිංහල' : 'தமிழ்'}
            </button>
          ))}
        </div>
      </div>

      <section className="login-side">
        <div className="login-card">
          {!challenge ? (
            <form onSubmit={submitPassword} className="stack" style={{ gap: 22 }} noValidate>
              <h2 className="sr-only">{t('auth.signIn', 'Sign in')}</h2>
              {notice && <Alert kind="warning">{notice}</Alert>}
              {error && <Alert kind="danger">{error}</Alert>}
              <div className="field">
                <label htmlFor="u">{t('auth.username', 'Username')}</label>
                <input id="u" className="input" placeholder={t('auth.usernamePh', 'Enter your username')} autoComplete="username"
                  value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
              </div>
              <div className="field">
                <label htmlFor="p">{t('auth.password', 'Password')}</label>
                <div className="pw">
                  <input id="p" type={showPw ? 'text' : 'password'} className="input" placeholder={t('auth.passwordPh', 'Enter your password')}
                    autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
                  <button type="button" onClick={() => setShowPw((s) => !s)} aria-label={showPw ? 'Hide password' : 'Show password'} aria-pressed={showPw}>
                    {showPw ? <EyeOff /> : <Eye />}
                  </button>
                </div>
              </div>
              <button className="btn btn-primary btn-block btn-signin" style={{ marginTop: 14 }} disabled={busy || !username || !password}>
                {busy ? '…' : t('auth.signIn', 'Sign in')}
              </button>
              <p className="login-note">{t('auth.otpNote', 'A verification code will be sent to you after you sign in.')}</p>
            </form>
          ) : (
            <form onSubmit={submitCode} className="stack" style={{ gap: 20 }} noValidate>
              <button type="button" className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start', marginLeft: -10 }} onClick={() => { setChallenge(null); setError(null); setCode(''); }}>
                <ArrowLeft /> Back
              </button>
              <div>
                <h2>{t('auth.verify', 'Verify it’s you')}</h2>
                <p className="muted" style={{ marginTop: 8, fontSize: 15 }}>
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
                <span className="help" style={{ fontSize: 13 }}>
                  {remaining > 0 ? `Code expires in ${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}` : 'Code expired — request a new one.'}
                </span>
              </div>
              <button className="btn btn-primary btn-block btn-signin" disabled={busy || code.length < 6 || remaining === 0}>
                <ShieldCheck style={{ width: 20, height: 20 }} /> {busy ? '…' : t('auth.verifyBtn', 'Verify')}
              </button>
              <button type="button" className="btn btn-ghost" onClick={() => void resend()}>Send a new code</button>
            </form>
          )}
        </div>
      </section>
    </div>
  );
}
