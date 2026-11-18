import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Calculator, Send } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, DL, KpiCard, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDateTime } from '@/components/DataTable';
import { WorkflowTrail } from '@/components/WorkflowTrail';
import { empLabel, type NoPayLine, type NoPayRun } from './types';

/** Monthly no-pay: calculate, review/adjust, submit for approval; released to payroll once approved (FR-ATT-021..023). */
export default function NoPayPage() {
  const { t } = useTranslation();
  const { can } = useAuth();
  const toast = useToast();
  const [params] = useSearchParams();
  const now = new Date();
  const thisMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  const prevMonth = (() => { const d = new Date(now.getFullYear(), now.getMonth() - 1, 1); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`; })();
  const [period, setPeriod] = useState(params.get('period') ?? prevMonth);
  const runs = useApi<NoPayRun[]>('/attendance/nopay/runs');
  const exists = (runs.data ?? []).some((r) => r.period === period);
  const run = useApi<{ run: NoPayRun; lines: NoPayLine[] }>(exists ? `/attendance/nopay/runs/${period}` : null);
  const [busy, setBusy] = useState(false);
  const [adjust, setAdjust] = useState<{ line: NoPayLine; days: string; reason: string } | null>(null);

  const reloadAll = () => {
    void runs.reload();
    void run.reload();
  };

  const calculate = async () => {
    setBusy(true);
    try {
      await api.post('/attendance/nopay/calculate', { period });
      toast(`No-pay calculated for ${period}`, 'success');
      reloadAll();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const submit = async () => {
    if (!run.data) return;
    setBusy(true);
    try {
      await api.post(`/attendance/nopay/runs/${run.data.run.id}/submit`);
      toast('Submitted for approval', 'success');
      reloadAll();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const saveAdjust = async () => {
    if (!adjust || !run.data) return;
    try {
      await api.post(`/attendance/nopay/runs/${run.data.run.id}/lines/${adjust.line.id}/adjust`, { days: adjust.days === '' ? null : Number(adjust.days), reason: adjust.reason });
      toast('Line adjusted', 'success');
      setAdjust(null);
      reloadAll();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const r = exists ? run.data?.run : undefined;
  const editable = r && (r.status === 'DRAFT' || r.status === 'RETURNED');
  const periods = Array.from(new Set([thisMonth, prevMonth, ...(runs.data ?? []).map((x) => x.period)])).sort().reverse();

  return (
    <>
      <PageHeader eyebrow={t('nav.time', 'Time & attendance')} title={t('attendance.nopay', 'No-pay')}
        subtitle="Unauthorized absences and approved unpaid leave, calculated by the configured rules and approved before release to payroll"
        actions={can('ATT_MANAGE') ? (
          <>
            {(!r || editable || r.status === 'REJECTED') && <button className="btn btn-outline" disabled={busy} onClick={() => void calculate()}><Calculator /> {r ? 'Recalculate' : 'Calculate'}</button>}
            {editable && <button className="btn btn-gold" disabled={busy} onClick={() => void submit()}><Send /> Submit for approval</button>}
          </>
        ) : undefined} />
      <div className="filters">
        <SelectField label="Period" value={period} placeholder={null} onChange={setPeriod} options={periods.map((p) => ({ value: p, label: p }))} />
        <TextField label="Other period" type="month" value={period} onChange={(v) => v && setPeriod(v)} />
      </div>
      {!exists && !runs.loading && <Alert kind="info">No calculation for {period} yet.{can('ATT_MANAGE') ? ' Process the month\'s attendance first, then calculate.' : ''}</Alert>}
      {r && (
        <>
          {r.unprocessedDays > 0 && <Alert kind="warning">{r.unprocessedDays} working day(s) in {r.period} had no processed attendance when calculated — process them and recalculate.</Alert>}
          <div className="grid grid-4" style={{ marginBottom: 14 }}>
            <KpiCard label="Status" value={<StatusBadge status={r.status} />} />
            <KpiCard label="Employees" value={r.employeeCount} tone="navy" />
            <KpiCard label="No-pay days" value={Number(r.totalDays)} tone="red" />
            <KpiCard label="Released to payroll" value={r.status === 'APPROVED' ? 'Yes' : 'No'} tone={r.status === 'APPROVED' ? 'green' : 'neutral'} />
          </div>
          <div className="grid grid-2" style={{ marginBottom: 14 }}>
            <Card title="Calculation">
              <DL items={[
                ['Calculated', `${fmtDateTime(r.calculatedAt)} by ${r.calculatedBy ?? '—'}`],
                ['Rules applied', r.rulesSnapshot],
                ['Approved', r.approvedAt ? fmtDateTime(r.approvedAt) : null],
                ['Approval request', r.workflowInstanceId ? <Link key="l" to={`/approvals/${r.workflowInstanceId}`}>#{r.workflowInstanceId}</Link> : null],
              ]} />
            </Card>
            <Card title="Approval trail"><WorkflowTrail entityType="NoPayRun" entityId={r.id} /></Card>
          </div>
          <Card pad={false} title="Employees">
            <DataTable<NoPayLine>
              rows={run.data?.lines}
              loading={run.loading}
              empty="No no-pay days in this period"
              columns={[
                { key: 'emp', header: 'Employee', render: (l) => empLabel(l.employee, l.employeeId) },
                { key: 'unit', header: 'Unit', render: (l) => l.employee?.orgUnitName ?? '—' },
                { key: 'absenceDays', header: 'Absence', align: 'right', render: (l) => Number(l.absenceDays) },
                { key: 'unpaidLeaveDays', header: 'Unpaid leave', align: 'right', render: (l) => Number(l.unpaidLeaveDays) },
                { key: 'late', header: 'Late (count / days)', align: 'right', render: (l) => `${l.lateCount} / ${Number(l.lateDays)}` },
                { key: 'calculatedDays', header: 'Calculated', align: 'right', render: (l) => Number(l.calculatedDays) },
                { key: 'adjustedDays', header: 'Adjusted', align: 'right', render: (l) => (l.adjustedDays !== null && l.adjustedDays !== undefined ? <span title={l.adjustmentReason ?? ''}>{Number(l.adjustedDays)}</span> : '—') },
                { key: 'effectiveDays', header: 'No-pay days', align: 'right', render: (l) => <strong>{Number(l.effectiveDays)}</strong> },
                { key: 'x', header: '', render: (l) => editable && can('ATT_MANAGE') ? <button className="btn btn-ghost btn-sm" onClick={() => setAdjust({ line: l, days: String(Number(l.effectiveDays)), reason: '' })}>Adjust</button> : null },
              ]}
            />
          </Card>
        </>
      )}
      {adjust && (
        <Modal title={`Adjust no-pay · ${empLabel(adjust.line.employee, adjust.line.employeeId)}`} onClose={() => setAdjust(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setAdjust(null)}>Cancel</button>
            <button className="btn btn-primary" disabled={!adjust.reason.trim()} onClick={() => void saveAdjust()}>Save</button>
          </>
        }>
          <div className="form-grid">
            <TextField label="No-pay days" type="number" step="0.5" help="Leave empty to restore the calculated value" value={adjust.days} onChange={(v) => setAdjust({ ...adjust, days: v })} />
            <div className="field"><label>Calculated</label><div>{Number(adjust.line.calculatedDays)}</div></div>
            <TextArea className="span-2" label="Reason" required value={adjust.reason} onChange={(v) => setAdjust({ ...adjust, reason: v })} />
          </div>
        </Modal>
      )}
    </>
  );
}
