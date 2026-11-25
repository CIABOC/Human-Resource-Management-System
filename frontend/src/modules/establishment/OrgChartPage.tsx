import { useMemo, useState } from 'react';
import { Download, GripVertical, Plus, Printer, RotateCcw, Search, Send, UserRound, X } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import type { Designation } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Loading, Modal, PageHeader, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable } from '@/components/DataTable';
import CategorizationChart from './CategorizationChart';
import { ChangeModal, ChangeTarget } from './ChangeForms';
import { DragItem, usePointerDnd } from './dnd';
import { ChartViewport } from './ChartViewport';
import './orgchart.css';

interface OrgNode {
  code: string; name: string; nameSi?: string; nameTa?: string; unitType: string; parentCode?: string; location?: string;
  headTitle?: string; headPositionCode?: string; headEmployeeId?: number; headEmployeeName?: string; headActing: boolean;
  headcount: number; approvedCadre: number; rollupFilled: number; rollupActing: number; rollupApproved: number; children: OrgNode[];
}
interface Staff { id: number; employeeNo: string; nameWithInitials: string; designation?: string; orgUnitCode: string; orgUnitName?: string; status: string }
interface Page<T> { content: T[]; totalElements: number }

/** Org chart: unit structure (REQ 7.6) and drag-and-drop position categorization (REQ 7.1–7.5). */
export default function OrgChartPage() {
  const [tab, setTab] = useState<'units' | 'positions'>('units');
  return (
    <>
      <PageHeader eyebrow="Establishment & Cadre" title={tab === 'positions' ? 'Drag-and-Drop Position Categorization' : 'Organization chart'}
        subtitle={tab === 'units' ? 'Commission → Director General → divisions and units, with heads, approved cadre and actual staff.' : undefined} />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'units', label: 'Organization structure' }, { key: 'positions', label: 'Position categorization' }]} />
      {tab === 'positions' ? <CategorizationChart /> : <UnitChart />}
    </>
  );
}

interface Transfer { employeeId: number; name: string; fromCode: string; toCode: string }

