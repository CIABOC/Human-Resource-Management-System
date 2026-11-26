import { useState } from 'react';
import { Lock } from 'lucide-react';
import { download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Empty, Loading, Modal, SelectField } from '@/components/ui';
import { fmtDateTime } from '@/components/DataTable';
import { DesignationSelect, OrgUnitSelect } from '@/components/pickers';
import type { DrillRow, Overview, Period, ResultRow } from './types';
import { AppraisalShell, Bar, Chip, Confidential, fmtNum, Note, pretty, useAprContext } from './shared';

/* Category spread: ordered categories (best first) on one hue, dark -> light (sequential, monotone lightness). */
const SEQ = ['#14213d', '#2a4a85', '#4d70b0', '#86a3d4', '#c4d3ee'];
const catColor = (i: number) => SEQ[Math.min(i, SEQ.length - 1)];

/** Screen 5: management and administrators - completion separately from aggregated results. */
export default function ManagementPage() {
  return (
    <AppraisalShell title="Management dashboard">
      <Body />
    </AppraisalShell>
  );
}

function Body() {
  const { ctx } = useAprContext();
  const [periodId, setPeriodId] = useState(String(ctx?.currentPeriodId ?? ''));
  const [orgUnitCode, setOrgUnitCode] = useState('');
  const [designationId, setDesignationId] = useState('');
  return (
    <ManagementOverview periods={ctx?.periods ?? []} periodId={periodId} setPeriodId={setPeriodId}
      orgUnitCode={orgUnitCode} setOrgUnitCode={setOrgUnitCode} designationId={designationId} setDesignationId={setDesignationId} />
  );
}

export function ManagementOverview({ periods, periodId, setPeriodId, orgUnitCode, setOrgUnitCode, designationId, setDesignationId, compact }: {
  periods: Period[]; periodId: string; setPeriodId: (v: string) => void; orgUnitCode: string; setOrgUnitCode: (v: string) => void;
  designationId: string; setDesignationId: (v: string) => void; compact?: boolean;
}) {
  const toast = useToast();
  const { data, loading, error } = useApi<Overview>('/appraisal/overview', { periodId, orgUnitCode, designationId });
  const [drill, setDrill] = useState<ResultRow | null>(null);

  const exportAs = async (format: 'pdf' | 'xlsx') => {
    try {
      await download(`/reports/APR_RESULTS/export?format=${format}`, {
        method: 'post', body: { status: data?.period?.code ?? null, orgUnitCode: orgUnitCode || null, designationId: designationId ? Number(designationId) : null },
      });
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <div className="stack" style={{ gap: 10 }}>
        <div className="apr-chips"><Confidential /></div>
        {data?.period && (
          <div className="apr-chips">
            <Chip>Reporting period: {data.period.name}</Chip>
            <Chip>Data as of {fmtDateTime(data.dataAsOf)}</Chip>
            <Chip>Aggregated view</Chip>
          </div>
        )}
      </div>
      <div className="form-grid cols-3">
        <SelectField label="Appraisal period" value={periodId} onChange={setPeriodId} placeholder="" options={periods.map((p) => ({ value: String(p.id), label: p.name }))} />
        <OrgUnitSelect label="Department" value={orgUnitCode} onChange={setOrgUnitCode} placeholder="All departments" />
        <DesignationSelect label="Position" value={designationId} onChange={setDesignationId} placeholder="All positions" />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !data ? <Loading /> : data && (
        data.totalAppraisals === 0 ? <Empty text="No appraisals in this period and selection." /> : (
          <>
            <div className="apr-funnel" aria-label="Appraisals by stage">
              {Object.entries(data.stages).map(([k, v]) => <div key={k}><b>{v}</b><span>{k}</span></div>)}
            </div>
            <div className="grid grid-2">
              <Card title="Appraisal completion (process status)" accent>
                <div className="apr-table-wrap">
                  <table className="apr-table">
                    <thead><tr><th>Department</th><th className="num">Completed</th><th className="num">Pending</th><th className="num">Overdue</th></tr></thead>
                    <tbody>{data.completion.map((c) => (
                      <tr key={c.unitCode}><td>{c.unitName}</td><td className="num">{c.completed}</td><td className="num">{c.pending}</td>
                        <td className="num" style={{ color: c.overdue ? 'var(--danger)' : undefined }}>{c.overdue}</td></tr>
                    ))}</tbody>
                    <tfoot><tr><td>All</td><td className="num">{data.completed}</td><td className="num">{data.pending}</td><td className="num">{data.overdue}</td></tr></tfoot>
                  </table>
                </div>
              </Card>
              <Card title="Performance results (aggregated)" accent>
                <div className="apr-table-wrap">
                  <table className="apr-table">
                    <thead><tr><th>Department</th><th>Average achievement</th><th>Category spread</th></tr></thead>
                    <tbody>{data.results.map((r) => (
                      <tr key={r.unitCode}>
                        <td>{r.unitName}<div className="muted" style={{ fontSize: 11 }}>{r.finalized} finalized</div></td>
                        <td>{r.averageScore != null ? <><Bar value={r.averageScore} /> <span style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }}>{fmtNum(r.averageScore)}</span></> : <span className="muted">No results yet</span>}</td>
                        <td><SpreadBar row={r} categories={data.categories} /></td>
                      </tr>
                    ))}</tbody>
                    <tfoot><tr><td>All</td><td>{data.averageScore != null ? fmtNum(data.averageScore) : '—'}</td><td /></tr></tfoot>
                  </table>
                </div>
                <div className="apr-legend" style={{ marginTop: 10 }}>
                  {data.categorySpread.map((s, i) => <span key={s.category}><i style={{ background: catColor(i) }} />{s.category}: <b>{s.count}</b></span>)}
                </div>
              </Card>
            </div>
            <p className="muted" style={{ fontSize: 12.5, margin: 0 }}>Completion shows how far the appraisal process has progressed. It does not show how well employees performed.</p>
            {!compact && (
              <div className="row">
                <button className="btn btn-outline btn-sm" disabled={!data.canDrillDown} onClick={() => setDrill(data.results[0] ?? null)}>Drill down</button>
                <button className="btn btn-outline btn-sm" disabled={!data.canExport} onClick={() => void exportAs('pdf')}>Export PDF</button>
                <button className="btn btn-outline btn-sm" disabled={!data.canExport} onClick={() => void exportAs('xlsx')}>Export Excel</button>
                <Chip><Lock size={11} aria-hidden /> Restricted: {data.canDrillDown ? 'authorized for your departments' : 'requires authorization'}</Chip>
              </div>
            )}
            {!compact && <Note>Results use the same calculation as individual appraisals (KPI {fmtNum(data.kpiWeight)}%, behaviour {fmtNum(data.behaviourWeight)}%). Drill-down shows only the departments you are authorized to view.</Note>}
            {drill && <DrillDown periodId={periodId} results={data.results} initial={drill} designationId={designationId} onClose={() => setDrill(null)} />}
          </>
        )
      )}
    </>
  );
}

