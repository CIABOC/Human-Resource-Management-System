import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Plus, SlidersHorizontal } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, DL, Loading, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtMoney } from '@/components/DataTable';
import { EmployeeSelect, MasterDataSelect, useMasterDataLabels } from '@/components/pickers';
import { WorkflowTrail } from '@/components/WorkflowTrail';
import { num, today, type LoanAdjustment, type LoanDetail, type LoanTransaction, type LoanView } from './types';

/** Loans of seconded employees recovered through payroll (FR-PAY-020..023). */
export default function LoansPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const toast = useToast();
  const { can } = useAuth();
  const [status, setStatus] = useState('ACTIVE');
  const { data, loading, error, reload } = useApi<LoanView[]>('/payroll/loans', { status });
  const typeLabel = useMasterDataLabels('LOAN_TYPE');
  const blank = { employeeId: '', loanType: '', lender: '', previousWorkplace: '', referenceNo: '', originalAmount: '', outstandingBalance: '', monthlyInstalment: '', startDate: today(), remarks: '' };
  const [f, setF] = useState<typeof blank | null>(null);
  const [busy, setBusy] = useState(false);

  const save = async () => {
    if (!f) return;
    setBusy(true);
    try {
      await api.post('/payroll/loans', { ...f, employeeId: num(f.employeeId), originalAmount: num(f.originalAmount), outstandingBalance: num(f.outstandingBalance), monthlyInstalment: num(f.monthlyInstalment) });
      toast('Loan recorded', 'success');
      setF(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('nav.compensation', 'Compensation')} title={t('pay.loans', 'Employee loans')}
        subtitle="Loans brought by seconded employees. Instalments are deducted in payroll until cleared; balances change only at payroll finalization or through approved adjustments."
        actions={can('PAY_MANAGE') && <button className="btn btn-gold" onClick={() => setF(blank)}><Plus /> Record loan</button>} />
      <div className="filters">
        <SelectField label="Status" value={status} onChange={setStatus} placeholder="— All —"
          options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'SETTLED', label: 'Settled' }, { value: 'TRANSFERRED_OUT', label: 'Transferred out' }]} />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<LoanView> rows={data} loading={loading} onRowClick={(l) => nav(`/payroll/loans/${l.id}`)}
          columns={[
            { key: 'employeeNo', header: 'Emp. no', render: (l) => <span className="mono">{l.employeeNo}</span> },
            { key: 'employeeName', header: 'Name' },
            { key: 'loanType', header: 'Type', render: (l) => typeLabel(l.loanType) },
            { key: 'lender', header: 'Lender' },
            { key: 'originalAmount', header: 'Original', align: 'right', render: (l) => fmtMoney(l.originalAmount) },
            { key: 'outstandingBalance', header: 'Outstanding', align: 'right', render: (l) => <strong>{fmtMoney(l.outstandingBalance)}</strong> },
            { key: 'monthlyInstalment', header: 'Instalment', align: 'right', render: (l) => fmtMoney(l.monthlyInstalment) },
            { key: 'status', header: 'Status', render: (l) => <StatusBadge status={l.status} /> },
          ]} />
      </Card>
      {f && (
        <Modal title="Record loan" onClose={() => setF(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setF(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void save()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <EmployeeSelect required value={f.employeeId} onChange={(v) => setF({ ...f, employeeId: v })} />
            <MasterDataSelect category="LOAN_TYPE" label="Loan type" required value={f.loanType} onChange={(v) => setF({ ...f, loanType: v })} />
            <TextField label="Lender" required value={f.lender} onChange={(v) => setF({ ...f, lender: v })} />
            <TextField label="Previous workplace" value={f.previousWorkplace} onChange={(v) => setF({ ...f, previousWorkplace: v })} />
            <TextField label="Reference no" value={f.referenceNo} onChange={(v) => setF({ ...f, referenceNo: v })} />
            <TextField label="Start date" type="date" required value={f.startDate} onChange={(v) => setF({ ...f, startDate: v })} />
            <TextField label="Original amount (LKR)" type="number" required value={f.originalAmount} onChange={(v) => setF({ ...f, originalAmount: v })} />
            <TextField label="Outstanding balance (LKR)" type="number" value={f.outstandingBalance} onChange={(v) => setF({ ...f, outstandingBalance: v })} help="Defaults to the original amount" />
            <TextField label="Monthly instalment (LKR)" type="number" required value={f.monthlyInstalment} onChange={(v) => setF({ ...f, monthlyInstalment: v })} />
            <TextField label="Remarks" value={f.remarks} onChange={(v) => setF({ ...f, remarks: v })} />
          </div>
        </Modal>
      )}
    </>
  );
}

export function LoanDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const { can } = useAuth();
  const { data, loading, error, reload } = useApi<LoanDetail>(`/payroll/loans/${id}`);
  const typeLabel = useMasterDataLabels('LOAN_TYPE');
  const [adj, setAdj] = useState<null | { adjustmentType: string; newInstalment: string; settlementAmount: string; effectiveDate: string; reason: string }>(null);
  const [busy, setBusy] = useState(false);

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert kind="danger">{error ?? 'Not found'}</Alert>;
  const l = data.loan;
  const pending = data.adjustments.some((a) => a.status === 'PENDING' || a.status === 'RETURNED');

  const save = async () => {
    if (!adj) return;
    setBusy(true);
    try {
      await api.post(`/payroll/loans/${id}/adjustments`, { ...adj, newInstalment: num(adj.newInstalment), settlementAmount: num(adj.settlementAmount) });
      toast('Adjustment submitted for approval', 'success');
      setAdj(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader eyebrow="Employee loans" title={`${typeLabel(l.loanType)} — ${l.employeeNo}`} subtitle={<StatusBadge status={l.status} />}
        actions={can('PAY_MANAGE') && l.status === 'ACTIVE' && !pending && (
          <button className="btn btn-gold" onClick={() => setAdj({ adjustmentType: 'INSTALMENT_CHANGE', newInstalment: String(l.monthlyInstalment), settlementAmount: '', effectiveDate: today(), reason: '' })}><SlidersHorizontal /> Request adjustment</button>
        )} />
      {pending && <Alert kind="info">An adjustment is awaiting approval; it will be applied when approved.</Alert>}
      <div className="grid grid-2">
        <Card title="Loan">
          <DL items={[
            ['Employee', `${l.employeeNo} — ${l.employeeName}`],
            ['Lender', l.lender],
            ['Previous workplace', l.previousWorkplace],
            ['Reference', l.referenceNo],
            ['Original amount', `LKR ${fmtMoney(l.originalAmount)}`],
            ['Outstanding balance', <strong key="o">LKR {fmtMoney(l.outstandingBalance)}</strong>],
            ['Monthly instalment', `LKR ${fmtMoney(l.monthlyInstalment)}`],
            ['Start date', fmtDate(l.startDate)],
            ['Closed on', l.closedOn ? fmtDate(l.closedOn) : null],
            ['Remarks', l.remarks],
          ]} />
        </Card>
        <Card title="Adjustments" pad={false}>
          <DataTable<LoanAdjustment> rows={data.adjustments} empty="No adjustments"
            columns={[
              { key: 'adjustmentType', header: 'Type', render: (a) => a.adjustmentType.replace(/_/g, ' ').toLowerCase() },
              { key: 'detail', header: 'Detail', render: (a) => (a.newInstalment != null ? `New instalment ${fmtMoney(a.newInstalment)}` : a.settlementAmount != null ? `Settlement ${fmtMoney(a.settlementAmount)}` : '—') },
              { key: 'effectiveDate', header: 'Effective', render: (a) => fmtDate(a.effectiveDate) },
              { key: 'status', header: 'Status', render: (a) => <StatusBadge status={a.status} /> },
              { key: 'wf', header: '', render: (a) => a.workflowInstanceId && <Link to={`/approvals/${a.workflowInstanceId}`}>#{a.workflowInstanceId}</Link> },
            ]} />
        </Card>
      </div>
      <div style={{ height: 16 }} />
      <Card title="Loan deduction history" pad={false}>
        <DataTable<LoanTransaction> rows={data.transactions}
          columns={[
            { key: 'txnDate', header: 'Date', render: (x) => fmtDate(x.txnDate) },
            { key: 'txnType', header: 'Transaction', render: (x) => <StatusBadge status={x.txnType} /> },
            { key: 'period', header: 'Payroll period' },
            { key: 'amount', header: 'Amount', align: 'right', render: (x) => fmtMoney(x.amount) },
            { key: 'balanceAfter', header: 'Balance after', align: 'right', render: (x) => fmtMoney(x.balanceAfter) },
            { key: 'remarks', header: 'Remarks' },
          ]} />
      </Card>
      {data.adjustments.length > 0 && (
        <>
          <div style={{ height: 16 }} />
          <Card title="Approval trail">
            <div className="stack">{data.adjustments.map((a) => <WorkflowTrail key={a.id} entityType="LoanAdjustment" entityId={a.id} />)}</div>
          </Card>
        </>
      )}
      {adj && (
        <Modal title="Request loan adjustment" onClose={() => setAdj(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setAdj(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={busy} onClick={() => void save()}>Submit for approval</button>
          </>
        }>
          <div className="form-grid">
            <SelectField label="Adjustment" value={adj.adjustmentType} onChange={(v) => setAdj({ ...adj, adjustmentType: v })} placeholder={null}
              options={[{ value: 'INSTALMENT_CHANGE', label: 'Instalment change' }, { value: 'EARLY_SETTLEMENT', label: 'Early settlement' }, { value: 'TRANSFER_OUT', label: 'Transfer out (end of secondment)' }]} />
            <TextField label="Effective date" type="date" required value={adj.effectiveDate} onChange={(v) => setAdj({ ...adj, effectiveDate: v })} />
            {adj.adjustmentType === 'INSTALMENT_CHANGE' && <TextField label="New instalment (LKR)" type="number" required value={adj.newInstalment} onChange={(v) => setAdj({ ...adj, newInstalment: v })} />}
            {adj.adjustmentType === 'EARLY_SETTLEMENT' && <TextField label="Settlement amount (LKR)" type="number" value={adj.settlementAmount} onChange={(v) => setAdj({ ...adj, settlementAmount: v })} help={`Blank = full outstanding (${fmtMoney(l.outstandingBalance)})`} />}
            <TextArea className="span-2" label="Reason" required value={adj.reason} onChange={(v) => setAdj({ ...adj, reason: v })} />
          </div>
        </Modal>
      )}
    </>
  );
}
