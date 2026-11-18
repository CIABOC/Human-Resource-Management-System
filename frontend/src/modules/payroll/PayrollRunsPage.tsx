import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Modal, PageHeader, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime, fmtMoney } from '@/components/DataTable';
import { thisPeriod, type PayrollRun } from './types';

export default function PayrollRunsPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const toast = useToast();
  const { can } = useAuth();
  const { data, loading, error } = useApi<PayrollRun[]>('/payroll/runs');
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ period: thisPeriod(), payDate: '', notes: '' });
  const [busy, setBusy] = useState(false);

  const create = async () => {
    setBusy(true);
    try {
      const { data: run } = await api.post<PayrollRun>('/payroll/runs', { period: f.period, payDate: f.payDate || null, notes: f.notes || null });
      toast(`Payroll run ${run.period} created`, 'success');
      nav(`/payroll/runs/${run.id}`);
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('nav.compensation', 'Compensation')} title={t('pay.runs', 'Payroll runs')}
        subtitle="Monthly payroll: prepare and calculate, submit for checking and authorisation, then finalize and lock."
        actions={can('PAY_MANAGE') && <button className="btn btn-gold" onClick={() => setOpen(true)}><Plus /> New payroll run</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<PayrollRun> rows={data} loading={loading} onRowClick={(r) => nav(`/payroll/runs/${r.id}`)}
          columns={[
            { key: 'period', header: 'Period', render: (r) => <span className="mono" style={{ fontWeight: 700 }}>{r.period}</span> },
            { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
            { key: 'employeeCount', header: 'Employees', align: 'right' },
            { key: 'totalGross', header: 'Gross', align: 'right', render: (r) => fmtMoney(r.totalGross) },
            { key: 'totalDeductions', header: 'Deductions', align: 'right', render: (r) => fmtMoney(r.totalDeductions) },
            { key: 'totalNet', header: 'Net pay', align: 'right', render: (r) => fmtMoney(r.totalNet) },
            { key: 'payDate', header: 'Pay date', render: (r) => fmtDate(r.payDate) },
            { key: 'calculatedAt', header: 'Calculated', render: (r) => (r.calculatedAt ? `${fmtDateTime(r.calculatedAt)} · ${r.calculatedBy}` : '—') },
          ]} />
      </Card>
      {open && (
        <Modal title="New payroll run" onClose={() => setOpen(false)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setOpen(false)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void create()}>Create</button>
          </>
        }>
          <div className="form-grid">
            <TextField label="Period (YYYY-MM)" required value={f.period} onChange={(v) => setF({ ...f, period: v })} placeholder="2026-11" />
            <TextField label="Pay date" type="date" value={f.payDate} onChange={(v) => setF({ ...f, payDate: v })} />
            <TextArea className="span-2" label="Notes" value={f.notes} onChange={(v) => setF({ ...f, notes: v })} />
          </div>
          <Alert kind="info">Only one run can be open at a time, and the period must follow the last finalized period.</Alert>
        </Modal>
      )}
    </>
  );
}
