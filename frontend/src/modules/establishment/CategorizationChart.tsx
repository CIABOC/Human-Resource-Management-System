import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { CornerDownRight, Download, FolderTree, GripVertical, Lock, Pencil, Plus, RotateCcw, Save, Send, X } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Checkbox, Loading, Modal, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import { ChangeModal } from './ChangeForms';
import { DragItem, DropVerdict, usePointerDnd } from './dnd';
import { ChartViewport } from './ChartViewport';
import './orgchart.css';

interface Card { designationId: number; code: string; name: string; grade?: string; level?: number; dyingCadre: boolean; statutory: boolean; pending: boolean }
interface Category {
  id: number; code: string; name: string; nameSi?: string; nameTa?: string; color: string; sortOrder: number; closed: boolean;
  description?: string; parentId?: number | null; pending: boolean; positions: Card[];
}
interface PendingBatch { id: number; workflowInstanceId?: number; status: string; effectiveDate: string; moves: number }
interface Chart { rootName: string; asOf: string; categories: Category[]; unassigned: Card[]; pendingBatches: PendingBatch[] }
interface HistoryRow { lineId: number; when: string; by: string; designation: string; from: string; to: string; effective: string; status: string; workflowInstanceId?: number }

/** id → target (null = unassigned / top level). */
type Moves = Map<number, number | null>;
const STORE = 'hrms.categorization.draft.v2';
const ROOT = 'root';
const UNASSIGNED = 'u';
const catZone = (id: number) => `c:${id}`;
export const VIEW_ONLY = 'View only: moving positions needs the Establishment Officer or Administrative Officer role.';

/**
 * Drag-and-drop position categorization (REQ 7.1–7.5). Designations are dragged between categories; categories can be
 * dragged under other categories (nesting) or to the top level. Every move is held as a draft (which can be saved in
 * this browser) and changes the official structure only after the batch is submitted and approved. Closed categories,
 * locked (pending) items and circular nesting are rejected with an explanation while dragging.
 */
