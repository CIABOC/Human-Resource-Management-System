import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Empty, Loading } from '@/components/ui';
import { fmtDateTime } from '@/components/DataTable';
import { AppraisalShell, Chip, Note } from './shared';

interface Row { moduleCode: string; moduleLabel: string; dataLabel: string; lastSyncAt?: string; lastSuccessAt?: string; status: string; lastMessage?: string }
interface Screen { modules: Row[]; failures: { moduleCode: string; moduleLabel: string; failedAt: string; reason: string }[] }

/** Screen 8: system administrator only; shows technical status, never appraisal content. */
export default function SyncPage() {
  const toast = useToast();
  const { data, loading, error, setData } = useApi<Screen>('/appraisal/sync');
  const [busy, setBusy] = useState<string | null>(null);
  const sync = async (code: string) => {
    setBusy(code);
    try {
      const { data: d } = await api.post<Screen>(code === 'all' ? '/appraisal/sync/all' : `/appraisal/sync/${code}`);
      setData(d);
      const failed = d.modules.filter((m) => m.status === 'FAILED');
      toast(failed.length ? `${failed.length} module(s) failed to synchronize` : 'Synchronization complete', failed.length ? 'error' : 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };
  const failed = data?.modules.filter((m) => m.status === 'FAILED') ?? [];
  return (
    <AppraisalShell title="HRMS synchronization">
      <div className="apr-chips"><Chip>Administrator only: no appraisal content is shown</Chip></div>
      {error && <Alert kind="danger">{error}</Alert>}
      {failed.map((m) => (
        <Note key={m.moduleCode} kind="danger">{m.moduleLabel} synchronization failed. Last successful sync {fmtDateTime(m.lastSuccessAt) || 'never'}. Retry, or check the data mapping.</Note>
      ))}
      {loading && !data ? <Loading /> : data && (
        <>
          <Card title="Connected HRMS modules" accent>
            <div className="apr-table-wrap">
              <table className="apr-table">
                <thead><tr><th>Module</th><th>Data</th><th>Last sync</th><th>Status</th><th aria-label="Action" /></tr></thead>
                <tbody>{data.modules.map((m) => (
                  <tr key={m.moduleCode}>
                    <td>{m.moduleLabel}</td><td>{m.dataLabel}{m.lastMessage && <div className="muted" style={{ fontSize: 11 }}>{m.lastMessage}</div>}</td>
                    <td>{m.lastSyncAt ? fmtDateTime(m.lastSyncAt) : 'Never'}</td>
                    <td><Chip tone={m.status === 'SUCCESS' ? 'success' : m.status === 'FAILED' ? 'danger' : 'muted'}>{m.status === 'NEVER' ? 'Not synced' : m.status.charAt(0) + m.status.slice(1).toLowerCase()}</Chip></td>
                    <td><button className="btn btn-outline btn-sm" disabled={!!busy} onClick={() => void sync(m.moduleCode)}>{busy === m.moduleCode ? 'Syncing…' : 'Sync now'}</button></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          </Card>
          <Card title="Failed synchronizations" accent>
            {data.failures.length === 0 ? <Empty text="No failed synchronizations." /> : (
              <table className="apr-table">
                <thead><tr><th>Module</th><th>Time</th><th>Reason</th></tr></thead>
                <tbody>{data.failures.map((f, i) => <tr key={i}><td>{f.moduleLabel}</td><td>{fmtDateTime(f.failedAt)}</td><td>{f.reason}</td></tr>)}</tbody>
              </table>
            )}
          </Card>
          <div className="row">
            <Link className="btn btn-outline" to="/admin/settings">Edit data mapping</Link>
            <button className="btn btn-navy" disabled={!!busy} onClick={() => void sync('all')}>{busy === 'all' ? 'Syncing…' : 'Sync all'}</button>
          </div>
        </>
      )}
    </AppraisalShell>
  );
}
