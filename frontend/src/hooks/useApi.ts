import { useCallback, useEffect, useRef, useState } from 'react';
import { api, errorMessage } from '@/api/client';

/**
 * Minimal data-fetching hook: GETs `url` (skipped when null) and re-fetches when url or deps change.
 * Usage: const { data, loading, error, reload } = useApi<Employee[]>('/employees');
 */
export function useApi<T>(url: string | null, params?: Record<string, unknown>) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState<boolean>(!!url);
  const [error, setError] = useState<string | null>(null);
  const key = url + JSON.stringify(params ?? {});
  const seq = useRef(0);

  const load = useCallback(async () => {
    if (!url) {
      setLoading(false);
      return;
    }
    const mine = ++seq.current;
    setLoading(true);
    setError(null);
    try {
      const clean = Object.fromEntries(Object.entries(params ?? {}).filter(([, v]) => v !== '' && v !== null && v !== undefined));
      const res = await api.get<T>(url, { params: clean });
      if (mine === seq.current) setData(res.data);
    } catch (e) {
      if (mine === seq.current) setError(errorMessage(e));
    } finally {
      if (mine === seq.current) setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  useEffect(() => {
    void load();
  }, [load]);

  return { data, loading, error, reload: load, setData };
}
