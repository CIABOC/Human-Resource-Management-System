import { useMemo, useState } from 'react';
import { History, Pencil, Plus, Power } from 'lucide-react';
import type { Designation, OrgUnit, Position } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { Alert, Card, Checkbox, Modal, PageHeader, StatusBadge, Tabs, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { useMasterDataLabels } from '@/components/pickers';
import { ChangeModal, ChangeTarget } from './ChangeForms';

type Tab = 'units' | 'designations' | 'positions';

export default function StructurePage() {
  const { can } = useAuth();
  const manage = can('EST_MANAGE');
  const [tab, setTab] = useState<Tab>('units');
  const [target, setTarget] = useState<ChangeTarget | null>(null);
  const [historyOf, setHistoryOf] = useState<string | null>(null);
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const [inactive, setInactive] = useState(false);

  const units = useApi<OrgUnit[]>('/establishment/org-units', { asOf });
  const designations = useApi<Designation[]>('/establishment/designations', { includeInactive: inactive });
  const positions = useApi<Position[]>('/establishment/positions', { includeInactive: inactive });
  const grade = useMasterDataLabels('GRADE');
  const desName = useMemo(() => new Map((designations.data ?? []).map((d) => [d.id, d.name])), [designations.data]);
  const unitName = useMemo(() => new Map((units.data ?? []).map((u) => [u.code, u.name])), [units.data]);

  const reloadAll = () => { void units.reload(); void designations.reload(); void positions.reload(); };
  const action = (t: ChangeTarget, label: string, Icon: typeof Pencil) => manage ? (
    <button className="btn btn-ghost btn-sm" onClick={(e) => { e.stopPropagation(); setTarget(t); }} title={label} aria-label={label}><Icon /></button>
  ) : null;

  return (
    <>
      <PageHeader eyebrow="Establishment" title="Structure & posts" subtitle="Organizational units, designations and positions. All changes are proposed and approved before taking effect."
        actions={manage ? (
          <>
            {tab === 'units' && <button className="btn btn-gold" onClick={() => setTarget({ type: 'ORG_UNIT_CREATE' })}><Plus /> New unit</button>}
            {tab === 'designations' && <button className="btn btn-gold" onClick={() => setTarget({ type: 'DESIGNATION_CREATE' })}><Plus /> New designation</button>}
            {tab === 'positions' && <button className="btn btn-gold" onClick={() => setTarget({ type: 'POSITION_CREATE' })}><Plus /> New position</button>}
          </>
        ) : undefined} />
      <Tabs tabs={[{ key: 'units', label: 'Organizational units' }, { key: 'designations', label: 'Designations' }, { key: 'positions', label: 'Positions' }]} value={tab} onChange={setTab} />

      {tab === 'units' && (
        <>
          <div className="filters"><TextField label="As at" type="date" value={asOf} onChange={setAsOf} /></div>
          {units.error && <Alert kind="danger">{units.error}</Alert>}
          <Card pad={false}>
            <DataTable<OrgUnit> rows={units.data} loading={units.loading} columns={[
              { key: 'code', header: 'Code', render: (u) => <span className="mono">{u.code}</span> },
              { key: 'name', header: 'Name', render: (u) => <div><div style={{ fontWeight: 600 }}>{u.name}</div><div className="faint" style={{ fontSize: 12 }}>{[u.nameSi, u.nameTa].filter(Boolean).join(' · ')}</div></div> },
              { key: 'unitType', header: 'Type', render: (u) => u.unitType.replace('_', '-').toLowerCase() },
              { key: 'parentCode', header: 'Parent', render: (u) => (u.parentCode ? unitName.get(u.parentCode) ?? u.parentCode : '—') },
              { key: 'headTitle', header: 'Head', render: (u) => (u.headTitle ? <div><div>{u.headTitle}</div>{u.headPositionCode && <div className="faint mono" style={{ fontSize: 11.5 }}>{u.headPositionCode}</div>}</div> : '—') },
              { key: 'location', header: 'Location', render: (u) => u.location ?? '—' },
              { key: 'effectiveFrom', header: 'Since', render: (u) => fmtDate(u.effectiveFrom) },
              { key: 'a', header: '', render: (u) => (
                <div className="row" style={{ gap: 2 }}>
                  <button className="btn btn-ghost btn-sm" onClick={() => setHistoryOf(u.code)} title="History" aria-label="History"><History /></button>
                  {action({ type: 'ORG_UNIT_UPDATE', unit: u }, 'Propose change', Pencil)}
                  {action({ type: 'ORG_UNIT_DEACTIVATE', unit: u }, 'Propose deactivation', Power)}
                </div>
              ) },
            ]} />
          </Card>
        </>
      )}

      {tab === 'designations' && (
        <>
          <div className="filters"><Checkbox label="Include inactive" checked={inactive} onChange={setInactive} /></div>
          <Card pad={false}>
            <DataTable<Designation> rows={designations.data} loading={designations.loading} columns={[
              { key: 'code', header: 'Code', render: (d) => <span className="mono">{d.code}</span> },
              { key: 'name', header: 'Designation', render: (d) => (
                <div>
                  <strong>{d.name}</strong>
                  {d.dyingCadre && <span className="cat-tag" style={{ marginLeft: 6 }} title="No new posts; existing holders retained">dying cadre</span>}
                  {d.statutory && <span className="cat-tag gold" style={{ marginLeft: 6 }}>statutory</span>}
                  {d.statutory && d.legalBasis && <div className="faint" style={{ fontSize: 11.5 }}>{d.legalBasis}</div>}
                </div>
              ) },
              { key: 'level', header: 'Level', align: 'right', render: (d) => d.level ?? '—' },
              { key: 'grade', header: 'Grade', render: (d) => grade(d.grade) },
              { key: 'salaryCode', header: 'Salary code', render: (d) => (d.salaryCode ? <span className="mono">{d.salaryCode}</span> : '—') },
              { key: 'serviceCategory', header: 'Service category' },
              { key: 'active', header: 'Status', render: (d) => <StatusBadge status={d.active ? 'ACTIVE' : 'INACTIVE'} /> },
              { key: 'a', header: '', render: (d) => d.active && (
                <div className="row" style={{ gap: 2 }}>
                  {action({ type: 'DESIGNATION_UPDATE', designation: d }, 'Propose change', Pencil)}
                  {action({ type: 'DESIGNATION_DEACTIVATE', designation: d }, 'Propose deactivation', Power)}
                </div>
              ) },
            ]} />
          </Card>
        </>
      )}

      {tab === 'positions' && (
        <>
          <div className="filters"><Checkbox label="Include inactive" checked={inactive} onChange={setInactive} /></div>
          <Card pad={false}>
            <DataTable<Position> rows={positions.data} loading={positions.loading} columns={[
              { key: 'code', header: 'Code', render: (p) => <span className="mono">{p.code}</span> },
              { key: 'title', header: 'Position', render: (p) => <strong>{p.title}</strong> },
              { key: 'designationId', header: 'Designation', render: (p) => desName.get(p.designationId) ?? p.designationId },
              { key: 'orgUnitCode', header: 'Unit', render: (p) => unitName.get(p.orgUnitCode) ?? p.orgUnitCode },
              { key: 'reportsToPositionCode', header: 'Reports to' },
              { key: 'active', header: 'Status', render: (p) => <StatusBadge status={!p.active ? 'INACTIVE' : p.postStatus && p.postStatus !== 'ACTIVE' ? p.postStatus : 'ACTIVE'} /> },
              { key: 'a', header: '', render: (p) => p.active && (
                <div className="row" style={{ gap: 2 }}>
                  {action({ type: 'POSITION_UPDATE', position: p }, 'Propose change', Pencil)}
                  {action({ type: 'POSITION_DEACTIVATE', position: p }, 'Propose deactivation', Power)}
                </div>
              ) },
            ]} />
          </Card>
        </>
      )}

      {target && <ChangeModal target={target} onClose={() => setTarget(null)} onDone={reloadAll} />}
      {historyOf && <UnitHistory code={historyOf} onClose={() => setHistoryOf(null)} />}
    </>
  );
}

function UnitHistory({ code, onClose }: { code: string; onClose: () => void }) {
  const { data, loading } = useApi<OrgUnit[]>(`/establishment/org-units/${code}/history`);
  return (
    <Modal title={`History of ${code}`} onClose={onClose} wide>
      <DataTable<OrgUnit> rows={data} loading={loading} columns={[
        { key: 'name', header: 'Name' },
        { key: 'unitType', header: 'Type' },
        { key: 'parentCode', header: 'Parent' },
        { key: 'effectiveFrom', header: 'From', render: (u) => fmtDate(u.effectiveFrom) },
        { key: 'effectiveTo', header: 'Until', render: (u) => (u.effectiveTo ? fmtDate(u.effectiveTo) : 'Current') },
      ]} />
    </Modal>
  );
}
