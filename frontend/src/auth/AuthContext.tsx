import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { api, setSessionExpiredHandler, tokens } from '@/api/client';
import type { Me } from '@/api/types';
import i18n from '@/i18n';
import { applyTheme } from '@/theme';

interface AuthState {
  me: Me | null;
  loading: boolean;
  /** Message shown on the login page after an involuntary sign-out. */
  notice: string | null;
  completeLogin: (accessToken: string, refreshToken: string) => Promise<void>;
  logout: (notice?: string) => Promise<void>;
  can: (...anyOf: string[]) => boolean;
  reloadMe: () => Promise<void>;
}

const Ctx = createContext<AuthState | null>(null);

const ACTIVITY_EVENTS = ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart'] as const;

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<string | null>(null);
  const lastActivity = useRef(Date.now());

  const loadMe = useCallback(async () => {
    const { data } = await api.get<Me>('/auth/me');
    const preferredTheme = applyTheme(data.preferredTheme);
    setMe({ ...data, preferredTheme });
    if (data.language && data.language !== i18n.language) await i18n.changeLanguage(data.language);
  }, []);

  const logout = useCallback(async (msg?: string) => {
    try {
      if (tokens.access) await api.post('/auth/logout', { refreshToken: tokens.refresh });
    } catch {
      /* ignore */
    }
    tokens.clear();
    setMe(null);
    setNotice(msg ?? null);
  }, []);

  useEffect(() => {
    setSessionExpiredHandler(() => {
      setMe(null);
      setNotice('Your session has expired. Please sign in again.');
    });
    (async () => {
      if (tokens.access || tokens.refresh) {
        try {
          await loadMe();
        } catch {
          tokens.clear();
        }
      }
      setLoading(false);
    })();
  }, [loadMe]);

  // Idle timeout (FR-UAM-007): sign out after the configured period without user activity.
  useEffect(() => {
    if (!me) return;
    const mark = () => (lastActivity.current = Date.now());
    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, mark, { passive: true }));
    const limitMs = Math.max(1, me.sessionIdleMinutes) * 60_000;
    const timer = window.setInterval(() => {
      if (Date.now() - lastActivity.current > limitMs) {
        void logout('You were signed out after a period of inactivity.');
      }
    }, 15_000);
    return () => {
      ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, mark));
      window.clearInterval(timer);
    };
  }, [me, logout]);

  const completeLogin = useCallback(
    async (at: string, rt: string) => {
      tokens.set(at, rt);
      lastActivity.current = Date.now();
      setNotice(null);
      await loadMe();
    },
    [loadMe],
  );

  const can = useCallback((...anyOf: string[]) => !!me && (anyOf.length === 0 || anyOf.some((p) => me.permissions.includes(p))), [me]);

  const value = useMemo<AuthState>(
    () => ({ me, loading, notice, completeLogin, logout, can, reloadMe: loadMe }),
    [me, loading, notice, completeLogin, logout, can, loadMe],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const c = useContext(Ctx);
  if (!c) throw new Error('useAuth outside AuthProvider');
  return c;
}
