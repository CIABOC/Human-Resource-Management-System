import { useMemo, useState } from 'react';
import { Download, History, Pencil, Plus } from 'lucide-react';
import { download } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { Alert, Card, KpiCard, Modal, PageHeader, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { OrgUnitSelect } from '@/components/pickers';
import { ChangeModal, ChangeTarget } from './ChangeForms';

interface CadreRow {
  designationId: number; designationCode: string; designationName: string; grade?: string; orgUnitCode: string; orgUnitName: string;
  approved: number; proposed?: number | null; occupied: number; vacant: number; excess: number;
}

/** Approved, proposed, occupied and vacant cadre (FR-EST-020..027). Quantities are data, never hard-coded. */
export default function CadrePage() {
  const { can } = useAuth();
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const [unit, setUnit] = useState('');
  const { data, loading, error, reload } = useApi<CadreRow[]>('/establishment/cadre', { asOf, orgUnitCode: unit });
  const [target, setTarget] = useState<ChangeTarget | null>(null);
  const [hist, setHist] = useState<CadreRow | null>(null);

  const totals = useMemo(() => (data ?? []).reduce((a, r) => ({ approved: a.approved + r.approved, occupied: a.occupied + r.occupied, vacant: a.vacant + r.vacant, proposed: a.proposed + (r.proposed != null ? 1 : 0) }), { approved: 0, occupied: 0, vacant: 0, proposed: 0 }), [data]);

  return (
    <>
      <PageHeader eyebrow="Establishment" title="Cadre" subtitle="Approved cadre per designation and unit against occupied posts. Pending proposals are shown separately."
        actions={
          <>
            <button className="btn btn-outline" onClick={() => void download('/reports/EST_CADRE_VACANCY/export?format=xlsx', { method: 'post', body: { asOf, orgUnitCode: unit || null } })}><Download /> Excel</button>
            {can('EST_MANAGE') && <button className="btn btn-gold" onClick={() => setTarget({ type: 'CADRE_SET' })}><Plus /> Propose cadre</button>}
          </>
        } />
      <div className="grid grid-4" style={{ marginBottom: 18 }}>
        <KpiCard label="Approved cadre" value={totals.approved} tone="navy" />
        <KpiCard label="Occupied" value={totals.occupied} tone="green" />
        <KpiCard label="Vacant" value={totals.vacant} tone="red" />
        <KpiCard label="Pending proposals" value={totals.proposed} tone="gold" />
      </div>
      <div className="filters">
        <TextField label="As at" type="date" value={asOf} onChange={setAsOf} />
        <OrgUnitSelect label="Unit (incl. sub-units)" value={unit} onChange={setUnit} placeholder="— All units —" />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<CadreRow> rows={data} loading={loading} rowKey={(r) => `${r.designationId}|${r.orgUnitCode}`} columns={[
          { key: 'orgUnitName', header: 'Unit', render: (r) => <div><div>{r.orgUnitName}</div><div className="faint mono">{r.orgUnitCode}</div></div> },
          { key: 'designationName', header: 'Designation', render: (r) => <strong>{r.designationName}</strong> },
          { key: 'grade', header: 'Grade' },
          { key: 'approved', header: 'Approved', align: 'right' },
          { key: 'proposed', header: 'Proposed', align: 'right', render: (r) => (r.proposed != null ? <span className="badge badge-gold">{r.proposed}</span> : '—') },
          { key: 'occupied', header: 'Occupied', align: 'right' },
          { key: 'vacant', header: 'Vacant', align: 'right', render: (r) => (r.vacant > 0 ? <strong style={{ color: 'var(--danger)' }}>{r.vacant}</strong> : '0') },
          { key: 'excess', header: 'Excess', align: 'right', render: (r) => (r.excess > 0 ? <span className="badge badge-warning">{r.excess}</span> : '—') },
          { key: 'a', header: '', render: (r) => (
            <div className="row" style={{ gap: 2 }}>
              <button className="btn btn-ghost btn-sm" onClick={() => setHist(r)} aria-label="Cadre history" title="Cadre history"><History /></button>
              {can('EST_MANAGE') && (
                <button className="btn btn-ghost btn-sm" aria-label="Propose change" title="Propose change"
                  onClick={() => setTarget({ type: 'CADRE_SET', cadre: { designationId: r.designationId, orgUnitCode: r.orgUnitCode, approved: r.approved } })}><Pencil /></button>
              )}
            </div>
          ) },
        ]} />
      </Card>
      {target && <ChangeModal target={target} onClose={() => setTarget(null)} onDone={() => void reload()} />}
      {hist && <CadreHistory row={hist} onClose={() => setHist(null)} />}
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
        { key: 'changeId', header: 'Change ref', render: (r) => (r.changeId ? `#${r.changeId}` : 'Initial') },
      ]} />
    </Modal>
  );
}
