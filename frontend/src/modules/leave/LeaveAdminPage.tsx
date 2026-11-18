import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Plus, RefreshCw, SlidersHorizontal } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import type { Page } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { EmployeeSelect, MasterDataSelect, OrgUnitSelect, useMasterDataLabels } from '@/components/pickers';
import { fmtDays, isoDate, type Balance, type Entitlement, type LeaveApplication, type LeaveType } from './types';

type Tab = 'applications' | 'balances' | 'types';
const STATUSES = ['PENDING', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED'];

/** HR leave administration: all applications, balances (initialise / adjust) and configuration (FR-LEV-001, -002, -006, -009). */
export default function LeaveAdminPage() {
  const { t } = useTranslation();
  const { can } = useAuth();
  const [tab, setTab] = useState<Tab>('applications');
  const tabs: { key: Tab; label: string }[] = [
    { key: 'applications', label: 'Applications' },
    { key: 'balances', label: 'Balances' },
    ...(can('LEAVE_CONFIG') ? [{ key: 'types' as Tab, label: 'Leave types & entitlements' }] : []),
  ];
  return (
    <>
      <PageHeader eyebrow={t('nav.time', 'Time & attendance')} title={t('leave.admin', 'Leave administration')} subtitle="Applications, balances and leave rules" />
      <Tabs tabs={tabs} value={tab} onChange={setTab} />
      <div style={{ marginTop: 14 }}>
        {tab === 'applications' && <Applications />}
        {tab === 'balances' && <Balances />}
        {tab === 'types' && <TypesConfig />}
      </div>
    </>
  );
}

function Applications() {
  const nav = useNavigate();
  const { data: types } = useApi<LeaveType[]>('/leave/types', { activeOnly: false });
  const [f, setF] = useState({ status: '', leaveTypeId: '', orgUnitCode: '', from: '', to: '' });
  const [page, setPage] = useState(0);
  const { data, loading, error } = useApi<Page<LeaveApplication>>('/leave/applications', { ...f, page, size: 25 });
  const set = (k: keyof typeof f) => (v: string) => { setF({ ...f, [k]: v }); setPage(0); };
  return (
    <Card pad={false}>
      <div className="card-body">
        <div className="filters">
          <SelectField label="Status" value={f.status} onChange={set('status')} placeholder="All" options={STATUSES.map((s) => ({ value: s, label: s.charAt(0) + s.slice(1).toLowerCase() }))} />
          <SelectField label="Leave type" value={f.leaveTypeId} onChange={set('leaveTypeId')} placeholder="All" options={(types ?? []).map((x) => ({ value: String(x.id), label: x.name }))} />
          <OrgUnitSelect label="Unit" value={f.orgUnitCode} onChange={set('orgUnitCode')} placeholder="All units" />
          <TextField label="From" type="date" value={f.from} onChange={set('from')} />
          <TextField label="To" type="date" value={f.to} onChange={set('to')} />
        </div>
        {error && <Alert kind="danger">{error}</Alert>}
      </div>
      <DataTable<LeaveApplication>
        rows={data?.content}
        loading={loading}
        page={data?.page}
        totalPages={data?.totalPages}
        totalElements={data?.totalElements}
        onPage={setPage}
        onRowClick={(a) => nav(`/leave/applications/${a.id}`)}
        columns={[
          { key: 'emp', header: 'Employee', render: (a) => a.employee ? `${a.employee.employeeNo} — ${a.employee.nameWithInitials}` : a.employeeId },
          { key: 'unit', header: 'Unit', render: (a) => a.employee?.orgUnitName ?? '—' },
          { key: 'leaveTypeName', header: 'Type' },
          { key: 'period', header: 'Period', render: (a) => <span className="nowrap">{fmtDate(a.startDate)}{a.endDate !== a.startDate ? ` – ${fmtDate(a.endDate)}` : ''}</span> },
          { key: 'days', header: 'Days', align: 'right', render: (a) => fmtDays(a.days) },
          { key: 'status', header: 'Status', render: (a) => <><StatusBadge status={a.status} />{a.cancellationStatus === 'PENDING' && <> <StatusBadge status="PENDING" label="Cancellation pending" /></>}</> },
        ]}
      />
    </Card>
  );
}

function Balances() {
  const { can } = useAuth();
  const toast = useToast();
  const thisYear = new Date().getFullYear();
  const [year, setYear] = useState(String(thisYear));
  const [employeeId, setEmployeeId] = useState('');
  const { data, loading, error, reload } = useApi<Balance[]>('/leave/balances', { year, employeeId });
  const { data: types } = useApi<LeaveType[]>('/leave/types');
  const [adjust, setAdjust] = useState<{ employeeId: string; leaveTypeId: string; days: string; reason: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const initialise = async () => {
    setBusy(true);
    try {
      const r = await api.post<{ created: number; updated: number; employees: number }>('/leave/balances/initialise', { year: Number(year) });
      toast(`Balances initialised: ${r.data.created} created, ${r.data.updated} refreshed (${r.data.employees} employees)`, 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const saveAdjust = async () => {
    if (!adjust) return;
    setBusy(true);
    try {
      await api.post('/leave/balances/adjust', { employeeId: Number(adjust.employeeId), leaveTypeId: Number(adjust.leaveTypeId), year: Number(year), days: Number(adjust.days), reason: adjust.reason });
      toast('Balance adjusted', 'success');
      setAdjust(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card pad={false} title="Leave balances" actions={can('LEAVE_MANAGE') ? (
      <>
        <button className="btn btn-outline" disabled={busy} onClick={() => void initialise()}><RefreshCw /> Initialise {year}</button>
        <button className="btn btn-primary" onClick={() => setAdjust({ employeeId, leaveTypeId: '', days: '', reason: '' })}><SlidersHorizontal /> Adjust balance</button>
      </>
    ) : undefined}>
      <div className="card-body">
        <div className="filters">
          <SelectField label="Year" value={year} placeholder={null} onChange={setYear} options={[thisYear - 1, thisYear, thisYear + 1].map((y) => ({ value: String(y), label: String(y) }))} />
          <EmployeeSelect label="Employee (optional)" value={employeeId} onChange={setEmployeeId} />
        </div>
        {error && <Alert kind="danger">{error}</Alert>}
      </div>
      <DataTable<Balance>
        rows={data}
        loading={loading}
        rowKey={(b, i) => `${b.employeeId}-${b.leaveTypeId}-${i}`}
        empty="No balances for this year. Use 'Initialise' to create them from the entitlements."
        columns={[
          { key: 'emp', header: 'Employee', render: (b) => b.employee ? `${b.employee.employeeNo} — ${b.employee.nameWithInitials}` : b.employeeId },
          { key: 'leaveTypeName', header: 'Leave type' },
          { key: 'entitled', header: 'Entitled', align: 'right', render: (b) => fmtDays(b.entitled) },
          { key: 'adjusted', header: 'Adjusted', align: 'right', render: (b) => fmtDays(b.adjusted) },
          { key: 'used', header: 'Used', align: 'right', render: (b) => fmtDays(b.used) },
          { key: 'pending', header: 'Pending', align: 'right', render: (b) => fmtDays(b.pending) },
          { key: 'available', header: 'Available', align: 'right', render: (b) => <strong>{fmtDays(b.available)}</strong> },
        ]}
      />
      {adjust && (
        <Modal title={`Adjust leave balance (${year})`} onClose={() => setAdjust(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setAdjust(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy || !adjust.employeeId || !adjust.leaveTypeId || !adjust.days || !adjust.reason.trim()} onClick={() => void saveAdjust()}>Save adjustment</button>
          </>
        }>
          <div className="form-grid">
            <EmployeeSelect className="span-2" label="Employee" required value={adjust.employeeId} onChange={(v) => setAdjust({ ...adjust, employeeId: v })} />
            <SelectField label="Leave type" required value={adjust.leaveTypeId} onChange={(v) => setAdjust({ ...adjust, leaveTypeId: v })}
              options={(types ?? []).filter((x) => x.balanceTracked).map((x) => ({ value: String(x.id), label: x.name }))} />
            <TextField label="Days (+ add / − deduct)" type="number" step="0.5" required value={adjust.days} onChange={(v) => setAdjust({ ...adjust, days: v })} />
            <TextArea className="span-2" label="Reason" required value={adjust.reason} onChange={(v) => setAdjust({ ...adjust, reason: v })} help="Recorded in the audit trail; the employee is notified" />
          </div>
        </Modal>
      )}
    </Card>
  );
}

const EMPTY_TYPE = {
  code: '', name: '', nameSi: '', nameTa: '', description: '', paid: true, balanceTracked: true, requiresReason: false, requiresDocument: false,
  documentAfterDays: '', minNoticeDays: '0', maxConsecutiveDays: '', allowBackdated: false, halfDayAllowed: false, sortOrder: '0', active: true,
};
type TypeForm = typeof EMPTY_TYPE;

function TypesConfig() {
  const toast = useToast();
  const { data: types, reload } = useApi<LeaveType[]>('/leave/types', { activeOnly: false });
  const [selected, setSelected] = useState<LeaveType | null>(null);
  const [edit, setEdit] = useState<{ id?: number; form: TypeForm } | null>(null);
  const ents = useApi<Entitlement[]>(selected ? '/leave/entitlements' : null, { leaveTypeId: selected?.id });
  const catLabel = useMasterDataLabels('EMPLOYEE_CATEGORY');
  const [ent, setEnt] = useState<{ employeeCategory: string; daysPerYear: string; effectiveFrom: string; remarks: string } | null>(null);

  const openEdit = (x?: LeaveType) => setEdit(x ? {
    id: x.id,
    form: { ...EMPTY_TYPE, ...x, nameSi: x.nameSi ?? '', nameTa: x.nameTa ?? '', description: x.description ?? '', documentAfterDays: x.documentAfterDays?.toString() ?? '',
      minNoticeDays: String(x.minNoticeDays), maxConsecutiveDays: x.maxConsecutiveDays?.toString() ?? '', sortOrder: String(x.sortOrder) },
  } : { form: { ...EMPTY_TYPE } });

  const num = (v: string) => (v === '' ? null : Number(v));
  const saveType = async () => {
    if (!edit) return;
    const f = edit.form;
    const body = { ...f, documentAfterDays: num(f.documentAfterDays), minNoticeDays: num(f.minNoticeDays), maxConsecutiveDays: num(f.maxConsecutiveDays), sortOrder: num(f.sortOrder) };
    try {
      if (edit.id) await api.put(`/leave/types/${edit.id}`, body);
      else await api.post('/leave/types', body);
      toast('Leave type saved', 'success');
      setEdit(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const saveEnt = async () => {
    if (!ent || !selected) return;
    try {
      await api.post('/leave/entitlements', { leaveTypeId: selected.id, employeeCategory: ent.employeeCategory, daysPerYear: Number(ent.daysPerYear), effectiveFrom: ent.effectiveFrom, remarks: ent.remarks });
      toast('Entitlement saved; the previous version was closed', 'success');
      setEnt(null);
      void ents.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const F = edit?.form;
  const setF = (patch: Partial<TypeForm>) => edit && setEdit({ ...edit, form: { ...edit.form, ...patch } });

  return (
    <div className="grid grid-2">
      <Card pad={false} title="Leave types" actions={<button className="btn btn-primary btn-sm" onClick={() => openEdit()}><Plus /> New type</button>}>
        <DataTable<LeaveType>
          rows={types}
          onRowClick={setSelected}
          columns={[
            { key: 'code', header: 'Code' },
            { key: 'name', header: 'Name' },
            { key: 'rules', header: 'Rules', render: (x) => <span className="muted" style={{ fontSize: 12.5 }}>
              {[x.paid ? 'paid' : 'unpaid', x.balanceTracked ? 'balance' : 'no balance', `notice ${x.minNoticeDays}d`, x.maxConsecutiveDays ? `max ${x.maxConsecutiveDays}d` : null,
                x.requiresDocument ? `document${x.documentAfterDays ? ` > ${x.documentAfterDays}d` : ''}` : null, x.allowBackdated ? 'back-dated' : null, x.halfDayAllowed ? 'half-day' : null].filter(Boolean).join(' · ')}
            </span> },
            { key: 'active', header: 'Status', render: (x) => <StatusBadge status={x.active ? 'ACTIVE' : 'INACTIVE'} /> },
            { key: 'e', header: '', render: (x) => <button className="btn btn-outline btn-sm" onClick={(e) => { e.stopPropagation(); openEdit(x); }}>Edit</button> },
          ]}
        />
      </Card>
      <Card pad={false} title={selected ? `Entitlements · ${selected.name}` : 'Entitlements'} actions={selected?.balanceTracked ? (
        <button className="btn btn-primary btn-sm" onClick={() => setEnt({ employeeCategory: '', daysPerYear: '', effectiveFrom: isoDate(new Date()), remarks: '' })}><Plus /> Set entitlement</button>
      ) : undefined}>
        {!selected ? <div className="card-body muted">Select a leave type to see its entitlements by employee category.</div> : !selected.balanceTracked ? (
          <div className="card-body muted">This leave type does not use a balance.</div>
        ) : (
          <DataTable<Entitlement>
            rows={ents.data}
            loading={ents.loading}
            columns={[
              { key: 'employeeCategory', header: 'Category', render: (x) => catLabel(x.employeeCategory) },
              { key: 'daysPerYear', header: 'Days / year', align: 'right', render: (x) => fmtDays(x.daysPerYear) },
              { key: 'effectiveFrom', header: 'From', render: (x) => fmtDate(x.effectiveFrom) },
              { key: 'effectiveTo', header: 'Until', render: (x) => (x.effectiveTo ? fmtDate(x.effectiveTo) : <StatusBadge status="ACTIVE" label="Current" />) },
              { key: 'remarks', header: 'Remarks' },
            ]}
          />
        )}
      </Card>
      {edit && F && (
        <Modal wide title={edit.id ? `Edit leave type ${F.code}` : 'New leave type'} onClose={() => setEdit(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setEdit(null)}>Cancel</button>
            <button className="btn btn-primary" onClick={() => void saveType()}>Save</button>
          </>
        }>
          <div className="form-grid cols-3">
            <TextField label="Code" required disabled={!!edit.id} value={F.code} onChange={(v) => setF({ code: v.toUpperCase() })} />
            <TextField className="span-2" label="Name (English)" required value={F.name} onChange={(v) => setF({ name: v })} />
            <TextField label="Name (Sinhala)" value={F.nameSi} onChange={(v) => setF({ nameSi: v })} />
            <TextField label="Name (Tamil)" value={F.nameTa} onChange={(v) => setF({ nameTa: v })} />
            <TextField label="Sort order" type="number" value={F.sortOrder} onChange={(v) => setF({ sortOrder: v })} />
            <TextField label="Minimum notice (days)" type="number" value={F.minNoticeDays} onChange={(v) => setF({ minNoticeDays: v })} />
            <TextField label="Max consecutive days" type="number" help="Empty = no limit" value={F.maxConsecutiveDays} onChange={(v) => setF({ maxConsecutiveDays: v })} />
            <TextField label="Document required above (days)" type="number" help="Empty = always when required" value={F.documentAfterDays} onChange={(v) => setF({ documentAfterDays: v })} />
            <TextArea className="span-all" label="Description" value={F.description} onChange={(v) => setF({ description: v })} />
            <Checkbox label="Paid" checked={F.paid} onChange={(v) => setF({ paid: v })} />
            <Checkbox label="Uses a balance" checked={F.balanceTracked} onChange={(v) => setF({ balanceTracked: v })} />
            <Checkbox label="Reason required" checked={F.requiresReason} onChange={(v) => setF({ requiresReason: v })} />
            <Checkbox label="Supporting document required" checked={F.requiresDocument} onChange={(v) => setF({ requiresDocument: v })} />
            <Checkbox label="May be back-dated" checked={F.allowBackdated} onChange={(v) => setF({ allowBackdated: v })} />
            <Checkbox label="Half days allowed" checked={F.halfDayAllowed} onChange={(v) => setF({ halfDayAllowed: v })} />
            <Checkbox label="Active" checked={F.active} onChange={(v) => setF({ active: v })} />
          </div>
        </Modal>
      )}
      {ent && (
        <Modal title={`Set entitlement · ${selected?.name}`} onClose={() => setEnt(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setEnt(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={!ent.employeeCategory || ent.daysPerYear === ''} onClick={() => void saveEnt()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <MasterDataSelect className="span-2" category="EMPLOYEE_CATEGORY" label="Employee category" required value={ent.employeeCategory} onChange={(v) => setEnt({ ...ent, employeeCategory: v })} />
            <TextField label="Days per year" type="number" step="0.5" required value={ent.daysPerYear} onChange={(v) => setEnt({ ...ent, daysPerYear: v })} />
            <TextField label="Effective from" type="date" required value={ent.effectiveFrom} onChange={(v) => setEnt({ ...ent, effectiveFrom: v })} />
            <TextArea className="span-2" label="Remarks / authority" value={ent.remarks} onChange={(v) => setEnt({ ...ent, remarks: v })} />
          </div>
        </Modal>
      )}
    </div>
  );
}
