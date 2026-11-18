import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDateTime, fmtMoney } from '@/components/DataTable';
import { EmployeeSelect } from '@/components/pickers';
import { num, thisPeriod, type PayrollAdjustmentView, type PayrollRun } from './types';

/** Controlled payroll corrections, picked up by the next run (FR-PAY-033). */
export default function AdjustmentsPage() {
  const { t } = useTranslation();
  const toast = useToast();
  const { can } = useAuth();
  const [status, setStatus] = useState('OPEN');
  const { data, loading, error, reload } = useApi<PayrollAdjustmentView[]>('/payroll/adjustments', { status });
  const runs = useApi<PayrollRun[]>('/payroll/runs');
  const blank = { employeeId: '', adjustmentType: 'EARNING', code: '', description: '', amount: '', targetPeriod: thisPeriod(), relatedRunId: '', reason: '' };
  const [f, setF] = useState<typeof blank | null>(null);
  const [busy, setBusy] = useState(false);

  const save = async () => {
    if (!f) return;
    setBusy(true);
    try {
      await api.post('/payroll/adjustments', { ...f, employeeId: num(f.employeeId), amount: num(f.amount), relatedRunId: num(f.relatedRunId) });
      toast('Adjustment recorded; it will be included in the next payroll calculation', 'success');
      setF(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const cancel = async (a: PayrollAdjustmentView) => {
    const reason = window.prompt('Reason for cancelling this adjustment');
    if (!reason) return;
    try {
      await api.post(`/payroll/adjustments/${a.id}/cancel`, { reason });
      toast('Adjustment cancelled', 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('nav.compensation', 'Compensation')} title={t('pay.adjustments', 'Payroll adjustments')}
        subtitle="Finalized periods are locked. Arrears, recoveries and corrections are recorded here (audited) and picked up by the next payroll run."
        actions={can('PAY_MANAGE') && <button className="btn btn-gold" onClick={() => setF(blank)}><Plus /> New adjustment</button>} />
      <div className="filters">
        <SelectField label="Status" value={status} onChange={setStatus} placeholder="— All —"
          options={[{ value: 'OPEN', label: 'Open' }, { value: 'APPLIED', label: 'Applied' }, { value: 'CANCELLED', label: 'Cancelled' }]} />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<PayrollAdjustmentView> rows={data} loading={loading}
          columns={[
            { key: 'employeeNo', header: 'Emp. no', render: (a) => <span className="mono">{a.employeeNo}</span> },
            { key: 'employeeName', header: 'Name' },
            { key: 'adjustmentType', header: 'Type', render: (a) => <StatusBadge status={a.adjustmentType} /> },
            { key: 'description', header: 'Description', render: (a) => <><span className="mono">{a.code}</span> {a.description}</> },
            { key: 'amount', header: 'Amount', align: 'right', render: (a) => fmtMoney(a.amount) },
            { key: 'targetPeriod', header: 'From period' },
            { key: 'relatedPeriod', header: 'Corrects' },
            { key: 'status', header: 'Status', render: (a) => <StatusBadge status={a.status} /> },
            { key: 'appliedPeriod', header: 'Applied in' },
            { key: 'createdAt', header: 'Recorded', render: (a) => `${fmtDateTime(a.createdAt)} · ${a.createdBy ?? ''}` },
            { key: 'x', header: '', render: (a) => can('PAY_MANAGE') && a.status === 'OPEN' && <button className="btn btn-ghost btn-sm" onClick={() => void cancel(a)}>Cancel</button> },
          ]} />
      </Card>
      {f && (
        <Modal title="New payroll adjustment" onClose={() => setF(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setF(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void save()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <EmployeeSelect required value={f.employeeId} onChange={(v) => setF({ ...f, employeeId: v })} />
            <SelectField label="Type" value={f.adjustmentType} onChange={(v) => setF({ ...f, adjustmentType: v })} placeholder={null}
              options={[{ value: 'EARNING', label: 'Earning (e.g. arrears)' }, { value: 'DEDUCTION', label: 'Deduction (e.g. recovery)' }]} />
            <TextField label="Code" required value={f.code} onChange={(v) => setF({ ...f, code: v.toUpperCase() })} placeholder="ARREARS" />
            <TextField label="Description" required value={f.description} onChange={(v) => setF({ ...f, description: v })} />
            <TextField label="Amount (LKR)" type="number" required value={f.amount} onChange={(v) => setF({ ...f, amount: v })} />
            <TextField label="Include from period (YYYY-MM)" required value={f.targetPeriod} onChange={(v) => setF({ ...f, targetPeriod: v })} />
            <SelectField label="Corrects finalized run" value={f.relatedRunId} onChange={(v) => setF({ ...f, relatedRunId: v })} placeholder="— None —"
              options={(runs.data ?? []).filter((r) => r.status === 'FINALIZED').map((r) => ({ value: String(r.id), label: r.period }))} />
            <TextArea label="Reason" required value={f.reason} onChange={(v) => setF({ ...f, reason: v })} />
          </div>
        </Modal>
      )}
    </>
  );
}
