import { DragEvent, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, GripVertical, Lock, Pencil, Plus, RotateCcw, Send, X, ZoomIn, ZoomOut } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Checkbox, Loading, Modal, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import { ChangeModal } from './ChangeForms';

interface Card { designationId: number; code: string; name: string; grade?: string; pending: boolean }
interface Category { id: number; code: string; name: string; nameSi?: string; nameTa?: string; color: string; sortOrder: number; closed: boolean; description?: string; positions: Card[] }
interface PendingBatch { id: number; workflowInstanceId?: number; status: string; effectiveDate: string; moves: number }
interface Chart { rootName: string; asOf: string; categories: Category[]; unassigned: Card[]; pendingBatches: PendingBatch[] }
interface HistoryRow { lineId: number; when: string; by: string; designation: string; from: string; to: string; effective: string; status: string; workflowInstanceId?: number }

/** Draft key: designationId → target category id (null = unassigned). */
type Draft = Map<number, number | null>;

/**
 * Drag-and-drop position categorization (org chart). Moves are held as a browser draft; nothing changes officially
 * until the batch is submitted and approved, effective from the chosen date.
 */
export default function CategorizationChart() {
  const { can } = useAuth();
  const toast = useToast();
  const manage = can('EST_MANAGE');
  const { data, loading, error, reload } = useApi<Chart>('/establishment/categorization');
  const history = useApi<HistoryRow[]>(can('EST_VIEW') ? '/establishment/categorization/history' : null);
  const [draft, setDraft] = useState<Draft>(new Map());
  const [hover, setHover] = useState<string | null>(null);
  const [scale, setScale] = useState(1);
  const [submitOpen, setSubmitOpen] = useState(false);
  const [catEdit, setCatEdit] = useState<Partial<Category> | null>(null);
  const [newPosition, setNewPosition] = useState(false);
  const dragId = useRef<number | null>(null);
  const viewport = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLDivElement>(null);

  // Base category of every card, and lookup of cards by id.
  const { base, cards } = useMemo(() => {
    const b = new Map<number, number | null>();
    const c = new Map<number, Card>();
    data?.categories.forEach((cat) => cat.positions.forEach((p) => { b.set(p.designationId, cat.id); c.set(p.designationId, p); }));
    data?.unassigned.forEach((p) => { b.set(p.designationId, null); c.set(p.designationId, p); });
    return { base: b, cards: c };
  }, [data]);

  const placeOf = (id: number) => (draft.has(id) ? draft.get(id)! : base.get(id) ?? null);
  const membersOf = (catId: number | null) => [...cards.values()].filter((c) => placeOf(c.designationId) === catId).sort((a, b) => a.name.localeCompare(b.name));
  const catName = (id: number | null) => (id === null ? 'Unassigned' : data?.categories.find((c) => c.id === id)?.name ?? '?');

  const move = (id: number, to: number | null) => {
    const card = cards.get(id);
    if (!card || card.pending) return;
    if (to !== null && data?.categories.find((c) => c.id === to)?.closed) {
      toast('That category is closed and does not accept positions', 'error');
      return;
    }
    setDraft((d) => {
      const n = new Map(d);
      if ((base.get(id) ?? null) === to) n.delete(id);
      else n.set(id, to);
      return n;
    });
  };

  const onDragStart = (e: DragEvent, id: number) => {
    dragId.current = id;
    e.dataTransfer.setData('text/plain', String(id));
    e.dataTransfer.effectAllowed = 'move';
  };
  const dropZone = (key: string, target: number | null, closed = false) => ({
    onDragOver: (e: DragEvent) => {
      if (!manage) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = closed ? 'none' : 'move';
      setHover(key);
    },
    onDragLeave: () => setHover((h) => (h === key ? null : h)),
    onDrop: (e: DragEvent) => {
      e.preventDefault();
      setHover(null);
      const id = dragId.current ?? Number(e.dataTransfer.getData('text/plain'));
      dragId.current = null;
      if (id) move(id, target);
    },
  });

  const fit = () => {
    if (!viewport.current || !canvas.current) return;
    const w = canvas.current.scrollWidth;
    setScale(Math.max(0.4, Math.min(1.4, (viewport.current.clientWidth - 24) / w)));
  };

  if (loading && !data) return <Loading />;
  if (error) return <Alert kind="danger">{error}</Alert>;
  if (!data) return null;

  const changes = [...draft.entries()];
  const totalCategorised = [...cards.keys()].filter((id) => placeOf(id) !== null).length;
  const unassigned = membersOf(null);

  return (
    <div className="stack">
      <div className="row between" style={{ alignItems: 'flex-start' }}>
        <p className="muted" style={{ margin: 0, maxWidth: 720 }}>
          Drag a position into a category on the chart. Moves are held as a draft. The official structure changes only after you
          save and submit for approval.
        </p>
        {manage && (
          <div className="row">
            <button className="btn btn-outline" onClick={() => setCatEdit({ color: '#0B1F4B', sortOrder: data.categories.length + 1, closed: false })}><Plus /> New Category</button>
            <button className="btn btn-gold" onClick={() => setNewPosition(true)}><Plus /> New Position</button>
          </div>
        )}
      </div>

      <div className="cat-banner">
        <span className={`cat-banner-pill ${changes.length ? 'has' : ''}`}>{changes.length ? `${changes.length} change${changes.length > 1 ? 's' : ''}` : 'No changes'}</span>
        <span style={{ flex: 1 }}>{changes.length ? 'Draft not yet submitted. Save and submit to send these moves for approval.' : 'Drag a position into a category to start a draft.'}</span>
        {changes.length > 0 && (
          <>
            <button className="btn btn-sm btn-outline" style={{ background: 'transparent', color: '#fff', borderColor: 'rgba(255,255,255,.4)' }} onClick={() => setDraft(new Map())}><RotateCcw /> Discard</button>
            <button className="btn btn-sm btn-gold" onClick={() => setSubmitOpen(true)}><Send /> Save &amp; submit</button>
          </>
        )}
      </div>

      {data.pendingBatches.map((b) => (
        <Alert key={b.id} kind="warning">
          {b.moves} move(s) effective {fmtDate(b.effectiveDate)} are {b.status === 'RETURNED' ? 'returned for correction' : 'awaiting approval'}
          {b.workflowInstanceId && <> — <Link to={`/approvals/${b.workflowInstanceId}`}>open request #{b.workflowInstanceId}</Link></>}. Those positions are locked until decided.
        </Alert>
      ))}

      <div className="cat-top">
        <section className="card">
          <div className="card-header"><h3>Category Tree</h3></div>
          <div className="cat-tree">
            <div className="cat-tree-row root"><span>{data.rootName}</span><span>{totalCategorised}</span></div>
            {data.categories.map((c) => (
              <div key={c.id} className={`cat-tree-row ${c.closed ? 'closed' : ''}`}>
                <span className="row" style={{ gap: 6 }}>
                  <span className="cat-dot" style={{ background: c.color }} />
                  {c.name}{c.closed && ' · closed'}
                </span>
                <span className="row" style={{ gap: 4 }}>
                  {membersOf(c.id).length}
                  {manage && <button className="btn btn-ghost btn-sm" style={{ height: 22, padding: '0 4px' }} aria-label={`Edit ${c.name}`} onClick={() => setCatEdit(c)}><Pencil size={13} /></button>}
                </span>
              </div>
            ))}
            <div className="cat-tree-row"><span>Unassigned</span><span>{unassigned.length}</span></div>
            <div className="faint" style={{ fontSize: 12, marginTop: 8, textAlign: 'center' }}><Lock size={12} /> Closed categories reject drops</div>
          </div>
        </section>
        <section className={`card ${hover === 'unassigned' ? 'cat-drop-hover' : ''}`} {...dropZone('unassigned', null)}>
          <div className="card-header" style={{ display: 'block' }}>
            <h3>Unassigned positions</h3>
            <div className="muted" style={{ fontSize: 12.5 }}>New positions waiting for a category. Drag one into the chart, or drop a card here to unassign it.</div>
          </div>
          <div className="card-body row" style={{ gap: 10 }}>
            {unassigned.length === 0 && <span className="faint">All positions are categorised.</span>}
            {unassigned.map((p) => (
              <span key={p.designationId} className={`cat-chip ${draft.has(p.designationId) ? 'moved' : ''} ${p.pending ? 'pending' : ''}`}
                draggable={manage && !p.pending} onDragStart={(e) => onDragStart(e, p.designationId)} title={p.pending ? 'Awaiting approval' : p.code}>
                {p.pending && <Lock size={12} />} {p.name}
                {draft.has(p.designationId) && <button aria-label="Undo" onClick={() => setDraft((d) => { const n = new Map(d); n.delete(p.designationId); return n; })}><X size={13} /></button>}
              </span>
            ))}
          </div>
        </section>
      </div>

      <section className="card">
        <div className="card-header" style={{ display: 'block' }}>
          <h3>Position structure</h3>
          <div className="muted" style={{ fontSize: 12.5 }}>{data.categories.length} categories · {totalCategorised} positions · {unassigned.length} unassigned</div>
        </div>
        <div className="cat-canvas" ref={viewport}>
          <div ref={canvas} className="cat-scale" style={{ transform: `scale(${scale})` }}>
            <div className="cat-root"><span className="cat-root-badge">{data.rootName.slice(0, 2).toUpperCase()}</span><div><div className="n">{data.rootName}</div><div className="m">{totalCategorised} categorised positions</div></div></div>
            <div className="cat-trunk" />
            <div className="cat-columns">
              {data.categories.map((c) => {
                const members = membersOf(c.id);
                return (
                  <div key={c.id} className={`cat-col ${hover === `c${c.id}` ? (c.closed ? 'cat-drop-deny' : 'cat-drop-hover') : ''}`} {...dropZone(`c${c.id}`, c.id, c.closed)}>
                    <div className="cat-col-head" style={{ background: c.color }}>
                      <span>{c.name}{c.closed ? ' · closed' : ''}</span>
                      <span className="row" style={{ gap: 4 }}><span className="cat-count">{members.length}</span>{c.closed && <Lock size={12} />}</span>
                    </div>
                    <div className="cat-col-body">
                      {members.map((p) => (
                        <div key={p.designationId} className={`cat-card ${draft.has(p.designationId) ? 'moved' : ''} ${p.pending ? 'pending' : ''}`}
                          draggable={manage && !p.pending} onDragStart={(e) => onDragStart(e, p.designationId)} title={p.pending ? 'Awaiting approval' : `${p.code}${p.grade ? ' · ' + p.grade : ''}`}>
                          {p.pending ? <Lock size={13} className="faint" /> : <GripVertical size={13} className="faint" />}
                          <span className="cat-card-name">{p.name}</span>
                          {manage && !p.pending && <button aria-label={`Unassign ${p.name}`} onClick={() => move(p.designationId, null)}><X size={12} /></button>}
                        </div>
                      ))}
                      {members.length === 0 && <div className="faint" style={{ fontSize: 11.5, padding: '8px 4px' }}>{c.closed ? 'Closed' : 'Drop positions here'}</div>}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
          <div className="cat-zoom">
            <button aria-label="Zoom in" onClick={() => setScale((s) => Math.min(1.6, s + 0.1))}><ZoomIn /></button>
            <button aria-label="Zoom out" onClick={() => setScale((s) => Math.max(0.4, s - 0.1))}><ZoomOut /></button>
            <button className="fit" onClick={fit}>Fit</button>
          </div>
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
            { key: 'designation', header: 'Designation', render: (r) => <strong>{r.designation}</strong> },
            { key: 'from', header: 'From', render: (r) => <span className="muted">{r.from}</span> },
            { key: 'to', header: 'To' },
            { key: 'effective', header: 'Effective', render: (r) => fmtDate(r.effective) },
            { key: 'status', header: 'Status', render: (r) => (r.workflowInstanceId ? <Link to={`/approvals/${r.workflowInstanceId}`}><StatusBadge status={r.status === 'APPLIED' ? 'APPROVED' : r.status} /></Link> : <StatusBadge status={r.status} />) },
          ]} />
        </section>
      )}

      {submitOpen && (
        <SubmitModal changes={changes} name={(id) => cards.get(id)?.name ?? String(id)} from={(id) => catName(base.get(id) ?? null)} to={catName}
          onClose={() => setSubmitOpen(false)}
          onDone={() => { setDraft(new Map()); setSubmitOpen(false); void reload(); void history.reload(); }} />
      )}
      {catEdit && <CategoryModal value={catEdit} onClose={() => setCatEdit(null)} onDone={() => { setCatEdit(null); void reload(); }} />}
      {newPosition && <ChangeModal target={{ type: 'DESIGNATION_CREATE' }} onClose={() => setNewPosition(false)} />}
    </div>
  );
}

function SubmitModal({ changes, name, from, to, onClose, onDone }: {
  changes: [number, number | null][]; name: (id: number) => string; from: (id: number) => string; to: (id: number | null) => string;
  onClose: () => void; onDone: () => void;
}) {
  const toast = useToast();
  const [effectiveDate, setEffectiveDate] = useState(new Date().toISOString().slice(0, 10));
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    setBusy(true);
    try {
      await api.post('/establishment/categorization/batches', { effectiveDate, reason, moves: changes.map(([designationId, toCategoryId]) => ({ designationId, toCategoryId })) });
      toast('Submitted for approval', 'success');
      onDone();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title="Save and submit for approval" onClose={onClose} wide footer={
      <><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-gold" disabled={busy || !reason.trim()} onClick={() => void submit()}><Send /> Submit</button></>
    }>
      <div className="stack">
        <DataTable rows={changes.map(([id, t]) => ({ id, d: name(id), f: from(id), t: to(t) }))} columns={[
          { key: 'd', header: 'Designation', render: (r) => <strong>{r.d}</strong> },
          { key: 'f', header: 'From' },
          { key: 't', header: 'To' },
        ]} />
        <div className="form-grid">
          <TextField label="Effective date" type="date" required value={effectiveDate} onChange={setEffectiveDate} />
          <div />
          <TextArea className="span-2" label="Reason / authority" required value={reason} onChange={setReason} help="e.g. Management Services approval letter reference" />
        </div>
      </div>
    </Modal>
  );
}

function CategoryModal({ value, onClose, onDone }: { value: Partial<Category>; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const [f, setF] = useState(value);
  const save = async () => {
    try {
      if (f.id) await api.put(`/establishment/position-categories/${f.id}`, f);
      else await api.post('/establishment/position-categories', f);
      toast('Category saved', 'success');
      onDone();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  return (
    <Modal title={f.id ? `Edit ${f.name}` : 'New category'} onClose={onClose} footer={
      <><button className="btn btn-outline" onClick={onClose}>Cancel</button><button className="btn btn-primary" disabled={!f.name || !f.code} onClick={() => void save()}>Save</button></>
    }>
      <div className="form-grid">
        <TextField label="Code" required value={f.code} disabled={!!f.id} onChange={(v) => setF({ ...f, code: v.toUpperCase() })} />
        <TextField label="Display order" type="number" value={f.sortOrder} onChange={(v) => setF({ ...f, sortOrder: Number(v) })} />
        <TextField className="span-2" label="Name (English)" required value={f.name} onChange={(v) => setF({ ...f, name: v })} />
        <TextField label="Name (Sinhala)" lang="si" value={f.nameSi} onChange={(v) => setF({ ...f, nameSi: v })} />
        <TextField label="Name (Tamil)" lang="ta" value={f.nameTa} onChange={(v) => setF({ ...f, nameTa: v })} />
        <div className="field">
          <label htmlFor="cat-color">Colour</label>
          <input id="cat-color" type="color" className="input" style={{ padding: 3 }} value={f.color ?? '#0B1F4B'} onChange={(e) => setF({ ...f, color: e.target.value.toUpperCase() })} />
        </div>
        <div className="field" style={{ justifyContent: 'flex-end' }}>
          <Checkbox label="Closed (rejects new positions)" checked={!!f.closed} onChange={(v) => setF({ ...f, closed: v })} />
        </div>
        <TextArea className="span-2" label="Description" value={f.description} onChange={(v) => setF({ ...f, description: v })} rows={2} />
      </div>
    </Modal>
  );
}
