import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { FileSpreadsheet, FileText, Lock, Play, Sheet } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Empty, Loading, PageHeader, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime, fmtMoney } from '@/components/DataTable';
import { DesignationSelect, OrgUnitSelect } from '@/components/pickers';

interface Column { key: string; label: string; type: string }
interface Entry { code: string; category: string; title: string; description: string; columns: Column[]; filters: string[]; sensitive: boolean }
interface Result { code: string; title: string; columns: Column[]; rows: Record<string, unknown>[]; truncated: boolean; generatedAt: string; generatedBy: string }

/** Report catalogue (SRS §8): every report respects role and data scope; exports are audit-logged (RP-01, RP-02). */
export default function ReportsPage() {
  const { t } = useTranslation();
  const { code } = useParams();
  const nav = useNavigate();
  const toast = useToast();
  const { data: catalogue, loading, error } = useApi<Entry[]>('/reports');
  const current = catalogue?.find((c) => c.code === code) ?? null;
  const [f, setF] = useState({ from: '', to: '', asOf: '', period: '', orgUnitCode: '', designationId: '', status: '' });
  const [result, setResult] = useState<Result | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => setResult(null), [code]);
  const grouped = useMemo(() => {
    const m = new Map<string, Entry[]>();
    (catalogue ?? []).forEach((e) => m.set(e.category, [...(m.get(e.category) ?? []), e]));
    return [...m.entries()];
  }, [catalogue]);

  const body = () => ({
    from: f.from || null, to: f.to || null, asOf: f.asOf || null, period: f.period || null, orgUnitCode: f.orgUnitCode || null,
    designationId: f.designationId ? Number(f.designationId) : null, status: f.status || null,
  });

  const run = async () => {
    if (!current) return;
    setBusy(true);
    try {
      const { data } = await api.post<Result>(`/reports/${current.code}/run`, body());
      setResult(data);
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const exp = (fmt: 'csv' | 'xlsx' | 'pdf') => current && void download(`/reports/${current.code}/export?format=${fmt}`, { method: 'post', body: body() }).catch((e) => toast(errorMessage(e), 'error'));
  const has = (x: string) => current?.filters.includes(x);
  const set = (k: keyof typeof f) => (v: string) => setF({ ...f, [k]: v });

  return (
    <>
      <PageHeader eyebrow={t('nav.insight', 'Insight')} title={t('reports.title', 'Reports & analytics')} subtitle="Operational and management reports. Results are limited to your role and data scope; every export is recorded." />
      {error && <Alert kind="danger">{error}</Alert>}
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(240px, 300px) 1fr', gap: 18 }} className="reports-grid">
        <Card pad={false} title="Catalogue">
          {loading && !catalogue ? <Loading /> : (
            <div style={{ padding: '6px 8px 12px' }}>
              {grouped.map(([cat, entries]) => (
                <div key={cat} style={{ marginBottom: 10 }}>
                  <div className="nav-section-title" style={{ color: 'var(--gold-700)' }}>{cat}</div>
                  {entries.map((e) => (
                    <button key={e.code} onClick={() => nav(`/reports/${e.code}`)} className="btn btn-ghost btn-block"
                      style={{ justifyContent: 'flex-start', fontWeight: e.code === code ? 700 : 500, background: e.code === code ? 'var(--gold-50)' : undefined, height: 'auto', padding: '7px 10px', whiteSpace: 'normal', textAlign: 'left' }}>
                      {e.sensitive && <Lock size={13} style={{ color: 'var(--gold-700)' }} />} {e.title}
                    </button>
                  ))}
                </div>
              ))}
              {catalogue?.length === 0 && <Empty text="No reports are available for your role." />}
            </div>
          )}
        </Card>
        <div className="stack" style={{ minWidth: 0 }}>
          {!current ? (
            <Card><Empty text="Choose a report from the catalogue." /></Card>
          ) : (
            <>
              <Card accent title={<div><h3>{current.title} {current.sensitive && <Badge tone="gold"><Lock size={11} /> Sensitive</Badge>}</h3>{current.description && <div className="muted" style={{ fontSize: 12.5 }}>{current.description}</div>}</div>}>
                <div className="filters" style={{ marginBottom: 0 }}>
                  {has('DATE_RANGE') && <><TextField label={t('common.from', 'From')} type="date" value={f.from} onChange={set('from')} /><TextField label={t('common.to', 'To')} type="date" value={f.to} onChange={set('to')} /></>}
                  {has('AS_OF') && <TextField label={t('common.asOf', 'As at')} type="date" value={f.asOf} onChange={set('asOf')} />}
                  {has('PERIOD') && <TextField label="Period" type="month" value={f.period} onChange={set('period')} />}
                  {has('ORG_UNIT') && <OrgUnitSelect label="Unit (incl. sub-units)" value={f.orgUnitCode} onChange={set('orgUnitCode')} placeholder="— All —" />}
                  {has('DESIGNATION') && <DesignationSelect value={f.designationId} onChange={set('designationId')} placeholder="— All —" />}
                  {has('STATUS') && <TextField label="Status / type" value={f.status} onChange={(v) => set('status')(v.toUpperCase())} />}
                  <button className="btn btn-primary" disabled={busy} onClick={() => void run()}><Play /> Run</button>
                  <button className="btn btn-outline" onClick={() => exp('xlsx')}><FileSpreadsheet /> Excel</button>
                  <button className="btn btn-outline" onClick={() => exp('pdf')}><FileText /> PDF</button>
                  <button className="btn btn-outline" onClick={() => exp('csv')}><Sheet /> CSV</button>
                </div>
              </Card>
              {busy && <Loading />}
              {result && !busy && (
                <Card pad={false} title={<span className="muted" style={{ fontSize: 12.5 }}>{result.rows.length} rows · generated {fmtDateTime(result.generatedAt)} by {result.generatedBy}{result.truncated ? ' · truncated to maximum rows' : ''}</span>}>
                  <DataTable rows={result.rows} rowKey={(_, i) => i} columns={result.columns.map((c) => ({
                    key: c.key, header: c.label, align: c.type === 'number' || c.type === 'money' ? 'right' as const : undefined,
                    render: (r: Record<string, unknown>) => {
                      const v = r[c.key];
                      if (v === null || v === undefined) return <span className="faint">—</span>;
                      if (c.type === 'money') return fmtMoney(v as number);
                      if (c.type === 'date') return fmtDate(String(v));
                      if (c.type === 'datetime') return fmtDateTime(String(v));
                      if (c.type === 'boolean') return v ? 'Yes' : 'No';
                      return String(v);
                    },
                  }))} />
                </Card>
              )}
            </>
          )}
        </div>
      </div>
      <style>{`@media (max-width: 860px) { .reports-grid { grid-template-columns: 1fr !important; } }`}</style>
    </>
  );
}
