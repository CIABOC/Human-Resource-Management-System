import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { BellRing, Plus, ShieldCheck } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Badge, Card, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtMoney } from '@/components/DataTable';
import { EmployeeSelect } from '@/components/pickers';
import { num, today, type CoverageView, type Eligibility, type InsuranceScheme } from './types';

interface Form {
  id?: number; employeeId: string; schemeId: string; policyNo: string; startDate: string; expiryDate: string; employeePremium: string;
  employerPremium: string; coverageAmount: string; status: string; remarks: string;
}

/** Employee coverage / policies (FR-BEN-002/003). With `expiring` it lists policies expiring soon (FR-BEN-004). */
export default function CoveragePage({ expiring = false }: { expiring?: boolean }) {
  const { t } = useTranslation();
  const toast = useToast();
  const { can } = useAuth();
  const schemes = useApi<InsuranceScheme[]>('/benefits/schemes');
  const [filter, setFilter] = useState({ schemeId: '', status: 'ACTIVE', employeeId: '' });
  const [days, setDays] = useState('30');
  const list = useApi<CoverageView[]>(expiring ? '/benefits/coverages/expiring' : '/benefits/coverages', expiring ? { days } : filter);
  const [f, setF] = useState<Form | null>(null);
  const eligibility = useApi<Eligibility[]>(f && !f.id && f.employeeId ? '/benefits/eligibility' : null, { employeeId: f?.employeeId });
  const [busy, setBusy] = useState(false);

  const edit = (c?: CoverageView) => setF(c ? {
    id: c.id, employeeId: String(c.employeeId), schemeId: String(c.schemeId), policyNo: c.policyNo ?? '', startDate: c.startDate,
    expiryDate: c.expiryDate ?? '', employeePremium: String(c.employeePremium), employerPremium: String(c.employerPremium),
    coverageAmount: c.coverageAmount == null ? '' : String(c.coverageAmount), status: c.status, remarks: c.remarks ?? '',
  } : { employeeId: '', schemeId: '', policyNo: '', startDate: today(), expiryDate: '', employeePremium: '', employerPremium: '', coverageAmount: '', status: 'ACTIVE', remarks: '' });

  const save = async () => {
    if (!f) return;
    setBusy(true);
    const body = { ...f, employeeId: num(f.employeeId), schemeId: num(f.schemeId), expiryDate: f.expiryDate || null, employeePremium: num(f.employeePremium), employerPremium: num(f.employerPremium), coverageAmount: num(f.coverageAmount) };
    try {
      if (f.id) await api.put(`/benefits/coverages/${f.id}`, body);
      else await api.post('/benefits/coverages', body);
      toast('Coverage saved', 'success');
      setF(null);
      void list.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const runCheck = async () => {
    try {
      const { data } = await api.post<{ expiredMarked: number; notified: number }>('/benefits/coverages/expiry-check');
      toast(`${data.notified} policy holder(s) notified, ${data.expiredMarked} lapsed policies marked expired`, 'success');
      void list.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const verify = async (c: CoverageView) => {
    try {
      const { data } = await api.post<{ message: string }>(`/benefits/coverages/${c.id}/verify`);
      toast(data.message, 'info');
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const schemeOptions = (schemes.data ?? []).map((s) => ({ value: String(s.id), label: `${s.code} — ${s.name}` }));
  return (
    <>
      <PageHeader eyebrow={t('nav.compensation', 'Compensation')} title={expiring ? t('ben.expiring', 'Expiring policies') : t('ben.coverage', 'Coverage and policies')}
        subtitle={expiring ? 'Active policies expiring soon. Employees and HR are notified automatically before expiry (days configurable in settings).' : 'Insurance coverage of employees within your data scope.'}
        actions={
          <>
            {expiring && can('BEN_MANAGE') && <button className="btn btn-outline" onClick={() => void runCheck()}><BellRing /> Run expiry notifications now</button>}
            {!expiring && can('BEN_MANAGE') && <button className="btn btn-gold" onClick={() => edit()}><Plus /> Add coverage</button>}
          </>
        } />
      <div className="filters">
        {expiring ? (
          <TextField label="Within days" type="number" value={days} onChange={setDays} />
        ) : (
          <>
            <SelectField label="Scheme" value={filter.schemeId} onChange={(v) => setFilter({ ...filter, schemeId: v })} options={schemeOptions} placeholder="— All —" />
            <SelectField label="Status" value={filter.status} onChange={(v) => setFilter({ ...filter, status: v })} placeholder="— All —"
              options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'EXPIRED', label: 'Expired' }, { value: 'CANCELLED', label: 'Cancelled' }]} />
            <EmployeeSelect value={filter.employeeId} onChange={(v) => setFilter({ ...filter, employeeId: v })} />
          </>
        )}
      </div>
      {list.error && <Alert kind="danger">{list.error}</Alert>}
      <Card pad={false}>
        <DataTable<CoverageView> rows={list.data} loading={list.loading} onRowClick={can('BEN_MANAGE') ? edit : undefined}
          empty={expiring ? 'No policies expire in this window' : undefined}
          columns={[
            { key: 'employeeNo', header: 'Emp. no', render: (c) => <span className="mono">{c.employeeNo}</span> },
            { key: 'employeeName', header: 'Name' },
            { key: 'schemeName', header: 'Scheme' },
            { key: 'policyNo', header: 'Policy no' },
            { key: 'startDate', header: 'Start', render: (c) => fmtDate(c.startDate) },
            { key: 'expiryDate', header: 'Expiry', render: (c) => <>{fmtDate(c.expiryDate)} {c.status === 'ACTIVE' && c.daysToExpiry != null && c.daysToExpiry <= 30 && <Badge tone="warning">{c.daysToExpiry} days</Badge>}</> },
            { key: 'employeePremium', header: 'Premium', align: 'right', render: (c) => fmtMoney(c.employeePremium) },
            { key: 'coverageAmount', header: 'Coverage', align: 'right', render: (c) => fmtMoney(c.coverageAmount) },
            { key: 'status', header: 'Status', render: (c) => <StatusBadge status={c.status} /> },
            ...(expiring ? [{ key: 'notified', header: 'Notified', render: (c: CoverageView) => fmtDate(c.expiryNotifiedOn) }] : []),
            { key: 'verify', header: '', render: (c) => can('BEN_MANAGE') && <button className="btn btn-ghost btn-sm" title="Verify with provider" onClick={(e) => { e.stopPropagation(); void verify(c); }}><ShieldCheck size={15} /></button> },
          ]} />
      </Card>
      {f && (
        <Modal wide title={f.id ? 'Edit coverage' : 'Add coverage'} onClose={() => setF(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setF(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void save()}>Save</button>
          </>
        }>
          <div className="form-grid">
            {f.id ? <TextField label="Employee" value={list.data?.find((c) => c.id === f.id)?.employeeNo ?? f.employeeId} onChange={() => undefined} disabled /> :
              <EmployeeSelect required value={f.employeeId} onChange={(v) => setF({ ...f, employeeId: v })} />}
            <SelectField label="Scheme" required value={f.schemeId} onChange={(v) => setF({ ...f, schemeId: v })} options={schemeOptions} disabled={!!f.id} />
            <TextField label="Policy no" value={f.policyNo} onChange={(v) => setF({ ...f, policyNo: v })} />
            <TextField label="Start date" type="date" required value={f.startDate} onChange={(v) => setF({ ...f, startDate: v })} />
            <TextField label="Expiry date" type="date" value={f.expiryDate} onChange={(v) => setF({ ...f, expiryDate: v })} />
            <TextField label="Coverage amount (LKR)" type="number" value={f.coverageAmount} onChange={(v) => setF({ ...f, coverageAmount: v })} help="Blank = scheme default" />
            <TextField label="Employee premium (LKR / month)" type="number" value={f.employeePremium} onChange={(v) => setF({ ...f, employeePremium: v })} help="Blank = scheme default" />
            <TextField label="Employer premium (LKR / month)" type="number" value={f.employerPremium} onChange={(v) => setF({ ...f, employerPremium: v })} />
            {f.id && <SelectField label="Status" value={f.status} onChange={(v) => setF({ ...f, status: v })} placeholder={null}
              options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'EXPIRED', label: 'Expired' }, { value: 'CANCELLED', label: 'Cancelled' }]} />}
            <TextArea label="Remarks" value={f.remarks} onChange={(v) => setF({ ...f, remarks: v })} />
          </div>
          {!f.id && eligibility.data && (
            <div className="stack" style={{ marginTop: 12 }}>
              {eligibility.data.map((e) => (
                <Alert key={e.schemeId} kind={e.eligible ? (e.covered ? 'info' : 'success') : 'warning'}>
                  <strong>{e.schemeName}:</strong> {e.eligible ? (e.covered ? 'eligible, already covered' : 'eligible') : `not eligible — ${e.reasons.join('; ')}`}
                </Alert>
              ))}
            </div>
          )}
        </Modal>
      )}
    </>
  );
}
