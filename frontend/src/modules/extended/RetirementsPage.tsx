import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { BellRing, Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { EmployeeSelect, MasterDataSelect, employeeLabel, useMasterDataLabels } from '@/components/pickers';
import type { CaseSummary, ClearanceTemplate, ForecastRow, SeparationType } from './types';
import { today, titleCase } from './types';

type Tab = 'forecast' | 'cases' | 'checklist';

export default function RetirementsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'forecast';
  return (
    <>
      <PageHeader title={t('extended.retirements', 'Retirement & offboarding')}
        subtitle="Retirement forecast, separation cases, clearance checklist and final settlement" />
      <Tabs<Tab> value={tab} onChange={(k) => setParams({ tab: k })} tabs={[
        { key: 'forecast', label: 'Retirement forecast' },
        { key: 'cases', label: 'Separation cases' },
        { key: 'checklist', label: 'Clearance checklist' },
      ]} />
      <div style={{ marginTop: 16 }}>
        {tab === 'forecast' && <Forecast />}
        {tab === 'cases' && <Cases />}
        {tab === 'checklist' && <Checklist />}
      </div>
    </>
  );
}

function Forecast() {
  const { can } = useAuth();
  const toast = useToast();
  const [months, setMonths] = useState('12');
  const { data, loading, error, reload } = useApi<ForecastRow[]>('/extended/retirements/forecast', { months });
  const [start, setStart] = useState<ForecastRow | null>(null);
  const nav = useNavigate();

  const runNotices = async () => {
    try {
      const r = await api.post<{ sent: number }>('/extended/retirements/run-notices');
      toast(`${r.data.sent} advance notice(s) sent to HR`, 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false} title={
        <div className="filters">
          <SelectField label="Horizon" value={months} placeholder={null} onChange={setMonths}
            options={['3', '6', '12', '24', '36'].map((m) => ({ value: m, label: `Next ${m} months` }))} />
        </div>
      } actions={can('EXT_MANAGE') && <button className="btn btn-outline btn-sm" onClick={() => void runNotices()}><BellRing /> Send due notices now</button>}>
        <DataTable<ForecastRow>
          rows={data}
          loading={loading}
          rowKey={(r) => r.employee.id}
          empty="No retirements in this period"
          columns={[
            { key: 'employee', header: 'Employee', render: (r) => employeeLabel(r.employee) },
            { key: 'designation', header: 'Designation', render: (r) => r.employee.designation ?? '—' },
            { key: 'unit', header: 'Unit', render: (r) => r.employee.orgUnitName ?? r.employee.orgUnitCode ?? '—' },
            { key: 'retirementDate', header: 'Retirement date', render: (r) => fmtDate(r.retirementDate) },
            { key: 'monthsRemaining', header: 'Remaining', render: (r) => (
              <span className={`badge badge-${r.monthsRemaining < 3 ? 'danger' : r.monthsRemaining < 6 ? 'warning' : 'info'}`}>
                {r.monthsRemaining < 1 ? `${r.daysRemaining} days` : `${r.monthsRemaining} months`}
              </span>
            ) },
            { key: 'case', header: 'Separation case', render: (r) => r.openCaseId
              ? <button className="btn btn-ghost btn-sm" onClick={() => nav(`/extended/separations/${r.openCaseId}`)}>Case #{r.openCaseId}</button>
              : can('EXT_MANAGE') ? <button className="btn btn-outline btn-sm" onClick={() => setStart(r)}>Start offboarding</button> : <span className="faint">—</span> },
          ]}
        />
      </Card>
      {start && <OpenCaseModal employeeId={start.employee.id} label={employeeLabel(start.employee)} type="RETIREMENT" date={start.retirementDate}
        onClose={() => setStart(null)} onSaved={(id) => { setStart(null); void reload(); nav(`/extended/separations/${id}`); }} />}
    </>
  );
}

function Cases() {
  const { can } = useAuth();
  const nav = useNavigate();
  const [status, setStatus] = useState('OPEN');
  const { data, loading, error } = useApi<CaseSummary[]>('/extended/separations', { status });
  const reason = useMasterDataLabels('SEPARATION_REASON');
  const [open, setOpen] = useState(false);
  return (
    <>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false} title={
        <div className="filters">
          <SelectField label="Status" value={status} onChange={setStatus} placeholder="All"
            options={['OPEN', 'COMPLETED', 'CANCELLED'].map((s) => ({ value: s, label: titleCase(s) }))} />
        </div>
      } actions={can('EXT_MANAGE') && <button className="btn btn-gold btn-sm" onClick={() => setOpen(true)}><Plus /> New separation case</button>}>
        <DataTable<CaseSummary>
          rows={data}
          loading={loading}
          onRowClick={(c) => nav(`/extended/separations/${c.id}`)}
          columns={[
            { key: 'id', header: 'Case', render: (c) => `#${c.id}` },
            { key: 'employee', header: 'Employee', render: (c) => employeeLabel(c.employee) },
            { key: 'separationType', header: 'Type', render: (c) => titleCase(c.separationType) },
            { key: 'separationReason', header: 'Reason', render: (c) => reason(c.separationReason) },
            { key: 'separationDate', header: 'Separation date', render: (c) => fmtDate(c.separationDate) },
            { key: 'progress', header: 'Clearance', render: (c) => `${c.itemsDone} / ${c.itemsTotal}` },
            { key: 'settlement', header: 'Settlement', render: (c) => c.settlementRecorded ? <span className="badge badge-success">Recorded</span> : <span className="faint">Pending</span> },
            { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
          ]}
        />
      </Card>
      {open && <OpenCaseModal onClose={() => setOpen(false)} onSaved={(id) => nav(`/extended/separations/${id}`)} />}
    </>
  );
}

function OpenCaseModal({ employeeId, label, type, date, onClose, onSaved }: {
  employeeId?: number; label?: string; type?: string; date?: string; onClose: () => void; onSaved: (id: number) => void;
}) {
  const toast = useToast();
  const { data: types } = useApi<SeparationType[]>('/extended/separation-types');
  const [f, setF] = useState({ employeeId: employeeId ? String(employeeId) : '', separationType: type ?? 'RESIGNATION', separationReason: '', separationDate: date ?? today(), remarks: '' });
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      const r = await api.post<{ summary: { id: number } }>('/extended/separations', { ...f, employeeId: Number(f.employeeId), separationReason: f.separationReason || null });
      toast('Separation case opened; clearance requests sent to the responsible officers', 'success');
      onSaved(r.data.summary.id);
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title="Open separation case" onClose={onClose} footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={busy || !f.employeeId || !f.separationDate} onClick={() => void save()}>Open case</button>
      </>
    }>
      <div className="form-grid">
        {employeeId ? <TextField label="Employee" value={label ?? ''} onChange={() => undefined} disabled />
          : <EmployeeSelect required value={f.employeeId} onChange={(v) => setF({ ...f, employeeId: v })} />}
        <SelectField label="Separation type" required value={f.separationType} placeholder={null} onChange={(v) => setF({ ...f, separationType: v })}
          options={(types ?? []).filter((x) => x.active).map((x) => ({ value: x.code, label: x.nameEn }))} />
        <MasterDataSelect category="SEPARATION_REASON" label="Separation reason (blank = default for type)" value={f.separationReason}
          onChange={(v) => setF({ ...f, separationReason: v })} />
        <TextField label="Separation date" type="date" required value={f.separationDate} onChange={(v) => setF({ ...f, separationDate: v })} />
        <TextArea label="Remarks" value={f.remarks} onChange={(v) => setF({ ...f, remarks: v })} />
      </div>
    </Modal>
  );
}

