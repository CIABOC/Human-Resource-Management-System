import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, ConfirmDialog, KpiCard, PageHeader, SelectField, StatusBadge, TextArea } from '@/components/ui';
import { DataTable, fmtDate } from '@/components/DataTable';
import ApplyLeaveModal from './ApplyLeaveModal';
import { fmtDays, type Balance, type LeaveApplication } from './types';

/** Employee self-service: balances, apply, history, withdraw and cancellation requests (FR-LEV-003..008). */
export default function MyLeavePage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const toast = useToast();
  const thisYear = new Date().getFullYear();
  const [year, setYear] = useState(String(thisYear));
  const balances = useApi<Balance[]>('/leave/my/balances', { year });
  const apps = useApi<LeaveApplication[]>('/leave/my/applications');
  const [applyOpen, setApplyOpen] = useState(false);
  const [revise, setRevise] = useState<LeaveApplication | null>(null);
  const [withdraw, setWithdraw] = useState<LeaveApplication | null>(null);
  const [cancel, setCancel] = useState<LeaveApplication | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  const reload = () => {
    void balances.reload();
    void apps.reload();
  };

  const doWithdraw = async () => {
    if (!withdraw) return;
    setBusy(true);
    try {
      await api.post(`/leave/applications/${withdraw.id}/withdraw`, { comment: reason || null });
      toast('Application withdrawn', 'success');
      setWithdraw(null);
      reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const doCancel = async () => {
    if (!cancel) return;
    setBusy(true);
    try {
      await api.post(`/leave/applications/${cancel.id}/cancel-request`, { comment: reason });
      toast('Cancellation request submitted for approval', 'success');
      setCancel(null);
      reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('nav.self', 'Self-service')} title={t('leave.my', 'My leave')} subtitle="Your balances, applications and their approval status"
        actions={<button className="btn btn-gold" onClick={() => setApplyOpen(true)}><Plus /> {t('leave.apply', 'Apply for leave')}</button>} />
      {balances.error && <Alert kind="danger">{balances.error}</Alert>}
      <div className="row between" style={{ marginBottom: 10 }}>
        <h3 className="section-title">Balances</h3>
        <SelectField label="Year" value={year} placeholder={null} onChange={setYear}
          options={[thisYear - 1, thisYear, thisYear + 1].map((y) => ({ value: String(y), label: String(y) }))} />
      </div>
      <div className="grid grid-4" style={{ marginBottom: 18 }}>
        {(balances.data ?? []).map((b) => (
          <KpiCard key={b.leaveTypeId} label={b.leaveTypeName} value={fmtDays(b.available)} tone={Number(b.available) > 0 ? 'green' : 'red'}
            hint={`Entitled ${fmtDays(b.entitled)}${Number(b.adjusted) ? ` · adj. ${fmtDays(b.adjusted)}` : ''} · used ${fmtDays(b.used)} · pending ${fmtDays(b.pending)}`} />
        ))}
      </div>
      <Card pad={false} title="Leave history">
        <DataTable<LeaveApplication>
          rows={apps.data}
          loading={apps.loading}
          onRowClick={(a) => nav(`/leave/applications/${a.id}`)}
          empty="You have not applied for leave yet"
          columns={[
            { key: 'leaveTypeName', header: 'Type' },
            { key: 'period', header: 'Period', render: (a) => <span className="nowrap">{fmtDate(a.startDate)}{a.endDate !== a.startDate ? ` – ${fmtDate(a.endDate)}` : ''}{a.halfDay ? ` (${a.halfDaySession})` : ''}</span> },
            { key: 'days', header: 'Days', align: 'right', render: (a) => fmtDays(a.days) },
            { key: 'status', header: 'Status', render: (a) => <><StatusBadge status={a.status} />{a.cancellationStatus && <> <StatusBadge status={a.cancellationStatus} label={`Cancellation ${a.cancellationStatus.toLowerCase()}`} /></>}</> },
            { key: 'x', header: '', render: (a) => (
              <div className="row" onClick={(e) => e.stopPropagation()}>
                {a.status === 'RETURNED' && <button className="btn btn-primary btn-sm" onClick={() => setRevise(a)}>Revise</button>}
                {(a.status === 'PENDING' || a.status === 'RETURNED') && <button className="btn btn-outline btn-sm" onClick={() => { setReason(''); setWithdraw(a); }}>Withdraw</button>}
                {a.status === 'APPROVED' && a.cancellationStatus !== 'PENDING' && <button className="btn btn-outline btn-sm" onClick={() => { setReason(''); setCancel(a); }}>Request cancellation</button>}
              </div>
            ) },
          ]}
        />
      </Card>
      {applyOpen && <ApplyLeaveModal onClose={() => setApplyOpen(false)} onSaved={() => { setApplyOpen(false); reload(); }} />}
      {revise && <ApplyLeaveModal existing={revise} onClose={() => setRevise(null)} onSaved={() => { setRevise(null); reload(); }} />}
      {withdraw && (
        <ConfirmDialog title="Withdraw application" message={`Withdraw your ${withdraw.leaveTypeName} application? Reserved days will be released.`}
          confirmLabel="Withdraw" danger busy={busy} onCancel={() => setWithdraw(null)} onConfirm={() => void doWithdraw()}>
          <TextArea label="Comment (optional)" value={reason} onChange={setReason} />
        </ConfirmDialog>
      )}
      {cancel && (
        <ConfirmDialog title="Request cancellation" message="Cancellation of approved leave must be approved. On approval the days are restored to your balance. To change dates, cancel and apply again."
          confirmLabel="Submit request" busy={busy || !reason.trim()} onCancel={() => setCancel(null)} onConfirm={() => void doCancel()}>
          <TextArea label="Reason" required value={reason} onChange={setReason} />
        </ConfirmDialog>
      )}
    </>
  );
}
