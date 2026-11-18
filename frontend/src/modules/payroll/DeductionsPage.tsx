import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Checkbox, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import { EmployeeSelect } from '@/components/pickers';
import { BASIS_LABELS, num, rateText, today, type Basis, type DeductionRate, type DeductionTypeView, type MembershipView } from './types';

const BASES: Basis[] = ['NONE', 'FIXED', 'PERCENT_OF_BASIC', 'PERCENT_OF_GROSS'];

export default function DeductionsPage() {
  const { t } = useTranslation();
  const [tab, setTab] = useState<'types' | 'members'>('types');
  return (
    <>
      <PageHeader eyebrow={t('nav.compensation', 'Compensation')} title={t('pay.deductions', 'Deductions and schemes')}
        subtitle="Statutory, scheme and other deductions with effective-dated employee and employer rates, and per-employee scheme membership." />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'types', label: 'Deduction types and rates' }, { key: 'members', label: 'Memberships' }]} />
      {tab === 'types' ? <TypesTab /> : <MembersTab />}
    </>
  );
}

function useRun() {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  return {
    busy,
    run: async (fn: () => Promise<unknown>, ok: string) => {
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
    },
  };
}

interface TypeForm { id?: number; code: string; name: string; nameSi: string; nameTa: string; category: string; membershipRequired: boolean; pensionRelated: boolean; active: boolean; description: string }

