import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus, RefreshCw, TrendingUp } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Checkbox, DL, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtMoney } from '@/components/DataTable';
import { EmployeeSelect } from '@/components/pickers';
import { BASIS_LABELS, num, rateText, today, type AllowanceRate, type AllowanceTypeView, type DeductionHistoryRow, type IncrementRow, type SalaryOverview, type SalaryScale } from './types';

type Tab = 'scales' | 'allowances' | 'employee' | 'increments';

export default function SalaryAdminPage() {
  const { t } = useTranslation();
  const [tab, setTab] = useState<Tab>('employee');
  return (
    <>
      <PageHeader eyebrow={t('nav.compensation', 'Compensation')} title={t('pay.salary', 'Salary administration')}
        subtitle="Effective-dated salary scales, allowances, employee salary placements and annual increments. History is never overwritten." />
      <Tabs value={tab} onChange={setTab} tabs={[
        { key: 'employee', label: 'Employee salary' }, { key: 'scales', label: 'Salary scales' },
        { key: 'allowances', label: 'Allowance types' }, { key: 'increments', label: 'Bulk increments' },
      ]} />
      {tab === 'scales' && <ScalesTab />}
      {tab === 'allowances' && <AllowancesTab />}
      {tab === 'employee' && <EmployeeSalaryTab />}
      {tab === 'increments' && <IncrementsTab />}
    </>
  );
}

function usePost() {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const run = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    try {
      await fn();
      toast(ok, 'success');
      return true;
    } catch (e) {
      toast(errorMessage(e), 'error');
      return false;
    } finally {
      setBusy(false);
    }
  };
  return { busy, run };
}

/* ------------------------------------------------------------------ scales */

