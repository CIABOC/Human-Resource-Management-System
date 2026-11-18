import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { CheckCircle2 } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, ConfirmDialog, DL, Loading, Modal, PageHeader, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime, fmtMoney } from '@/components/DataTable';
import { employeeLabel, useMasterDataLabels } from '@/components/pickers';
import type { CaseDetail, ClearanceItem } from './types';
import { titleCase } from './types';

type Action = { kind: 'clear' | 'waive'; item: ClearanceItem } | { kind: 'cancel' } | { kind: 'complete' } | { kind: 'settlement' };

export default function SeparationDetailPage() {
  const { id } = useParams();
  const { t } = useTranslation();
  const { me, can } = useAuth();
  const toast = useToast();
  const reason = useMasterDataLabels('SEPARATION_REASON');
  const { data: c, loading, error, setData } = useApi<CaseDetail>(`/extended/separations/${id}`);
  const [action, setAction] = useState<Action | null>(null);
  const [text, setText] = useState('');
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);

  if (loading && !c) return <Loading />;
  if (error || !c) return <Alert kind="danger">{error ?? 'Not found'}</Alert>;
  const s = c.summary;
  const open = s.status === 'OPEN';
  const roles = me?.roles ?? [];

  const run = async (url: string, body: unknown, ok: string) => {
    setBusy(true);
    try {
      const r = await api.post<CaseDetail>(url, body);
      setData(r.data);
      toast(ok, 'success');
      setAction(null);
      setText('');
      setAmount('');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const submit = () => {
    if (!action) return;
    switch (action.kind) {
      case 'clear': return run(`/extended/clearance/items/${action.item.id}/clear`, { remarks: text }, 'Item cleared');
      case 'waive': return run(`/extended/clearance/items/${action.item.id}/waive`, { remarks: text }, 'Item waived');
      case 'cancel': return run(`/extended/separations/${s.id}/cancel`, { remarks: text }, 'Case cancelled');
      case 'settlement': return run(`/extended/separations/${s.id}/settlement`, { amount: Number(amount), notes: text }, 'Final settlement recorded');
      case 'complete': return run(`/extended/separations/${s.id}/complete`, {}, 'Separation completed; access removed');
    }
  };

  return (
    <>
      <PageHeader eyebrow={`${t('extended.separationCase', 'Separation case')} #${s.id}`} title={employeeLabel(s.employee)}
        subtitle={<><StatusBadge status={s.status} /> &nbsp;{titleCase(s.separationType)} on {fmtDate(s.separationDate)}</>}
        actions={open && (
          <>
            {roles.includes(c.settlementRole) && <button className="btn btn-outline" onClick={() => { setAmount(c.settlementAmount != null ? String(c.settlementAmount) : ''); setText(c.settlementNotes ?? ''); setAction({ kind: 'settlement' }); }}>Record settlement</button>}
            {can('EXT_MANAGE') && <button className="btn btn-outline" onClick={() => setAction({ kind: 'cancel' })}>Cancel case</button>}
            {can('EXT_MANAGE') && <button className="btn btn-gold" disabled={!c.canComplete} onClick={() => setAction({ kind: 'complete' })}><CheckCircle2 /> Complete separation</button>}
          </>
        )} />
      {open && c.blockers.length > 0 && <Alert kind="info">Before completion: {c.blockers.join('; ')}.</Alert>}
      {s.status === 'COMPLETED' && <Alert kind="success">Completed by {c.completedBy} on {fmtDateTime(s.completedAt)}. The employee status is now {titleCase(s.targetStatus)} and the user account has been deactivated.</Alert>}
      {s.status === 'CANCELLED' && <Alert kind="warning">Cancelled: {c.cancelReason}</Alert>}
      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 2fr) minmax(260px, 1fr)', alignItems: 'start' }}>
        <Card title="Clearance checklist" pad={false}>
          <DataTable<ClearanceItem>
            rows={c.items}
            columns={[
              { key: 'itemName', header: 'Item' },
              { key: 'responsibleRole', header: 'Cleared by', render: (i) => titleCase(i.responsibleRole) },
              { key: 'status', header: 'Status', render: (i) => <StatusBadge status={i.status === 'CLEARED' ? 'COMPLETED' : i.status === 'WAIVED' ? 'CLOSED' : 'PENDING'} label={titleCase(i.status)} /> },
              { key: 'by', header: 'By / remarks', render: (i) => i.clearedBy ? <div><div>{i.clearedBy} · {fmtDateTime(i.clearedAt)}</div>{i.remarks && <div className="muted">{i.remarks}</div>}</div> : <span className="faint">—</span> },
              { key: 'actions', header: '', render: (i) => open && i.status === 'PENDING' ? (
                <div className="row">
                  {roles.includes(i.responsibleRole) && <button className="btn btn-primary btn-sm" onClick={() => setAction({ kind: 'clear', item: i })}>Clear</button>}
                  {can('EXT_MANAGE') && <button className="btn btn-ghost btn-sm" onClick={() => setAction({ kind: 'waive', item: i })}>Waive</button>}
                </div>
              ) : null },
            ]}
          />
        </Card>
        <div className="stack">
          <Card title="Case">
            <DL items={[
              ['Type', titleCase(s.separationType)],
              ['Reason', reason(s.separationReason)],
              ['New status', titleCase(s.targetStatus)],
              ['Separation date', fmtDate(s.separationDate)],
              ['Remarks', c.remarks],
              ['Opened by', `${s.createdBy} · ${fmtDateTime(s.createdAt)}`],
            ]} />
          </Card>
          <Card title="Final settlement">
            {c.settlementAt ? (
              <DL items={[
                ['Amount (LKR)', c.settlementAmount != null ? fmtMoney(c.settlementAmount) : 'Restricted'],
                ['Notes', c.settlementNotes ?? 'Restricted'],
                ['Recorded by', `${c.settlementBy} · ${fmtDateTime(c.settlementAt)}`],
              ]} />
            ) : <div className="faint">{c.settlementRequired ? `Awaiting input from ${titleCase(c.settlementRole)}` : 'Not required'}</div>}
          </Card>
        </div>
      </div>

      {action && action.kind === 'complete' && (
        <ConfirmDialog title="Complete separation" confirmLabel="Complete" busy={busy} onCancel={() => setAction(null)} onConfirm={() => void submit()}
          message={`This sets ${s.employee.nameWithInitials}'s status to ${titleCase(s.targetStatus)} from ${fmtDate(s.separationDate)}, closes the placement and deactivates the user account. Continue?`} />
      )}
      {action && action.kind !== 'complete' && (
        <Modal title={action.kind === 'clear' ? `Clear: ${action.item.itemName}` : action.kind === 'waive' ? `Waive: ${action.item.itemName}`
          : action.kind === 'cancel' ? 'Cancel separation case' : 'Record final settlement'} onClose={() => setAction(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setAction(null)}>Close</button>
            <button className="btn btn-primary" onClick={() => void submit()}
              disabled={busy || (action.kind !== 'clear' && !text.trim()) || (action.kind === 'settlement' && amount === '')}>Confirm</button>
          </>
        }>
          <div className="form-grid">
            {action.kind === 'settlement' && (
              <TextField label="Net settlement amount (LKR; negative = recovery)" type="number" step="0.01" required value={amount} onChange={setAmount} />
            )}
            <TextArea label={action.kind === 'clear' ? 'Remarks (optional)' : action.kind === 'settlement' ? 'Settlement notes' : 'Reason'}
              required={action.kind !== 'clear'} value={text} onChange={setText} />
          </div>
        </Modal>
      )}
    </>
  );
}
