import { useState } from 'react';
import { ChevronDown, ChevronRight, Printer } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { Alert, Card, Loading, PageHeader, Tabs, TextField } from '@/components/ui';
import CategorizationChart from './CategorizationChart';

interface PositionNode { id: number; code: string; title: string; designationName?: string; reportsToPositionCode?: string; filled: boolean }
interface OrgNode { code: string; name: string; nameSi?: string; nameTa?: string; unitType: string; headcount: number; approvedCadre: number; positions: PositionNode[]; children: OrgNode[] }

/** Org chart: drag-and-drop position categorization, plus the unit hierarchy as at any date (FR-EST-005, -007). */
export default function OrgChartPage() {
  const [tab, setTab] = useState<'positions' | 'units'>('positions');
  return (
    <>
      <PageHeader eyebrow="Establishment & Cadre" title={tab === 'positions' ? 'Drag-and-Drop Position Categorization' : 'Organization chart'}
        actions={tab === 'units' ? <button className="btn btn-outline" onClick={() => window.print()}><Printer /> Print</button> : undefined} />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'positions', label: 'Position categorization' }, { key: 'units', label: 'Unit structure' }]} />
      {tab === 'positions' ? <CategorizationChart /> : <UnitChart />}
    </>
  );
}

function UnitChart() {
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const { data, loading, error } = useApi<OrgNode[]>('/establishment/org-chart', { asOf });
  return (
    <>
      <p className="muted" style={{ marginTop: 0 }}>Approved structure with headcount and cadre. Choose a date to see the structure as it was.</p>
      <div className="filters">
        <TextField label="Structure as at" type="date" value={asOf} onChange={setAsOf} />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card accent>
        {loading && !data ? <Loading /> : (
          <ul className="org-tree">
            {(data ?? []).map((n) => <Node key={n.code} n={n} depth={0} />)}
          </ul>
        )}
      </Card>
    </>
  );
}

function Node({ n, depth }: { n: OrgNode; depth: number }) {
  const [open, setOpen] = useState(depth < 2);
  const [showPos, setShowPos] = useState(false);
  const has = n.children.length > 0;
  return (
    <li>
      <div className={`org-node ${n.unitType}`} onClick={() => setOpen((o) => !o)} role="button" tabIndex={0}
        onKeyDown={(e) => e.key === 'Enter' && setOpen((o) => !o)} aria-expanded={has ? open : undefined}>
        <div className="row" style={{ gap: 6 }}>
          {has ? (open ? <ChevronDown size={16} /> : <ChevronRight size={16} />) : <span style={{ width: 16 }} />}
          <span className="name">{n.name}</span>
          <span className="badge badge-neutral">{n.code}</span>
        </div>
        <div className="meta" style={{ paddingLeft: 22 }}>
          {n.unitType.replace('_', '-').toLowerCase()} · {n.headcount} staff · cadre {n.approvedCadre}
          {n.positions.length > 0 && (
            <button className="btn btn-ghost btn-sm" style={{ marginLeft: 8, height: 22 }} onClick={(e) => { e.stopPropagation(); setShowPos((s) => !s); }}>
              {showPos ? 'Hide' : 'Show'} {n.positions.length} positions
            </button>
          )}
        </div>
        {showPos && (
          <div style={{ paddingLeft: 22, marginTop: 6 }}>
            {n.positions.map((p) => (
              <div key={p.id} className="meta">
                <span className={`badge ${p.filled ? 'badge-success' : 'badge-warning'}`} style={{ marginRight: 6 }}>{p.filled ? 'Filled' : 'Vacant'}</span>
                {p.title} <span className="faint">({p.code}{p.reportsToPositionCode ? ` → ${p.reportsToPositionCode}` : ''})</span>
              </div>
            ))}
          </div>
        )}
      </div>
      {has && open && (
        <ul>
          {n.children.map((c) => <Node key={c.code} n={c} depth={depth + 1} />)}
        </ul>
      )}
    </li>
  );
}