function UnitChart() {
  const { can } = useAuth();
  const toast = useToast();
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const { data, loading, error } = useApi<OrgNode[]>('/establishment/org-chart', { asOf });
  const [selected, setSelected] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [transfers, setTransfers] = useState<Map<number, Transfer>>(new Map());
  const [submitOpen, setSubmitOpen] = useState(false);
  const [change, setChange] = useState<ChangeTarget | null>(null);
  const canTransfer = can('EXT_MANAGE');
  const canPropose = can('EST_MANAGE');
  const designations = useApi<Designation[]>(canPropose ? '/establishment/designations' : null);
  const cadre = useApi<{ designationId: number; approved: number }[]>(canPropose ? '/establishment/cadre' : null, { asOf });
  /** Designations with approved posts somewhere in the structure; the rest are new / not yet placed. */
  const placed = useMemo(() => new Set((cadre.data ?? []).filter((r) => r.approved > 0).map((r) => r.designationId)), [cadre.data]);

  const byCode = useMemo(() => {
    const m = new Map<string, OrgNode>();
    const walk = (n: OrgNode) => { m.set(n.code, n); n.children.forEach(walk); };
    (data ?? []).forEach(walk);
    return m;
  }, [data]);

  const { dragging, handle, zone } = usePointerDnd({
    enabled: canTransfer || canPropose,
    check: (item, z) => {
      const code = z.slice(2);
      if (item.kind === 'person') {
        if (!canTransfer) return { ok: false, reason: 'You may not propose transfers' };
        const t = transfers.get(item.id);
        const from = t?.fromCode ?? selected;
        if (code === from && !t) return { ok: false, reason: 'Already in this unit' };
        return { ok: true };
      }
      if (item.kind === 'cadre') return canPropose ? { ok: true } : { ok: false };
      return { ok: false };
    },
    onDrop: (item: DragItem, z: string) => {
      const code = z.slice(2);
      if (item.kind === 'person') {
        setTransfers((m) => {
          const n = new Map(m);
          const from = m.get(item.id)?.fromCode ?? selected ?? '';
          if (code === from) n.delete(item.id);
          else n.set(item.id, { employeeId: item.id, name: item.label, fromCode: from, toCode: code });
          return n;
        });
      } else if (item.kind === 'cadre') {
        setChange({ type: 'CADRE_SET', cadre: { designationId: item.id, orgUnitCode: code } });
      }
    },
    onReject: (_i, _z, reason) => reason && toast(reason, 'error'),
  });

  const unitName = (c: string) => byCode.get(c)?.name ?? c;

  const renderNode = (n: OrgNode): JSX.Element => {
    const isCollapsed = collapsed.has(n.code);
    const occupied = n.rollupFilled + n.rollupActing;
    const over = occupied > n.rollupApproved;
    const pct = (v: number) => `${n.rollupApproved ? Math.min(100, (v / n.rollupApproved) * 100) : 0}%`;
    const incoming = [...transfers.values()].filter((t) => t.toCode === n.code).length;
    const z = zone(`u:${n.code}`);
    return (
      <li key={n.code}>
        <div {...z} className={`oc-card ${n.unitType} ${selected === n.code ? 'selected' : ''} ${z.className}`} role="button" tabIndex={0}
          onClick={() => setSelected(n.code)} onKeyDown={(e) => e.key === 'Enter' && setSelected(n.code)}>
          <div className="t">{n.name}</div>
          {n.headTitle && <div className="h">{n.headTitle}</div>}
          {n.headPositionCode && (
            n.headEmployeeName
              ? <div className="p"><UserRound size={12} /> {n.headEmployeeName}{n.headActing && <span className="cat-tag gold">acting</span>}</div>
              : <div className="p vacant">Head post vacant</div>
          )}
          <div className="oc-stats">
            <span>Approved <b>{n.rollupApproved}</b></span>
            <span>Filled <b>{n.rollupFilled}</b></span>
            <span>Acting <b>{n.rollupActing}</b></span>
          </div>
          <div className={`util ${over ? 'over' : ''}`} title={`${occupied} of ${n.rollupApproved} (incl. sub-units)`}>
            <span className="f" style={{ width: pct(n.rollupFilled) }} /><span className="a" style={{ width: pct(n.rollupActing) }} />
          </div>
          {(n.location || incoming > 0) && (
            <div className="oc-stats">{n.location && <span>{n.location}</span>}{incoming > 0 && <span className="cat-tag gold">+{incoming} drafted</span>}</div>
          )}
          {n.children.length > 0 && (
            <button className="oc-toggle" onClick={(e) => { e.stopPropagation(); setCollapsed((s) => { const x = new Set(s); if (x.has(n.code)) x.delete(n.code); else x.add(n.code); return x; }); }}>
              {isCollapsed ? `+ ${n.children.length} units` : '− collapse'}
            </button>
          )}
        </div>
        {!isCollapsed && n.children.length > 0 && (
          // leaf children are listed in a column under their parent, which keeps the chart narrow enough to see whole
          <ul className={n.children.length > 1 && n.children.every((k) => k.children.length === 0) ? 'oc-vstack' : undefined}>
            {n.children.map(renderNode)}
          </ul>
        )}
      </li>
    );
  };

  const exportXlsx = () => void download('/reports/EST_ORG_STRUCTURE/export?format=xlsx', { method: 'post', body: { asOf } }).catch((e) => toast(errorMessage(e), 'error'));

  return (
    <>
      <div className="row between" style={{ alignItems: 'flex-end', marginBottom: 12, flexWrap: 'wrap', gap: 12 }}>
        <div className="filters" style={{ margin: 0 }}>
          <TextField label="Structure as at" type="date" value={asOf} onChange={setAsOf} />
        </div>
        <div className="row">
          <button className="btn btn-outline" onClick={() => window.print()}><Printer /> Print</button>
          {can('EST_VIEW') && <button className="btn btn-outline" onClick={exportXlsx}><Download /> Excel</button>}
          {canPropose && <button className="btn btn-outline" onClick={() => setChange({ type: 'DESIGNATION_CREATE' })}><Plus /> New position</button>}
          {canPropose && <button className="btn btn-gold" onClick={() => setChange({ type: 'ORG_UNIT_CREATE' })}><Plus /> Add division / unit</button>}
        </div>
      </div>

      {transfers.size > 0 && (
        <div className="cat-banner" style={{ marginBottom: 12 }}>
          <span className="cat-banner-pill has">{transfers.size} transfer{transfers.size > 1 ? 's' : ''}</span>
          <span style={{ flex: 1 }}>Draft transfers — nobody moves until each request is approved.</span>
          <button className="btn btn-sm btn-on-navy" onClick={() => setTransfers(new Map())}><RotateCcw /> Discard</button>
          <button className="btn btn-sm btn-gold" onClick={() => setSubmitOpen(true)}><Send /> Submit transfers</button>
        </div>
      )}

      {error && <Alert kind="danger">{error}</Alert>}
      <section className="card accent oc-frame">
        {loading && !data ? <Loading /> : (
          <ChartViewport label="Organization chart" fitKey={data}
            top={canPropose ? (
              <PositionTray designations={designations.data ?? []} placed={placed} loading={designations.loading || cadre.loading}
                handle={handle} dragging={dragging} />
            ) : (
              <div className="cv-note">Click a unit to see its head, cadre and staff.{canTransfer && ' Drag a person from the staff list onto another unit to draft a transfer.'}{!canPropose && <> <span className="badge badge-warning">View only</span> Placing positions on units needs the Establishment Officer or Administrative Officer role.</>}</div>
            )}
            side={selected && byCode.get(selected) ? (
              <UnitPanel node={byCode.get(selected)!} transfers={transfers} handle={handle} canDrag={canTransfer} dragging={dragging}
                onUndo={(id) => setTransfers((m) => { const n = new Map(m); n.delete(id); return n; })} onClose={() => setSelected(null)}
                onPropose={(t) => setChange(t)} canPropose={canPropose} unitName={unitName} />
            ) : undefined}>
            <div className="oc-tree">
              <div className="oc-commission">Commission<small>Commission to Investigate Allegations of Bribery or Corruption</small></div>
              <div className="oc-stem" />
              <ul>{(data ?? []).map(renderNode)}</ul>
            </div>
          </ChartViewport>
        )}
      </section>

      {submitOpen && <TransferModal transfers={[...transfers.values()]} unitName={unitName} onClose={() => setSubmitOpen(false)}
        onDone={(failed) => { setTransfers(new Map(failed.map((t) => [t.employeeId, t]))); setSubmitOpen(false); }} />}
      {change && <ChangeModal target={change} onClose={() => setChange(null)} />}
    </>
  );
}

