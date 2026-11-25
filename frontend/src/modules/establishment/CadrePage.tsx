import { Fragment, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronDown, ChevronRight, Download, History, Pencil, Plus } from 'lucide-react';
import { download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, KpiCard, Modal, PageHeader, SelectField, Tabs, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { OrgUnitSelect } from '@/components/pickers';
import { ChangeModal, ChangeTarget } from './ChangeForms';

export interface CadreRow {
  designationId: number; designationCode: string; designationName: string; grade?: string; orgUnitCode: string; orgUnitName: string;
  approved: number; proposed?: number | null; occupied: number; vacant: number; excess: number; filled: number; acting: number;
  level?: number | null; location?: string | null; division?: string | null; positionCategory?: string | null; dyingCadre: boolean; statutory: boolean;
}
interface ReconLine { scope: string; refCode: string; label: string; controlTotal: number; matrixTotal: number; difference: number }
interface Recon { organizationControl: number; matrixTotal: number; statutoryExcluded: number; units: ReconLine[]; designations: ReconLine[]; unitsWithoutControl: ReconLine[] }

type GroupBy = 'designation' | 'division' | 'grade' | 'unit' | 'none';
interface Totals { approved: number; filled: number; acting: number; vacant: number; excess: number }
const sum = (rows: CadreRow[]): Totals => rows.reduce((a, r) => ({
  approved: a.approved + r.approved, filled: a.filled + r.filled, acting: a.acting + r.acting, vacant: a.vacant + r.vacant, excess: a.excess + r.excess,
}), { approved: 0, filled: 0, acting: 0, vacant: 0, excess: 0 });

/** Cadre vs actual (REQ 6), cadre history (6.7) and reconciliation with the control totals (6.11). */
export default function CadrePage() {
  const { can } = useAuth();
  const [tab, setTab] = useState<'cadre' | 'recon'>('cadre');
  const [target, setTarget] = useState<ChangeTarget | null>(null);
  return (
    <>
      <PageHeader eyebrow="Establishment" title="Cadre vs Actual"
        subtitle="Vacant = Approved − Filled − Acting. Excess = holders above the approved cadre. Quantities come from approved cadre decisions, never hard-coded."
        actions={can('EST_MANAGE') && <button className="btn btn-gold" onClick={() => setTarget({ type: 'CADRE_SET' })}><Plus /> Cadre change request</button>} />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'cadre', label: 'Cadre vs actual' }, { key: 'recon', label: 'Reconciliation' }]} />
      {tab === 'cadre' ? <CadreTable onPropose={setTarget} /> : <Reconciliation />}
      {target && <ChangeModal target={target} onClose={() => setTarget(null)} />}
    </>
  );
}