export default function CategorizationChart() {
  const { can } = useAuth();
  const toast = useToast();
  const manage = can('EST_MANAGE');
  const { data, loading, error, reload } = useApi<Chart>('/establishment/categorization');
  const history = useApi<HistoryRow[]>(can('EST_VIEW') ? '/establishment/categorization/history' : null);
  const [moves, setMoves] = useState<Moves>(new Map());
  const [catMoves, setCatMoves] = useState<Moves>(new Map());
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [submitOpen, setSubmitOpen] = useState(false);
  const [catEdit, setCatEdit] = useState<Partial<Category> | null>(null);
  const [newPosition, setNewPosition] = useState(false);
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set());
  const restored = useRef(false);

  const { cards, cats, baseCat, baseParent } = useMemo(() => {
    const cards = new Map<number, Card>();
    const baseCat = new Map<number, number | null>();
    const cats = new Map<number, Category>();
    const baseParent = new Map<number, number | null>();
    data?.categories.forEach((c) => {
      cats.set(c.id, c);
      baseParent.set(c.id, c.parentId ?? null);
      c.positions.forEach((p) => { cards.set(p.designationId, p); baseCat.set(p.designationId, c.id); });
    });
    data?.unassigned.forEach((p) => { cards.set(p.designationId, p); baseCat.set(p.designationId, null); });
    return { cards, cats, baseCat, baseParent };
  }, [data]);

  // Restore a draft saved in this browser once the chart has loaded.
  useEffect(() => {
    if (!data || restored.current) return;
    restored.current = true;
    try {
      const raw = localStorage.getItem(STORE);
      if (!raw) return;
      const s = JSON.parse(raw) as { moves: [number, number | null][]; catMoves: [number, number | null][]; savedAt: string };
      const m = new Map(s.moves.filter(([id, to]) => cards.has(id) && !cards.get(id)!.pending && (to === null || cats.has(to))));
      const cm = new Map(s.catMoves.filter(([id, to]) => cats.has(id) && !cats.get(id)!.pending && (to === null || cats.has(to))));
      setMoves(m);
      setCatMoves(cm);
      if (m.size || cm.size) setSavedAt(s.savedAt);
    } catch {
      /* ignore an unreadable draft */
    }
  }, [data, cards, cats]);

  const catOf = (id: number) => (moves.has(id) ? moves.get(id)! : baseCat.get(id) ?? null);
  const parentOf = (id: number) => (catMoves.has(id) ? catMoves.get(id)! : baseParent.get(id) ?? null);
  const membersOf = (catId: number | null) =>
    [...cards.values()].filter((c) => catOf(c.designationId) === catId).sort((a, b) => (a.level ?? 99) - (b.level ?? 99) || a.name.localeCompare(b.name));
  const childrenOf = (pid: number | null) =>
    [...cats.values()].filter((c) => parentOf(c.id) === pid).sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name));
  const rollup = (id: number): number => membersOf(id).length + childrenOf(id).reduce((s, c) => s + rollup(c.id), 0);
  const catName = (id: number | null, empty = 'Unassigned') => (id === null ? empty : cats.get(id)?.name ?? '?');
  /** Is {@code node} the same as or below {@code ancestor} in the draft hierarchy? */
  const within = (node: number, ancestor: number) => {
    const seen = new Set<number>();
    for (let cur: number | null = node; cur !== null && !seen.has(cur); cur = parentOf(cur)) {
      if (cur === ancestor) return true;
      seen.add(cur);
    }
    return false;
  };
  const path = (id: number | null) => {
    const parts: string[] = [];
    for (let cur = id; cur !== null && parts.length < 10; cur = parentOf(cur)) parts.unshift(catName(cur));
    return parts.join(' › ') || 'Top level';
  };

  const check = (item: DragItem, zone: string): DropVerdict => {
    const target = zone.startsWith('c:') ? Number(zone.slice(2)) : null;
    const tc = target !== null ? cats.get(target) : undefined;
    if (item.kind === 'designation') {
      if (zone === ROOT) return { ok: false, reason: 'Drop a position into a category, or onto “Unassigned positions”' };
      if (tc?.closed) return { ok: false, reason: `“${tc.name}” is closed and does not accept positions` };
      return { ok: true };
    }
    if (item.kind === 'category') {
      if (zone === UNASSIGNED) return { ok: false, reason: 'Categories cannot be unassigned — drop it on another category or the top level' };
      if (target === item.id) return { ok: false, reason: 'A category cannot be placed under itself' };
      if (tc?.closed) return { ok: false, reason: `“${tc.name}” is closed and cannot take sub-categories` };
      if (target !== null && within(target, item.id))
        return { ok: false, reason: `Circular hierarchy: “${tc?.name}” is already inside “${item.label}”` };
      return { ok: true };
    }
    return { ok: false };
  };

  const drop = (item: DragItem, zone: string) => {
    const target = zone.startsWith('c:') ? Number(zone.slice(2)) : null;
    if (item.kind === 'designation') {
      setMoves((d) => {
        const n = new Map(d);
        if ((baseCat.get(item.id) ?? null) === target) n.delete(item.id);
        else n.set(item.id, target);
        return n;
      });
    } else if (item.kind === 'category') {
      if (parentOf(item.id) === target) return;
      setCatMoves((d) => {
        const n = new Map(d);
        if ((baseParent.get(item.id) ?? null) === target) n.delete(item.id);
        else n.set(item.id, target);
        return n;
      });
      if (target !== null) setCollapsed((s) => { const n = new Set(s); n.delete(target); return n; });
    }
    setSavedAt(null);
  };

  const { dragging, over, handle, zone } = usePointerDnd({
    enabled: manage,
    check,
    onDrop: drop,
    onReject: (_i, _z, reason) => reason && toast(reason, 'error'),
    disabledReason: VIEW_ONLY,
  });

  const undoDesignation = (id: number) => { setMoves((d) => { const n = new Map(d); n.delete(id); return n; }); setSavedAt(null); };
  const undoCategory = (id: number) => { setCatMoves((d) => { const n = new Map(d); n.delete(id); return n; }); setSavedAt(null); };
  const discard = () => {
    setMoves(new Map());
    setCatMoves(new Map());
    setSavedAt(null);
    try { localStorage.removeItem(STORE); } catch { /* ignore */ }
  };
  const saveDraft = () => {
    const at = new Date().toISOString();
    try {
      localStorage.setItem(STORE, JSON.stringify({ moves: [...moves.entries()], catMoves: [...catMoves.entries()], savedAt: at }));
      setSavedAt(at);
      toast('Draft saved in this browser. It is not yet submitted.', 'success');
    } catch {
      toast('This browser would not store the draft', 'error');
    }
  };

  if (loading && !data) return <Loading />;
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return null;

  const changeCount = moves.size + catMoves.size;
  const totalCategorised = [...cards.keys()].filter((id) => catOf(id) !== null).length;
  const unassigned = membersOf(null);
  const tops = childrenOf(null);
  const z = (key: string, extra = '') => {
    const p = zone(key);
    return { ...p, className: `${extra} ${p.className}`.trim() };
  };

  const renderCard = (p: Card) => {
    const moved = moves.has(p.designationId);
    return (
      <div key={p.designationId} {...handle({ kind: 'designation', id: p.designationId, label: p.name }, p.pending)}
        className={`cat-card ${moved ? 'moved' : ''} ${p.pending ? 'pending' : ''} ${dragging?.kind === 'designation' && dragging.id === p.designationId ? 'dragging' : ''}`}
        title={p.pending ? 'Awaiting approval — locked' : `${p.code}${p.grade ? ' · ' + p.grade : ''}${p.level ? ' · level ' + p.level : ''}`}>
        {p.pending ? <Lock size={13} className="faint" /> : manage ? <GripVertical size={13} className="faint" /> : null}
        <span className="cat-card-name">{p.name}</span>
        {p.dyingCadre && <span className="cat-tag" title="Dying cadre: no new posts">dying</span>}
        {p.statutory && <span className="cat-tag gold" title="Statutory post">statutory</span>}
        {moved && <button aria-label={`Undo move of ${p.name}`} title="Undo" onClick={() => undoDesignation(p.designationId)}><RotateCcw size={12} /></button>}
        {manage && !p.pending && !moved && catOf(p.designationId) !== null && (
          <button aria-label={`Unassign ${p.name}`} title="Unassign" onClick={() => drop({ kind: 'designation', id: p.designationId, label: p.name }, UNASSIGNED)}><X size={12} /></button>
        )}
      </div>
    );
  };

  /** A category box on the chart; sub-categories nest inside their parent's box. */
  const renderBox = (c: Category, depth: number): JSX.Element => {
    const members = membersOf(c.id);
    const kids = childrenOf(c.id);
    const moved = catMoves.has(c.id);
    const isDragged = dragging?.kind === 'category' && dragging.id === c.id;
    const isCollapsed = collapsed.has(c.id);
    return (
      <div key={c.id} {...z(catZone(c.id), `${depth === 0 ? 'cat-col' : 'cat-sub'} ${moved ? 'moved' : ''} ${isDragged ? 'dragging' : ''}`)}>
        <div {...handle({ kind: 'category', id: c.id, label: c.name }, c.pending)} className="cat-col-head" style={{ background: c.color }}
          title={c.pending ? 'Re-nesting awaiting approval — locked' : manage ? 'Drag to move this category under another one' : c.description}>
          <span className="row" style={{ gap: 4, minWidth: 0 }}>
            {(kids.length > 0) && (
              <button className="cat-head-btn" aria-label={isCollapsed ? 'Expand' : 'Collapse'} onClick={() => setCollapsed((s) => { const n = new Set(s); if (n.has(c.id)) n.delete(c.id); else n.add(c.id); return n; })}>
                {isCollapsed ? '+' : '−'}
              </button>
            )}
            <span className="cat-head-name">{c.name}{c.closed ? ' · closed' : ''}</span>
          </span>
          <span className="row" style={{ gap: 4 }}>
            <span className="cat-count" title="Positions including sub-categories">{rollup(c.id)}</span>
            {(c.closed || c.pending) && <Lock size={12} />}
            {moved && <button className="cat-head-btn" aria-label={`Undo move of ${c.name}`} title="Undo" onClick={() => undoCategory(c.id)}><RotateCcw size={11} /></button>}
          </span>
        </div>
        {!isCollapsed && (
          <div className="cat-col-body">
            {members.map(renderCard)}
            {kids.map((k) => renderBox(k, depth + 1))}
            {members.length === 0 && kids.length === 0 && <div className="faint" style={{ fontSize: 11.5, padding: '8px 4px' }}>{c.closed ? 'Closed' : manage ? 'Drop positions here' : 'No positions'}</div>}
          </div>
        )}
      </div>
    );
  };

  const renderTree = (c: Category, depth: number): JSX.Element => (
    <div key={c.id}>
      <div {...z(catZone(c.id), `cat-tree-row ${c.closed ? 'closed' : ''} ${catMoves.has(c.id) ? 'moved' : ''}`)} style={{ paddingLeft: 12 + depth * 16 }}>
        <span {...handle({ kind: 'category', id: c.id, label: c.name }, c.pending)} className="row cat-tree-name" style={{ gap: 6 }}>
          {depth > 0 && <CornerDownRight size={12} className="faint" />}
          <span className="cat-dot" style={{ background: c.color }} />
          <span>{c.name}</span>
          {(c.closed || c.pending) && <Lock size={11} className="faint" />}
        </span>
        <span className="row" style={{ gap: 4 }}>
          {rollup(c.id)}
          {manage && <button className="btn btn-ghost btn-sm" style={{ height: 22, padding: '0 4px' }} aria-label={`Edit ${c.name}`} onClick={() => setCatEdit(c)}><Pencil size={13} /></button>}
        </span>
      </div>
      {childrenOf(c.id).map((k) => renderTree(k, depth + 1))}
    </div>
  );

  return (
    <div className="stack">
      <div className="row between" style={{ alignItems: 'flex-start' }}>
        <p className="muted" style={{ margin: 0, maxWidth: 760 }}>
          {manage
            ? 'Drag a position card into a category, or drag a category onto another category to nest it (onto “Top level” to un-nest). Works with mouse, touch and pen. Moves are held as a draft; the official structure changes only after approval.'
            : 'Approved position categories. Changes are proposed by establishment officers and take effect after approval.'}
        </p>
        {manage && (
          <div className="row">
            <button className="btn btn-outline" onClick={() => setCatEdit({ color: '#0B1F4B', sortOrder: data.categories.length + 1, closed: false, parentId: null })}><Plus /> New Category</button>
            <button className="btn btn-gold" onClick={() => setNewPosition(true)}><Plus /> New Position</button>
          </div>
        )}
      </div>

      {manage && (
        <div className="cat-banner">
          <span className={`cat-banner-pill ${changeCount ? 'has' : ''}`}>{changeCount ? `${changeCount} change${changeCount > 1 ? 's' : ''}` : 'No changes'}</span>
          <span style={{ flex: 1 }}>
            {changeCount
              ? savedAt ? `Draft saved ${fmtDateTime(savedAt)} — not yet submitted for approval.` : 'Unsaved draft. Save it to come back later, or submit it for approval.'
              : 'Drag a position or category to start a draft.'}
          </span>
          {changeCount > 0 && (
            <>
              <button className="btn btn-sm btn-on-navy" onClick={discard}><RotateCcw /> Discard</button>
              <button className="btn btn-sm btn-on-navy" onClick={saveDraft}><Save /> Save draft</button>
              <button className="btn btn-sm btn-gold" onClick={() => setSubmitOpen(true)}><Send /> Submit for approval</button>
            </>
          )}
        </div>
      )}

      {dragging && over && !over.verdict.ok && over.verdict.reason && (
        <div className="dnd-reason" role="status"><Lock size={14} /> {over.verdict.reason}</div>
      )}

      {data.pendingBatches.map((b) => (
        <Alert key={b.id} kind="warning">
          {b.moves} move(s) effective {fmtDate(b.effectiveDate)} are {b.status === 'RETURNED' ? 'returned for correction' : 'awaiting approval'}
          {b.workflowInstanceId && <> — <Link to={`/approvals/${b.workflowInstanceId}`}>open request #{b.workflowInstanceId}</Link></>}. Those items are locked until decided.
        </Alert>
      ))}

      <section className="card accent pc-frame">
        <div className="card-header" style={{ display: 'block' }}>
          <h3>Position structure</h3>
          <div className="muted" style={{ fontSize: 12.5 }}>{cats.size} categories · {totalCategorised} positions · {unassigned.length} unassigned</div>
        </div>
        <div className="pc-layout">
          <div className="cat-tree">
            <div className="row" style={{ gap: 6, padding: '6px 10px 8px', fontWeight: 700, color: 'var(--navy-800)' }}><FolderTree size={15} /> Category tree</div>
            <div {...z(ROOT, 'cat-tree-row root')} title="Drop a category here to move it to the top level">
              <span>{data.rootName}</span><span>{totalCategorised}</span>
            </div>
            {tops.map((c) => renderTree(c, 0))}
            <div {...z(UNASSIGNED, 'cat-tree-row')}><span>Unassigned</span><span>{unassigned.length}</span></div>
            <div className="faint" style={{ fontSize: 12, marginTop: 8, textAlign: 'center' }}><Lock size={12} /> Closed and pending items reject drops</div>
          </div>
          <ChartViewport label="Position structure" fitKey={data} top={
            <div {...z(UNASSIGNED, 'pc-tray')}>
              <div className="oc-tray-head" style={{ marginBottom: 8 }}>
                <div><strong>Unassigned positions</strong>
                  <span className="muted"> — {manage ? 'drag one onto a category below; drop a card here to unassign it' : 'waiting for a category'}</span></div>
                {!manage && <span className="badge badge-warning" title={VIEW_ONLY}>View only</span>}
                {manage && <button className="btn btn-outline btn-sm" onClick={() => setNewPosition(true)}><Plus /> New position</button>}
              </div>
              <div className="oc-tray-chips">
                {unassigned.length === 0 && <span className="faint">All positions are categorised.</span>}
                {unassigned.map((p) => (
                  <span key={p.designationId} {...handle({ kind: 'designation', id: p.designationId, label: p.name }, p.pending)}
                    className={`cat-chip oc-chip new ${moves.has(p.designationId) ? 'moved' : ''} ${p.pending ? 'pending' : ''} ${dragging?.kind === 'designation' && dragging.id === p.designationId ? 'dragging' : ''}`}
                    title={p.pending ? 'Awaiting approval' : `${p.code} — drag onto a category`}>
                    {p.pending ? <Lock size={12} /> : manage ? <GripVertical size={13} aria-hidden /> : null}{p.name}
                    {moves.has(p.designationId) && <button aria-label="Undo" onClick={() => undoDesignation(p.designationId)}><X size={13} /></button>}
                  </span>
                ))}
              </div>
            </div>
          }>
            <div className="cat-scale">
              <div {...z(ROOT, 'cat-root')} title={manage ? 'Drop a category here to move it to the top level' : undefined}>
                <span className="cat-root-badge">{data.rootName.slice(0, 2).toUpperCase()}</span>
                <div><div className="n">{data.rootName}</div><div className="m">{totalCategorised} categorised positions</div></div>
              </div>
              <div className="cat-trunk" />
              <div className="cat-columns">{tops.map((c) => renderBox(c, 0))}</div>
            </div>
          </ChartViewport>
        </div>
      </section>

      {can('EST_VIEW') && (
        <section className="card">
          <div className="card-header">
            <div><h3>Categorization Audit</h3><div className="muted" style={{ fontSize: 12.5 }}>Who moved what, from where, to where, with effective dates</div></div>
            <button className="btn btn-outline btn-sm" onClick={() => void download('/reports/EST_CATEGORIZATION_HISTORY/export?format=xlsx', { method: 'post', body: {} }).catch((e) => toast(errorMessage(e), 'error'))}><Download /> Export</button>
          </div>
          <DataTable<HistoryRow> rows={history.data} loading={history.loading} rowKey={(r) => r.lineId} empty="No categorization changes yet." columns={[
            { key: 'when', header: 'When', render: (r) => <span className="nowrap">{fmtDateTime(r.when)}</span> },
            { key: 'by', header: 'By' },
            { key: 'designation', header: 'Item', render: (r) => <strong>{r.designation}</strong> },
            { key: 'from', header: 'From', render: (r) => <span className="muted">{r.from}</span> },
            { key: 'to', header: 'To' },
            { key: 'effective', header: 'Effective', render: (r) => fmtDate(r.effective) },
            { key: 'status', header: 'Status', render: (r) => (r.workflowInstanceId ? <Link to={`/approvals/${r.workflowInstanceId}`}><StatusBadge status={r.status === 'APPLIED' ? 'APPROVED' : r.status} /></Link> : <StatusBadge status={r.status} />) },
          ]} />
        </section>
      )}

      {submitOpen && (
        <SubmitModal
          designationRows={[...moves.entries()].map(([id, to]) => ({ id: `d${id}`, item: cards.get(id)?.name ?? String(id), from: catName(baseCat.get(id) ?? null), to: catName(to) }))}
          categoryRows={[...catMoves.entries()].map(([id, to]) => ({ id: `c${id}`, item: catName(id), from: path(baseParent.get(id) ?? null), to: path(to) }))}
          moves={[...moves.entries()].map(([designationId, toCategoryId]) => ({ designationId, toCategoryId }))}
          categoryMoves={[...catMoves.entries()].map(([categoryId, toParentId]) => ({ categoryId, toParentId }))}
          onClose={() => setSubmitOpen(false)}
          onDone={() => { discard(); setSubmitOpen(false); void reload(); void history.reload(); }} />
      )}
      {catEdit && <CategoryModal value={catEdit} categories={data.categories} onClose={() => setCatEdit(null)} onDone={() => { setCatEdit(null); void reload(); }} />}
      {newPosition && <ChangeModal target={{ type: 'DESIGNATION_CREATE' }} onClose={() => setNewPosition(false)} />}
    </div>
  );
}