function Checklist() {
  const { can } = useAuth();
  const { data, loading, error, reload } = useApi<ClearanceTemplate[]>('/extended/clearance-templates');
  const [edit, setEdit] = useState<Partial<ClearanceTemplate> | null>(null);
  return (
    <>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false} title="Clearance checklist items"
        actions={can('EXT_MANAGE') && <button className="btn btn-outline btn-sm" onClick={() => setEdit({ active: true, sortOrder: 10 })}><Plus /> Add item</button>}>
        <DataTable<ClearanceTemplate>
          rows={data}
          loading={loading}
          onRowClick={can('EXT_MANAGE') ? (r) => setEdit(r) : undefined}
          columns={[
            { key: 'sortOrder', header: '#' },
            { key: 'code', header: 'Code', render: (r) => <span className="mono">{r.code}</span> },
            { key: 'nameEn', header: 'Item' },
            { key: 'responsibleRole', header: 'Cleared by', render: (r) => titleCase(r.responsibleRole) },
            { key: 'appliesTo', header: 'Applies to', render: (r) => r.appliesTo ? titleCase(r.appliesTo) : 'All separation types' },
            { key: 'active', header: 'Active', render: (r) => <StatusBadge status={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
          ]}
        />
      </Card>
      {edit && <TemplateModal value={edit} onClose={() => setEdit(null)} onSaved={() => { setEdit(null); void reload(); }} />}
    </>
  );
}

const ROLES = ['HR_ADMIN', 'FINANCE_OFFICER', 'ADMIN_OFFICER', 'SYSTEM_ADMIN', 'DIVISION_HEAD', 'ESTABLISHMENT_OFFICER', 'MANAGEMENT'];

function TemplateModal({ value, onClose, onSaved }: { value: Partial<ClearanceTemplate>; onClose: () => void; onSaved: () => void }) {
  const toast = useToast();
  const [f, setF] = useState<Partial<ClearanceTemplate>>(value);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      if (f.id) await api.put(`/extended/clearance-templates/${f.id}`, f);
      else await api.post('/extended/clearance-templates', f);
      toast('Checklist item saved', 'success');
      onSaved();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal title={f.id ? `Edit ${f.code}` : 'New checklist item'} onClose={onClose} footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" disabled={busy || !f.code || !f.nameEn || !f.responsibleRole} onClick={() => void save()}>Save</button>
      </>
    }>
      <div className="form-grid">
        <TextField label="Code" required value={f.code} disabled={!!f.id} onChange={(v) => setF({ ...f, code: v.toUpperCase() })} />
        <SelectField label="Cleared by role" required value={f.responsibleRole} onChange={(v) => setF({ ...f, responsibleRole: v })}
          options={ROLES.map((r) => ({ value: r, label: titleCase(r) }))} />
        <TextField label="Name (English)" required value={f.nameEn} onChange={(v) => setF({ ...f, nameEn: v })} />
        <TextField label="Name (Sinhala)" value={f.nameSi} onChange={(v) => setF({ ...f, nameSi: v })} />
        <TextField label="Name (Tamil)" value={f.nameTa} onChange={(v) => setF({ ...f, nameTa: v })} />
        <TextField label="Applies to (comma-separated types, blank = all)" value={f.appliesTo} onChange={(v) => setF({ ...f, appliesTo: v.toUpperCase() })} />
        <TextField label="Order" type="number" value={f.sortOrder} onChange={(v) => setF({ ...f, sortOrder: Number(v) })} />
        <TextArea label="Description" value={f.description} onChange={(v) => setF({ ...f, description: v })} />
        <Checkbox label="Active" checked={f.active !== false} onChange={(v) => setF({ ...f, active: v })} />
      </div>
    </Modal>
  );
}