function TypesTab() {
  const { can } = useAuth();
  const { data, loading, error, reload } = useApi<DeductionTypeView[]>('/payroll/deduction-types');
  const [form, setForm] = useState<TypeForm | null>(null);
  const [rateFor, setRateFor] = useState<DeductionTypeView | null>(null);
  const history = useApi<DeductionRate[]>(rateFor ? `/payroll/deduction-types/${rateFor.type.id}/rates` : null);
  const [rf, setRf] = useState({ employeeBasis: 'PERCENT_OF_BASIC', employeeValue: '', employerBasis: 'NONE', employerValue: '', effectiveFrom: today(), toBeConfirmed: false, notes: '' });
  const { busy, run } = useRun();

  const saveType = async () => {
    if (!form) return;
    if (await run(() => (form.id ? api.put(`/payroll/deduction-types/${form.id}`, form) : api.post('/payroll/deduction-types', form)), 'Deduction type saved')) {
      setForm(null);
      void reload();
    }
  };
  const saveRate = async () => {
    if (!rateFor) return;
    if (await run(() => api.post(`/payroll/deduction-types/${rateFor.type.id}/rates`, { ...rf, employeeValue: num(rf.employeeValue), employerValue: num(rf.employerValue) }), 'New rate recorded')) {
      void history.reload();
      void reload();
    }
  };
  const basisOptions = BASES.map((b) => ({ value: b, label: BASIS_LABELS[b] }));

  return (
    <>
      <Alert kind="warning">Rates marked “to be confirmed” must be verified with Finance before go-live (SRS 11.1 #9).</Alert>
      <div style={{ height: 12 }} />
      <Card pad={false} actions={can('PAY_CONFIG') && <button className="btn btn-primary btn-sm" onClick={() => setForm({ code: '', name: '', nameSi: '', nameTa: '', category: 'OTHER', membershipRequired: true, pensionRelated: false, active: true, description: '' })}><Plus /> New deduction type</button>}>
        {error && <Alert kind="danger">{error}</Alert>}
        <DataTable<DeductionTypeView> rows={data} loading={loading} rowKey={(r) => r.type.id} onRowClick={setRateFor}
          columns={[
            { key: 'code', header: 'Code', render: (r) => <span className="mono">{r.type.code}</span> },
            { key: 'name', header: 'Deduction', render: (r) => <>{r.type.name} {r.type.pensionRelated && <Badge tone="info">Pension</Badge>}</> },
            { key: 'category', header: 'Category', render: (r) => <StatusBadge status={r.type.category} /> },
            { key: 'membership', header: 'Applies to', render: (r) => (r.type.membershipRequired ? 'Members only' : 'All employees') },
            { key: 'emp', header: 'Employee rate', render: (r) => rateText(r.currentRate?.employeeBasis, r.currentRate?.employeeValue) },
            { key: 'er', header: 'Employer rate', render: (r) => rateText(r.currentRate?.employerBasis, r.currentRate?.employerValue) },
            { key: 'tbc', header: '', render: (r) => (r.currentRate ? r.currentRate.toBeConfirmed && <Badge tone="warning">To be confirmed</Badge> : <Badge tone="danger">No rate</Badge>) },
            { key: 'active', header: 'Status', render: (r) => <StatusBadge status={r.type.active ? 'ACTIVE' : 'INACTIVE'} /> },
            { key: 'edit', header: '', render: (r) => can('PAY_CONFIG') && <button className="btn btn-ghost btn-sm" onClick={(e) => { e.stopPropagation(); setForm({ id: r.type.id, code: r.type.code, name: r.type.name, nameSi: r.type.nameSi ?? '', nameTa: r.type.nameTa ?? '', category: r.type.category, membershipRequired: r.type.membershipRequired, pensionRelated: r.type.pensionRelated, active: r.type.active, description: r.type.description ?? '' }); }}>Edit</button> },
          ]} />
      </Card>
      {form && (
        <Modal title={form.id ? `Edit ${form.code}` : 'New deduction type'} onClose={() => setForm(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setForm(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void saveType()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <TextField label="Code" required disabled={!!form.id} value={form.code} onChange={(v) => setForm({ ...form, code: v })} />
            <TextField label="Name (English)" required value={form.name} onChange={(v) => setForm({ ...form, name: v })} />
            <TextField label="Name (Sinhala)" value={form.nameSi} onChange={(v) => setForm({ ...form, nameSi: v })} />
            <TextField label="Name (Tamil)" value={form.nameTa} onChange={(v) => setForm({ ...form, nameTa: v })} />
            <SelectField label="Category" value={form.category} onChange={(v) => setForm({ ...form, category: v })} placeholder={null}
              options={[{ value: 'STATUTORY', label: 'Statutory' }, { value: 'SCHEME', label: 'Scheme' }, { value: 'OTHER', label: 'Other' }]} />
            <TextArea label="Description" value={form.description} onChange={(v) => setForm({ ...form, description: v })} />
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            <Checkbox label="Only for employees with a membership" checked={form.membershipRequired} onChange={(v) => setForm({ ...form, membershipRequired: v })} />
            <Checkbox label="Pension-related" checked={form.pensionRelated} onChange={(v) => setForm({ ...form, pensionRelated: v })} />
            <Checkbox label="Active" checked={form.active} onChange={(v) => setForm({ ...form, active: v })} />
          </div>
        </Modal>
      )}
      {rateFor && (
        <Modal wide title={`Rate history — ${rateFor.type.name}`} onClose={() => setRateFor(null)}>
          <DataTable<DeductionRate> rows={history.data} loading={history.loading}
            columns={[
              { key: 'emp', header: 'Employee', render: (r) => rateText(r.employeeBasis, r.employeeValue) },
              { key: 'er', header: 'Employer', render: (r) => rateText(r.employerBasis, r.employerValue) },
              { key: 'from', header: 'From', render: (r) => fmtDate(r.effectiveFrom) },
              { key: 'to', header: 'To', render: (r) => fmtDate(r.effectiveTo) },
              { key: 'tbc', header: '', render: (r) => r.toBeConfirmed && <Badge tone="warning">TBC</Badge> },
              { key: 'notes', header: 'Notes' },
            ]} />
          {can('PAY_CONFIG') && (
            <>
              <h3 style={{ marginTop: 18 }}>Add a new rate</h3>
              <div className="form-grid">
                <SelectField label="Employee basis" value={rf.employeeBasis} onChange={(v) => setRf({ ...rf, employeeBasis: v })} placeholder={null} options={basisOptions} />
                <TextField label="Employee value" type="number" value={rf.employeeValue} onChange={(v) => setRf({ ...rf, employeeValue: v })} disabled={rf.employeeBasis === 'NONE'} />
                <SelectField label="Employer basis" value={rf.employerBasis} onChange={(v) => setRf({ ...rf, employerBasis: v })} placeholder={null} options={basisOptions} />
                <TextField label="Employer value" type="number" value={rf.employerValue} onChange={(v) => setRf({ ...rf, employerValue: v })} disabled={rf.employerBasis === 'NONE'} />
                <TextField label="Effective from" type="date" value={rf.effectiveFrom} onChange={(v) => setRf({ ...rf, effectiveFrom: v })} />
                <TextField label="Notes" value={rf.notes} onChange={(v) => setRf({ ...rf, notes: v })} />
              </div>
              <div className="row" style={{ marginTop: 12 }}>
                <Checkbox label="To be confirmed with Finance" checked={rf.toBeConfirmed} onChange={(v) => setRf({ ...rf, toBeConfirmed: v })} />
                <button className="btn btn-primary" disabled={busy} onClick={() => void saveRate()}>Add rate</button>
              </div>
            </>
          )}
        </Modal>
      )}
    </>
  );
}

function MembersTab() {
  const { can } = useAuth();
  const types = useApi<DeductionTypeView[]>('/payroll/deduction-types');
  const [typeId, setTypeId] = useState('');
  const [emp, setEmp] = useState('');
  const { data, loading, error, reload } = useApi<MembershipView[]>('/payroll/memberships', { deductionTypeId: typeId, employeeId: emp });
  const [form, setForm] = useState<null | { employeeId: string; deductionTypeId: string; memberNo: string; employeeValueOverride: string; employerValueOverride: string; effectiveFrom: string; remarks: string }>(null);
  const { busy, run } = useRun();
  const typeOptions = (types.data ?? []).map((t) => ({ value: String(t.type.id), label: `${t.type.code} — ${t.type.name}` }));

  const save = async () => {
    if (!form) return;
    if (!form.employeeId) return;
    if (await run(() => api.post(`/payroll/employees/${form.employeeId}/memberships`, {
      deductionTypeId: num(form.deductionTypeId), memberNo: form.memberNo, employeeValueOverride: num(form.employeeValueOverride),
      employerValueOverride: num(form.employerValueOverride), effectiveFrom: form.effectiveFrom, remarks: form.remarks,
    }), 'Membership recorded')) {
      setForm(null);
      void reload();
    }
  };
  const end = async (m: MembershipView) => {
    const to = window.prompt(`End ${m.code} membership of ${m.employeeNo} on (YYYY-MM-DD)`, today());
    if (to && (await run(() => api.post(`/payroll/memberships/${m.id}/end`, { effectiveTo: to }), 'Membership ended'))) void reload();
  };

  return (
    <>
      <div className="filters">
        <SelectField label="Deduction / scheme" value={typeId} onChange={setTypeId} options={typeOptions} placeholder="— All —" />
        <EmployeeSelect value={emp} onChange={setEmp} />
        {emp && <button className="btn btn-ghost" onClick={() => setEmp('')}>Clear employee</button>}
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false} actions={can('PAY_MANAGE') && <button className="btn btn-primary btn-sm" onClick={() => setForm({ employeeId: emp, deductionTypeId: typeId, memberNo: '', employeeValueOverride: '', employerValueOverride: '', effectiveFrom: today(), remarks: '' })}><Plus /> Add membership</button>}>
        <DataTable<MembershipView> rows={data} loading={loading}
          columns={[
            { key: 'employeeNo', header: 'Emp. no', render: (m) => <span className="mono">{m.employeeNo}</span> },
            { key: 'employeeName', header: 'Name' },
            { key: 'name', header: 'Deduction / scheme' },
            { key: 'memberNo', header: 'Member no' },
            { key: 'override', header: 'Rate override', render: (m) => (m.employeeValueOverride != null || m.employerValueOverride != null ? `${m.employeeValueOverride ?? '—'} / ${m.employerValueOverride ?? '—'}` : '—') },
            { key: 'period', header: 'Period', render: (m) => `${fmtDate(m.effectiveFrom)} – ${m.effectiveTo ? fmtDate(m.effectiveTo) : 'open'}` },
            { key: 'current', header: '', render: (m) => (m.current ? <Badge tone="success">Current</Badge> : null) },
            { key: 'end', header: '', render: (m) => can('PAY_MANAGE') && !m.effectiveTo && <button className="btn btn-ghost btn-sm" onClick={() => void end(m)}>End</button> },
          ]} />
      </Card>
      {form && (
        <Modal title="Add scheme membership" onClose={() => setForm(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setForm(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy || !form.employeeId} onClick={() => void save()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <EmployeeSelect value={form.employeeId} onChange={(v) => setForm({ ...form, employeeId: v })} required />
            <SelectField label="Deduction / scheme" required value={form.deductionTypeId} onChange={(v) => setForm({ ...form, deductionTypeId: v })} options={typeOptions} />
            <TextField label="Member no" value={form.memberNo} onChange={(v) => setForm({ ...form, memberNo: v })} />
            <TextField label="Effective from" type="date" required value={form.effectiveFrom} onChange={(v) => setForm({ ...form, effectiveFrom: v })} />
            <TextField label="Employee rate override" type="number" value={form.employeeValueOverride} onChange={(v) => setForm({ ...form, employeeValueOverride: v })} help="Same basis as the scheme rate; blank = standard" />
            <TextField label="Employer rate override" type="number" value={form.employerValueOverride} onChange={(v) => setForm({ ...form, employerValueOverride: v })} />
            <TextField label="Remarks" value={form.remarks} onChange={(v) => setForm({ ...form, remarks: v })} />
          </div>
        </Modal>
      )}
    </>
  );
}
