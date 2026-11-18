import axios, { AxiosError, AxiosRequestConfig } from 'axios';

/**
 * Token handling: the access token lives in memory (mirrored to sessionStorage so a page reload in the same tab keeps
 * the session); the refresh token is in sessionStorage only. Nothing is written to localStorage. Tokens are cleared on
 * sign-out, idle timeout, or a failed refresh.
 */
const ACCESS = 'hrms.at';
const REFRESH = 'hrms.rt';

let accessToken: string | null = sessionStorage.getItem(ACCESS);
let onSessionExpired: (() => void) | null = null;

export const tokens = {
  get access() {
    return accessToken;
  },
  get refresh() {
    return sessionStorage.getItem(REFRESH);
  },
  set(access: string, refresh: string) {
    accessToken = access;
    sessionStorage.setItem(ACCESS, access);
    sessionStorage.setItem(REFRESH, refresh);
  },
  clear() {
    accessToken = null;
    sessionStorage.removeItem(ACCESS);
    sessionStorage.removeItem(REFRESH);
  },
};

export function setSessionExpiredHandler(fn: () => void) {
  onSessionExpired = fn;
}

export const api = axios.create({ baseURL: '/api', timeout: 60000 });

api.interceptors.request.use((cfg) => {
  if (accessToken) cfg.headers.Authorization = `Bearer ${accessToken}`;
  cfg.headers['Accept-Language'] = document.documentElement.lang || 'en';
  return cfg;
});

let refreshing: Promise<boolean> | null = null;

async function refreshTokens(): Promise<boolean> {
  const rt = tokens.refresh;
  if (!rt) return false;
  try {
    const { data } = await axios.post('/api/auth/refresh', { refreshToken: rt });
    tokens.set(data.accessToken, data.refreshToken);
    return true;
  } catch {
    return false;
  }
}

api.interceptors.response.use(
  (r) => r,
  async (error: AxiosError) => {
    const original = error.config as AxiosRequestConfig & { _retry?: boolean };
    const isAuthCall = original?.url?.startsWith('/auth/');
    if (error.response?.status === 401 && original && !original._retry && !isAuthCall) {
      original._retry = true;
      refreshing = refreshing ?? refreshTokens().finally(() => (refreshing = null));
      if (await refreshing) return api(original);
      tokens.clear();
      onSessionExpired?.();
    }
    return Promise.reject(error);
  },
);

export interface ApiErrorBody {
  status: number;
  code: string;
  message: string;
  fieldErrors?: Record<string, string> | null;
}

/** Human-readable message from any API error. */
export function errorMessage(e: unknown): string {
  const ax = e as AxiosError<ApiErrorBody>;
  const body = ax?.response?.data;
  if (body && typeof body === 'object' && 'message' in body) {
    const fe = body.fieldErrors ? Object.entries(body.fieldErrors).map(([k, v]) => `${k}: ${v}`).join('; ') : '';
    return fe ? `${body.message} — ${fe}` : body.message;
  }
  if (ax?.code === 'ECONNABORTED') return 'The server took too long to respond.';
  if (ax?.message === 'Network Error') return 'Cannot reach the server. Check your connection.';
  return (e as Error)?.message ?? 'Unexpected error';
}

export function fieldErrors(e: unknown): Record<string, string> {
  return (e as AxiosError<ApiErrorBody>)?.response?.data?.fieldErrors ?? {};
}

/** Downloads a binary response (exports, documents) using the authenticated client. */
export async function download(url: string, opts: { method?: 'get' | 'post'; body?: unknown; filename?: string } = {}) {
  const res = await api.request({ url, method: opts.method ?? 'get', data: opts.body, responseType: 'blob' });
  const cd = (res.headers['content-disposition'] as string | undefined) ?? '';
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(cd);
  const name = opts.filename ?? (m ? decodeURIComponent(m[1]) : 'download');
  const href = URL.createObjectURL(res.data as Blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(href), 2000);
}