/**
 * Positions that can be dragged onto a unit in the chart. Dropping one proposes approved posts of that designation
 * in the unit (a cadre change request, approved through the workflow). Positions with no approved posts anywhere
 * yet (new designations) are listed first.
 */
function PositionTray({ designations, placed, loading, handle, dragging }: {
  designations: Designation[]; placed: Set<number>; loading: boolean;
  handle: ReturnType<typeof usePointerDnd>['handle']; dragging: DragItem | null;
}) {
  const [q, setQ] = useState('');
  const [all, setAll] = useState(false);
  const match = (d: Designation) => !q.trim() || `${d.name} ${d.code}`.toLowerCase().includes(q.trim().toLowerCase());
  const fresh = designations.filter((d) => !placed.has(d.id) && match(d)).sort((a, b) => a.name.localeCompare(b.name));
  const rest = designations.filter((d) => placed.has(d.id) && match(d)).sort((a, b) => a.name.localeCompare(b.name));
  const showRest = all || q.trim() !== '';
  const chip = (d: Designation, isNew: boolean) => (
    <span key={d.id} {...handle({ kind: 'cadre', id: d.id, label: `${d.name} → drop on a unit` })} title={`${d.code} — drag onto a unit`}
      className={`cat-chip oc-chip ${isNew ? 'new' : ''} ${dragging?.kind === 'cadre' && dragging.id === d.id ? 'dragging' : ''}`}>
      <GripVertical size={13} aria-hidden />{d.name}{isNew && <em>New</em>}
    </span>
  );
  return (
    <div className="oc-tray">
      <div className="oc-tray-head">
        <div>
          <strong>Positions</strong>
          <span className="muted"> — drag one onto a unit to place approved posts there (sent for approval)</span>
        </div>
        <div className="search oc-tray-search">
          <Search />
          <input className="input" placeholder="Find a position" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Find a position" />
        </div>
      </div>
      <div className="oc-tray-chips">
        {loading && designations.length === 0 && <span className="faint">Loading positions…</span>}
        {fresh.length > 0 && <span className="oc-tray-label">Not yet placed</span>}
        {fresh.map((d) => chip(d, true))}
        {!loading && fresh.length === 0 && !q && <span className="faint">Every position has posts in the structure.</span>}
        {showRest && rest.length > 0 && <span className="oc-tray-label">Already placed</span>}
        {showRest && rest.map((d) => chip(d, false))}
        {!q.trim() && rest.length > 0 && (
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setAll((a) => !a)}>
            {all ? 'Show new positions only' : `Show all ${designations.length} positions`}
          </button>
        )}
        {q.trim() && fresh.length + rest.length === 0 && <span className="faint">No position matches “{q}”.</span>}
      </div>
    </div>
  );
}

