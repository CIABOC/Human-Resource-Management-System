import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Calculator, Download, Lock, Printer, Send } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, ConfirmDialog, DL, KpiCard, Loading, Modal, PageHeader, StatusBadge, Tabs, TextArea } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime, fmtMoney } from '@/components/DataTable';
import { WorkflowTrail } from '@/components/WorkflowTrail';
import { PayslipView } from './PayslipView';
import type { ComponentTotal, LineDetail, LineSummary, RunDetail } from './types';

export default function PayrollRunDetailPage() {
  const { id } = useParams();
  const { t } = useTranslation();
  const toast = useToast();
  const { can } = useAuth();
  const { data, loading, error, reload } = useApi<RunDetail>(`/payroll/runs/${id}`);
  const lines = useApi<LineSummary[]>(`/payroll/runs/${id}/lines`);
  const [tab, setTab] = useState<'lines' | 'components' | 'approval'>('lines');
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<null | 'calc' | 'submit'>(null);
  const [comment, setComment] = useState('');
  const [detail, setDetail] = useState<LineDetail | null>(null);
  const [filter, setFilter] = useState('');

  if (loading && !data) return <Loading />;
  if (error || !data) return <Alert kind="danger">{error ?? 'Not found'}</Alert>;
  const { run } = data;

  const act = async (what: 'calc' | 'submit') => {
    setBusy(true);
    try {
      if (what === 'calc') await api.post(`/payroll/runs/${id}/calculate`);
      else await api.post(`/payroll/runs/${id}/submit`, { comment: comment || null });
      toast(what === 'calc' ? 'Payroll calculated' : 'Submitted for checking and authorisation', 'success');
      setConfirm(null);
      void reload();
      void lines.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const openLine = async (l: LineSummary) => {
    try {
      const { data: d } = await api.get<LineDetail>(`/payroll/runs/${id}/lines/${l.id}`);
      setDetail(d);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const rows = (lines.data ?? []).filter((l) => !filter || `${l.employeeNo} ${l.employeeName}`.toLowerCase().includes(filter.toLowerCase()));
  const finalized = run.status === 'FINALIZED';

  return (
    <>
      <PageHeader eyebrow={t('pay.runs', 'Payroll runs')} title={`${t('pay.run', 'Payroll')} ${run.period}`}
        subtitle={<><StatusBadge status={run.status} /> {run.locked && <span className="badge badge-neutral"><Lock size={12} /> Locked</span>} {run.payDate && <span className="muted">Pay date {fmtDate(run.payDate)}</span>}</>}
        actions={
          <>
            {can('PAY_MANAGE') && data.canCalculate && (
              <button className="btn btn-outline" disabled={busy} onClick={() => setConfirm('calc')}><Calculator /> {run.calculatedAt ? 'Recalculate' : 'Calculate'}</button>
            )}
            {can('PAY_MANAGE') && data.canSubmit && <button className="btn btn-gold" disabled={busy} onClick={() => setConfirm('submit')}><Send /> Submit for approval</button>}
            {finalized && can('PAY_MANAGE') && (
              <button className="btn btn-outline" onClick={() => void download(`/payroll/runs/${id}/bank-advice`).catch((e) => toast(errorMessage(e), 'error'))}><Download /> Bank advice</button>
            )}
            {finalized && (
              <button className="btn btn-outline" onClick={() => void download(`/payroll/runs/${id}/posting-summary`).catch((e) => toast(errorMessage(e), 'error'))}><Download /> Posting summary</button>
            )}
          </>
        } />
      {run.status === 'SUBMITTED' && <Alert kind="info">This run is awaiting checking and authorisation. It cannot be recalculated. {run.workflowInstanceId && <Link to={`/approvals/${run.workflowInstanceId}`}>Open request #{run.workflowInstanceId}</Link>}</Alert>}
      {run.status === 'RETURNED' && <Alert kind="warning">The run was returned for correction. Recalculate if needed and submit again.</Alert>}
      {finalized && <Alert kind="success">Finalized {fmtDateTime(run.finalizedAt)} and locked. Corrections can only be made through payroll adjustments picked up by the next run. Export formats are to be confirmed with Finance.</Alert>}
      {data.openAdjustmentsPending && <Alert kind="info">{data.openAdjustments} open payroll adjustment(s) will be included when the run is (re)calculated.</Alert>}
      {run.notes && <Alert kind="warning">{run.notes}</Alert>}

      <div className="grid grid-4" style={{ margin: '16px 0' }}>
        <KpiCard label="Employees" value={run.employeeCount} hint={run.warningCount ? `${run.warningCount} with warnings` : 'No warnings'} tone={run.warningCount ? 'gold' : 'navy'} />
        <KpiCard label="Gross pay" value={fmtMoney(run.totalGross)} hint="LKR" />
        <KpiCard label="Deductions" value={fmtMoney(run.totalDeductions)} hint="LKR" tone="red" />
        <KpiCard label="Net pay" value={fmtMoney(run.totalNet)} hint={`Employer contributions ${fmtMoney(run.totalEmployer)}`} tone="green" />
      </div>
      <Card>
        <DL items={[
          ['Prepared (calculated) by', run.calculatedBy ? `${run.calculatedBy}, ${fmtDateTime(run.calculatedAt)}` : 'Not yet calculated'],
          ['Submitted by', run.submittedBy ? `${run.submittedBy}, ${fmtDateTime(run.submittedAt)}` : '—'],
        ]} />
      </Card>
      <div style={{ height: 16 }} />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: 'lines', label: 'Employee lines' }, { key: 'components', label: 'Summary by component' }, { key: 'approval', label: 'Approval trail' }]} />
      {tab === 'lines' && (
        <Card pad={false} title={<input className="input" style={{ maxWidth: 280 }} placeholder="Filter by employee" value={filter} onChange={(e) => setFilter(e.target.value)} />}>
          {lines.error && <Alert kind="danger">{lines.error}</Alert>}
          <DataTable<LineSummary> rows={rows} loading={lines.loading} onRowClick={(l) => void openLine(l)}
            empty={run.calculatedAt ? 'No lines' : 'Calculate the payroll to produce employee lines'}
            columns={[
              { key: 'employeeNo', header: 'Emp. no', render: (l) => <span className="mono">{l.employeeNo}</span> },
              { key: 'employeeName', header: 'Name' },
              { key: 'scale', header: 'Scale / step', render: (l) => (l.scaleCode ? `${l.scaleCode} / ${l.step}` : '—') },
              { key: 'basicSalary', header: 'Basic', align: 'right', render: (l) => fmtMoney(l.basicSalary) },
              { key: 'grossPay', header: 'Gross', align: 'right', render: (l) => fmtMoney(l.grossPay) },
              { key: 'totalDeductions', header: 'Deductions', align: 'right', render: (l) => fmtMoney(l.totalDeductions) },
              { key: 'netPay', header: 'Net', align: 'right', render: (l) => <strong>{fmtMoney(l.netPay)}</strong> },
              { key: 'noPayDays', header: 'No-pay days', align: 'right', render: (l) => (Number(l.noPayDays) ? l.noPayDays : '—') },
              { key: 'warnings', header: '', render: (l) => (l.warnings ? <span className="badge badge-warning" title={l.warnings}>Warning</span> : null) },
            ]} />
        </Card>
      )}
      {tab === 'components' && (
        <Card pad={false}>
          <DataTable<ComponentTotal> rows={data.components} rowKey={(c) => `${c.componentType}-${c.code}`}
            columns={[
              { key: 'componentType', header: 'Type', render: (c) => <StatusBadge status={c.componentType} /> },
              { key: 'code', header: 'Code', render: (c) => <span className="mono">{c.code}</span> },
              { key: 'description', header: 'Component' },
              { key: 'employees', header: 'Employees', align: 'right' },
              { key: 'amount', header: 'Amount', align: 'right', render: (c) => fmtMoney(c.amount) },
            ]} />
        </Card>
      )}
      {tab === 'approval' && <Card><WorkflowTrail entityType="PayrollRun" entityId={run.id} /></Card>}

      {confirm === 'calc' && (
        <ConfirmDialog title={run.calculatedAt ? 'Recalculate payroll' : 'Calculate payroll'} busy={busy} onCancel={() => setConfirm(null)} onConfirm={() => void act('calc')}
          message="All in-service employees will be calculated from the salary placements, allowances, deductions, loans, approved no-pay and benefit premiums in force at the end of the period. Existing lines of this run are replaced." />
      )}
      {confirm === 'submit' && (
        <ConfirmDialog title="Submit for approval" confirmLabel="Submit" busy={busy} onCancel={() => setConfirm(null)} onConfirm={() => void act('submit')}
          message={`Submit payroll ${run.period} (net LKR ${fmtMoney(run.totalNet)}) to a second Finance Officer for checking and to Management for authorisation?`}>
          <TextArea label="Comment" value={comment} onChange={setComment} />
        </ConfirmDialog>
      )}
      {detail && (
        <Modal wide title={`${detail.line.employeeNo} — ${detail.period}`} onClose={() => setDetail(null)}
          footer={<button className="btn btn-outline" onClick={() => window.print()}><Printer /> Print</button>}>
          <PayslipView d={detail} />
        </Modal>
      )}
    </>
  );
}
