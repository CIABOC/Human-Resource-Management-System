import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Copy, Plus, Trash2 } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Checkbox, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import type { CategoryAssignment, Criterion, ModelView, Period } from './types';

type TabKey = 'periods' | 'models' | 'categories';

/** Evaluation periods, configurable models with weighted criteria, and category → model assignment (FR-PER-001..003). */
export default function PerformanceConfigPage() {
  const { t } = useTranslation();
  const [tab, setTab] = useState<TabKey>('periods');
  return (
    <>
      <PageHeader eyebrow={t('per.section', 'Performance')} title={t('per.config', 'Evaluation setup')}
        subtitle="Periods, evaluation models and their assignment to employee categories" />
      <Tabs<TabKey> value={tab} onChange={setTab} tabs={[
        { key: 'periods', label: 'Periods' }, { key: 'models', label: 'Models & criteria' }, { key: 'categories', label: 'Category assignment' },
      ]} />
      {tab === 'periods' && <PeriodsTab />}
      {tab === 'models' && <ModelsTab />}
      {tab === 'categories' && <CategoriesTab />}
    </>
  );
}

function PeriodsTab() {
  const toast = useToast();
  const { data, loading, reload } = useApi<Period[]>('/performance/periods');
  const [edit, setEdit] = useState<Partial<Period> | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const save = async () => {
    if (!edit) return;
    setErr(null);
    try {
      const body = { ...edit, dueDate: edit.dueDate || null };
      if (edit.id) await api.put(`/performance/periods/${edit.id}`, body);
      else await api.post('/performance/periods', body);
      setEdit(null);
      toast('Period saved', 'success');
      void reload();
    } catch (e) {
      setErr(errorMessage(e));
    }
  };
  const toggle = async (p: Period) => {
    try {
      await api.post(`/performance/periods/${p.id}/status`, { status: p.status === 'OPEN' ? 'CLOSED' : 'OPEN' });
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <Card pad={false} actions={<button className="btn btn-primary" onClick={() => setEdit({})}><Plus /> New period</button>}>
      <DataTable<Period> rows={data} loading={loading} columns={[
        { key: 'code', header: 'Code' },
        { key: 'name', header: 'Name' },
        { key: 'startDate', header: 'From', render: (p) => fmtDate(p.startDate) },
        { key: 'endDate', header: 'To', render: (p) => fmtDate(p.endDate) },
        { key: 'dueDate', header: 'Due', render: (p) => fmtDate(p.dueDate) },
        { key: 'status', header: 'Status', render: (p) => <StatusBadge status={p.status} /> },
        { key: 'act', header: '', render: (p) => (
          <div className="row">
            <button className="btn btn-ghost btn-sm" onClick={() => setEdit(p)}>Edit</button>
            <button className="btn btn-outline btn-sm" onClick={() => void toggle(p)}>{p.status === 'OPEN' ? 'Close' : 'Re-open'}</button>
          </div>
        ) },
      ]} />
      {edit && (
        <Modal title={edit.id ? 'Edit period' : 'New period'} onClose={() => setEdit(null)} footer={
          <><button className="btn btn-outline" onClick={() => setEdit(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>
        }>
          {err && <Alert kind="danger">{err}</Alert>}
          <div className="form-grid">
            <TextField label="Code" value={edit.code} onChange={(v) => setEdit({ ...edit, code: v.toUpperCase() })} required />
            <TextField label="Name" value={edit.name} onChange={(v) => setEdit({ ...edit, name: v })} required />
            <TextField label="Start date" type="date" value={edit.startDate} onChange={(v) => setEdit({ ...edit, startDate: v })} required />
            <TextField label="End date" type="date" value={edit.endDate} onChange={(v) => setEdit({ ...edit, endDate: v })} required />
            <TextField label="Due date" type="date" value={edit.dueDate} onChange={(v) => setEdit({ ...edit, dueDate: v })} help="Reminders start before this date" />
          </div>
        </Modal>
      )}
    </Card>
  );
}

interface ModelDraft { id?: number; code: string; name: string; description: string; ratingMin: string; ratingMax: string; active: boolean; criteria: Criterion[]; locked: boolean }

function ModelsTab() {
  const toast = useToast();
  const { data, loading, reload } = useApi<ModelView[]>('/performance/models');
  const [edit, setEdit] = useState<ModelDraft | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const total = edit ? edit.criteria.reduce((s, c) => s + (Number(c.weight) || 0), 0) : 0;

  const open = (m?: ModelView) => {
    setErr(null);
    setEdit(m ? {
      id: m.model.id, code: m.model.code, name: m.model.name, description: m.model.description ?? '', ratingMin: String(m.model.ratingMin),
      ratingMax: String(m.model.ratingMax), active: m.model.active, criteria: m.criteria.map((c) => ({ ...c })), locked: m.locked,
    } : { code: '', name: '', description: '', ratingMin: '1', ratingMax: '5', active: true, criteria: [{ criterionType: 'CRITERION', title: '', weight: 100 }], locked: false });
  };
  const save = async () => {
    if (!edit) return;
    setErr(null);
    try {
      const body = { ...edit, ratingMin: Number(edit.ratingMin), ratingMax: Number(edit.ratingMax), criteria: edit.criteria.map((c, i) => ({ ...c, weight: Number(c.weight), sortOrder: i + 1 })) };
      if (edit.id) await api.put(`/performance/models/${edit.id}`, body);
      else await api.post('/performance/models', body);
      setEdit(null);
      toast('Model saved', 'success');
      void reload();
    } catch (e) {
      setErr(errorMessage(e));
    }
  };
  const copy = async (m: ModelView) => {
    const code = window.prompt('Code for the new model version', `${m.model.code}-V2`);
    if (!code) return;
    try {
      await api.post(`/performance/models/${m.model.id}/copy`, { code, name: `${m.model.name} (revised)` });
      toast('Model copied', 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const updC = (i: number, p: Partial<Criterion>) => setEdit((d) => d && { ...d, criteria: d.criteria.map((c, j) => (j === i ? { ...c, ...p } : c)) });

  return (
    <div className="stack">
      <Alert kind="warning">Models marked SAMPLE are illustrative and must be confirmed with Administration (SRS 11.1 #12). CIABOC expects about 10–15 models for its employee types.</Alert>
      <Card pad={false} actions={<button className="btn btn-primary" onClick={() => open()}><Plus /> New model</button>}>
        <DataTable<ModelView> rows={data} loading={loading} rowKey={(m) => m.model.id} onRowClick={open} columns={[
          { key: 'code', header: 'Code', render: (m) => <>{m.model.code} {m.model.sample && <Badge tone="warning">Sample</Badge>}</> },
          { key: 'name', header: 'Name', render: (m) => m.model.name },
          { key: 'scale', header: 'Scale', render: (m) => `${m.model.ratingMin}–${m.model.ratingMax}` },
          { key: 'criteria', header: 'Criteria', align: 'right', render: (m) => m.criteria.length },
          { key: 'categories', header: 'Categories', render: (m) => m.categories.join(', ') || '—' },
          { key: 'evaluations', header: 'Used', align: 'right', render: (m) => m.evaluations },
          { key: 'active', header: 'Status', render: (m) => <StatusBadge status={m.model.active ? 'ACTIVE' : 'INACTIVE'} /> },
          { key: 'act', header: '', render: (m) => <button className="btn btn-ghost btn-sm" onClick={(e) => { e.stopPropagation(); void copy(m); }}><Copy /> Copy</button> },
        ]} />
      </Card>
      {edit && (
        <Modal title={edit.id ? `Model ${edit.code}` : 'New evaluation model'} onClose={() => setEdit(null)} wide footer={
          <><button className="btn btn-outline" onClick={() => setEdit(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Save</button></>
        }>
          <div className="stack">
            {err && <Alert kind="danger">{err}</Alert>}
            {edit.locked && <Alert kind="info">This model is used by recorded evaluations. Criteria, weights and scale are locked; copy it to create a revised version.</Alert>}
            <div className="form-grid cols-3">
              <TextField label="Code" value={edit.code} onChange={(v) => setEdit({ ...edit, code: v.toUpperCase() })} required />
              <TextField label="Name" value={edit.name} onChange={(v) => setEdit({ ...edit, name: v })} required className="span-2" />
              <TextField label="Rating minimum" type="number" value={edit.ratingMin} onChange={(v) => setEdit({ ...edit, ratingMin: v })} disabled={edit.locked} />
              <TextField label="Rating maximum" type="number" value={edit.ratingMax} onChange={(v) => setEdit({ ...edit, ratingMax: v })} disabled={edit.locked} />
              <Checkbox label="Active" checked={edit.active} onChange={(v) => setEdit({ ...edit, active: v })} />
              <TextArea label="Description" value={edit.description} onChange={(v) => setEdit({ ...edit, description: v })} className="span-all" rows={2} />
            </div>
            <div className="row between">
              <h4 style={{ margin: 0 }}>Criteria, KPIs and objectives</h4>
              <span className={total === 100 ? 'badge badge-success' : 'badge badge-danger'}>Total weight {total}%</span>
            </div>
            {edit.criteria.map((c, i) => (
              <div key={c.id ?? `n${i}`} className="form-grid cols-3" style={{ borderBottom: '1px solid var(--border)', paddingBottom: 10 }}>
                <SelectField label="Type" value={c.criterionType} onChange={(v) => updC(i, { criterionType: v as Criterion['criterionType'] })} placeholder={null}
                  disabled={edit.locked} options={[{ value: 'CRITERION', label: 'Criterion' }, { value: 'KPI', label: 'KPI' }, { value: 'OBJECTIVE', label: 'Objective' }]} />
                <TextField label="Title" value={c.title} onChange={(v) => updC(i, { title: v })} disabled={edit.locked} />
                <div className="row" style={{ alignItems: 'flex-end' }}>
                  <TextField label="Weight %" type="number" value={c.weight} onChange={(v) => updC(i, { weight: Number(v) })} disabled={edit.locked} />
                  {!edit.locked && <button className="btn btn-ghost btn-sm" aria-label="Remove" onClick={() => setEdit({ ...edit, criteria: edit.criteria.filter((_, j) => j !== i) })}><Trash2 /></button>}
                </div>
                <TextField label="Description / guidance" value={c.description} onChange={(v) => updC(i, { description: v })} className="span-all" />
              </div>
            ))}
            {!edit.locked && <button className="btn btn-outline" onClick={() => setEdit({ ...edit, criteria: [...edit.criteria, { criterionType: 'CRITERION', title: '', weight: 0 }] })}><Plus /> Add line</button>}
          </div>
        </Modal>
      )}
    </div>
  );
}

function CategoriesTab() {
  const toast = useToast();
  const { data, loading, reload } = useApi<CategoryAssignment[]>('/performance/category-models');
  const models = useApi<ModelView[]>('/performance/models');
  const assign = async (category: string, modelId: string) => {
    try {
      await api.put(`/performance/category-models/${category}`, { modelId: modelId ? Number(modelId) : null });
      toast('Assignment saved', 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  return (
    <Card pad={false} title="Each employee category uses its own model">
      <DataTable<CategoryAssignment> rows={data} loading={loading} rowKey={(c) => c.employeeCategory} columns={[
        { key: 'categoryName', header: 'Employee category' },
        { key: 'model', header: 'Evaluation model', render: (c) => (
          <select className="select" value={c.modelId ?? ''} onChange={(e) => void assign(c.employeeCategory, e.target.value)} aria-label={`Model for ${c.categoryName}`}>
            <option value="">— Not assigned —</option>
            {(models.data ?? []).filter((m) => m.model.active).map((m) => <option key={m.model.id} value={m.model.id}>{m.model.code} — {m.model.name}</option>)}
          </select>
        ) },
        { key: 'sample', header: '', render: (c) => c.sample && <Badge tone="warning">Sample model</Badge> },
      ]} />
    </Card>
  );
}