function SpreadBar({ row, categories }: { row: ResultRow; categories: string[] }) {
  const total = row.spread.reduce((s, x) => s + x.count, 0);
  if (!total) return <span className="muted">—</span>;
  const label = row.spread.filter((s) => s.count).map((s) => `${s.category} ${s.count}`).join(', ');
  return (
    <span className="apr-spread" role="img" aria-label={`${row.unitName}: ${label}`} title={label}>
      {row.spread.map((s) => s.count > 0 && (
        <span key={s.category} title={`${s.category}: ${s.count}`}
          style={{ flexGrow: s.count, background: catColor(categories.indexOf(s.category)), borderRight: '2px solid var(--surface)' }} />
      ))}
    </span>
  );
}

function DrillDown({ periodId, results, initial, designationId, onClose }: { periodId: string; results: ResultRow[]; initial: ResultRow; designationId: string; onClose: () => void }) {
  const [dept, setDept] = useState(initial.unitCode);
  const { data, loading, error } = useApi<DrillRow[]>('/appraisal/overview/drilldown', { periodId, departmentCode: dept, designationId });
  return (
    <Modal title="Drill down: individual results" wide onClose={onClose}>
      <div className="stack">
        <div className="row between">
          <SelectField label="Department" value={dept} onChange={setDept} placeholder="" options={results.map((r) => ({ value: r.unitCode, label: r.unitName }))} />
          <Confidential text="Restricted view: access is logged" />
        </div>
        {error && <Alert kind="danger">{error}</Alert>}
        {loading && !data ? <Loading /> : !data?.length ? <Empty /> : (
          <div className="apr-table-wrap">
            <table className="apr-table">
              <thead><tr><th>Employee</th><th>Designation</th><th>Unit</th><th>Status</th><th className="num">KPI</th><th className="num">Behavioural</th><th className="num">Final</th><th>Category</th></tr></thead>
              <tbody>{data.map((r) => (
                <tr key={r.appraisalId}><td><b>{r.employeeName}</b><div className="muted" style={{ fontSize: 11 }}>{r.employeeNo}</div></td><td>{r.designation}</td><td>{r.unitName}</td>
                  <td>{pretty(r.status)}</td><td className="num">{fmtNum(r.kpiScore)}</td><td className="num">{fmtNum(r.behaviourScore)}</td><td className="num">{fmtNum(r.finalScore)}</td><td>{r.category ?? '—'}</td></tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </div>
    </Modal>
  );
}
