import { FormEvent, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Save } from 'lucide-react';
import { api, errorMessage, fieldErrors } from '@/api/client';
import type { Position } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, Loading, PageHeader, SelectField, TextArea, TextField } from '@/components/ui';
import { DesignationSelect, MasterDataSelect, OrgUnitSelect } from '@/components/pickers';
import type { EmployeeView } from './types';

type Form = Record<string, string | boolean>;

const EMPTY: Form = {
  employeeNo: '', title: '', fullName: '', nameWithInitials: '', nameSi: '', nameTa: '', nic: '', dateOfBirth: '', gender: '', civilStatus: '',
  email: '', phone: '', mobile: '', permanentAddress: '', residentialAddress: '', status: 'ACTIVE', employmentType: 'PERMANENT', employeeCategory: '',
  serviceCategory: '', appointmentDate: '', confirmationDate: '', retirementDate: '', incrementMonth: '', secondedFrom: '', secondmentEndDate: '',
  bankCode: '', bankBranch: '', bankAccountNo: '', restricted: false, orgUnitCode: '', designationId: '', positionId: '',
};

/** Register or update an employee (FR-EMP-001..003, -011). */
export default function EmployeeFormPage() {
  const { id } = useParams();
  const editing = !!id;
  const nav = useNavigate();
  const toast = useToast();
  const { can } = useAuth();
  const existing = useApi<EmployeeView>(editing ? `/employees/${id}` : null);
  const [f, setF] = useState<Form>(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const positions = useApi<Position[]>(f.orgUnitCode ? '/establishment/positions' : null, { orgUnitCode: f.orgUnitCode });

  useEffect(() => {
    if (existing.data) {
      const e = existing.data.employee as unknown as Record<string, unknown>;
      const next: Form = { ...EMPTY };
      for (const k of Object.keys(EMPTY)) next[k] = typeof EMPTY[k] === 'boolean' ? !!e[k] : e[k] === null || e[k] === undefined ? '' : String(e[k]);
      setF(next);
    }
  }, [existing.data]);

  const set = (k: string) => (v: string | boolean) => setF((x) => ({ ...x, [k]: v }));
  const s = (k: string) => (f[k] as string) ?? '';

  const submit = async (ev: FormEvent) => {
    ev.preventDefault();
    setBusy(true);
    setError(null);
    setErrors({});
    const body: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(f)) body[k] = v === '' ? null : v;
    for (const k of ['incrementMonth', 'designationId', 'positionId']) if (body[k] !== null) body[k] = Number(body[k]);
    try {
      if (editing) {
        const { data } = await api.put(`/employees/${id}`, body);
        toast(data.pendingChangeRequestId ? 'Saved. Sensitive changes were sent for second-person approval.' : 'Employee updated', 'success');
        nav(`/employees/${id}`);
      } else {
        const { data } = await api.post('/employees', body);
        toast('Employee registered', 'success');
        nav(`/employees/${data.id}`);
      }
    } catch (e) {
      setError(errorMessage(e));
      setErrors(fieldErrors(e));
    } finally {
      setBusy(false);
    }
  };

  if (editing && existing.loading && !existing.data) return <Loading />;

  return (
    <form onSubmit={submit} noValidate>
      <PageHeader eyebrow="Employee record" title={editing ? `Edit ${existing.data?.employee.nameWithInitials ?? ''}` : 'Register employee'}
        subtitle={editing ? 'Changes to name, NIC, date of birth and bank details require approval by a second authorized officer.' : 'Mandatory fields are marked *. NIC and employee number must be unique.'}
        actions={
          <>
            <button type="button" className="btn btn-outline" onClick={() => nav(-1)}>Cancel</button>
            <button className="btn btn-gold" disabled={busy}><Save /> Save</button>
          </>
        } />
      {error && <div style={{ marginBottom: 16 }}><Alert kind="danger">{error}</Alert></div>}
      <div className="stack">
        <Card title="Identity" accent>
          <div className="form-grid cols-3">
            <TextField label="Employee number" required value={s('employeeNo')} onChange={set('employeeNo')} disabled={editing} error={errors.employeeNo} />
            <MasterDataSelect category="TITLE" label="Title" value={s('title')} onChange={set('title')} />
            <TextField label="NIC" required value={s('nic')} onChange={(v) => set('nic')(v.toUpperCase())} help="9 digits + V/X, or 12 digits" error={errors.nic} />
            <TextField className="span-2" label="Full name" required value={s('fullName')} onChange={set('fullName')} />
            <TextField label="Name with initials" required value={s('nameWithInitials')} onChange={set('nameWithInitials')} />
            <TextField label="Name in Sinhala" value={s('nameSi')} onChange={set('nameSi')} lang="si" />
            <TextField label="Name in Tamil" value={s('nameTa')} onChange={set('nameTa')} lang="ta" />
            <TextField label="Date of birth" type="date" required value={s('dateOfBirth')} onChange={set('dateOfBirth')} />
            <MasterDataSelect category="GENDER" label="Gender" value={s('gender')} onChange={set('gender')} />
            <MasterDataSelect category="CIVIL_STATUS" label="Civil status" value={s('civilStatus')} onChange={set('civilStatus')} />
          </div>
        </Card>
        <Card title="Contact">
          <div className="form-grid cols-3">
            <TextField label="E-mail" type="email" value={s('email')} onChange={set('email')} />
            <TextField label="Mobile" value={s('mobile')} onChange={set('mobile')} />
            <TextField label="Phone" value={s('phone')} onChange={set('phone')} />
            <TextArea className="span-2" label="Permanent address" value={s('permanentAddress')} onChange={set('permanentAddress')} rows={2} />
            <TextArea label="Residential address" value={s('residentialAddress')} onChange={set('residentialAddress')} rows={2} />
          </div>
        </Card>
        <Card title="Employment">
          <div className="form-grid cols-3">
            <MasterDataSelect category="EMPLOYEE_STATUS" label="Status" required value={s('status')} onChange={set('status')} />
            <MasterDataSelect category="EMPLOYMENT_TYPE" label="Employment type" required value={s('employmentType')} onChange={set('employmentType')} />
            <MasterDataSelect category="EMPLOYEE_CATEGORY" label="Employee category" required value={s('employeeCategory')} onChange={set('employeeCategory')} />
            <MasterDataSelect category="SERVICE_CATEGORY" label="Service category" value={s('serviceCategory')} onChange={set('serviceCategory')} />
            <TextField label="Appointment date" type="date" required value={s('appointmentDate')} onChange={set('appointmentDate')} />
            <TextField label="Confirmation date" type="date" value={s('confirmationDate')} onChange={set('confirmationDate')} />
            <TextField label="Retirement date" type="date" value={s('retirementDate')} onChange={set('retirementDate')} help="Leave blank to compute from the retirement age" />
            <SelectField label="Increment month" value={s('incrementMonth')} onChange={set('incrementMonth')}
              options={Array.from({ length: 12 }, (_, i) => ({ value: String(i + 1), label: new Date(2000, i, 1).toLocaleString('en', { month: 'long' }) }))} />
            <div />
            <TextField label="Seconded from (organization)" value={s('secondedFrom')} onChange={set('secondedFrom')} />
            <TextField label="Secondment end date" type="date" value={s('secondmentEndDate')} onChange={set('secondmentEndDate')} />
          </div>
        </Card>
        {!editing && (
          <Card title="Initial placement">
            <div className="form-grid cols-3">
              <OrgUnitSelect required value={s('orgUnitCode')} onChange={(v) => { set('orgUnitCode')(v); set('positionId')(''); }} />
              <DesignationSelect required value={s('designationId')} onChange={set('designationId')} />
              <SelectField label="Position (optional)" value={s('positionId')} onChange={set('positionId')}
                options={(positions.data ?? []).filter((p) => !f.designationId || String(p.designationId) === f.designationId).map((p) => ({ value: String(p.id), label: `${p.title} (${p.code})` }))} />
            </div>
          </Card>
        )}
        <Card title="Bank (salary remittance)">
          <div className="form-grid cols-3">
            <MasterDataSelect category="BANK" label="Bank" value={s('bankCode')} onChange={set('bankCode')} />
            <TextField label="Branch" value={s('bankBranch')} onChange={set('bankBranch')} />
            <TextField label="Account number" value={s('bankAccountNo')} onChange={set('bankAccountNo')} />
          </div>
        </Card>
        {can('EMP_RESTRICTED') && (
          <Card title="Classification">
            <Checkbox label="Restricted record (e.g. investigative or intelligence staff) — visible only to officers with restricted-record access" checked={!!f.restricted} onChange={set('restricted')} />
          </Card>
        )}
      </div>
    </form>
  );
}