function UnitPanel({ node, transfers, handle, canDrag, dragging, onUndo, onClose, onPropose, canPropose, unitName }: {
  node: OrgNode; transfers: Map<number, Transfer>; handle: ReturnType<typeof usePointerDnd>['handle']; canDrag: boolean;
  dragging: DragItem | null; onUndo: (id: number) => void; onClose: () => void; onPropose: (t: ChangeTarget) => void; canPropose: boolean;
  unitName: (c: string) => string;
}) {
  const { can } = useAuth();
  const staff = useApi<Page<Staff>>(can('EMP_VIEW') ? '/employees' : null, { orgUnitCode: node.code, size: 200 });
  const own = (staff.data?.content ?? []).filter((s) => s.orgUnitCode === node.code);
  const vacant = Math.max(0, node.approvedCadre - node.headcount);
  return (
    <Card title={node.name} actions={<button className="btn btn-ghost btn-sm" aria-label="Close" onClick={onClose}><X /></button>}>
      <div className="stack" style={{ gap: 10 }}>
        <div className="muted" style={{ fontSize: 12.5 }}>
          {node.code} · {node.unitType.replace('_', '-').toLowerCase()}{node.location ? ` · ${node.location}` : ''}
        </div>
        {node.headTitle && (
          <div><div className="faint" style={{ fontSize: 11.5 }}>{node.headTitle}</div>
            <strong>{node.headEmployeeName ?? <span style={{ color: 'var(--danger)' }}>Vacant</span>}</strong>{node.headActing && <span className="cat-tag gold" style={{ marginLeft: 6 }}>acting</span>}</div>
        )}
        <DataTable rows={[
          { k: 'This unit', a: node.approvedCadre, o: node.headcount, v: vacant },
          { k: 'Incl. sub-units', a: node.rollupApproved, o: node.rollupFilled + node.rollupActing, v: Math.max(0, node.rollupApproved - node.rollupFilled - node.rollupActing) },
        ]} rowKey={(r) => r.k} columns={[
          { key: 'k', header: '' }, { key: 'a', header: 'Approved', align: 'right' }, { key: 'o', header: 'Actual', align: 'right' }, { key: 'v', header: 'Vacant', align: 'right' },
        ]} />
        {canPropose && (
          <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
            <button className="btn btn-outline btn-sm" onClick={() => onPropose({ type: 'CADRE_SET', cadre: { orgUnitCode: node.code } })}>Propose cadre</button>
            <button className="btn btn-outline btn-sm" onClick={() => onPropose({ type: 'ORG_UNIT_CREATE', parentCode: node.code })}>Add sub-unit</button>
          </div>
        )}
        {can('EMP_VIEW') && (
          <>
            <h4 style={{ margin: '6px 0 0' }}>Staff ({own.length})</h4>
            {staff.loading && !staff.data ? <Loading /> : (
              <div className="stack" style={{ gap: 6 }}>
                {own.length === 0 && <span className="faint">No staff in this unit (excluding sub-units).</span>}
                {own.map((s) => {
                  const t = transfers.get(s.id);
                  return (
                    <div key={s.id} {...handle({ kind: 'person', id: s.id, label: s.nameWithInitials }, !canDrag)}
                      className={`oc-person ${t ? 'moved' : ''} ${dragging?.kind === 'person' && dragging.id === s.id ? 'dragging' : ''}`}>
                      <span className="av">{s.nameWithInitials.split(/[\s.]+/).filter(Boolean).slice(-1)[0]?.slice(0, 2).toUpperCase()}</span>
                      <span style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontWeight: 600 }}>{s.nameWithInitials}</div>
                        <div className="faint" style={{ fontSize: 11 }}>{s.employeeNo} · {s.designation ?? '—'}{t ? ` → ${unitName(t.toCode)}` : ''}</div>
                      </span>
                      {t && <button className="btn btn-ghost btn-sm" aria-label="Undo transfer" onClick={() => onUndo(s.id)}><RotateCcw size={13} /></button>}
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}
      </div>
    </Card>
  );
}

function TransferModal({ transfers, unitName, onClose, onDone }: {
  transfers: Transfer[]; unitName: (c: string) => string; onClose: () => void; onDone: (failed: Transfer[]) => void;
}) {
  const toast = useToast();
  const [effectiveDate, setEffectiveDate] = useState(new Date().toISOString().slice(0, 10));
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<number, string>>({});
  const submit = async () => {
    setBusy(true);
    const failed: Transfer[] = [];
    const errs: Record<number, string> = {};
    for (const t of transfers) {
      try {
        await api.post('/extended/movements', { requestType: 'TRANSFER', employeeId: t.employeeId, targetOrgUnitCode: t.toCode, effectiveDate, reason, reference });
      } catch (e) {
        failed.push(t);
        errs[t.employeeId] = errorMessage(e);
      }
    }
    setBusy(false);
    setErrors(errs);
    const ok = transfers.length - failed.length;
    if (ok) toast(`${ok} transfer request(s) submitted for approval`, 'success');
    if (!failed.length) onDone([]);
    else if (ok) onDone(failed);
  };
  return (
    <Modal title="Submit transfer requests" onClose={onClose} wide footer={
      <><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-gold" disabled={busy || !reason.trim()} onClick={() => void submit()}><Send /> Submit</button></>
    }>
      <div className="stack">
        <Alert kind="info">Each transfer becomes a movement request and follows the transfer approval workflow. Cadre limits are checked on submission.</Alert>
        <DataTable rows={transfers} rowKey={(t) => t.employeeId} columns={[
          { key: 'name', header: 'Officer', render: (t) => <strong>{t.name}</strong> },
          { key: 'fromCode', header: 'From', render: (t) => unitName(t.fromCode) },
          { key: 'toCode', header: 'To', render: (t) => unitName(t.toCode) },
          { key: 'e', header: '', render: (t) => errors[t.employeeId] ? <span style={{ color: 'var(--danger)', fontSize: 12 }}>{errors[t.employeeId]}</span> : null },
        ]} />
        <div className="form-grid">
          <TextField label="Effective date" type="date" required value={effectiveDate} onChange={setEffectiveDate} />
          <TextField label="Reference" value={reference} onChange={setReference} />
          <TextArea className="span-2" label="Reason" required value={reason} onChange={setReason} />
        </div>
      </div>
    </Modal>
  );
}
