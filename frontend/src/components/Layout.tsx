import { ReactNode, useEffect, useRef, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Bell, KeyRound, LogOut, Menu, Settings, UserRound } from 'lucide-react';
import { useAuth } from '@/auth/AuthContext';
import { api } from '@/api/client';
import { modules } from '@/modules/registry';
import { NAV_SECTIONS, NavItem } from '@/modules/types';

const SECTION_FALLBACK: Record<string, string> = {
  overview: 'Overview', self: 'My services', organization: 'Organization', people: 'People', time: 'Time & attendance',
  compensation: 'Pay & benefits', talent: 'Talent', insight: 'Insight', administration: 'Administration',
};

export function Layout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { can } = useAuth();
  const [open, setOpen] = useState(false);
  const loc = useLocation();
  useEffect(() => setOpen(false), [loc.pathname]);

  const items: NavItem[] = modules.flatMap((m) => m.nav).filter((n) => !n.anyOf || n.anyOf.length === 0 || can(...n.anyOf));

  return (
    <div className="app-shell">
      <aside className={`sidebar ${open ? 'open' : ''}`} aria-label="Main navigation">
        <Link to="/" className="sidebar-brand" style={{ textDecoration: 'none' }}>
          <img src="/emblem.svg" alt="" />
          <div>
            <div className="t1">CIABOC</div>
            <div className="t2">Human Resources</div>
          </div>
        </Link>
        <nav>
          {NAV_SECTIONS.map((s) => {
            const inSection = items.filter((i) => i.section === s).sort((a, b) => a.order - b.order);
            if (inSection.length === 0) return null;
            return (
              <div className="nav-section" key={s}>
                <div className="nav-section-title">{t(`nav.${s}`, SECTION_FALLBACK[s])}</div>
                {inSection.map((i) => (
                  <NavLink key={i.path} to={i.path} end={i.path === '/'} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                    <i.icon aria-hidden />
                    <span>{t(i.label, i.fallback)}</span>
                  </NavLink>
                ))}
              </div>
            );
          })}
        </nav>
        <div className="sidebar-footer">CIABOC HRMS · v0.1 · Official</div>
      </aside>
      <div className="main">
        <Topbar onMenu={() => setOpen((o) => !o)} />
        <main className="content" id="main">{children}</main>
      </div>
    </div>
  );
}

function Topbar({ onMenu }: { onMenu: () => void }) {
  const { t, i18n } = useTranslation();
  const { me, logout } = useAuth();
  const nav = useNavigate();
  const [unread, setUnread] = useState(0);
  const [menu, setMenu] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let alive = true;
    const poll = async () => {
      try {
        const { data } = await api.get<{ count: number }>('/notifications/unread-count');
        if (alive) setUnread(data.count);
      } catch {
        /* ignore */
      }
    };
    void poll();
    const id = window.setInterval(poll, 60_000);
    const onRead = () => void poll();
    window.addEventListener('hrms:notifications-changed', onRead);
    return () => {
      alive = false;
      window.clearInterval(id);
      window.removeEventListener('hrms:notifications-changed', onRead);
    };
  }, []);

  useEffect(() => {
    const close = (e: MouseEvent) => menuRef.current && !menuRef.current.contains(e.target as Node) && setMenu(false);
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  const setLang = async (lng: 'en' | 'si' | 'ta') => {
    await i18n.changeLanguage(lng);
    try {
      await api.put('/account/preferences', { preferredLanguage: lng });
    } catch {
      /* preference is best-effort */
    }
  };

  const initials = (me?.fullName ?? '?').split(/\s+/).filter(Boolean).slice(0, 2).map((s) => s[0]).join('').toUpperCase();

  return (
    <header className="topbar">
      <button className="icon-btn menu-btn" onClick={onMenu} aria-label="Toggle navigation"><Menu size={18} /></button>
      <div className="org">{t('app.org', 'Commission to Investigate Allegations of Bribery or Corruption')}</div>
      <div className="spacer" />
      <div className="lang-switch" role="group" aria-label="Language">
        {(['en', 'si', 'ta'] as const).map((l) => (
          <button key={l} className={i18n.language === l ? 'active' : ''} onClick={() => void setLang(l)} lang={l}>
            {l === 'en' ? 'EN' : l === 'si' ? 'සිං' : 'தமி'}
          </button>
        ))}
      </div>
      <button className="icon-btn" onClick={() => nav('/notifications')} aria-label={`Notifications (${unread} unread)`}>
        <Bell size={18} />
        {unread > 0 && <span className="dot">{unread > 99 ? '99+' : unread}</span>}
      </button>
      <div style={{ position: 'relative' }} ref={menuRef}>
        <button className="user-chip" onClick={() => setMenu((m) => !m)} aria-haspopup="menu" aria-expanded={menu}>
          <span className="avatar">{initials}</span>
          <span style={{ textAlign: 'left' }}>
            <div className="n">{me?.fullName}</div>
            <div className="r">{me?.roles.map((r) => r.replace(/_/g, ' ').toLowerCase()).join(', ')}</div>
          </span>
        </button>
        {menu && (
          <div className="dropdown" role="menu">
            {me?.employeeId && (
              <button role="menuitem" onClick={() => { setMenu(false); nav('/me'); }}><UserRound size={16} /> {t('profile.title', 'My profile')}</button>
            )}
            <button role="menuitem" onClick={() => { setMenu(false); nav('/account/password'); }}><KeyRound size={16} /> Change password</button>
            <button role="menuitem" onClick={() => { setMenu(false); nav('/account/settings'); }}><Settings size={16} /> Account settings</button>
            <button role="menuitem" onClick={() => void logout()}><LogOut size={16} /> {t('auth.signOut', 'Sign out')}</button>
          </div>
        )}
      </div>
    </header>
  );
}