function CadreTable({ onPropose }: { onPropose: (t: ChangeTarget) => void }) {
  const { can } = useAuth();
  const toast = useToast();
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const [unit, setUnit] = useState('');
  const [groupBy, setGroupBy] = useState<GroupBy>('designation');
  const [category, setCategory] = useState('');
  const [location, setLocation] = useState('');
  const [onlyGaps, setOnlyGaps] = useState('');
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [hist, setHist] = useState<CadreRow | null>(null);
  const { data, loading, error } = useApi<CadreRow[]>('/establishment/cadre', { asOf, orgUnitCode: unit });

  const rows = useMemo(() => (data ?? []).filter((r) =>
    (!category || (r.positionCategory ?? '—') === category) && (!location || (r.location ?? '—') === location)
    && (!onlyGaps || (onlyGaps === 'vacant' ? r.vacant > 0 : onlyGaps === 'excess' ? r.excess > 0 : r.proposed != null))), [data, category, location, onlyGaps]);
  const totals = useMemo(() => sum(rows), [rows]);
  const categories = useMemo(() => [...new Set((data ?? []).map((r) => r.positionCategory ?? '—'))].sort(), [data]);
  const locations = useMemo(() => [...new Set((data ?? []).map((r) => r.location ?? '—'))].sort(), [data]);

  const groups = useMemo(() => {
    if (groupBy === 'none') return [{ key: 'all', label: 'All lines', rows, level: 0 }];
    const m = new Map<string, { key: string; label: string; rows: CadreRow[]; level: number }>();
    for (const r of rows) {
      const [key, label, level] = groupBy === 'designation' ? [String(r.designationId), r.designationName, r.level ?? 99]
        : groupBy === 'division' ? [r.division ?? '—', r.division ?? '—', 0]
        : groupBy === 'grade' ? [r.grade ?? '—', r.grade ?? 'No grade', 0]
        : [r.orgUnitCode, r.orgUnitName, 0];
      if (!m.has(key)) m.set(key, { key, label, rows: [], level });
      m.get(key)!.rows.push(r);
    }
    return [...m.values()].sort((a, b) => a.level - b.level || a.label.localeCompare(b.label));
  }, [rows, groupBy]);

  const pct = totals.approved ? Math.round(((totals.filled + totals.acting) / totals.approved) * 100) : 0;
  const toggle = (k: string) => setOpen((s) => { const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  const exportXlsx = (code: string, body: Record<string, unknown>) => void download(`/reports/${code}/export?format=xlsx`, { method: 'post', body }).catch((e) => toast(errorMessage(e), 'error'));

  return (
    <>
      <div className="grid grid-4" style={{ marginBottom: 18 }}>
        <KpiCard label="Approved cadre" value={totals.approved} tone="navy" />
        <KpiCard label="Filled + acting" value={`${totals.filled} + ${totals.acting}`} hint={`${pct}% utilised`} tone="green" />
        <KpiCard label="Vacant" value={totals.vacant} tone="red" />
        <KpiCard label="Excess" value={totals.excess} tone="gold" />
      </div>
      <div className="filters">
        <TextField label="As at" type="date" value={asOf} onChange={setAsOf} />
        <OrgUnitSelect label="Division / unit (incl. sub-units)" value={unit} onChange={setUnit} placeholder="— All units —" />
        <SelectField label="Group by" value={groupBy} onChange={(v) => setGroupBy(v as GroupBy)} placeholder={null}
          options={[{ value: 'designation', label: 'Designation' }, { value: 'division', label: 'Division' }, { value: 'unit', label: 'Unit' }, { value: 'grade', label: 'Grade' }, { value: 'none', label: 'No grouping' }]} />
        <SelectField label="Position category" value={category} onChange={setCategory} placeholder="— All —" options={categories.map((c) => ({ value: c, label: c }))} />
        <SelectField label="Location" value={location} onChange={setLocation} placeholder="— All —" options={locations.map((c) => ({ value: c, label: c }))} />
        <SelectField label="Show" value={onlyGaps} onChange={setOnlyGaps} placeholder="— All lines —"
          options={[{ value: 'vacant', label: 'With vacancies' }, { value: 'excess', label: 'With excess' }, { value: 'proposed', label: 'With pending proposals' }]} />
        <div className="field" style={{ justifyContent: 'flex-end' }}>
          <div className="row" style={{ gap: 6 }}>
            <button className="btn btn-outline" onClick={() => exportXlsx('EST_CADRE_VACANCY', { asOf, orgUnitCode: unit || null })}><Download /> Excel</button>
            <button className="btn btn-outline" onClick={() => exportXlsx('EST_CADRE_APPROVED', { asOf, orgUnitCode: unit || null })}><History /> Cadre as at</button>
          </div>
        </div>
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>{groupBy === 'none' ? 'Line' : groupBy[0].toUpperCase() + groupBy.slice(1)}</th>
                <th style={{ textAlign: 'right' }}>Approved</th><th style={{ textAlign: 'right' }}>Filled</th><th style={{ textAlign: 'right' }}>Acting</th>
                <th style={{ textAlign: 'right' }}>Vacant</th><th style={{ textAlign: 'right' }}>Excess</th><th style={{ width: 140 }}>Utilisation</th><th />
              </tr>
            </thead>
            <tbody>
              {loading && !data && <tr><td colSpan={8} className="muted">Loading…</td></tr>}
              {!loading && rows.length === 0 && <tr><td colSpan={8} className="muted">No cadre lines match.</td></tr>}
              {groups.map((g) => {
                const t = sum(g.rows);
                const isOpen = groupBy === 'none' || open.has(g.key);
                return (
                  <Fragment key={g.key}>
                    {groupBy !== 'none' && (
                      <tr className="row-group" onClick={() => toggle(g.key)} style={{ cursor: 'pointer', background: 'var(--surface-2)' }}>
                        <td><span className="row" style={{ gap: 6 }}>{isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}<strong>{g.label}</strong>
                          <span className="faint" style={{ fontSize: 12 }}>{g.rows.length} line(s)</span>
                          {groupBy === 'designation' && g.rows[0]?.dyingCadre && <span className="cat-tag">dying</span>}</span></td>
                        <Nums t={t} />
                        <td />
                      </tr>
                    )}
                    {isOpen && g.rows.map((r) => (
                      <tr key={`${r.designationId}|${r.orgUnitCode}`}>
                        <td style={{ paddingLeft: groupBy === 'none' ? undefined : 34 }}>
                          {groupBy === 'designation' ? r.orgUnitName : <><strong>{r.designationName}</strong> <span className="faint">· {r.orgUnitName}</span></>}
                          {r.proposed != null && <span className="badge badge-gold" style={{ marginLeft: 6 }}>proposed {r.proposed}</span>}
                          {r.statutory && <span className="cat-tag gold" style={{ marginLeft: 6 }}>statutory</span>}
                        </td>
                        <Nums t={r} />
                        <td>
                          <div className="row" style={{ gap: 2, justifyContent: 'flex-end' }}>
                            {can('EMP_VIEW') && <Link className="btn btn-ghost btn-sm" to={`/employees?orgUnitCode=${encodeURIComponent(r.orgUnitCode)}&designationId=${r.designationId}`} title="Holders">Holders</Link>}
                            <button className="btn btn-ghost btn-sm" onClick={() => setHist(r)} aria-label="Cadre history" title="Cadre history"><History /></button>
                            {can('EST_MANAGE') && (
                              <button className="btn btn-ghost btn-sm" aria-label="Propose change" title="Cadre change request"
                                onClick={() => onPropose({ type: 'CADRE_SET', cadre: { designationId: r.designationId, orgUnitCode: r.orgUnitCode, approved: r.approved } })}><Pencil /></button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </Fragment>
                );
              })}
            </tbody>
            {rows.length > 0 && (
              <tfoot>
                <tr style={{ fontWeight: 700, borderTop: '2px solid var(--navy-700)' }}>
                  <td>Total</td><Nums t={totals} /><td />
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      </Card>
      {hist && <CadreHistory row={hist} onClose={() => setHist(null)} />}
    </>
  );
}

function Nums({ t }: { t: Totals }) {
  const occ = t.filled + t.acting;
  const w = (v: number) => `${t.approved ? Math.min(100, (v / t.approved) * 100) : v ? 100 : 0}%`;
  return (
    <>
      <td style={{ textAlign: 'right' }}>{t.approved}</td>
      <td style={{ textAlign: 'right' }}>{t.filled}</td>
      <td style={{ textAlign: 'right' }}>{t.acting || '—'}</td>
      <td style={{ textAlign: 'right' }}>{t.vacant > 0 ? <strong style={{ color: 'var(--danger)' }}>{t.vacant}</strong> : '0'}</td>
      <td style={{ textAlign: 'right' }}>{t.excess > 0 ? <span className="badge badge-warning">{t.excess}</span> : '—'}</td>
      <td title={`${occ} of ${t.approved}`}>
        <div className={`util ${occ > t.approved ? 'over' : ''}`} style={{ marginTop: 0 }}><span className="f" style={{ width: w(t.filled) }} /><span className="a" style={{ width: w(t.acting) }} /></div>
        <div className="faint" style={{ fontSize: 10.5 }}>{t.approved ? Math.round((occ / t.approved) * 100) : 0}%</div>
      </td>
    </>
  );
}

function Reconciliation() {
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const { data, loading, error } = useApi<Recon>('/establishment/cadre/reconciliation', { asOf });
  const cols = [
    { key: 'refCode', header: 'Code', render: (r: ReconLine) => <span className="mono">{r.refCode}</span> },
    { key: 'label', header: 'Name' },
    { key: 'controlTotal', header: 'Control total', align: 'right' as const },
    { key: 'matrixTotal', header: 'Cadre matrix', align: 'right' as const },
    { key: 'difference', header: 'Difference', align: 'right' as const, render: (r: ReconLine) => (r.difference === 0 ? <span className="badge badge-success">✓</span> : <span className="badge badge-danger">{r.difference > 0 ? '+' : ''}{r.difference}</span>) },
  ];
  const diffs = data ? [...data.units, ...data.designations, ...data.unitsWithoutControl].filter((l) => l.difference !== 0).length + (data.matrixTotal !== data.organizationControl ? 1 : 0) : 0;
  return (
    <>
      <div className="filters"><TextField label="As at" type="date" value={asOf} onChange={setAsOf} /></div>
      {error && <Alert kind="danger">{error}</Alert>}
      {data && (
        <>
          <div className="grid grid-4" style={{ marginBottom: 18 }}>
            <KpiCard label="Organization control total" value={data.organizationControl} tone="navy" />
            <KpiCard label="Cadre matrix total" value={data.matrixTotal} tone={data.matrixTotal === data.organizationControl ? 'green' : 'red'} />
            <KpiCard label="Statutory posts (excluded)" value={data.statutoryExcluded} tone="gold" />
            <KpiCard label="Differences flagged" value={diffs} tone={diffs ? 'red' : 'green'} />
          </div>
          {diffs === 0 ? <Alert kind="success">The cadre matrix reconciles with every control total.</Alert>
            : <Alert kind="warning">{diffs} difference(s) between the cadre matrix and the source control totals. Correct them through cadre change requests.</Alert>}
        </>
      )}
      <div className="grid grid-2" style={{ marginTop: 16 }}>
        <Card title="By unit" pad={false}><DataTable rows={data?.units} loading={loading} rowKey={(r) => r.refCode} columns={cols} /></Card>
        <Card title="By designation" pad={false}><DataTable rows={data?.designations} loading={loading} rowKey={(r) => r.refCode} columns={cols} /></Card>
      </div>
      {data && data.unitsWithoutControl.length > 0 && (
        <div style={{ marginTop: 16 }}><Card title="Units with cadre but no control total" pad={false}><DataTable rows={data.unitsWithoutControl} rowKey={(r) => r.refCode} columns={cols} /></Card></div>
      )}
    </>
  );
}

function CadreHistory({ row, onClose }: { row: CadreRow; onClose: () => void }) {
  const { data, loading } = useApi<{ id: number; approvedQty: number; effectiveFrom: string; effectiveTo?: string; changeId?: number }[]>(
    '/establishment/cadre/history', { designationId: row.designationId, orgUnitCode: row.orgUnitCode });
  return (
    <Modal title={`Cadre history — ${row.designationName}, ${row.orgUnitName}`} onClose={onClose}>
      <DataTable rows={data} loading={loading} columns={[
        { key: 'approvedQty', header: 'Approved', align: 'right' },
        { key: 'effectiveFrom', header: 'From', render: (r) => fmtDate(r.effectiveFrom) },
        { key: 'effectiveTo', header: 'Until', render: (r) => (r.effectiveTo ? fmtDate(r.effectiveTo) : 'Current') },
        { key: 'changeId', header: 'Change ref', render: (r) => (r.changeId ? <Link to={`/establishment/changes/${r.changeId}`}>#{r.changeId}</Link> : 'Initial') },
      ]} />
    </Modal>
  );
}