interface MoveRow { id: string; item: string; from: string; to: string }

function SubmitModal({ designationRows, categoryRows, moves, categoryMoves, onClose, onDone }: {
  designationRows: MoveRow[]; categoryRows: MoveRow[];
  moves: { designationId: number; toCategoryId: number | null }[]; categoryMoves: { categoryId: number; toParentId: number | null }[];
  onClose: () => void; onDone: () => void;
}) {
  const toast = useToast();
  const [effectiveDate, setEffectiveDate] = useState(new Date().toISOString().slice(0, 10));
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const submit = async () => {
    setBusy(true);
    setErr(null);
    try {
      await api.post('/establishment/categorization/batches', { effectiveDate, reason, moves, categoryMoves });
      toast('Submitted for approval', 'success');
      onDone();
    } catch (e) {
      setErr(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  const cols = [
    { key: 'item', header: 'Item', render: (r: MoveRow) => <strong>{r.item}</strong> },
    { key: 'from', header: 'From', render: (r: MoveRow) => <span className="muted">{r.from}</span> },
    { key: 'to', header: 'To' },
  ];
  return (
    <Modal title="Submit categorization changes for approval" onClose={onClose} wide footer={
      <><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-gold" disabled={busy || !reason.trim()} onClick={() => void submit()}><Send /> Submit</button></>
    }>
      <div className="stack">
        {err && <Alert kind="danger">{err}</Alert>}
        {designationRows.length > 0 && <><h4 style={{ margin: 0 }}>Position moves</h4><DataTable rows={designationRows} columns={cols} /></>}
        {categoryRows.length > 0 && <><h4 style={{ margin: 0 }}>Category re-nesting</h4><DataTable rows={categoryRows} columns={cols} /></>}
        <div className="form-grid">
          <TextField label="Effective date" type="date" required value={effectiveDate} onChange={setEffectiveDate} />
          <div />
          <TextArea className="span-2" label="Reason / authority" required value={reason} onChange={setReason} help="e.g. Management Services approval letter reference" />
        </div>
      </div>
    </Modal>
  );
}

function CategoryModal({ value, categories, onClose, onDone }: { value: Partial<Category>; categories: Category[]; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [f, setF] = useState(value);
  const [err, setErr] = useState<string | null>(null);
  const save = async () => {
    setErr(null);
    try {
      if (f.id) await api.put(`/establishment/position-categories/${f.id}`, f);
      else await api.post('/establishment/position-categories', f);
      toast('Category saved', 'success');
      onDone();
    } catch (e) {
      setErr(errorMessage(e));
    }
  };
  return (
    <Modal title={f.id ? `Edit ${f.name}` : 'New category'} onClose={onClose} footer={
      <><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-primary" disabled={!f.name || !f.code} onClick={() => void save()}>Save</button></>
    }>
      <div className="stack">
        {err && <Alert kind="danger">{err}</Alert>}
        <div className="form-grid">
          <TextField label="Code" required value={f.code} disabled={!!f.id} onChange={(v) => setF({ ...f, code: v.toUpperCase() })} />
          <TextField label="Display order" type="number" value={f.sortOrder} onChange={(v) => setF({ ...f, sortOrder: Number(v) })} />
          <TextField className="span-2" label="Name (English)" required value={f.name} onChange={(v) => setF({ ...f, name: v })} />
          <TextField label="Name (Sinhala)" lang="si" value={f.nameSi} onChange={(v) => setF({ ...f, nameSi: v })} />
          <TextField label="Name (Tamil)" lang="ta" value={f.nameTa} onChange={(v) => setF({ ...f, nameTa: v })} />
          {!f.id ? (
            <SelectField label="Parent category" value={f.parentId ? String(f.parentId) : ''} onChange={(v) => setF({ ...f, parentId: v ? Number(v) : null })}
              placeholder="— Top level —" options={categories.filter((c) => !c.closed).map((c) => ({ value: String(c.id), label: c.name }))} />
          ) : (
            <div className="field"><label>Parent category</label><div className="muted" style={{ fontSize: 13, paddingTop: 8 }}>Drag the category on the chart to move it (needs approval).</div></div>
          )}
          <div className="field">
            <label htmlFor="cat-color">Colour</label>
            <input id="cat-color" type="color" className="input" style={{ padding: 3 }} value={f.color ?? '#0B1F4B'} onChange={(e) => setF({ ...f, color: e.target.value.toUpperCase() })} />
          </div>
          <div className="field span-2"><Checkbox label="Closed (rejects new positions and sub-categories)" checked={!!f.closed} onChange={(v) => setF({ ...f, closed: v })} /></div>
          <TextArea className="span-2" label="Description" value={f.description} onChange={(v) => setF({ ...f, description: v })} rows={2} />
        </div>
      </div>
    </Modal>
  );
}