function ScalesTab() {
  const { can } = useAuth();
  const [all, setAll] = useState(false);
  const { data, loading, error, reload } = useApi<SalaryScale[]>('/payroll/scales', { all });
  const [edit, setEdit] = useState<null | { mode: 'create' | 'revise'; code?: string }>(null);
  const empty = { code: '', name: '', grade: '', initialSalary: '', incrementAmount: '', maxSteps: '', effectiveFrom: today(), notes: '', revisePlacements: true };
  const [f, setF] = useState(empty);
  const { busy, run } = usePost();

  const save = async () => {
    const body = { ...f, initialSalary: num(f.initialSalary), incrementAmount: num(f.incrementAmount), maxSteps: num(f.maxSteps) };
    const ok = await run(() => (edit?.mode === 'revise' ? api.post(`/payroll/scales/${edit.code}/revise`, body) : api.post('/payroll/scales', body)), 'Salary scale saved');
    if (ok) {
      setEdit(null);
      void reload();
    }
  };

  return (
    <>
      <Card pad={false} title={<Checkbox label="Show all versions (history)" checked={all} onChange={setAll} />}
        actions={can('PAY_CONFIG') && <button className="btn btn-primary btn-sm" onClick={() => { setF(empty); setEdit({ mode: 'create' }); }}><Plus /> New scale</button>}>
        {error && <Alert kind="danger">{error}</Alert>}
        <DataTable<SalaryScale> rows={data} loading={loading}
          columns={[
            { key: 'code', header: 'Code', render: (s) => <span className="mono">{s.code}</span> },
            { key: 'name', header: 'Scale' },
            { key: 'grade', header: 'Grade' },
            { key: 'initialSalary', header: 'Initial', align: 'right', render: (s) => fmtMoney(s.initialSalary) },
            { key: 'incrementAmount', header: 'Increment', align: 'right', render: (s) => fmtMoney(s.incrementAmount) },
            { key: 'maxSteps', header: 'Steps', align: 'right' },
            { key: 'maxSalary', header: 'Maximum', align: 'right', render: (s) => fmtMoney(s.maxSalary) },
            { key: 'effectiveFrom', header: 'Effective', render: (s) => `${fmtDate(s.effectiveFrom)}${s.effectiveTo ? ` – ${fmtDate(s.effectiveTo)}` : ''}` },
            { key: 'actions', header: '', render: (s) => can('PAY_CONFIG') && !s.effectiveTo && (
              <button className="btn btn-ghost btn-sm" onClick={(e) => { e.stopPropagation(); setF({ ...empty, code: s.code, name: s.name, grade: s.grade ?? '', initialSalary: String(s.initialSalary), incrementAmount: String(s.incrementAmount), maxSteps: String(s.maxSteps) }); setEdit({ mode: 'revise', code: s.code }); }}>Revise</button>
            ) },
          ]} />
      </Card>
      {edit && (
        <Modal title={edit.mode === 'revise' ? `Revise scale ${edit.code}` : 'New salary scale'} onClose={() => setEdit(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setEdit(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void save()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <TextField label="Code" required value={f.code} disabled={edit.mode === 'revise'} onChange={(v) => setF({ ...f, code: v })} />
            <TextField label="Name" required value={f.name} onChange={(v) => setF({ ...f, name: v })} />
            <TextField label="Grade" value={f.grade} onChange={(v) => setF({ ...f, grade: v })} />
            <TextField label="Effective from" type="date" required value={f.effectiveFrom} onChange={(v) => setF({ ...f, effectiveFrom: v })} />
            <TextField label="Initial salary (LKR)" type="number" required value={f.initialSalary} onChange={(v) => setF({ ...f, initialSalary: v })} />
            <TextField label="Increment amount (LKR)" type="number" required value={f.incrementAmount} onChange={(v) => setF({ ...f, incrementAmount: v })} />
            <TextField label="Number of increment steps" type="number" required value={f.maxSteps} onChange={(v) => setF({ ...f, maxSteps: v })} />
            <TextArea label="Notes" value={f.notes} onChange={(v) => setF({ ...f, notes: v })} />
          </div>
          {edit.mode === 'revise' && (
            <div style={{ marginTop: 12 }}>
              <Checkbox label="Re-place employees on this scale at the same step from the revision date" checked={f.revisePlacements} onChange={(v) => setF({ ...f, revisePlacements: v })} />
            </div>
          )}
        </Modal>
      )}
    </>
  );
}

/* ------------------------------------------------------------------ allowances */

function AllowancesTab() {
  const { can } = useAuth();
  const { data, loading, error, reload } = useApi<AllowanceTypeView[]>('/payroll/allowance-types');
  const [type, setType] = useState<null | { id?: number; code: string; name: string; nameSi: string; nameTa: string; appliesToAll: boolean; active: boolean; description: string }>(null);
  const [rateFor, setRateFor] = useState<AllowanceTypeView | null>(null);
  const [rf, setRf] = useState({ basis: 'FIXED', value: '', effectiveFrom: today(), notes: '' });
  const history = useApi<AllowanceRate[]>(rateFor ? `/payroll/allowance-types/${rateFor.type.id}/rates` : null);
  const { busy, run } = usePost();

  const saveType = async () => {
    if (!type) return;
    const ok = await run(() => (type.id ? api.put(`/payroll/allowance-types/${type.id}`, type) : api.post('/payroll/allowance-types', type)), 'Allowance type saved');
    if (ok) {
      setType(null);
      void reload();
    }
  };
  const saveRate = async () => {
    if (!rateFor) return;
    const ok = await run(() => api.post(`/payroll/allowance-types/${rateFor.type.id}/rates`, { ...rf, value: num(rf.value) }), 'New rate recorded');
    if (ok) {
      void history.reload();
      void reload();
    }
  };

  return (
    <>
      <Card pad={false} actions={can('PAY_CONFIG') && <button className="btn btn-primary btn-sm" onClick={() => setType({ code: '', name: '', nameSi: '', nameTa: '', appliesToAll: false, active: true, description: '' })}><Plus /> New allowance type</button>}>
        {error && <Alert kind="danger">{error}</Alert>}
        <DataTable<AllowanceTypeView> rows={data} loading={loading} rowKey={(r) => r.type.id} onRowClick={(r) => setRateFor(r)}
          columns={[
            { key: 'code', header: 'Code', render: (r) => <span className="mono">{r.type.code}</span> },
            { key: 'name', header: 'Allowance', render: (r) => r.type.name },
            { key: 'applies', header: 'Applies to', render: (r) => (r.type.appliesToAll ? <Badge tone="info">All employees</Badge> : 'Assigned employees') },
            { key: 'rate', header: 'Current rate', render: (r) => (r.currentRate ? rateText(r.currentRate.basis, r.currentRate.rateValue) : <Badge tone="warning">No rate</Badge>) },
            { key: 'since', header: 'Since', render: (r) => fmtDate(r.currentRate?.effectiveFrom) },
            { key: 'active', header: 'Status', render: (r) => <StatusBadge status={r.type.active ? 'ACTIVE' : 'INACTIVE'} /> },
            { key: 'edit', header: '', render: (r) => can('PAY_CONFIG') && <button className="btn btn-ghost btn-sm" onClick={(e) => { e.stopPropagation(); setType({ id: r.type.id, code: r.type.code, name: r.type.name, nameSi: r.type.nameSi ?? '', nameTa: r.type.nameTa ?? '', appliesToAll: r.type.appliesToAll, active: r.type.active, description: r.type.description ?? '' }); }}>Edit</button> },
          ]} />
      </Card>
      {type && (
        <Modal title={type.id ? `Edit ${type.code}` : 'New allowance type'} onClose={() => setType(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setType(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void saveType()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <TextField label="Code" required disabled={!!type.id} value={type.code} onChange={(v) => setType({ ...type, code: v })} />
            <TextField label="Name (English)" required value={type.name} onChange={(v) => setType({ ...type, name: v })} />
            <TextField label="Name (Sinhala)" value={type.nameSi} onChange={(v) => setType({ ...type, nameSi: v })} />
            <TextField label="Name (Tamil)" value={type.nameTa} onChange={(v) => setType({ ...type, nameTa: v })} />
            <TextArea label="Description" value={type.description} onChange={(v) => setType({ ...type, description: v })} />
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            <Checkbox label="Paid to all in-service employees" checked={type.appliesToAll} onChange={(v) => setType({ ...type, appliesToAll: v })} />
            <Checkbox label="Active" checked={type.active} onChange={(v) => setType({ ...type, active: v })} />
          </div>
        </Modal>
      )}
      {rateFor && (
        <Modal wide title={`Rates — ${rateFor.type.name}`} onClose={() => setRateFor(null)}>
          <DataTable<AllowanceRate> rows={history.data} loading={history.loading}
            columns={[
              { key: 'rate', header: 'Rate', render: (r) => rateText(r.basis, r.rateValue) },
              { key: 'effectiveFrom', header: 'From', render: (r) => fmtDate(r.effectiveFrom) },
              { key: 'effectiveTo', header: 'To', render: (r) => fmtDate(r.effectiveTo) },
              { key: 'notes', header: 'Notes' },
            ]} />
          {can('PAY_CONFIG') && (
            <>
              <h3 style={{ marginTop: 18 }}>Add a new rate</h3>
              <div className="form-grid">
                <SelectField label="Basis" value={rf.basis} onChange={(v) => setRf({ ...rf, basis: v })} placeholder={null}
                  options={[{ value: 'FIXED', label: BASIS_LABELS.FIXED }, { value: 'PERCENT_OF_BASIC', label: BASIS_LABELS.PERCENT_OF_BASIC }]} />
                <TextField label={rf.basis === 'FIXED' ? 'Amount (LKR)' : 'Percentage'} type="number" value={rf.value} onChange={(v) => setRf({ ...rf, value: v })} />
                <TextField label="Effective from" type="date" value={rf.effectiveFrom} onChange={(v) => setRf({ ...rf, effectiveFrom: v })} />
                <TextField label="Notes" value={rf.notes} onChange={(v) => setRf({ ...rf, notes: v })} />
              </div>
              <div className="row" style={{ marginTop: 12 }}><button className="btn btn-primary" disabled={busy} onClick={() => void saveRate()}>Add rate</button></div>
            </>
          )}
        </Modal>
      )}
    </>
  );
}

/* ------------------------------------------------------------------ employee salary */

function EmployeeSalaryTab() {
  const { can } = useAuth();
  const [emp, setEmp] = useState('');
  const { data, loading, error, reload } = useApi<SalaryOverview>(emp ? `/payroll/employees/${emp}/salary` : null);
  const history = useApi<DeductionHistoryRow[]>(emp ? `/payroll/employees/${emp}/deduction-history` : null);
  const scales = useApi<SalaryScale[]>('/payroll/scales');
  const types = useApi<AllowanceTypeView[]>('/payroll/allowance-types');
  const [place, setPlace] = useState<null | { scaleCode: string; step: string; effectiveFrom: string; reason: string; reference: string; remarks: string }>(null);
  const [allow, setAllow] = useState<null | { allowanceTypeId: string; amountOverride: string; effectiveFrom: string; effectiveTo: string; remarks: string }>(null);
  const { busy, run } = usePost();

  const savePlace = async () => {
    if (!place) return;
    const ok = await run(() => api.post(`/payroll/employees/${emp}/salary`, { ...place, step: num(place.step) }), 'Salary placement recorded');
    if (ok) { setPlace(null); void reload(); }
  };
  const saveAllow = async () => {
    if (!allow) return;
    const ok = await run(() => api.post(`/payroll/employees/${emp}/allowances`, {
      allowanceTypeId: num(allow.allowanceTypeId), amountOverride: num(allow.amountOverride), effectiveFrom: allow.effectiveFrom,
      effectiveTo: allow.effectiveTo || null, remarks: allow.remarks,
    }), 'Allowance assigned');
    if (ok) { setAllow(null); void reload(); }
  };
  const endAllowance = async (id: number) => {
    const to = window.prompt('End date (YYYY-MM-DD)', today());
    if (!to) return;
    if (await run(() => api.post(`/payroll/employee-allowances/${id}/end`, { effectiveTo: to }), 'Allowance assignment ended')) void reload();
  };
  const selScale = scales.data?.find((s) => s.code === place?.scaleCode);

  return (
    <div className="stack">
      <div className="filters"><EmployeeSelect value={emp} onChange={setEmp} /></div>
      {error && <Alert kind="danger">{error}</Alert>}
      {emp && data && !loading && (
        <>
          <div className="grid grid-2">
            <Card title="Current placement" actions={can('PAY_MANAGE') && <button className="btn btn-primary btn-sm" onClick={() => setPlace({ scaleCode: data.current?.scaleCode ?? '', step: String(data.current?.step ?? 0), effectiveFrom: today(), reason: data.current ? 'REVISION' : 'INITIAL', reference: '', remarks: '' })}><TrendingUp /> New placement</button>}>
              {data.current ? (
                <DL items={[
                  ['Employee', `${data.employee.employeeNo} — ${data.employee.nameWithInitials}`],
                  ['Scale', `${data.current.scaleCode}${data.scale ? ` — ${data.scale.name}` : ''}`],
                  ['Step', `${data.current.step}${data.scale ? ` of ${data.scale.maxSteps}` : ''}`],
                  ['Basic salary', `LKR ${fmtMoney(data.current.basicSalary)}`],
                  ['Since', fmtDate(data.current.effectiveFrom)],
                  ['Increment month', data.incrementMonth ? new Date(2000, data.incrementMonth - 1, 1).toLocaleString('en-GB', { month: 'long' }) : '—'],
                  ['Active loans', data.activeLoans],
                ]} />
              ) : <Alert kind="warning">No salary placement. The employee will receive no pay until placed on a scale.</Alert>}
            </Card>
            <Card title="Allowances" actions={can('PAY_MANAGE') && <button className="btn btn-outline btn-sm" onClick={() => setAllow({ allowanceTypeId: '', amountOverride: '', effectiveFrom: today(), effectiveTo: '', remarks: '' })}><Plus /> Assign</button>}>
              <DataTable rows={data.allowances} empty="Only all-staff allowances apply"
                columns={[
                  { key: 'name', header: 'Allowance' },
                  { key: 'amountOverride', header: 'Amount', align: 'right', render: (a) => (a.amountOverride != null ? fmtMoney(a.amountOverride) : 'Standard rate') },
                  { key: 'period', header: 'Period', render: (a) => `${fmtDate(a.effectiveFrom)} – ${a.effectiveTo ? fmtDate(a.effectiveTo) : 'open'}` },
                  { key: 'end', header: '', render: (a) => can('PAY_MANAGE') && a.current && <button className="btn btn-ghost btn-sm" onClick={() => void endAllowance(a.id)}>End</button> },
                ]} />
            </Card>
          </div>
          <Card title="Salary history" pad={false}>
            <DataTable rows={data.history}
              columns={[
                { key: 'effectiveFrom', header: 'From', render: (s) => fmtDate(s.effectiveFrom) },
                { key: 'effectiveTo', header: 'To', render: (s) => fmtDate(s.effectiveTo) },
                { key: 'scaleCode', header: 'Scale', render: (s) => <span className="mono">{s.scaleCode}</span> },
                { key: 'step', header: 'Step', align: 'right' },
                { key: 'basicSalary', header: 'Basic', align: 'right', render: (s) => fmtMoney(s.basicSalary) },
                { key: 'reason', header: 'Reason', render: (s) => <StatusBadge status={s.reason} /> },
                { key: 'reference', header: 'Reference' },
                { key: 'createdBy', header: 'By' },
              ]} />
          </Card>
          <Card title="Scheme memberships" pad={false}>
            <DataTable rows={data.memberships} empty="No memberships"
              columns={[
                { key: 'name', header: 'Deduction / scheme' },
                { key: 'category', header: 'Category' },
                { key: 'memberNo', header: 'Member no' },
                { key: 'period', header: 'Period', render: (m) => `${fmtDate(m.effectiveFrom)} – ${m.effectiveTo ? fmtDate(m.effectiveTo) : 'open'}` },
              ]} />
          </Card>
          <Card title="Deduction history (finalized payroll)" pad={false}>
            <DataTable<DeductionHistoryRow> rows={history.data} loading={history.loading} rowKey={(r, i) => `${r.period}-${r.code}-${i}`} empty="No finalized payroll yet"
              columns={[
                { key: 'period', header: 'Period', render: (r) => <span className="mono">{r.period}</span> },
                { key: 'componentType', header: 'Type', render: (r) => <StatusBadge status={r.componentType} /> },
                { key: 'description', header: 'Component' },
                { key: 'amount', header: 'Amount', align: 'right', render: (r) => fmtMoney(r.amount) },
              ]} />
          </Card>
        </>
      )}
      {place && (
        <Modal title="New salary placement" onClose={() => setPlace(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setPlace(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void savePlace()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <SelectField label="Salary scale" required value={place.scaleCode} onChange={(v) => setPlace({ ...place, scaleCode: v })}
              options={(scales.data ?? []).map((s) => ({ value: s.code, label: `${s.code} — ${s.name}` }))} />
            <TextField label={`Step${selScale ? ` (0–${selScale.maxSteps})` : ''}`} type="number" required value={place.step} onChange={(v) => setPlace({ ...place, step: v })}
              help={selScale && place.step !== '' ? `Basic: LKR ${fmtMoney(Number(selScale.initialSalary) + Number(selScale.incrementAmount) * Number(place.step))}` : undefined} />
            <TextField label="Effective from" type="date" required value={place.effectiveFrom} onChange={(v) => setPlace({ ...place, effectiveFrom: v })} />
            <SelectField label="Reason" value={place.reason} onChange={(v) => setPlace({ ...place, reason: v })} placeholder={null}
              options={['INITIAL', 'INCREMENT', 'REVISION', 'PROMOTION', 'CORRECTION'].map((r) => ({ value: r, label: r.charAt(0) + r.slice(1).toLowerCase() }))} />
            <TextField label="Reference" value={place.reference} onChange={(v) => setPlace({ ...place, reference: v })} />
            <TextField label="Remarks" value={place.remarks} onChange={(v) => setPlace({ ...place, remarks: v })} />
          </div>
        </Modal>
      )}
      {allow && (
        <Modal title="Assign allowance" onClose={() => setAllow(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setAllow(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void saveAllow()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <SelectField label="Allowance" required value={allow.allowanceTypeId} onChange={(v) => setAllow({ ...allow, allowanceTypeId: v })}
              options={(types.data ?? []).filter((x) => x.type.active).map((x) => ({ value: String(x.type.id), label: `${x.type.name} (${x.currentRate ? rateText(x.currentRate.basis, x.currentRate.rateValue) : 'no rate'})` }))} />
            <TextField label="Amount override (LKR)" type="number" value={allow.amountOverride} onChange={(v) => setAllow({ ...allow, amountOverride: v })} help="Leave blank to use the standard rate" />
            <TextField label="From" type="date" required value={allow.effectiveFrom} onChange={(v) => setAllow({ ...allow, effectiveFrom: v })} />
            <TextField label="To (exclusive)" type="date" value={allow.effectiveTo} onChange={(v) => setAllow({ ...allow, effectiveTo: v })} />
            <TextField label="Remarks" value={allow.remarks} onChange={(v) => setAllow({ ...allow, remarks: v })} />
          </div>
        </Modal>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ bulk increments */

function IncrementsTab() {
  const { can } = useAuth();
  const now = new Date();
  const [month, setMonth] = useState(String(now.getMonth() + 1));
  const [date, setDate] = useState(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`);
  const { data, loading, error, reload } = useApi<IncrementRow[]>('/payroll/increments/preview', { month, effectiveDate: date });
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const { busy, run } = usePost();
  const eligible = (data ?? []).filter((r) => r.status === 'ELIGIBLE');

  const post = async () => {
    if (!window.confirm(`Post annual increments for ${selected.size} employee(s) effective ${date}?`)) return;
    const ok = await run(() => api.post('/payroll/increments/post', { month: Number(month), effectiveDate: date, employeeIds: [...selected] }), 'Increments posted');
    if (ok) { setSelected(new Set()); void reload(); }
  };
  const toggle = (id: number) => {
    const s = new Set(selected);
    if (s.has(id)) s.delete(id); else s.add(id);
    setSelected(s);
  };

  return (
    <>
      <div className="filters">
        <SelectField label="Increment month" value={month} onChange={setMonth} placeholder={null}
          options={Array.from({ length: 12 }, (_, i) => ({ value: String(i + 1), label: new Date(2000, i, 1).toLocaleString('en-GB', { month: 'long' }) }))} />
        <TextField label="Effective date" type="date" value={date} onChange={setDate} />
        <button className="btn btn-outline" onClick={() => void reload()}><RefreshCw /> Preview</button>
      </div>
      <Alert kind="info">Employees whose increment month matches are listed. Review the preview, select employees and post; each posting creates a new salary version (audited).</Alert>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false} title={<Checkbox label={`Select all eligible (${eligible.length})`} checked={eligible.length > 0 && eligible.every((r) => selected.has(r.employeeId))}
        onChange={(v) => setSelected(v ? new Set(eligible.map((r) => r.employeeId)) : new Set())} />}
        actions={can('PAY_MANAGE') && <button className="btn btn-gold btn-sm" disabled={busy || selected.size === 0} onClick={() => void post()}><TrendingUp /> Post {selected.size} increment(s)</button>}>
        <DataTable<IncrementRow> rows={data} loading={loading} rowKey={(r) => r.employeeId} empty="No employees have this increment month"
          columns={[
            { key: 'sel', header: '', render: (r) => r.status === 'ELIGIBLE' && <input type="checkbox" aria-label={`Select ${r.employeeNo}`} checked={selected.has(r.employeeId)} onChange={() => toggle(r.employeeId)} /> },
            { key: 'employeeNo', header: 'Emp. no', render: (r) => <span className="mono">{r.employeeNo}</span> },
            { key: 'name', header: 'Name' },
            { key: 'scaleCode', header: 'Scale' },
            { key: 'current', header: 'Current', align: 'right', render: (r) => (r.currentBasic != null ? `${fmtMoney(r.currentBasic)} (step ${r.currentStep})` : '—') },
            { key: 'new', header: 'After increment', align: 'right', render: (r) => (r.newBasic != null ? `${fmtMoney(r.newBasic)} (step ${r.newStep})` : '—') },
            { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
            { key: 'note', header: 'Note' },
          ]} />
      </Card>
    </>
  );
}
